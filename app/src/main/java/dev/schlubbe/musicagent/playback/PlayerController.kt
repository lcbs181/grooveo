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

    // Tracks what's currently loaded so play_complete/skip can be reported
    // against it once we know how the track ended.
    private var currentQueue: List<TrackResultDto> = emptyList()
    private var currentTrack: TrackResultDto? = null
    private var currentTrackCompleted = false

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
                _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
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
                val newIndex = controller?.currentMediaItemIndex ?: return
                val newTrack = currentQueue.getOrNull(newIndex) ?: return

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
                eventReporter.playStart(newTrack)
                _playbackState.value = _playbackState.value.copy(
                    durationMs = (newTrack.durationSec ?: 0) * 1000L,
                    currentTrackId = "${newTrack.source}:${newTrack.sourceId}",
                    queueIndex = newIndex,
                    isLocalPlayback = isCurrentItemLocal(),
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

    /** Loads [tracks] as the playback queue starting at [startIndex] — everything
     * after it becomes the "up next" list surfaced on the Player screen.
     *
     * In data-saver mode, tracks without a completed local download are silently
     * dropped from the queue (rather than blocking the whole queue, or falling back to
     * streaming) since that keeps the rest of an otherwise-downloaded queue playable.
     * Outside data-saver mode, tracks whose on-device stream resolution fails (the
     * client-side extractor can break more often than the old stable backend did) are
     * dropped the same way. Either case notifies the user via a toast. */
    suspend fun playQueue(tracks: List<TrackResultDto>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val requestedStartTrack = tracks[startIndex]
        val requestedKey = "${requestedStartTrack.source}:${requestedStartTrack.sourceId}"
        if (pendingTrackKey == requestedKey) return
        pendingTrackKey = requestedKey
        _playbackState.value = _playbackState.value.copy(isLoading = true, loadingTrackId = requestedKey)

        try {
            val mediaController = ensureConnected()
            val dataSaver = settingsRepository.dataSaverModeCached

            val playable: List<Pair<TrackResultDto, ResolvedStream>> = if (dataSaver) {
                resolveLocalOnly(tracks, requestedStartTrack)
            } else {
                resolveStreams(tracks, requestedStartTrack)
            }

            if (playable.isEmpty()) {
                showToast(
                    if (dataSaver) {
                        "Datensparmodus: Keine heruntergeladenen Titel in dieser Auswahl."
                    } else {
                        "Keiner der Titel konnte aufgelöst werden."
                    },
                )
                return
            }

            val newStartIndex = playable.indexOfFirst { it.first == requestedStartTrack }.takeIf { it >= 0 } ?: 0

            currentTrack?.let { previous ->
                if (!currentTrackCompleted) eventReporter.skip(previous)
            }

            val queueTracks = playable.map { it.first }
            val startTrack = queueTracks[newStartIndex]
            currentQueue = queueTracks
            currentTrack = startTrack
            currentTrackCompleted = false
            eventReporter.playStart(startTrack)

            val mediaItems = playable.map { (track, resolved) -> buildMediaItem(track, resolved) }

            _playbackState.value = _playbackState.value.copy(
                durationMs = (startTrack.durationSec ?: 0) * 1000L,
                currentTrackId = "${startTrack.source}:${startTrack.sourceId}",
                queue = queueTracks,
                queueIndex = newStartIndex,
                isLocalPlayback = dataSaver,
            )
            mediaController.setMediaItems(mediaItems, newStartIndex, 0L)
            mediaController.prepare()
            mediaController.play()
            refreshDownloadAvailability(startTrack)
        } finally {
            if (pendingTrackKey == requestedKey) pendingTrackKey = null
            _playbackState.value = _playbackState.value.copy(isLoading = false, loadingTrackId = null)
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
        _playbackState.value = _playbackState.value.copy(isLoading = true, loadingTrackId = requestedKey)

        try {
            val mediaController = ensureConnected()
            currentTrack?.let { previous ->
                if (!currentTrackCompleted) eventReporter.skip(previous)
            }
            currentQueue = listOf(track)
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
            )
            mediaController.setMediaItem(buildMediaItem(track, ResolvedStream(url = localUri, isHls = false)))
            mediaController.prepare()
            mediaController.play()
        } finally {
            if (pendingTrackKey == requestedKey) pendingTrackKey = null
            _playbackState.value = _playbackState.value.copy(isLoading = false, loadingTrackId = null)
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

        mediaController.addMediaItem(buildMediaItem(track, resolved))
        val newQueue = currentQueue + track
        currentQueue = newQueue
        _playbackState.value = _playbackState.value.copy(queue = newQueue)
    }

    suspend fun playFromUri(uriString: String) {
        val mediaController = ensureConnected()
        val mediaItem = MediaItem.Builder().setUri(uriString).build()
        currentQueue = emptyList()
        currentTrack = null
        // No TrackResultDto here (just a raw MediaStore uri from the Downloads tab), so
        // there's no stream URL to switch back to - hide the switch button entirely.
        _playbackState.value = _playbackState.value.copy(
            queue = emptyList(),
            queueIndex = -1,
            isLocalPlayback = true,
            hasLocalDownload = false,
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
        val mediaController = ensureConnected()
        if (mediaController.isPlaying) mediaController.pause() else mediaController.play()
    }

    suspend fun skipToNext() {
        val mediaController = ensureConnected()
        if (mediaController.hasNextMediaItem()) mediaController.seekToNextMediaItem()
    }

    suspend fun skipToPrevious() {
        val mediaController = ensureConnected()
        if (mediaController.hasPreviousMediaItem()) mediaController.seekToPreviousMediaItem()
    }

    suspend fun skipToQueueIndex(index: Int) {
        ensureConnected().seekTo(index, 0L)
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
     * the user (via toast) about any that were skipped. */
    private suspend fun resolveLocalOnly(
        tracks: List<TrackResultDto>,
        requestedStartTrack: TrackResultDto,
    ): List<Pair<TrackResultDto, ResolvedStream>> {
        val playable = mutableListOf<Pair<TrackResultDto, ResolvedStream>>()
        for (track in tracks) {
            val download = downloadDao.getByTrackId("${track.source}:${track.sourceId}")
            if (download?.state == DownloadState.COMPLETED && download.mediaStoreUri != null) {
                playable += track to ResolvedStream(url = download.mediaStoreUri, isHls = false)
            }
        }
        val skipped = tracks.size - playable.size
        if (playable.none { it.first == requestedStartTrack }) {
            showToast("Datensparmodus: „${requestedStartTrack.title}“ ist nicht heruntergeladen und wird übersprungen.")
        } else if (skipped > 0) {
            showToast("Datensparmodus: $skipped Titel ohne Download wurden aus der Warteschlange übersprungen.")
        }
        return playable
    }

    /** Resolves every track's stream URL on-device, prioritizing the requested
     * start track: resolve that one immediately (blocking) so playback starts ASAP,
     * then resolve the rest concurrently in the background and drop any that fail.
     * This prevents a 10s+ delay when playing a large playlist if we wait for every
     * track to resolve before starting the first one. */
    private suspend fun resolveStreams(
        tracks: List<TrackResultDto>,
        requestedStartTrack: TrackResultDto,
    ): List<Pair<TrackResultDto, ResolvedStream>> = coroutineScope {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "resolveStreams: starting with ${tracks.size} tracks, prioritizing ${requestedStartTrack.title}")

        // Priority 1: Resolve the requested track first (blocking) so we can start
        // playback immediately - users need to hear sound fast, not wait for a
        // whole queue to load.
        val startResolved = coroutineScope {
            val result = resolveWithRetry(requestedStartTrack)
            if (result.isSuccess) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "resolveStreams: requested track resolved in ${elapsed}ms")
                requestedStartTrack to result.getOrNull()!!
            } else {
                Log.w(TAG, "resolveStreams: requested track failed to resolve")
                null
            }
        }

        if (startResolved == null) {
            // Requested track failed; fall back to resolving everything and hope
            // one of them succeeds.
            Log.w(TAG, "resolveStreams: falling back to full-queue resolve")
            val resolved = tracks.map { track ->
                async { track to resolveWithRetry(track) }
            }.awaitAll()
            val playable = resolved.mapNotNull { (track, result) -> result.getOrNull()?.let { track to it } }
            val error = resolved.firstOrNull { it.first == requestedStartTrack }?.second?.exceptionOrNull()
            showToast(resolveFailureMessage(requestedStartTrack.title, error) + " Wird übersprungen.")
            return@coroutineScope playable
        }

        // Priority 2: Resolve the rest in the background (fire-and-forget, we don't
        // block on them).
        val otherTracks = tracks.filter { it != requestedStartTrack }
        val backgroundJob = async {
            val resolved = otherTracks.map { track ->
                async { track to resolveWithRetry(track) }
            }.awaitAll()
            resolved.mapNotNull { (track, result) -> result.getOrNull()?.let { track to it } }
        }

        // Return immediately with just the start track; the rest will resolve
        // in the background and we'll report failures once they're available.
        val backgroundResults = backgroundJob.await()
        val totalElapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "resolveStreams: all tracks done in ${totalElapsed}ms (start: fast, rest: background)")

        // Combine start track + background results, notifying about any failures.
        val playable = mutableListOf(startResolved)
        val failed = otherTracks.size - backgroundResults.size
        if (failed > 0) {
            showToast("$failed Titel konnten nicht aufgelöst werden und wurden übersprungen.")
        }
        playable.addAll(backgroundResults)
        playable
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
