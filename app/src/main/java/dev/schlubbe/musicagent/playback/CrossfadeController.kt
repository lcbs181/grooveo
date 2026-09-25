package dev.schlubbe.musicagent.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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

/** See [CrossfadeController.isOwnAdvance]. */
private const val OWN_ADVANCE_GRACE_MS = 700L

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
 * with no analysis (streamed-only, or not analyzed yet) falls back to today's plain
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
    private val scope: CoroutineScope,
    private val trackAnalysisDao: TrackAnalysisDao,
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

    private var ownAdvanceAtMs = 0L

    /** Whether the player events arriving right now are the echo of this class's own
     * [ExoPlayer.seekToNextMediaItem] rather than a real user skip - [PlaybackService]
     * checks it before cancelling a fade. A short time window, not a flag cleared
     * straight after the call: ExoPlayer delivers the resulting transition,
     * discontinuity and isPlaying events across several dispatches, so a flag only set
     * during the call itself was already false when they arrived and every fade
     * cancelled itself. A real skip within the first [OWN_ADVANCE_GRACE_MS] of a fade is
     * ignored as a result, which is a fair trade for fades lasting seconds. */
    val isOwnAdvance: Boolean
        get() = android.os.SystemClock.elapsedRealtime() - ownAdvanceAtMs < OWN_ADVANCE_GRACE_MS

    /** Call roughly every 200ms from [PlaybackService]'s poll loop with the user's
     * configured crossfade length (0 = off) and a hook to mark the outgoing track as
     * "finished" rather than "skipped" for analytics before it's advanced (see
     * [PlayerController.markCurrentTrackCompletedForCrossfade]). Every check here is a
     * cheap field read/comparison (plus, at most once per transition, one indexed
     * Room lookup per track), so the 0-duration (default, crossfade off) path costs
     * nothing beyond this one early return - no secondary player, no volume changes. */
    suspend fun poll(crossfadeDurationMs: Long, onBeforeAdvance: () -> Unit) {
        if (isFading) {
            return
        }
        // Self-heal: a fade left the main player silenced only if some path skipped
        // cleanup. Cheap to check every tick, and the alternative is an app that plays
        // nothing until it is restarted.
        if (mainPlayer.volume != 1f) mainPlayer.volume = 1f
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
        val mixInMs = cachedMixInMs

        // mediaId is the app's own "source:sourceId" track id (see
        // PlayerController.buildMediaItem) - looking it straight up here means this
        // needs no coupling back to PlayerController's own queue bookkeeping.
        val currentTrackId = mainPlayer.currentMediaItem?.mediaId
        if (currentTrackId != cachedCurrentTrackId) {
            cachedCurrentTrackId = currentTrackId
            cachedMixOutMs = currentTrackId?.let { trackAnalysisDao.getByTrackId(it)?.mixOutMs }
        }
        val mixOutMs = cachedMixOutMs
            // Always leaves at least MIN_FADE_MS of runway - an analysis result a few
            // ms off the container's own duration (measured independently by decoding,
            // see TrackAnalyzer) should never produce a negative/zero fade window.
            ?.coerceAtMost(duration - MIN_FADE_MS)

        if (mixOutMs != null) {
            if (position < mixOutMs) return
            val fadeDurationMs = (duration - mixOutMs).coerceIn(MIN_FADE_MS, crossfadeDurationMs)
            startFade(min(fadeDurationMs, remaining), mixInMs, onBeforeAdvance)
            return
        }

        if (remaining > crossfadeDurationMs) return
        startFade(remaining, mixInMs, onBeforeAdvance)
    }

    /** The next window Media3 itself would advance to via [ExoPlayer.seekToNextMediaItem]
     * - [Timeline.getNextWindowIndex] respects repeat/shuffle mode the same way that
     * call does internally, rather than assuming "current index + 1". */
    private fun nextWindowIndex(): Int {
        val timeline = mainPlayer.currentTimeline
        if (timeline.isEmpty) return C.INDEX_UNSET
        return timeline.getNextWindowIndex(mainPlayer.currentMediaItemIndex, mainPlayer.repeatMode, mainPlayer.shuffleModeEnabled)
    }

    private fun startFade(fadeDurationMs: Long, mixInMs: Long?, onBeforeAdvance: () -> Unit) {
        val uri = mainPlayer.currentMediaItem?.localConfiguration?.uri ?: return
        val position = mainPlayer.currentPosition

        // No renderers factory (unlike the main player) - the secondary only ever
        // plays a few seconds of a track already audible on the main player, so it
        // has no visualizer tap or EQ/3D session of its own to wire up.
        val secondary = ExoPlayer.Builder(context)
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
        // Published before prepare/play so an immediate load error's cancelFade() finds
        // and releases this player instead of leaving it running under a cancelled fade.
        secondaryPlayer = secondary
        secondary.setMediaItem(MediaItem.fromUri(uri))
        secondary.seekTo(position)
        secondary.volume = 1f
        secondary.prepare()
        secondary.play()

        // Marks the outgoing track completed *before* the seek below so the resulting
        // onMediaItemTransition (reason SEEK, not AUTO) doesn't report it to analytics
        // as a user skip.
        onBeforeAdvance()
        ownAdvanceAtMs = android.os.SystemClock.elapsedRealtime()
        val nextIndex = nextWindowIndex()
        if (mixInMs != null && nextIndex != C.INDEX_UNSET) {
            mainPlayer.seekTo(nextIndex, mixInMs)
        } else {
            mainPlayer.seekToNextMediaItem()
        }
        mainPlayer.volume = 0f

        fadeJob = scope.launch {
            try {
                var elapsed = 0L
                while (elapsed < fadeDurationMs) {
                    delay(RAMP_INTERVAL_MS)
                    // The fade may have been cancelled (and this secondary released)
                    // while we were suspended - writing a volume to a released player
                    // throws on its dead internal thread.
                    if (secondaryPlayer !== secondary) return@launch
                    elapsed += RAMP_INTERVAL_MS
                    val t = (elapsed.toFloat() / fadeDurationMs).coerceIn(0f, 1f)
                    // Equal-power curve: sin^2 + cos^2 = 1 keeps combined loudness
                    // roughly constant through the middle of the fade, unlike a plain
                    // linear ramp on both players (which dips there).
                    val angle = t * HALF_PI
                    mainPlayer.volume = sin(angle)
                    secondary.volume = cos(angle)
                }
            } finally {
                cleanup()
            }
        }
    }

    /** Cancels an in-flight fade immediately - a manual skip/seek/pause, a queue
     * change, playback stopping, or the service being destroyed all call this. Safe to
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
        mainPlayer.volume = 1f
        secondaryPlayer?.release()
        secondaryPlayer = null
        fadeJob = null
    }
}
