package dev.schlubbe.musicagent.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource
import dev.schlubbe.musicagent.data.local.dao.TrackAnalysisDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** How often the ramp updates both players' volume once a fade has started - see
 * [CrossfadeController.poll]'s own kdoc for the (coarser) trigger-check interval. */
private const val RAMP_INTERVAL_MS = 50L

private const val MIN_FADE_MS = 1_000L

/** How long before the fade the tail player starts loading, and how long it may take at most. */
private const val PREPARE_LEAD_MS = 2_500L
private const val PREPARE_TIMEOUT_MS = 6_000L

/** The main player may be this far past the hand-over point when the tail starts (one audio buffer or so). */
private const val HANDOVER_SLACK_MS = 60L

private val HALF_PI = (Math.PI / 2).toFloat()

/**
 * True overlapping crossfade between the outgoing and incoming track.
 *
 * The main ExoPlayer in [PlaybackService] stays the single source of truth for the
 * queue, MediaSession, visualizer tap and EQ/3D-audio session (see
 * docs/ARCHITECTURE.md) - this never wraps or replaces it. Instead, a fade borrows the
 * main player's *current* item URI/position for a short-lived secondary [ExoPlayer]
 * that carries only the outgoing track's tail, while the main player itself is
 * advanced immediately to the next item (silenced, then ramped back up). Both players
 * are ramped on an equal-power curve so the combined loudness doesn't dip mid-fade the
 * way a linear 0..1 ramp on both would.
 *
 * When both the outgoing and incoming track have a cached [dev.schlubbe.musicagent.data.local.entity.TrackAnalysisEntity]
 * (see [TrackAnalyzer][dev.schlubbe.musicagent.playback.analysis.TrackAnalyzer]), the
 * fade triggers at the outgoing track's marked mix-out point and lands the incoming
 * track at its marked mix-in point instead of blind position 0 - the "positions to
 * jump to" a track's own analysis picked out, same idea as djay's Automix. A track
 * with no analysis (streamed-only, or not analyzed yet) - or one that is playing from
 * its stream rather than the download the analysis was measured on - falls back to today's plain
 * fixed-duration fade from the raw file's end - the two behaviors mix freely per pair
 * of tracks, there's no "smart mode" toggle.
 *
 * Owned directly by [PlaybackService] (constructed with its main player and scope),
 * not a Hilt singleton - a fade is inherently tied to one running player instance.
 */
