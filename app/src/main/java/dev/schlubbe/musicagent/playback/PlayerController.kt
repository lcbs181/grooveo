package dev.schlubbe.musicagent.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.schlubbe.musicagent.data.extract.ResolvedStream
import dev.schlubbe.musicagent.data.extract.StreamResolverRegistry
import dev.schlubbe.musicagent.data.extract.soundcloud.SoundCloudDrmOnlyException
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.repository.EventReporter
import dev.schlubbe.musicagent.data.repository.FeedRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PlayerController"

private const val DRM_UNAVAILABLE_MESSAGE = "Titel nicht verfügbar – auf SoundCloud DRM-geschützt oder nur als Vorschau, und kein passender Titel auf YouTube Music gefunden."
private const val PLAYBACK_ERROR_MESSAGE = "Wiedergabe unterbrochen – Verbindung prüfen und erneut versuchen."
private const val MAX_PLAYBACK_ERROR_RETRIES = 2

// How long ensureConnected() waits for MediaController.Builder(...).buildAsync() to
// resolve before giving up. Without this, a PlaybackService that MIUI (or another
// OEM's battery manager) has killed/throttled in the background left every command
// (play/pause/skip/...) awaiting a Deferred that never completes - the button taps
// silently did nothing at all, no crash, no message, indistinguishable from a UI bug.
// See the connection-lost branch in togglePlayPause()/skipToNext()/etc below.
private const val CONNECT_TIMEOUT_MS = 5_000L

/** How many predicted tracks [PlayerController.continueWithRadio] tries to resolve
 * and append per pass, once "Autoplay-Radio" continues a queue that has run out. */
private const val EXTEND_BATCH_SIZE = 8

/** How many of the most recently played tracks count as "the session" handed to
 * [FeedRepository.predictNext] - see that function's own kdoc for why this is
 * weighted so heavily over the user's lifetime history. */
private const val SESSION_CONTEXT_SIZE = 5

data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    // From the search result's own duration metadata, not the stream -- kept as the
    // source of truth even though on-device HLS (SoundCloud) can now report its own
    // duration too, since search-result metadata is available immediately on tap
    // while the stream itself is still loading.
    val durationMs: Long = 0L,
    val currentTrackId: String? = null,
    val artworkUrl: String? = null,
    val queue: List<TrackResultDto> = emptyList(),
    val queueIndex: Int = -1,
    val shuffleEnabled: Boolean = false,
    // Player.REPEAT_MODE_OFF / _ONE / _ALL.
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    // Whether the currently loaded item is playing from its local download rather than
    // a network stream - drives the Player screen's stream/download switch icon.
    val isLocalPlayback: Boolean = false,
    // Whether the current track has a completed local download available at all,
    // regardless of which one is currently playing - the switch button only shows
    // when this is true.
    val hasLocalDownload: Boolean = false,
    // True while a requested track is being resolved/loaded but hasn't started
    // playing yet - on-device stream resolution (a real network round-trip, unlike
    // the old backend's near-instant proxy) can take a moment, and without this the
    // Player screen/mini bar just kept showing the *previous* track with no visual
    // change, making a tap look like it didn't register.
    val isLoading: Boolean = false,
    // Which track [isLoading] refers to (source:sourceId) - lets a tapped list row
    // show its own loading spinner rather than only the Player screen.
    val loadingTrackId: String? = null,
    // True when the *current* queue slot ([currentTrackId]/title/artist/artworkUrl
    // above still describe it) is a SoundCloudDrmOnlyException track - nothing was
    // ever handed to ExoPlayer for it, playback is paused, and the queue does NOT
    // auto-advance past it. The Player screen shows [unavailableMessage] in place of
    // transport controls (skip-only) instead of silently jumping to another track,
    // which is what used to happen since such tracks were just dropped from the
    // resolved queue with no trace. See PlayerController's logicalQueue/
    // exoIndexForLogical for how a track can be "selected" here without being loaded.
    val isUnavailable: Boolean = false,
    val unavailableMessage: String? = null,
)

