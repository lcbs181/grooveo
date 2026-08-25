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
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PlayerController"

private const val DRM_UNAVAILABLE_MESSAGE = "Titel nicht verfügbar – DRM-geschützt und kann von dieser App nicht abgespielt werden."
private const val PLAYBACK_ERROR_MESSAGE = "Wiedergabe unterbrochen – Verbindung prüfen und erneut versuchen."
private const val MAX_PLAYBACK_ERROR_RETRIES = 2

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
) {
    private var controller: MediaController? = null

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    // The Player screen's chosen Visualizer style - a singleton field (not
    // per-screen remembered state) so it survives closing and reopening the
    // Player, the same way the queue/current track already do.
    private val _vizVariant = MutableStateFlow("orb")
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

    // Singleton-scoped: outlives any one screen, so fire-and-forget DB lookups
    // triggered from the (non-suspend) Player.Listener callbacks below can use it
    // without needing a ViewModel's viewModelScope in hand.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

    private suspend fun ensureConnected(): MediaController {
        controller?.let { return it }

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val newController = MediaController.Builder(context, sessionToken).buildAsync().await()

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
     * Outside data-saver mode, tracks whose on-device stream resolution fails (the
     * client-side extractor can break more often than the old stable backend did) are
     * dropped the same way, EXCEPT a [SoundCloudDrmOnlyException] track: that one is
     * kept in the logical queue as an unplayable placeholder (see [currentQueue]'s
     * kdoc) so landing on it - by tapping it directly, or the queue naturally reaching
     * it - shows "Titel nicht verfügbar" instead of silently continuing past it. */
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
            val dataSaver = settingsRepository.dataSaverModeCached

            val resolutionsAndIndex: Pair<List<TrackResolution>, Int> = if (dataSaver) {
                val (playable, startPos) = resolveLocalOnly(tracks, startIndex)
                playable.map<Pair<TrackResultDto, ResolvedStream>, TrackResolution> { (track, resolved) ->
                    TrackResolution.Playable(track, resolved)
                } to startPos
            } else {
                resolveStreamsWithGaps(tracks, startIndex)
            }
            val (resolutions, newQueueIndex) = resolutionsAndIndex

            // A newer playQueue()/playLocalDownload() call has started while this one
            // was resolving streams (a network round-trip, not instant) - bail out
            // before touching any shared queue state or issuing MediaController
            // commands, so a slow earlier request can never stomp a faster later one
            // and "un-skip" playback back to a track the user already left.
            if (myGeneration != playRequestGeneration) return

            if (resolutions.isEmpty()) {
                showToast(
                    if (dataSaver) {
                        "Datensparmodus: Keine heruntergeladenen Titel in dieser Auswahl."
                    } else {
                        "Keiner der Titel konnte aufgelöst werden."
                    },
                )
                return
            }

            currentTrack?.let { previous ->
                if (!currentTrackCompleted) eventReporter.skip(previous)
            }

            val queueTracks = resolutions.map { it.track }
            val mediaItems = mutableListOf<MediaItem>()
            val exoMapping = arrayOfNulls<Int>(resolutions.size)
            resolutions.forEachIndexed { i, resolution ->
                if (resolution is TrackResolution.Playable) {
                    exoMapping[i] = mediaItems.size
                    mediaItems += buildMediaItem(resolution.track, resolution.resolved)
                }
            }

            val startTrack = queueTracks[newQueueIndex]
            currentQueue = queueTracks
            exoIndexForLogical = exoMapping.toList()
            currentQueueIndex = newQueueIndex
            currentTrack = startTrack
            currentTrackCompleted = false

            _playbackState.value = _playbackState.value.copy(
                durationMs = (startTrack.durationSec ?: 0) * 1000L,
                currentTrackId = "${startTrack.source}:${startTrack.sourceId}",
                queue = queueTracks,
                queueIndex = newQueueIndex,
                isLocalPlayback = dataSaver,
                isUnavailable = false,
                unavailableMessage = null,
            )

            if (mediaItems.isNotEmpty()) {
                mediaController.setMediaItems(mediaItems, exoMapping[newQueueIndex] ?: 0, 0L)
            } else {
                mediaController.clearMediaItems()
            }
            mediaController.prepare()

            val startExoIndex = exoMapping[newQueueIndex]
            if (startExoIndex != null) {
                eventReporter.playStart(startTrack)
                mediaController.play()
            } else {
                // The requested/start slot is a SoundCloudDrmOnlyException track - nothing
                // was loaded for it above. Stay put and show it as unavailable rather than
                // falling back to whatever else happened to resolve (the old behaviour,
                // which is exactly the "silently plays something else" bug this replaces).
                mediaController.pause()
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    title = startTrack.title,
                    artist = startTrack.artist,
                    artworkUrl = startTrack.thumbnailUrl,
                    isUnavailable = true,
                    unavailableMessage = DRM_UNAVAILABLE_MESSAGE,
                )
            }
            refreshDownloadAvailability(startTrack)
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

    /** Appends [track] to the current queue without disturbing current playback --
     * used by other screens to queue a track "up next" style. If nothing is currently
     * playing, starts playing it immediately instead. Data-saver mode applies the same
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

        val newExoIndex = mediaController.mediaItemCount
        mediaController.addMediaItem(buildMediaItem(track, resolved))
        currentQueue = currentQueue + track
        exoIndexForLogical = exoIndexForLogical + newExoIndex
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
        val mediaController = ensureConnected()
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
        val mediaController = ensureConnected()
        mediaController.prepare()
        mediaController.play()
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
        ensureConnected()
        nextLogicalIndex()?.let { moveToLogicalIndex(it) }
    }

    suspend fun skipToPrevious() {
        ensureConnected()
        previousLogicalIndex()?.let { moveToLogicalIndex(it) }
    }

    suspend fun skipToQueueIndex(index: Int) {
        ensureConnected()
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
        ensureConnected().seekTo(positionMs)
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
        return scheme != null && scheme != "http" && scheme != "https"
    }

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
        runCatching { streamResolverRegistry.resolve(track.source, track.sourceId) }

    private fun resolveFailureMessage(title: String, error: Throwable?): String =
        if (error is SoundCloudDrmOnlyException) {
            "„$title“ ist DRM-geschützt und kann von dieser App nicht abgespielt werden."
        } else {
            "„$title“ konnte nicht aufgelöst werden."
        }

    /** "Automatische Weiterempfehlung" (Einstellungen > Wiedergabe): once the
     * queue naturally runs out, keeps playback going with shuffled global
     * trending tracks - the same always-populated signal Home's Charts shelf
     * uses, rather than nothing (no per-user "radio" generation exists
     * on-device beyond that). */
    private fun continueWithRadio() {
        scope.launch {
            val tracks = runCatching { searchRepository.getTrending() }.getOrNull()?.shuffled() ?: return@launch
            if (tracks.isEmpty()) return@launch
            playQueue(tracks, 0)
        }
    }

    private fun refreshDownloadAvailability(track: TrackResultDto) {
        scope.launch {
            val hasDownload = localDownloadUri(track) != null
            if (currentTrack === track) {
                _playbackState.value = _playbackState.value.copy(hasLocalDownload = hasDownload)
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

    /** Resolves every track's stream URL on-device, prioritizing the requested
     * start track: resolve that one immediately (blocking) so playback starts ASAP,
     * then resolve the rest concurrently in the background. This prevents a 10s+
     * delay when playing a large playlist if we wait for every track to resolve
     * before starting the first one.
     *
     * A resolve failure normally drops that track from the result entirely, same as
     * the old resolveStreams - EXCEPT a [SoundCloudDrmOnlyException], which produces
     * a [TrackResolution.DrmBlocked] entry instead of vanishing (see [playQueue]'s
     * kdoc for why). */
    private suspend fun resolveStreamsWithGaps(
        tracks: List<TrackResultDto>,
        startIndex: Int,
    ): Pair<List<TrackResolution>, Int> = coroutineScope {
        val requestedStartTrack = tracks[startIndex]
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "resolveStreams: starting with ${tracks.size} tracks, prioritizing ${requestedStartTrack.title}")

        // Priority 1: Resolve the requested track first (blocking) so we can start
        // playback immediately - users need to hear sound fast, not wait for a
        // whole queue to load.
        val startResult = resolveWithRetry(requestedStartTrack)
        val startResolution: TrackResolution? = when {
            startResult.isSuccess -> {
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "resolveStreams: requested track resolved in ${elapsed}ms")
                TrackResolution.Playable(requestedStartTrack, startResult.getOrThrow())
            }
            startResult.exceptionOrNull() is SoundCloudDrmOnlyException -> {
                Log.w(TAG, "resolveStreams: requested track is DRM-only")
                TrackResolution.DrmBlocked(requestedStartTrack)
            }
            else -> {
                Log.w(TAG, "resolveStreams: requested track failed to resolve")
                null
            }
        }

        if (startResolution == null) {
            // Requested track failed for a non-DRM reason; fall back to resolving
            // everything and hope one of them succeeds. Original index is carried
            // alongside each result so the eventual start position can be found by
            // index, not by value-equality (see this function's kdoc).
            Log.w(TAG, "resolveStreams: falling back to full-queue resolve")
            val resolved = tracks.mapIndexed { i, track ->
                async { i to (track to resolveWithRetry(track)) }
            }.awaitAll()
            val error = resolved.firstOrNull { (i, _) -> i == startIndex }?.second?.second?.exceptionOrNull()
            showToast(resolveFailureMessage(requestedStartTrack.title, error) + " Wird übersprungen.")
            var startPos = -1
            val resolutions = mutableListOf<TrackResolution>()
            for ((origIndex, pair) in resolved) {
                val resolution = toResolution(pair.first, pair.second) ?: continue
                if (origIndex == startIndex) startPos = resolutions.size
                resolutions += resolution
            }
            return@coroutineScope resolutions to startPos.coerceAtLeast(0)
        }

        // Priority 2: Resolve the rest in the background - by original index, not
        // value-equality, so a queue containing the same track twice only excludes
        // the one copy actually at [startIndex], not every occurrence.
        val backgroundResults = tracks.withIndex()
            .filter { it.index != startIndex }
            .map { (_, track) -> async { track to resolveWithRetry(track) } }
            .awaitAll()
        val totalElapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "resolveStreams: all tracks done in ${totalElapsed}ms (start: fast, rest: background)")

        val failedOther = backgroundResults.count { (_, result) ->
            result.isFailure && result.exceptionOrNull() !is SoundCloudDrmOnlyException
        }
        if (failedOther > 0) {
            showToast("$failedOther Titel konnten nicht aufgelöst werden und wurden übersprungen.")
        }

        val resolutions = mutableListOf(startResolution)
        backgroundResults.forEach { (track, result) -> toResolution(track, result)?.let { resolutions += it } }
        // The requested start track is always prepended first above, so it's always
        // at position 0 in this (non-fallback) branch.
        resolutions to 0
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