@UnstableApi
class CrossfadeController(
    private val context: Context,
    private val mainPlayer: ExoPlayer,
    // The same factory the main player uses: OkHttp for http(s), plus Android's own
    // ContentDataSource for the content:// URIs downloads play from. ExoPlayer's
    // default factory handles neither the app's OkHttp setup nor content:// reliably,
    // and a tail that fails to open just aborts the fade (see the error listener).
    private val mediaSourceFactory: MediaSource.Factory,
    // Audio sink for the tail player: carries its own EQ instance so the outgoing
    // track doesn't lose the EQ for the length of the fade.
    private val renderersFactory: RenderersFactory? = null,
    // Called with the outgoing track's media id when its tail starts on the secondary
    // player, so that player's EQ/loudness normalisation knows which track it plays.
    private val onTailStarted: (mediaId: String) -> Unit = {},
    private val scope: CoroutineScope,
    private val trackAnalysisDao: TrackAnalysisDao,
    // The level both players are scaled to: 1 normally, lower while the sleep timer
    // fades playback out (see PlayerController.sleepFadeVolume). Read on every ramp
    // step, so a fade running into the sleep fade still ends up quiet.
    private val masterVolume: () -> Float = { 1f },
) {
    private var secondaryPlayer: ExoPlayer? = null
    private var fadeJob: Job? = null

    // Mix-point lookups only need to happen once per track, not on every ~200ms
    // poll tick for that track's entire runtime - caching by the last-looked-up
    // trackId (mediaId can be null for a placeholder item) turned a real device's
    // CPU usage during ordinary single-track playback from ~25% to 85-120%,
    // confirmed by disabling crossfade entirely and watching it drop back down.
    private var cachedCurrentTrackId: String? = "unset"
    private var cachedMixOutMs: Long? = null
    private var cachedNextTrackId: String? = "unset"
    private var cachedMixInMs: Long? = null

    /** Whether a fade is currently ramping - gates [poll] against starting a second one
     * on top of an in-progress fade. */
    val isFading: Boolean get() = fadeJob != null

    // The two tracks the running fade is between, and whether the main player has
    // already moved on to the incoming one - see onPlaylistChanged.
    private var fadeOutgoingId: String? = null
    private var fadeIncomingId: String? = null
    private var fadeAdvanced = false

    /** Whether the fade's ramp currently sets the main player's volume - from the
     * hand-over to the end of the fade. Before the hand-over (the tail still loading)
     * the main player plays on at the normal level. */
    val controlsMainVolume: Boolean get() = isFading && fadeAdvanced

    /**
     * Called by [PlaybackService] whenever the main player's playlist changes. Most such
     * changes don't touch the fade at all - the background queue fill, "add to queue" and
     * the autoplay radio all just add items around it - so the fade only stops when the
     * track it fades from (before the hand-over) or to (both before and after) is no
     * longer the one in that place. Cancelling on every change cut fades off at random.
     */
    fun onPlaylistChanged() {
        if (!isFading) return
        val current = mainPlayer.currentMediaItem?.mediaId
        val stillValid = if (fadeAdvanced) {
            current == fadeIncomingId
        } else {
            current == fadeOutgoingId && nextMediaId() == fadeIncomingId
        }
        if (!stillValid) cancelFade()
    }

    /** Call roughly every 200ms from [PlaybackService]'s poll loop with the user's
     * configured crossfade length (0 = off) and a hook to mark the outgoing track as
     * "finished" rather than "skipped" for analytics before it's advanced (see
     * [PlayerController.markCurrentTrackCompletedForCrossfade]). Every check here is a
     * cheap field read/comparison (plus, at most once per transition, one indexed
     * Room lookup per track), so the 0-duration (default, crossfade off) path costs
     * nothing beyond this one early return - no secondary player, no volume changes. */
    suspend fun poll(crossfadeDurationMs: Long, onBeforeAdvance: () -> Unit) =
        androidx.tracing.traceAsync("Crossfade.poll", 0) { pollTraced(crossfadeDurationMs, onBeforeAdvance) }

    private suspend fun pollTraced(crossfadeDurationMs: Long, onBeforeAdvance: () -> Unit) {
        if (isFading) {
            return
        }
        // Self-heal: a fade left the main player silenced only if some path skipped
        // cleanup. Cheap to check every tick, and the alternative is an app that plays
        // nothing until it is restarted.
        val master = masterVolume()
        if (mainPlayer.volume != master) mainPlayer.volume = master
        if (crossfadeDurationMs <= 0L) return
        if (!mainPlayer.isPlaying) return
        // A repeated single track fading into itself would need to crossfade against
        // its own still-loading start, which isn't what this feature is for.
        if (mainPlayer.repeatMode == Player.REPEAT_MODE_ONE) return
        // Only considers ExoPlayer's own (already gapped) item list, which is exactly
        // what's wanted here - a SoundCloudDrmOnlyException slot was never loaded into
        // it in the first place (see PlayerController.currentQueue's kdoc), so this
        // naturally never fades into an unplayable gap.
        if (!mainPlayer.hasNextMediaItem()) return
        val duration = mainPlayer.duration
        if (duration == C.TIME_UNSET) return
        val position = mainPlayer.currentPosition
        val remaining = duration - position
        // Below a second there is no tail worth overlapping - and fading over longer
        // than what is actually left would ramp the incoming track up against silence.
        if (remaining < MIN_FADE_MS) return

        val nextIndex = nextWindowIndex()
        val nextTrackId = nextIndex.takeIf { it != C.INDEX_UNSET }?.let { mainPlayer.getMediaItemAt(it).mediaId }
        if (nextTrackId != cachedNextTrackId) {
            cachedNextTrackId = nextTrackId
            cachedMixInMs = nextTrackId?.let { trackAnalysisDao.getByTrackId(it)?.mixInMs }
        }
        // mediaId is the app's own "source:sourceId" track id (see
        // PlayerController.buildMediaItem) - looking it straight up here means this
        // needs no coupling back to PlayerController's own queue bookkeeping.
        val currentTrackId = mainPlayer.currentMediaItem?.mediaId
        if (currentTrackId != cachedCurrentTrackId) {
            cachedCurrentTrackId = currentTrackId
            cachedMixOutMs = currentTrackId?.let { trackAnalysisDao.getByTrackId(it)?.mixOutMs }
        }
        val mixOutMs = cachedMixOutMs
            ?.takeIf { isLocalMediaUri(mainPlayer.currentMediaItem?.localConfiguration?.uri?.scheme) }
            // Always leaves at least MIN_FADE_MS of runway - an analysis result a few
            // ms off the container's own duration (measured independently by decoding,
            // see TrackAnalyzer) should never produce a negative/zero fade window.
            ?.coerceAtMost(duration - MIN_FADE_MS)

        // The two Room lookups above suspend, and the queue can change underneath them
        // (a skip, a queue edit, the background fill inserting items) - every value
        // read before them would then describe some other pair of tracks. Bail out and
        // let the next tick start over against the player's current state.
        if (mainPlayer.currentMediaItem?.mediaId != currentTrackId) return
        val nextIndexNow = nextWindowIndex()
        if (nextIndexNow == C.INDEX_UNSET || nextIndexNow >= mainPlayer.mediaItemCount) return
        if (mainPlayer.getMediaItemAt(nextIndexNow).mediaId != nextTrackId) return

        // Mix points are measured on the downloaded file (see TrackAnalyzer). A stream of
        // the same track can be a different cut or encode - a YouTube upload with a
        // longer intro, say - so they only apply when the local file is what plays.
        val mixInMs = cachedMixInMs?.takeIf {
            isLocalMediaUri(mainPlayer.getMediaItemAt(nextIndexNow).localConfiguration?.uri?.scheme)
        }

        // the tail player is prepared PREPARE_LEAD_MS ahead, so it is ready (buffered) when the fade begins
        val fadeAtMs = mixOutMs ?: (duration - crossfadeDurationMs)
        if (position < fadeAtMs - PREPARE_LEAD_MS) return
        val fadeDurationMs = (duration - fadeAtMs).coerceIn(MIN_FADE_MS, crossfadeDurationMs)
        startFade(maxOf(fadeAtMs, position), fadeDurationMs, mixInMs, onBeforeAdvance)
    }

    /** The next window Media3 itself would advance to via [ExoPlayer.seekToNextMediaItem]
     * - [Timeline.getNextWindowIndex] respects repeat/shuffle mode the same way that
     * call does internally, rather than assuming "current index + 1". */
    private fun nextWindowIndex(): Int {
        val timeline = mainPlayer.currentTimeline
        if (timeline.isEmpty) return C.INDEX_UNSET
        return timeline.getNextWindowIndex(mainPlayer.currentMediaItemIndex, mainPlayer.repeatMode, mainPlayer.shuffleModeEnabled)
    }

    private fun nextMediaId(): String? = nextWindowIndex().takeIf { it != C.INDEX_UNSET }?.let { mainPlayer.getMediaItemAt(it).mediaId }

    /**
     * Fades from the current track into the next one at [fadeAtMs] of the current track. A
     * second player takes over the outgoing tail; it is prepared first and only once it is
     * buffered and the main player has reached [fadeAtMs] does the main player move on to
     * the next track - so the tail is heard from exactly where the main player left it,
     * without a gap. If the tail cannot be made ready in time, nothing changes and the
     * track plays to its end (gapless).
     */
    private fun startFade(fadeAtMs: Long, fadeDurationMs: Long, mixInMs: Long?, onBeforeAdvance: () -> Unit) {
        val uri = mainPlayer.currentMediaItem?.localConfiguration?.uri ?: return
        val outgoingId = mainPlayer.currentMediaItem?.mediaId
        val incomingId = nextMediaId()

        // Only the EQ is wired up here (see renderersFactory); the visualizer tap and
        // 3D-sound stay on the main player.
        val secondary = (renderersFactory?.let { ExoPlayer.Builder(context, it) } ?: ExoPlayer.Builder(context))
            .setMediaSourceFactory(mediaSourceFactory)
            // handleAudioFocus = false: the main player already holds focus for the
            // whole app, and a second focus request here would fight it.
            .setAudioAttributes(mainPlayer.audioAttributes, /* handleAudioFocus= */ false)
            .build()
        secondary.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // The outgoing track's tail failed to (re)load into the secondary
                // player - cancel the whole fade rather than leaving the main player
                // silenced with nothing audible playing underneath it.
                cancelFade()
            }
        })
        // Published before prepare so an immediate load error's cancelFade() finds
        // and releases this player instead of leaving it running under a cancelled fade.
        secondaryPlayer = secondary
        fadeOutgoingId = outgoingId
        fadeIncomingId = incomingId
        fadeAdvanced = false
        secondary.setMediaItem(MediaItem.fromUri(uri))
        secondary.playWhenReady = false
        secondary.volume = masterVolume()
        secondary.seekTo(fadeAtMs)
        secondary.prepare()

        fadeJob = scope.launch {
            try {
                var startAt = fadeAtMs
                var tries = 0
                while (true) {
                    // buffered at the start point
                    val readyBy = android.os.SystemClock.elapsedRealtime() + PREPARE_TIMEOUT_MS
                    while (secondary.playbackState != Player.STATE_READY) {
                        if (secondaryPlayer !== secondary || mainPlayer.currentMediaItem?.mediaId != outgoingId) return@launch
                        if (android.os.SystemClock.elapsedRealtime() > readyBy) return@launch
                        delay(10)
                    }
                    // wait for the main player to get there
                    while (mainPlayer.currentPosition < startAt - 5) {
                        if (secondaryPlayer !== secondary || mainPlayer.currentMediaItem?.mediaId != outgoingId || !mainPlayer.isPlaying) return@launch
                        delay(5)
                    }
                    if (mainPlayer.currentPosition - startAt <= HANDOVER_SLACK_MS) break
                    // it got there late (slow load): start the tail a little ahead again
                    if (++tries > 2) return@launch
                    startAt = mainPlayer.currentPosition + 300
                    if (mainPlayer.duration != C.TIME_UNSET && mainPlayer.duration - startAt < MIN_FADE_MS) return@launch
                    secondary.seekTo(startAt)
                }
                val fadeMs = if (mainPlayer.duration != C.TIME_UNSET) min(fadeDurationMs, mainPlayer.duration - startAt) else fadeDurationMs

                // The queue may have changed after the PLAYLIST_CHANGED callback last
                // checked it - the mix-in point belongs to the track picked at the start.
                val nextIndex = nextWindowIndex()
                if (nextIndex == C.INDEX_UNSET || mainPlayer.getMediaItemAt(nextIndex).mediaId != incomingId) return@launch

                outgoingId?.takeIf { it.isNotEmpty() }?.let(onTailStarted)
                secondary.play()
                // Marks the outgoing track completed *before* the seek below so the resulting
                // onMediaItemTransition (reason SEEK, not AUTO) doesn't report it to analytics
                // as a user skip.
                onBeforeAdvance()
                fadeAdvanced = true
                mainPlayer.volume = 0f
                if (mixInMs != null && nextIndex != C.INDEX_UNSET) {
                    mainPlayer.seekTo(nextIndex, mixInMs)
                } else {
                    mainPlayer.seekToNextMediaItem()
                }

                // the ramp runs on the incoming track's progress: while it still buffers, nothing moves
                var elapsed = 0L
                var last = android.os.SystemClock.elapsedRealtime()
                while (elapsed < fadeMs) {
                    delay(RAMP_INTERVAL_MS)
                    // The fade may have been cancelled (and this secondary released)
                    // while we were suspended - writing a volume to a released player
                    // throws on its dead internal thread.
                    if (secondaryPlayer !== secondary) return@launch
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (mainPlayer.isPlaying) elapsed += now - last
                    last = now
                    val t = (elapsed.toFloat() / fadeMs).coerceIn(0f, 1f)
                    // Equal-power curve: sin^2 + cos^2 = 1 keeps combined loudness
                    // roughly constant through the middle of the fade, unlike a plain
                    // linear ramp on both players (which dips there).
                    val angle = t * HALF_PI
                    val master = masterVolume()
                    mainPlayer.volume = sin(angle) * master
                    secondary.volume = cos(angle) * master
                }
            } finally {
                // Only if this fade still owns the player state: a cancelled fade's
                // finally runs on a later dispatch, by which time cancelFade() already
                // cleaned up and the next poll may have started a new fade - releasing
                // that one's secondary player (and un-silencing its main player) here
                // would cut it off mid-ramp.
                if (secondaryPlayer === secondary) cleanup()
            }
        }
    }

    /** Cancels an in-flight fade immediately - a manual skip/seek/pause, a queue
     * change that replaces either track of the fade, playback stopping, or the service being destroyed all call this. Safe to
     * call when no fade is running. Cleans up synchronously here (rather than only
     * relying on the ramp coroutine's own cancellation) so the main player is never
     * left silenced for even one more poll tick, and [cleanup] being idempotent means
     * the coroutine's own `finally` running later is harmless. */
    fun cancelFade() {
        fadeJob?.cancel()
        cleanup()
    }

    private fun cleanup() {
        // Never leave the main player at volume 0 - that would silence the app
        // permanently once a fade is interrupted.
        mainPlayer.volume = masterVolume()
        secondaryPlayer?.release()
        secondaryPlayer = null
        fadeJob = null
        fadeOutgoingId = null
        fadeIncomingId = null
        fadeAdvanced = false
    }
}

/** Whether a media item's URI scheme points at a file on the device (a download) rather
 * than a network stream - the only case where analysed mix points are trusted. */
internal fun isLocalMediaUri(scheme: String?): Boolean =
    scheme.equals("content", ignoreCase = true) || scheme.equals("file", ignoreCase = true)