/** Single shared [MediaController], connected lazily, that every screen plays through. */
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val eventReporter: EventReporter,
    private val downloadDao: DownloadDao,
    private val streamResolverRegistry: StreamResolverRegistry,
    private val searchRepository: SearchRepository,
    private val feedRepository: FeedRepository,
) {
    private var controller: MediaController? = null

    // Guards ensureConnected() against two callers racing to build a MediaController
    // before `controller` is first set (e.g. app start and an immediate playback tap)
    // - without it, both would call buildAsync(), and the loser's own listener stays
    // registered on an orphaned MediaController nobody dispatches commands through.
    private val connectMutex = Mutex()

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    // The Player screen's chosen Visualizer style - a singleton field (not
    // per-screen remembered state) so it survives closing and reopening the
    // Player, the same way the queue/current track already do.
    private val _vizVariant = MutableStateFlow("particles")
    val vizVariant: StateFlow<String> = _vizVariant.asStateFlow()
    fun setVizVariant(variant: String) {
        _vizVariant.value = variant
    }

    // Real-time FFT-derived spectrum + beat scalars from AudioVisualizerController,
    // pushed by PlaybackService (which owns the actual audio session) - see that
    // class's kdoc for the capture/reduction details.
    private val _visualizerFrame = MutableStateFlow(EMPTY_VISUALIZER_FRAME)
    val visualizerFrame: StateFlow<VisualizerFrame> = _visualizerFrame.asStateFlow()
    fun updateVisualizerFrame(frame: VisualizerFrame) {
        _visualizerFrame.value = frame
    }

    // Tracks what's currently loaded so play_complete/skip can be reported
    // against it once we know how the track ended. currentQueue is the *logical*
    // queue - every requested track, in order, INCLUDING SoundCloudDrmOnlyException
    // ones (see PlaybackUiState.isUnavailable) - which is why it can be longer than
    // what's actually loaded into the MediaController. exoIndexForLogical maps a
    // position in currentQueue to the corresponding index in the MediaController's
    // own item list, or null if that logical slot was never loaded there (DRM-only -
    // there is nothing playable to load). currentQueueIndex is the logical position
    // ([PlaybackUiState.queueIndex]), which is why skip/seek navigation below walks
    // this mapping instead of the MediaController's native
    // seekToNextMediaItem()/hasNextMediaItem() - those only know about the
    // (shorter, gapped) loaded item list, not the full logical queue.
    private var currentQueue: List<TrackResultDto> = emptyList()
    private var exoIndexForLogical: List<Int?> = emptyList()
    private var currentQueueIndex: Int = -1
    private var currentTrack: TrackResultDto? = null
    private var currentTrackCompleted = false

    // Counts consecutive PlaybackExceptions for the *current* track (reset by
    // resetPlaybackErrorState(), called from every real track-change/manual-pause
    // entry point below) so a network hiccup gets a couple of automatic retries
    // instead of silently leaving playback stopped, while a track that's genuinely
    // broken doesn't retry forever - and so a track right after one that exhausted
    // its retries still gets its own full retry budget.
    private var playbackErrorRetryCount = 0

    // The pending delayed retry scheduled by onPlayerError, if any - tracked so it
    // can be cancelled from resetPlaybackErrorState() when the user manually pauses
    // or the controller moves to a different track while the retry is still
    // waiting out its delay. Without this, a stale retry can fire prepare()+play()
    // against whatever the controller happens to be sitting on by then (silently
    // overriding a manual pause, or re-playing an unrelated track).
    private var playbackRetryJob: Job? = null

    private fun resetPlaybackErrorState() {
        playbackRetryJob?.cancel()
        playbackRetryJob = null
        playbackErrorRetryCount = 0
    }

    /** Called by [CrossfadeController] just before it seeks the main player onto the
     * next track early - that seek surfaces here as onMediaItemTransition with reason
     * SEEK, which would otherwise report the outgoing track as a user skip instead of
     * a normal completion. */
    /** Reports the outgoing track as finished right before a crossfade advances the
     * player early. Without this the resulting SEEK transition would either log it as
     * a user skip or - once the flag is set - as nothing at all, and the skip signal
     * feeds [FeedRepository]'s recommendations. */
    fun markCurrentTrackCompletedForCrossfade() {
        val track = currentTrack ?: return
        if (currentTrackCompleted) return
        eventReporter.playComplete(track, (track.durationSec ?: 0) * 1000L)
        currentTrackCompleted = true
    }

    // Singleton-scoped: outlives any one screen, so fire-and-forget DB lookups
    // triggered from the (non-suspend) Player.Listener callbacks below can use it
    // without needing a ViewModel's viewModelScope in hand.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Guards extendQueue() against two passes running at once - see that function.
    private val extendQueueMutex = Mutex()

    // Set right before we call replaceMediaItem()+seekTo() on the *current* index (see
    // toggleSource), so the resulting onMediaItemTransition callback is consumed as a
    // seek, not treated as a real track change that would fire spurious
    // play_complete/skip/play_start events.
    private var suppressNextTransitionEvent = false

    // Sleep timer: pauses playback once, independent of queue/track changes -- a
    // plain delay()+pause() on the shared scope rather than anything queue-aware,
    // since "stop whatever is playing after N minutes" is the whole feature.
    private var sleepTimerJob: Job? = null
    private val _sleepTimerEndAtMs = MutableStateFlow<Long?>(null)
    val sleepTimerEndAtMs: StateFlow<Long?> = _sleepTimerEndAtMs.asStateFlow()

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        val durationMs = minutes * 60_000L
        _sleepTimerEndAtMs.value = System.currentTimeMillis() + durationMs
        sleepTimerJob = scope.launch {
            delay(durationMs)
            controller?.pause()
            _sleepTimerEndAtMs.value = null
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerEndAtMs.value = null
    }

    private suspend fun ensureConnected(): MediaController = connectMutex.withLock {
        controller?.let { return@withLock it }

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val newController = try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                MediaController.Builder(context, sessionToken).buildAsync().await()
            }
        } catch (e: TimeoutCancellationException) {
            // Deliberately not the same as a real CancellationException from this
            // call's own coroutine being cancelled (e.g. the screen closing mid-tap) -
            // that still needs to propagate and unwind normally. This one means the
            // service didn't answer in time and every caller (togglePlayPause,
            // skipToNext, ...) needs to see it as a real, catchable failure instead.
            throw IllegalStateException("PlaybackService did not connect within ${CONNECT_TIMEOUT_MS}ms", e)
        }

        newController.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) resetPlaybackErrorState()
                _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
            }

            // ExoPlayer drops to STATE_IDLE on any fatal error (network hiccup mid-stream,
            // a stale/expired signed CDN URL, etc.) and just stops -- there was no
            // handling here at all before, so a transient error silently ended playback
            // with no recovery and no feedback. Retry a couple of times first (a fresh
            // prepare() re-resolves/reopens the same MediaItem's data source), then fall
            // back to the same isUnavailable messaging DRM-only tracks already use (the
            // play button retries directly from there instead of being a dead end - see
            // togglePlayPause). The scheduled retry double-checks the media item is still
            // the one that errored before acting, since the delay window gives the user
            // time to pause or skip away in the meantime.
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w(TAG, "Playback error (retry $playbackErrorRetryCount/$MAX_PLAYBACK_ERROR_RETRIES)", error)
                val controllerRef = controller ?: return
                if (playbackErrorRetryCount < MAX_PLAYBACK_ERROR_RETRIES) {
                    playbackErrorRetryCount++
                    val mediaIdAtError = controllerRef.currentMediaItem?.mediaId
                    playbackRetryJob?.cancel()
                    playbackRetryJob = scope.launch {
                        delay(1_000L * playbackErrorRetryCount)
                        if (controller?.currentMediaItem?.mediaId != mediaIdAtError) return@launch
                        controllerRef.prepare()
                        controllerRef.play()
                    }
                } else {
                    _playbackState.value = _playbackState.value.copy(
                        isUnavailable = true,
                        unavailableMessage = PLAYBACK_ERROR_MESSAGE,
                    )
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                // While frozen on a DRM-unavailable logical slot (see the AUTO-transition
                // gap check below), the MediaController may still be sitting on/starting
                // the *next real* item under the hood and broadcast fresh metadata for
                // it - applying that here would silently overwrite the unavailable
                // track's title/artist/artwork with the next track's, which is exactly
                // the silent-skip appearance this is meant to prevent. Ignored until a
                // real move (moveToLogicalIndex/playQueue) clears isUnavailable again.
                //
                // Deliberately narrowed to the DRM case. isUnavailable is now also set
                // for ordinary transient playback errors, and a blanket gate here meant
                // one network blip could suppress every later title/artist/artwork
                // update - Media3 only emits this on *change*, so a swallowed event is
                // never re-sent and the UI stayed blank or stale until the next
                // playQueue. That was the "sometimes the thumbnail and track info don't
                // load" report.
                val state = _playbackState.value
                if (state.isUnavailable && state.unavailableMessage == DRM_UNAVAILABLE_MESSAGE) return
                _playbackState.value = _playbackState.value.copy(
                    title = mediaMetadata.title?.toString(),
                    artist = mediaMetadata.artist?.toString(),
                    artworkUrl = mediaMetadata.artworkUri?.toString(),
                )
            }

            // Fires when playback moves to a different item in the queue — either
            // ExoPlayer auto-advancing (reason AUTO) or a skip/seek-to-item call
            // (reason SEEK). PLAYLIST_CHANGED is skipped since that's the transition
            // setMediaItems() itself causes when playQueue() starts a fresh queue,
            // already accounted for there. A suppressed transition is our own
            // toggleSource() reloading the *same* queue index -- not a real track change.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (suppressNextTransitionEvent) {
                    suppressNextTransitionEvent = false
                    return
                }
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
                resetPlaybackErrorState()
                val newExoIndex = controller?.currentMediaItemIndex ?: return
                val newLogicalIndex = exoIndexForLogical.indexOf(newExoIndex).takeIf { it >= 0 } ?: return

                // AUTO means ExoPlayer advanced on its own to the next item in *its own*
                // (gapped) item list - since DRM-only logical slots were never loaded
                // there in the first place, that native next item can correspond to a
                // logical index further ahead than +1, meaning one or more unavailable
                // tracks sit in between and were about to be skipped over silently. Land
                // on the first one instead of letting the transition to newLogicalIndex
                // happen; the real track ExoPlayer already moved to stays loaded
                // (paused) there, ready for when the user manually skips past it.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && newLogicalIndex > currentQueueIndex + 1) {
                    val gapIndex = currentQueueIndex + 1
                    val gapTrack = currentQueue.getOrNull(gapIndex)
                    if (gapTrack != null) {
                        controller?.pause()
                        currentTrack?.let { previous ->
                            if (!currentTrackCompleted) eventReporter.playComplete(previous, (previous.durationSec ?: 0) * 1000L)
                        }
                        currentTrack = gapTrack
                        currentTrackCompleted = false
                        currentQueueIndex = gapIndex
                        _playbackState.value = _playbackState.value.copy(
                            isPlaying = false,
                            title = gapTrack.title,
                            artist = gapTrack.artist,
                            artworkUrl = gapTrack.thumbnailUrl,
                            durationMs = (gapTrack.durationSec ?: 0) * 1000L,
                            currentTrackId = "${gapTrack.source}:${gapTrack.sourceId}",
                            queueIndex = gapIndex,
                            isUnavailable = true,
                            unavailableMessage = DRM_UNAVAILABLE_MESSAGE,
                        )
                        return
                    }
                }

                val newTrack = currentQueue.getOrNull(newLogicalIndex) ?: return

                currentTrack?.let { previous ->
                    if (!currentTrackCompleted) {
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                            eventReporter.playComplete(previous, (previous.durationSec ?: 0) * 1000L)
                        } else {
                            eventReporter.skip(previous)
                        }
                    }
                }

                currentTrack = newTrack
                currentTrackCompleted = false
                currentQueueIndex = newLogicalIndex
                eventReporter.playStart(newTrack)
                // title/artist/artworkUrl are set from our own track data here, not
                // left to onMediaMetadataChanged alone. That callback used to be their
                // only writer on this path, and Media3 delivers the transition and the
                // metadata as two separate IPC messages - so any time the metadata
                // event was missed or arrived while a gate was up, the UI kept the
                // previous track's text and cover, or none at all. We already know
                // exactly what is playing; there is no reason to wait to be told.
                _playbackState.value = _playbackState.value.copy(
                    title = newTrack.title,
                    artist = newTrack.artist,
                    artworkUrl = newTrack.thumbnailUrl,
                    durationMs = (newTrack.durationSec ?: 0) * 1000L,
                    currentTrackId = "${newTrack.source}:${newTrack.sourceId}",
                    queueIndex = newLogicalIndex,
                    isLocalPlayback = isCurrentItemLocal(),
                    isUnavailable = false,
                    unavailableMessage = null,
                )
                refreshDownloadAvailability(newTrack)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    currentTrack?.let { eventReporter.playComplete(it, currentPositionMs()) }
                    currentTrackCompleted = true
                    // STATE_ENDED only fires once the whole queue is exhausted with no
                    // repeat mode active (a single track ending mid-queue instead fires
                    // onMediaItemTransition with reason AUTO) - the right moment to
                    // extend the queue with something else, if the user asked for it.
                    if (settingsRepository.autoplayRadioCached) continueWithRadio()
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _playbackState.value = _playbackState.value.copy(shuffleEnabled = shuffleModeEnabled)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _playbackState.value = _playbackState.value.copy(repeatMode = repeatMode)
            }
        })

        controller = newController
        _playbackState.value = _playbackState.value.copy(
            shuffleEnabled = newController.shuffleModeEnabled,
            repeatMode = newController.repeatMode,
        )
        return newController
    }

    suspend fun playTrack(track: TrackResultDto) = playQueue(listOf(track), 0)

    // Tracks the track key ("source:sourceId") a playQueue() call is currently
    // resolving, so a duplicate tap on the exact same track while it's still loading
    // is ignored instead of firing a second resolve and restarting playback once the
    // second one lands - see PlaybackUiState.isLoading for the user-visible half of
    // this fix.
    private var pendingTrackKey: String? = null

    // Incremented at the start of every playQueue()/playLocalDownload() call. Stream
    // resolution is a network round-trip (not instant), so two different tracks
    // requested in quick succession can otherwise both run to completion and race to
    // mutate currentQueue/currentTrack/etc. and issue MediaController commands - a
    // slow earlier request finishing after a faster later one would silently "un-skip"
    // playback back to a track the user already navigated away from. Each call
    // captures its own generation number and bails out (before touching any shared
    // state) if a newer call has started by the time its resolve finishes.
    private var playRequestGeneration = 0

    /** A single logical queue slot's resolution outcome - see [currentQueue]'s kdoc.
     * [Playable] gets an actual MediaController item; [DrmBlocked] stays in the
     * logical queue as an unplayable placeholder instead of being dropped, which is
     * what let a SoundCloudDrmOnlyException track disappear with no trace before. */
    private sealed class TrackResolution(val track: TrackResultDto) {
        class Playable(track: TrackResultDto, val resolved: ResolvedStream) : TrackResolution(track)
        class DrmBlocked(track: TrackResultDto) : TrackResolution(track)
    }

    /** Loads [tracks] as the playback queue starting at [startIndex] — everything
     * after it becomes the "up next" list surfaced on the Player screen.
     *
     * In data-saver mode, tracks without a completed local download are silently
     * dropped from the queue (rather than blocking the whole queue, or falling back to
     * streaming) since that keeps the rest of an otherwise-downloaded queue playable.
     * Outside data-saver mode, only [startIndex]'s own stream is resolved before
     * playback starts - see [playQueueStreaming]'s kdoc for why the rest of a large
     * queue (a whole Likes/Library list, commonly) must never block that. Tracks whose
     * on-device stream resolution fails (the client-side extractor can break more
     * often than the old stable backend did) stay in the logical queue as an
     * unplayable placeholder (see [currentQueue]'s kdoc) rather than being dropped, so
     * landing on one - by tapping it directly, or the queue naturally reaching it -
     * shows "Titel nicht verfügbar" instead of silently continuing past it. */
    suspend fun playQueue(tracks: List<TrackResultDto>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val requestedStartTrack = tracks[startIndex]
        val requestedKey = "${requestedStartTrack.source}:${requestedStartTrack.sourceId}"
        if (pendingTrackKey == requestedKey) return
        pendingTrackKey = requestedKey
        val myGeneration = ++playRequestGeneration
        // A fresh load replaces the controller's item(s) wholesale, which fires
        // onMediaItemTransition with reason PLAYLIST_CHANGED - explicitly ignored
        // there, so a pending retry from a *previous* track's error needs
        // cancelling here instead, before it can fire prepare()+play() against
        // whatever ends up loaded from this call.
        resetPlaybackErrorState()
        // Show the tapped track's own title/artist/cover straight away. Resolving a
        // queue is a real network round trip per track, and this used to leave the UI
        // on "Wird geladen..." with a blank cover for the whole time - which on a long
        // queue is seconds, and reads as "the artwork and info didn't load".
        _playbackState.value = _playbackState.value.copy(
            isLoading = true,
            loadingTrackId = requestedKey,
            title = requestedStartTrack.title,
            artist = requestedStartTrack.artist,
            artworkUrl = requestedStartTrack.thumbnailUrl,
            durationMs = (requestedStartTrack.durationSec ?: 0) * 1000L,
        )

        try {
            val mediaController = ensureConnected()
            if (settingsRepository.dataSaverModeCached) {
                playQueueDataSaver(tracks, startIndex, myGeneration, mediaController)
            } else {
                playQueueStreaming(tracks, startIndex, requestedStartTrack, myGeneration, mediaController)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Anything unexpected here (a resolve throwing outside the Result-wrapped
            // paths, a MediaController command failing) used to propagate silently:
            // the "show the tapped track's info immediately" update above had already
            // landed, so the screen was left displaying the track that failed to load
            // while whatever was loaded before kept playing underneath it - "the new
            // track's title shows, but pressing play still plays the old one", with no
            // error anywhere. Log it (so a recurrence is actually diagnosable) and put
            // the UI back in sync with what's really still loaded, rather than leaving
            // it pointed at a track that never became real.
            Log.e(TAG, "playQueue failed for $requestedKey", e)
            showToast(resolveFailureMessage(requestedStartTrack.title, e))
            restoreStateToCurrentTrack()
        } finally {
            if (pendingTrackKey == requestedKey) pendingTrackKey = null
            // Only the latest request may clear the loading flags - a stale request
            // that bailed out above (superseded generation) must not clobber the
            // still-in-flight newer request's own isLoading/loadingTrackId state.
            if (myGeneration == playRequestGeneration) {
                _playbackState.value = _playbackState.value.copy(isLoading = false, loadingTrackId = null)
            }
        }
    }

    /** Data-saver branch of [playQueue]: [resolveLocalOnly] only ever hits Room (no
     * network), so unlike [playQueueStreaming] there is no reason to split this into a
     * fast start + background fill - the whole thing is already fast. */
    private suspend fun playQueueDataSaver(
        tracks: List<TrackResultDto>,
        startIndex: Int,
        myGeneration: Int,
        mediaController: MediaController,
    ) {
        val (playable, newQueueIndex) = resolveLocalOnly(tracks, startIndex)
        if (myGeneration != playRequestGeneration) return

        if (playable.isEmpty()) {
            showToast("Datensparmodus: Keine heruntergeladenen Titel in dieser Auswahl.")
            // Nothing here actually loaded - roll the "show the tapped track
            // immediately" update in playQueue back to whatever's still really
            // playing, instead of leaving the screen pointed at a track that never
            // started.
            restoreStateToCurrentTrack()
            return
        }

        currentTrack?.let { previous -> if (!currentTrackCompleted) eventReporter.skip(previous) }

        val queueTracks = playable.map { it.first }
        val mediaItems = playable.map { (track, resolved) -> buildMediaItem(track, resolved) }
        val startTrack = queueTracks[newQueueIndex]

        currentQueue = queueTracks
        exoIndexForLogical = queueTracks.indices.toList()
        currentQueueIndex = newQueueIndex
        currentTrack = startTrack
        currentTrackCompleted = false

        _playbackState.value = _playbackState.value.copy(
            durationMs = (startTrack.durationSec ?: 0) * 1000L,
            currentTrackId = "${startTrack.source}:${startTrack.sourceId}",
            queue = queueTracks,
            queueIndex = newQueueIndex,
            // Every entry resolveLocalOnly kept is, by construction, a completed local
            // download.
            isLocalPlayback = true,
            isUnavailable = false,
            unavailableMessage = null,
        )

        mediaController.setMediaItems(mediaItems, newQueueIndex, 0L)
        mediaController.prepare()
        eventReporter.playStart(startTrack)
        mediaController.play()
        refreshDownloadAvailability(startTrack)
    }

    /** Streaming branch of [playQueue]. Resolves only [requestedStartTrack]'s own
     * stream (a single on-device network round trip via [resolvePreferLocal], or
     * none at all when it's already downloaded) and starts playback from that alone,
     * then hands the rest of [tracks] to [launchBackgroundQueueFill] to resolve and
     * splice in afterwards.
     *
     * This used to resolve *every* track in [tracks] concurrently and wait for all of
     * them (see git history) before calling prepare()/play() on any of them - fine for
     * a short search-result queue, but tapping one track deep inside a long list (a
     * whole Likes or Library queue, commonly hundreds of tracks) meant waiting on
     * hundreds of on-device SoundCloud/YouTube extraction requests - each a real
     * network call, capped at [StreamResolverRegistry]'s concurrency limit - before
     * the *one* tapped track (already sitting on disk, needing no network at all)
     * made a sound. That is what made downloaded Likes effectively unusable: minutes
     * of silence after a tap. Only the tapped track's own resolution can gate
     * playback now; everything else fills in around it in the background. */
    private suspend fun playQueueStreaming(
        tracks: List<TrackResultDto>,
        startIndex: Int,
        requestedStartTrack: TrackResultDto,
        myGeneration: Int,
        mediaController: MediaController,
    ) {
        val startResult = resolvePreferLocal(requestedStartTrack)
        if (myGeneration != playRequestGeneration) return

        val startResolution: TrackResolution? = when {
            startResult.isSuccess -> TrackResolution.Playable(requestedStartTrack, startResult.getOrThrow())
            startResult.exceptionOrNull() is SoundCloudDrmOnlyException -> TrackResolution.DrmBlocked(requestedStartTrack)
            else -> null
        }

        if (startResolution == null) {
            // The tapped track itself failed to resolve (network error, YouTube
            // rate-limiting, ...). Say so and leave whatever was playing alone - the
            // old fallback resolved the whole list and started some *other* track,
            // so a tap on one song played a different one.
            showToast(resolveFailureMessage(requestedStartTrack.title, startResult.exceptionOrNull()))
            restoreStateToCurrentTrack()
            return
        }

        currentTrack?.let { previous -> if (!currentTrackCompleted) eventReporter.skip(previous) }

        val exoMapping = arrayOfNulls<Int>(tracks.size)
        val mediaItems = mutableListOf<MediaItem>()
        if (startResolution is TrackResolution.Playable) {
            exoMapping[startIndex] = 0
            mediaItems += buildMediaItem(startResolution.track, startResolution.resolved)
        }

        // The full, unresolved [tracks] list becomes the logical queue immediately -
        // every slot other than [startIndex] starts out mapped to `null` (nothing
        // loaded yet, same representation [currentQueue]'s kdoc already uses for a
        // DRM-blocked slot) and gets filled in as [launchBackgroundQueueFill] resolves
        // it, rather than waiting for that to happen before the queue exists at all.
        currentQueue = tracks
        exoIndexForLogical = exoMapping.toList()
        currentQueueIndex = startIndex
        currentTrack = requestedStartTrack
        currentTrackCompleted = false

        val startIsLocal = (startResolution as? TrackResolution.Playable)
            ?.let { isLocalUri(it.resolved.url) } ?: false

        _playbackState.value = _playbackState.value.copy(
            durationMs = (requestedStartTrack.durationSec ?: 0) * 1000L,
            currentTrackId = "${requestedStartTrack.source}:${requestedStartTrack.sourceId}",
            queue = tracks,
            queueIndex = startIndex,
            isLocalPlayback = startIsLocal,
            isUnavailable = false,
            unavailableMessage = null,
        )

        if (mediaItems.isNotEmpty()) {
            mediaController.setMediaItems(mediaItems, 0, 0L)
            mediaController.prepare()
            eventReporter.playStart(requestedStartTrack)
            mediaController.play()
        } else {
            // requestedStartTrack itself is a SoundCloudDrmOnlyException track -
            // nothing was loaded for it above. Stay put and show it as unavailable
            // rather than falling back to whatever else happens to resolve later.
            mediaController.clearMediaItems()
            mediaController.prepare()
            mediaController.pause()
            _playbackState.value = _playbackState.value.copy(
                isPlaying = false,
                title = requestedStartTrack.title,
                artist = requestedStartTrack.artist,
                artworkUrl = requestedStartTrack.thumbnailUrl,
                isUnavailable = true,
                unavailableMessage = DRM_UNAVAILABLE_MESSAGE,
            )
        }
        refreshDownloadAvailability(requestedStartTrack)

        if (tracks.size > 1) launchBackgroundQueueFill(tracks, startIndex, myGeneration)
    }

    /** Resolves every slot in [tracks] other than [startIndex] - already handled by
     * [playQueueStreaming] before this was launched - splicing each into the live
     * MediaController as it resolves instead of making playback wait on all of them.
     *
     * Slots after [startIndex] are resolved in ascending order and each is appended at
     * the controller's current end once ready - nothing earlier ever needs to move.
     * Slots before [startIndex] are resolved nearest-to-start first and always
     * inserted at position 0, so they land in the right relative order while only
     * ever shifting already-loaded exo indices forward (see [insertResolvedAt]). Both
     * groups' resolves are kicked off together up front (bounded by
     * [StreamResolverRegistry]'s own concurrency cap) - only the order they're
     * *applied* to the player is sequential, not the network calls themselves.
     *
     * A track that fails to resolve (DRM-blocked or otherwise) simply stays an
     * unplayable gap in the logical queue, same as [currentQueue]'s kdoc describes for
     * the track initially tapped. Bails out - leaving anything already spliced in in
     * place, touching nothing further - as soon as [generation] is no longer the live
     * one, since that means a newer playQueue()/playLocalDownload() call has already
     * moved the user on. */
    private fun launchBackgroundQueueFill(tracks: List<TrackResultDto>, startIndex: Int, generation: Int) {
        scope.launch {
            coroutineScope {
                val order = (startIndex + 1 until tracks.size) + (startIndex - 1 downTo 0)
                val pending = order.map { i -> tracks[i] to async { resolvePreferLocal(tracks[i]) } }
                for ((track, deferred) in pending) {
                    val result = deferred.await()
                    if (generation != playRequestGeneration) return@coroutineScope
                    val resolved = (toResolution(track, result) as? TrackResolution.Playable)?.resolved ?: continue
                    insertResolved(track, resolved)
                }
            }
        }
    }

    /** Loads [track] into the MediaController at the position matching its logical
     * slot. The slot is looked up by identity at insert time (not by a captured
     * index), so queue edits made while the background fill is still running
     * (remove/move) can't make it write into the wrong slot. */
    private fun insertResolved(track: TrackResultDto, resolved: ResolvedStream): Int? {
        val mediaController = controller ?: return null
        val logicalIndex = currentQueue.indices.firstOrNull { currentQueue[it] === track && exoIndexForLogical[it] == null }
            ?: return null
        val loaded = loadedFlags().also { it[logicalIndex] = true }
        val newMapping = remap(loaded)
        val exoIndex = newMapping[logicalIndex]!!
        mediaController.addMediaItem(exoIndex, buildMediaItem(track, resolved))
        exoIndexForLogical = newMapping
        return exoIndex
    }

    // The loaded items in the MediaController always sit in the same relative order
    // as their logical slots, so a slot's exo index is just the number of loaded
    // slots before it - recomputing it this way after any edit keeps both in sync.
    private fun loadedFlags(): MutableList<Boolean> = exoIndexForLogical.map { it != null }.toMutableList()

    private fun remap(loaded: List<Boolean>): List<Int?> {
        var next = 0
        return loaded.map { if (it) next++ else null }
    }

    private fun publishQueue() {
        _playbackState.value = _playbackState.value.copy(queue = currentQueue, queueIndex = currentQueueIndex)
    }

    /** Removes the queue entry at logical [index] (never the current track). */
    suspend fun removeFromQueue(index: Int) {
        if (index == currentQueueIndex || index !in currentQueue.indices) return
        val mediaController = ensureConnected()
        exoIndexForLogical[index]?.let { mediaController.removeMediaItem(it) }
        val loaded = loadedFlags().apply { removeAt(index) }
        currentQueue = currentQueue.toMutableList().apply { removeAt(index) }
        if (index < currentQueueIndex) currentQueueIndex--
        exoIndexForLogical = remap(loaded)
        publishQueue()
    }

    /** Moves an upcoming entry from logical [from] to [to]; both must be after the
     * current track. */
    suspend fun moveInQueue(from: Int, to: Int) {
        if (from == to || from <= currentQueueIndex || to <= currentQueueIndex) return
        if (from !in currentQueue.indices || to !in currentQueue.indices) return
        val mediaController = ensureConnected()
        val fromExo = exoIndexForLogical[from]
        val loaded = loadedFlags()
        loaded.add(to, loaded.removeAt(from))
        currentQueue = currentQueue.toMutableList().apply { add(to, removeAt(from)) }
        val newMapping = remap(loaded)
        if (fromExo != null) mediaController.moveMediaItem(fromExo, newMapping[to]!!)
        exoIndexForLogical = newMapping
        publishQueue()
    }

    /** Drops everything after the current track. */
    suspend fun clearUpNext() {
        if (currentQueueIndex < 0 || currentQueueIndex >= currentQueue.size - 1) return
        val mediaController = ensureConnected()
        val currentExo = exoIndexForLogical.getOrNull(currentQueueIndex)
            ?: exoIndexForLogical.take(currentQueueIndex).lastOrNull { it != null }
        val firstRemoved = (currentExo ?: -1) + 1
        if (firstRemoved < mediaController.mediaItemCount) {
            mediaController.removeMediaItems(firstRemoved, mediaController.mediaItemCount)
        }
        currentQueue = currentQueue.take(currentQueueIndex + 1)
        exoIndexForLogical = exoIndexForLogical.take(currentQueueIndex + 1)
        publishQueue()
    }

    /** Plays [track] directly from its already-downloaded [localUri], with full
     * metadata (unlike the old bare playFromUri(uri) path) so the Player screen shows
     * the same title/artist/artist-click/switch-to-stream/download controls it would
     * for any other entry point - previously, opening a track from the Downloads tab
     * used a raw content:// uri with no [TrackResultDto] at all, which is why the
     * switch and every other control silently disappeared only for that one entry
     * point. */
    suspend fun playLocalDownload(track: TrackResultDto, localUri: String) {
        val requestedKey = "${track.source}:${track.sourceId}"
        if (pendingTrackKey == requestedKey) return
        pendingTrackKey = requestedKey
        val myGeneration = ++playRequestGeneration
        // A fresh load replaces the controller's item(s) wholesale, which fires
        // onMediaItemTransition with reason PLAYLIST_CHANGED - explicitly ignored
        // there, so a pending retry from a *previous* track's error needs
        // cancelling here instead, before it can fire prepare()+play() against
        // whatever ends up loaded from this call.
        resetPlaybackErrorState()
        // Same reasoning as playQueue: fill in what we already know immediately rather
        // than leaving the UI blank until the load finishes.
        _playbackState.value = _playbackState.value.copy(
            isLoading = true,
            loadingTrackId = requestedKey,
            title = track.title,
            artist = track.artist,
            artworkUrl = track.thumbnailUrl,
            durationMs = (track.durationSec ?: 0) * 1000L,
        )

        try {
            val mediaController = ensureConnected()
            // See playQueue()'s identical check: ensureConnected() can suspend on
            // first connect, during which a newer playQueue()/playLocalDownload()
            // call may have already started and should win.
            if (myGeneration != playRequestGeneration) return
            currentTrack?.let { previous ->
                if (!currentTrackCompleted) eventReporter.skip(previous)
            }
            currentQueue = listOf(track)
            exoIndexForLogical = listOf(0)
            currentQueueIndex = 0
            currentTrack = track
            currentTrackCompleted = false
            eventReporter.playStart(track)

            _playbackState.value = _playbackState.value.copy(
                durationMs = (track.durationSec ?: 0) * 1000L,
                currentTrackId = requestedKey,
                queue = listOf(track),
                queueIndex = 0,
                isLocalPlayback = true,
                hasLocalDownload = true,
                isUnavailable = false,
                unavailableMessage = null,
            )
            mediaController.setMediaItem(buildMediaItem(track, ResolvedStream(url = localUri, isHls = false)))
            mediaController.prepare()
            mediaController.play()
        } finally {
            if (pendingTrackKey == requestedKey) pendingTrackKey = null
            if (myGeneration == playRequestGeneration) {
                _playbackState.value = _playbackState.value.copy(isLoading = false, loadingTrackId = null)
            }
        }
    }

    /** Inserts [track] to play right after the current one, without disturbing current
     * playback -- used by other screens' "Zur Warteschlange hinzufügen" action, which
     * is meant to queue a track "up next", not last. If nothing is currently playing,
     * starts playing it immediately instead. Data-saver mode applies the same
     * local-download-only rule as [playQueue]. */
    suspend fun addToQueue(track: TrackResultDto) {
        if (currentTrack == null) {
            playQueue(listOf(track), 0)
            return
        }
        val mediaController = ensureConnected()

        val resolved: ResolvedStream = if (settingsRepository.dataSaverModeCached) {
            val download = downloadDao.getByTrackId("${track.source}:${track.sourceId}")
            if (download?.state != DownloadState.COMPLETED || download.mediaStoreUri == null) {
                showToast("Datensparmodus: „${track.title}“ ist nicht heruntergeladen und wurde nicht zur Warteschlange hinzugefügt.")
                return
            }
            ResolvedStream(url = download.mediaStoreUri, isHls = false)
        } else {
            resolveWithRetry(track).getOrElse { e ->
                showToast(resolveFailureMessage(track.title, e))
                return
            }
        }

        // Insert one past whatever is actually loaded for the current logical slot,
        // rather than always appending. exoIndexForLogical[currentQueueIndex] is the
        // exo index to insert after in the common case; it is null only when the
        // current logical slot is itself a DRM-unavailable gap (see the class-level
        // kdoc on currentQueue), in which case nothing for that slot is loaded and the
        // player's own currentMediaItemIndex - the real track ExoPlayer already
        // auto-advanced to and is paused on - is the right anchor instead.
        val insertExoIndex = (exoIndexForLogical.getOrNull(currentQueueIndex) ?: mediaController.currentMediaItemIndex) + 1
        mediaController.addMediaItem(insertExoIndex, buildMediaItem(track, resolved))

        val insertLogicalIndex = currentQueueIndex + 1
        currentQueue = currentQueue.toMutableList().apply { add(insertLogicalIndex, track) }
        // Inserting into ExoPlayer's own item list shifts every item after it along by
        // one, so every already-recorded exo index at or past the insertion point has
        // to shift with it or it will point at the wrong loaded item.
        exoIndexForLogical = exoIndexForLogical
            .map { exoIndex -> if (exoIndex != null && exoIndex >= insertExoIndex) exoIndex + 1 else exoIndex }
            .toMutableList()
            .apply { add(insertLogicalIndex, insertExoIndex) }
        _playbackState.value = _playbackState.value.copy(queue = currentQueue)
    }

    suspend fun playFromUri(uriString: String) {
        val mediaController = ensureConnected()
        val mediaItem = MediaItem.Builder().setUri(uriString).build()
        currentQueue = emptyList()
        exoIndexForLogical = emptyList()
        currentQueueIndex = -1
        currentTrack = null
        // No TrackResultDto here (just a raw MediaStore uri from the Downloads tab), so
        // there's no stream URL to switch back to - hide the switch button entirely.
        _playbackState.value = _playbackState.value.copy(
            queue = emptyList(),
            queueIndex = -1,
            isLocalPlayback = true,
            hasLocalDownload = false,
            isUnavailable = false,
            unavailableMessage = null,
        )
        mediaController.setMediaItem(mediaItem)
        mediaController.prepare()
        mediaController.play()
    }

    suspend fun toggleShuffle() {
        val mediaController = ensureConnected()
        mediaController.shuffleModeEnabled = !mediaController.shuffleModeEnabled
    }

    /** Cycles Off -> Alle -> Einzeltitel -> Off, matching the usual single-button
     * repeat control most music players use. */
    suspend fun cycleRepeatMode() {
        val mediaController = ensureConnected()
        mediaController.repeatMode = when (mediaController.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    suspend fun togglePlayPause() {
        if (_playbackState.value.isUnavailable) {
            // A DRM-blocked slot has nothing loaded to play/pause (belt-and-suspenders
            // alongside the disabled buttons in PlayerScreen/MiniPlayerBar) - but a
            // playback error that exhausted its automatic retries (see onPlayerError)
            // still has a real, preparable MediaItem loaded, so the play button
            // retries in place instead of being a dead end with no way to act on its
            // own "check connection and try again" message.
            if (_playbackState.value.unavailableMessage == PLAYBACK_ERROR_MESSAGE) retryAfterPlaybackError()
            return
        }
        val mediaController = ensureConnectedOrReportError() ?: return
        if (mediaController.isPlaying) {
            resetPlaybackErrorState()
            mediaController.pause()
        } else {
            mediaController.play()
        }
    }

    private suspend fun retryAfterPlaybackError() {
        resetPlaybackErrorState()
        _playbackState.value = _playbackState.value.copy(isUnavailable = false, unavailableMessage = null)
        val mediaController = ensureConnectedOrReportError() ?: return
        mediaController.prepare()
        mediaController.play()
    }

    /** Wraps [ensureConnected] for every command entry point below - without this, a
     * PlaybackService that's died or is being throttled in the background (observed
     * on a real MIUI device; not reproducible on the plain AOSP emulator) left
     * ensureConnected() awaiting a Deferred that [CONNECT_TIMEOUT_MS] never lets
     * resolve, and every caller here is a fire-and-forget `viewModelScope.launch {}`
     * with no error handling of its own - so a tap on play/pause/skip just silently
     * did nothing, repeatably, with no crash and no message. Reports it through the
     * same [PLAYBACK_ERROR_MESSAGE]/isUnavailable state a playback error already
     * uses, so the *same* play-button-retries-in-place path in [togglePlayPause]
     * covers reconnecting too. A real CancellationException (the caller's own
     * coroutine/scope being cancelled, e.g. the screen closing mid-tap) still
     * propagates normally instead of being reported as a connection failure - see
     * [ensureConnected]'s own handling of a *timeout* specifically to keep the two
     * distinguishable here. */
    private suspend fun ensureConnectedOrReportError(): MediaController? = try {
        ensureConnected()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Lost connection to PlaybackService", e)
        _playbackState.value = _playbackState.value.copy(isUnavailable = true, unavailableMessage = PLAYBACK_ERROR_MESSAGE)
        null
    }

    /** Next/previous/jump-to-index all navigate the *logical* queue (see
     * [currentQueue]'s kdoc) via [moveToLogicalIndex], not MediaController's own
     * seekToNextMediaItem()/hasNextMediaItem()/seekTo(index) - those only know about
     * the shorter, gapped item list actually loaded there, which would either skip
     * straight past a SoundCloudDrmOnlyException slot (defeating the point of
     * keeping it around - see [PlaybackUiState.isUnavailable]) or, for
     * skipToQueueIndex, misinterpret a logical index as an Exo one once the two can
     * diverge. */
    suspend fun skipToNext() {
        ensureConnectedOrReportError() ?: return
        nextLogicalIndex()?.let { moveToLogicalIndex(it) }
    }

    suspend fun skipToPrevious() {
        ensureConnectedOrReportError() ?: return
        previousLogicalIndex()?.let { moveToLogicalIndex(it) }
    }

    suspend fun skipToQueueIndex(index: Int) {
        ensureConnectedOrReportError() ?: return
        moveToLogicalIndex(index)
    }

    private fun nextLogicalIndex(): Int? {
        if (currentQueueIndex < 0 || currentQueue.isEmpty()) return null
        val next = currentQueueIndex + 1
        return when {
            next < currentQueue.size -> next
            controller?.repeatMode == Player.REPEAT_MODE_ALL && currentQueue.size > 1 -> 0
            else -> null
        }
    }

    private fun previousLogicalIndex(): Int? {
        if (currentQueueIndex < 0 || currentQueue.isEmpty()) return null
        val previous = currentQueueIndex - 1
        return when {
            previous >= 0 -> previous
            controller?.repeatMode == Player.REPEAT_MODE_ALL && currentQueue.size > 1 -> currentQueue.size - 1
            else -> null
        }
    }

    /** Moves to [newLogicalIndex] in the logical queue. When that slot was actually
     * loaded into the MediaController, this is a real seek+play (state updates via
     * the resulting onMediaItemTransition, same as before - unless the controller
     * happens to already be sitting on that exact item, e.g. right after the
     * AUTO-transition gap check in [ensureConnected] paused on a real track it had
     * already advanced to under the hood, in which case a same-index seek fires no
     * transition and state is updated directly here instead). For a
     * SoundCloudDrmOnlyException slot (see [currentQueue]'s kdoc) there is nothing to
     * seek to - this just pauses and shows the "Titel nicht verfügbar" state. */
    private suspend fun moveToLogicalIndex(newLogicalIndex: Int) {
        val track = currentQueue.getOrNull(newLogicalIndex) ?: return
        resetPlaybackErrorState()
        val mediaController = ensureConnected()
        val exoIndex = exoIndexForLogical.getOrNull(newLogicalIndex)

        if (exoIndex == null) {
            // Not loaded yet (the background fill hasn't reached it, or it failed
            // earlier): try it now instead of assuming it's unplayable.
            val result = resolvePreferLocal(track)
            val resolved = result.getOrNull()
            if (resolved != null && insertResolved(track, resolved) != null) {
                moveToLogicalIndex(newLogicalIndex)
                return
            }
            if (result.exceptionOrNull() !is SoundCloudDrmOnlyException) {
                showToast(resolveFailureMessage(track.title, result.exceptionOrNull()))
                return
            }
            currentTrack?.let { previous -> if (!currentTrackCompleted) eventReporter.skip(previous) }
            mediaController.pause()
            currentTrack = track
            currentTrackCompleted = false
            currentQueueIndex = newLogicalIndex
            _playbackState.value = _playbackState.value.copy(
                isPlaying = false,
                title = track.title,
                artist = track.artist,
                artworkUrl = track.thumbnailUrl,
                durationMs = (track.durationSec ?: 0) * 1000L,
                currentTrackId = "${track.source}:${track.sourceId}",
                queueIndex = newLogicalIndex,
                isUnavailable = true,
                unavailableMessage = DRM_UNAVAILABLE_MESSAGE,
            )
        } else if (exoIndex == mediaController.currentMediaItemIndex) {
            currentTrack?.let { previous -> if (!currentTrackCompleted) eventReporter.skip(previous) }
            currentTrack = track
            currentTrackCompleted = false
            currentQueueIndex = newLogicalIndex
            eventReporter.playStart(track)
            mediaController.seekTo(0L)
            mediaController.play()
            _playbackState.value = _playbackState.value.copy(
                title = track.title,
                artist = track.artist,
                artworkUrl = track.thumbnailUrl,
                durationMs = (track.durationSec ?: 0) * 1000L,
                currentTrackId = "${track.source}:${track.sourceId}",
                queueIndex = newLogicalIndex,
                isLocalPlayback = isCurrentItemLocal(),
                isUnavailable = false,
                unavailableMessage = null,
            )
        } else {
            // Clear the unavailable state here rather than waiting for the resulting
            // onMediaItemTransition to do it: that callback has several early returns
            // before it gets there, and while the flag is up the metadata callback is
            // gated - so skipping away from an unavailable track could leave the title,
            // artist and cover stuck on the old one.
            _playbackState.value = _playbackState.value.copy(
                isUnavailable = false,
                unavailableMessage = null,
            )
            // prepare() because a preceding playback error leaves the player IDLE, and
            // seekTo+play alone does not bring it back - skipping past a failed track
            // used to land on a silently dead player.
            mediaController.prepare()
            mediaController.seekTo(exoIndex, 0L)
            mediaController.play()
            // onMediaItemTransition (reason SEEK) handles the rest of the state update.
        }
    }

    /** Seeks to [positionMs] within the current track. Both sources are now genuinely
     * seekable on-device: YouTube's chosen stream is progressive/Range-seekable, and
     * SoundCloud's HLS `.m3u8` is played natively via Media3's HLS extension (real
     * segment-accurate seeking) instead of the old backend's ffmpeg-transcoded,
     * offset-restart-only pipe. So this is just a plain seek, unlike the old
     * source-dependent branching. */
    suspend fun seekTo(positionMs: Long) {
        ensureConnectedOrReportError()?.seekTo(positionMs)
    }

    /** Manually flips the currently playing track between its stream URL and its local
     * download, independent of the global data-saver setting — the switch button on the
     * Player screen (only shown when [PlaybackUiState.hasLocalDownload] is true). Keeps
     * the current playback position across the swap. */
    suspend fun toggleSource() {
        // A DRM-unavailable slot has nothing loaded to switch between - neither the
        // stream nor the local-download branch below makes sense for it.
        if (_playbackState.value.isUnavailable) return
        val track = currentTrack ?: return
        val mediaController = ensureConnected()
        val index = mediaController.currentMediaItemIndex
        val positionMs = currentPositionMs()
        val wasLocal = isCurrentItemLocal()

        val newItem: MediaItem = if (wasLocal) {
            val resolved = resolveWithRetry(track).getOrElse { e ->
                showToast(resolveFailureMessage(track.title, e))
                return
            }
            buildMediaItem(track, resolved)
        } else {
            val localUri = localDownloadUri(track)
            if (localUri == null) {
                showToast("„${track.title}“ ist nicht heruntergeladen.")
                return
            }
            buildMediaItem(track, ResolvedStream(url = localUri, isHls = false))
        }

        suppressNextTransitionEvent = true
        mediaController.replaceMediaItem(index, newItem)
        mediaController.seekTo(index, positionMs)
        mediaController.play()
        _playbackState.value = _playbackState.value.copy(isLocalPlayback = !wasLocal)
    }

    fun currentPositionMs(): Long = controller?.currentPosition ?: 0L

    fun currentDurationMs(): Long =
        controller?.duration?.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L

    fun nowPlayingTrack(): TrackResultDto? = currentTrack

    // Whether the current item's own URI is a local file/content uri rather than a
    // network stream. Derived from the actual loaded MediaItem (not a hand-tracked
    // flag) so it stays correct after toggleSource() swaps a single queue slot without
    // touching the rest of the queue.
    private fun isCurrentItemLocal(): Boolean {
        val scheme = controller?.currentMediaItem?.localConfiguration?.uri?.scheme
        return isLocalScheme(scheme)
    }

    private fun isLocalUri(uri: String): Boolean = isLocalScheme(Uri.parse(uri).scheme)

    private fun isLocalScheme(scheme: String?): Boolean = scheme != null && scheme != "http" && scheme != "https"

    private suspend fun localDownloadUri(track: TrackResultDto): String? {
        val download = downloadDao.getByTrackId("${track.source}:${track.sourceId}") ?: return null
        return download.mediaStoreUri.takeIf { download.state == DownloadState.COMPLETED }
    }

    /** Resolves [track]'s stream. The concurrency cap, retry, and failure logging all
     * live in [StreamResolverRegistry] itself now (shared with
     * [dev.schlubbe.musicagent.download.DownloadWorker]'s playlist-download path, not
     * just playback), so this is just a thin wrapper - kept as a [Result] rather than
     * null-on-failure so callers can distinguish a [SoundCloudDrmOnlyException] (via
     * [resolveFailureMessage]) from a generic failure instead of showing the same
     * unhelpful "nicht aufgelöst" toast for both. */
    private suspend fun resolveWithRetry(track: TrackResultDto): Result<ResolvedStream> =
        runCatching {
            streamResolverRegistry.resolveWithFallback(track.source, track.sourceId, track.title, track.artist, track.durationSec)
        }

    /** Prefers an already-downloaded local copy over the network stream when both are
     * available - the default behavior for starting playback from Search/Library:
     * pressing a downloaded track should play what's already on the device instead
     * of re-streaming it, falling back to [resolveWithRetry] only when no local copy
     * exists. Deliberately NOT used by [toggleSource]'s "switch to stream" branch,
     * which needs [resolveWithRetry] to actually mean the stream - short-circuiting
     * that to the local file too would make toggling away from local playback a
     * no-op. */
    private suspend fun resolvePreferLocal(track: TrackResultDto): Result<ResolvedStream> {
        val localUri = localDownloadUri(track)
        return if (localUri != null) {
            Result.success(ResolvedStream(url = localUri, isHls = false))
        } else {
            resolveWithRetry(track)
        }
    }

    /** Puts the UI back in sync with [currentTrack] - the item actually still loaded
     * in the MediaController - after a [playQueue] request fails partway through,
     * undoing its own early "show the tapped track immediately" optimism (see that
     * function's kdoc) rather than leaving the screen pointed at a track that never
     * became real. */
    private fun restoreStateToCurrentTrack() {
        currentTrack?.let { stillLoaded ->
            _playbackState.value = _playbackState.value.copy(
                title = stillLoaded.title,
                artist = stillLoaded.artist,
                artworkUrl = stillLoaded.thumbnailUrl,
                durationMs = (stillLoaded.durationSec ?: 0) * 1000L,
                currentTrackId = "${stillLoaded.source}:${stillLoaded.sourceId}",
            )
        }
    }

    private fun resolveFailureMessage(title: String, error: Throwable?): String =
        if (error is SoundCloudDrmOnlyException) {
            "„$title“ ist auf SoundCloud DRM-geschützt oder nur als Vorschau verfügbar, und auf YouTube Music wurde kein passender Titel gefunden."
        } else {
            "„$title“ konnte gerade nicht geladen werden – bitte gleich noch einmal versuchen."
        }

    /** "Automatische Weiterempfehlung" (Einstellungen > Wiedergabe): once the
     * queue naturally runs out, keeps playback going with shuffled global
     * trending tracks - the same always-populated signal Home's Charts shelf
     * uses, rather than nothing (no per-user "radio" generation exists
     * on-device beyond that). */
    /**
     * "Autoplay-Radio" setting, fired from [onPlaybackStateChanged]'s STATE_ENDED
     * branch once the whole queue has genuinely run out. Used to just be
     * `getTrending().shuffled()` fed into a fresh [playQueue] - a hard reset onto
     * unrelated global trending with zero connection to what was actually just
     * playing, which is a large part of why "next song prediction" read as
     * arbitrary. Now appends [FeedRepository.predictNext]'s output - weighted
     * overwhelmingly toward the artists of the tracks that were just playing - onto
     * the *existing* queue instead of replacing it, so a radio continuation is a
     * continuation, not a restart.
     *
     * [extendQueueMutex] guards against this firing more than once concurrently
     * (STATE_ENDED shouldn't repeat while a pass is already resolving, but nothing
     * stops it structurally).
     */
    private fun continueWithRadio() {
        scope.launch {
            extendQueueMutex.withLock {
                if (currentQueue.isEmpty() || currentQueueIndex < 0) return@withLock

                val myGeneration = playRequestGeneration
                val recentContext = (currentQueueIndex downTo 0).asSequence()
                    .mapNotNull { currentQueue.getOrNull(it) }
                    .take(SESSION_CONTEXT_SIZE)
                    .toList()
                val excludeIds = currentQueue.mapTo(mutableSetOf()) { "${it.source}:${it.sourceId}" }

                val predicted = runCatching { feedRepository.predictNext(recentContext, excludeIds, EXTEND_BATCH_SIZE) }
                    .getOrDefault(emptyList())
                if (predicted.isEmpty()) return@withLock

                val resolved = coroutineScope {
                    predicted.map { track -> async { track to resolveWithRetry(track) } }.awaitAll()
                }.mapNotNull { (track, result) -> result.getOrNull()?.let { track to it } }
                if (resolved.isEmpty()) return@withLock

                // The queue this batch was built for may have been replaced by a
                // fresh playQueue() while resolving was in flight - discard rather
                // than glue a stale prediction onto an unrelated new session.
                if (myGeneration != playRequestGeneration) return@withLock

                val mediaController = ensureConnected()
                var exoIndex = mediaController.mediaItemCount
                resolved.forEach { (track, stream) ->
                    mediaController.addMediaItem(buildMediaItem(track, stream))
                    currentQueue = currentQueue + track
                    exoIndexForLogical = exoIndexForLogical + exoIndex
                    exoIndex++
                }
                _playbackState.value = _playbackState.value.copy(queue = currentQueue)

                // The player sat at STATE_ENDED with nothing left; now there is.
                // moveToLogicalIndex handles the DRM-unavailable case itself, so this
                // is safe even if predictNext's first candidate somehow ended up
                // DRM-blocked (it shouldn't - predictNext's own search results aren't
                // annotated that way at this layer - but resolveWithRetry already
                // dropped anything that failed to resolve above regardless).
                val next = currentQueueIndex + 1
                if (next < currentQueue.size) moveToLogicalIndex(next)
            }
        }
    }

    // Reactive, not a one-shot check: a track can start streaming, then finish
    // downloading in the background while it's still playing (or already playing
    // when the user taps "download" from the Player screen itself) - without
    // observing the DAO, hasLocalDownload only ever got refreshed on the *next*
    // track transition, so the "Offline verfügbar" toggle stayed stuck on "Zum
    // Offline-Hören herunterladen" until the user left and reopened the Player.
    // Cancelled and restarted per track (see onMediaItemTransition/playTrack) so a
    // late emission for the *previous* track can never land after currentTrack has
    // already moved on.
    private var downloadAvailabilityJob: Job? = null

    private fun refreshDownloadAvailability(track: TrackResultDto) {
        downloadAvailabilityJob?.cancel()
        downloadAvailabilityJob = scope.launch {
            downloadDao.observeByTrackId("${track.source}:${track.sourceId}").collectLatest { download ->
                val hasDownload = download != null && download.state == DownloadState.COMPLETED
                if (currentTrack === track) {
                    _playbackState.value = _playbackState.value.copy(hasLocalDownload = hasDownload)
                }
            }
        }
    }

    /** Filters [tracks] down to only those with a completed local download, notifying
     * the user (via toast) about any that were skipped. Returns the filtered list
     * together with [startIndex]'s position within it (by original index, not by
     * value-equality - a queue containing the same track twice would otherwise have
     * every copy matched/dropped together instead of just the one actually tapped). */
    private suspend fun resolveLocalOnly(
        tracks: List<TrackResultDto>,
        startIndex: Int,
    ): Pair<List<Pair<TrackResultDto, ResolvedStream>>, Int> {
        val playable = mutableListOf<Pair<TrackResultDto, ResolvedStream>>()
        var startPos = -1
        tracks.forEachIndexed { i, track ->
            val download = downloadDao.getByTrackId("${track.source}:${track.sourceId}")
            if (download?.state == DownloadState.COMPLETED && download.mediaStoreUri != null) {
                if (i == startIndex) startPos = playable.size
                playable += track to ResolvedStream(url = download.mediaStoreUri, isHls = false)
            }
        }
        val skipped = tracks.size - playable.size
        if (startPos < 0) {
            showToast("Datensparmodus: „${tracks[startIndex].title}“ ist nicht heruntergeladen und wird übersprungen.")
        } else if (skipped > 0) {
            showToast("Datensparmodus: $skipped Titel ohne Download wurden aus der Warteschlange übersprungen.")
        }
        return playable to startPos.coerceAtLeast(0)
    }

    private fun toResolution(track: TrackResultDto, result: Result<ResolvedStream>): TrackResolution? = when {
        result.isSuccess -> TrackResolution.Playable(track, result.getOrThrow())
        result.exceptionOrNull() is SoundCloudDrmOnlyException -> TrackResolution.DrmBlocked(track)
        else -> null
    }

    private fun buildMediaItem(track: TrackResultDto, resolved: ResolvedStream): MediaItem =
        MediaItem.Builder()
            .setUri(resolved.url)
            .setMediaId("${track.source}:${track.sourceId}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setArtworkUri(track.thumbnailUrl?.let(Uri::parse))
                    .build(),
            )
            .build()

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
