package dev.schlubbe.musicagent.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.schlubbe.musicagent.MainActivity
import dev.schlubbe.musicagent.R
import dev.schlubbe.musicagent.data.extract.StreamResolverRegistry
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import dev.schlubbe.musicagent.data.local.dao.TrackAnalysisDao
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.repository.DownloadRepository
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.PlaylistRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import dev.schlubbe.musicagent.playback.eq.LoudnessCache
import dev.schlubbe.musicagent.playback.eq.ParametricEqAudioProcessor
import dev.schlubbe.musicagent.playback.reverb.ConvolutionReverbAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import androidx.glance.appwidget.updateAll
import dev.schlubbe.musicagent.widget.PlaybackWidget
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.pow
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject
    lateinit var mediaSourceFactory: MediaSource.Factory

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var musicNotificationProvider: MusicNotificationProvider

    @Inject
    lateinit var playerController: PlayerController

    @Inject
    lateinit var likesRepository: LikesRepository

    @Inject
    lateinit var downloadRepository: DownloadRepository

    @Inject
    lateinit var downloadDao: DownloadDao

    @Inject
    lateinit var trackDao: TrackDao

    @Inject
    lateinit var playlistRepository: PlaylistRepository

    @Inject
    lateinit var searchRepository: SearchRepository

    @Inject
    lateinit var streamResolverRegistry: StreamResolverRegistry

    @Inject
    lateinit var trackAnalysisDao: TrackAnalysisDao

    private var player: ExoPlayer? = null
    private var mediaSession: MediaLibrarySession? = null
    // Parametric EQ in the sink's processor chain (one instance per player: the
    // crossfade tail player gets its own so the outgoing track keeps its EQ).
    private val eqAudioProcessor = ParametricEqAudioProcessor()
    private val tailEqAudioProcessor = ParametricEqAudioProcessor()
    // Measured loudness per track, so a track heard before is normalised from its first sample.
    private val loudnessCache by lazy { LoudnessCache(java.io.File(filesDir, "loudness.json")) }
    private val reverbAudioProcessor = ConvolutionReverbAudioProcessor(this)
    private val sound3dController = Sound3dController(reverbAudioProcessor)
    private val audioVisualizerController = AudioVisualizerController()
    private val silenceMeter = SilenceMeter()
    private var crossfadeController: CrossfadeController? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Built lazily (not at construction) since it closes over the @Inject fields above, which
    // Hilt only populates once this service's onCreate() starts - see BrowseTree's own kdoc for
    // why this is a separate class instead of more methods bolted onto sessionCallback below.
    private val browseTree by lazy {
        BrowseTree(trackDao, downloadDao, likesRepository, playlistRepository, searchRepository, streamResolverRegistry) {
            settingsRepository.dataSaverModeCached
        }
    }

    /** heart-outline/heart-filled for the "Gefällt mir" notification action -
     * CommandButton.ICON_HEART_FILLED/UNFILLED are Media3's own predefined @Icon
     * constants for exactly this (no custom drawable needed, unlike [downloadButton]). */
    private fun likeButton(liked: Boolean): CommandButton =
        CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setSessionCommand(SessionCommand(ACTION_LIKE, Bundle.EMPTY))
            .setDisplayName(if (liked) "Gefällt mir entfernen" else "Gefällt mir")
            // Flanks the standard transport row on the side closest to skip-back,
            // matching the design's heart-skipback-playpause-skipforward-download
            // button order (see the HTML in the PlaybackService kdoc-linked feature
            // request) - DefaultMediaNotificationProvider's default getMediaButtons()
            // places SLOT_BACK_SECONDARY/SLOT_FORWARD_SECONDARY buttons around the
            // standard controls without needing to override that method ourselves.
            .setSlots(CommandButton.SLOT_BACK_SECONDARY)
            .build()

    /** No CommandButton.ICON_DOWNLOAD constant exists (unlike the heart icons above),
     * so this one uses a plain custom icon resource instead. */
    private fun downloadButton(): CommandButton =
        CommandButton.Builder()
            .setIconResId(R.drawable.ic_notif_download)
            .setSessionCommand(SessionCommand(ACTION_DOWNLOAD, Bundle.EMPTY))
            .setDisplayName("Herunterladen")
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY)
            .build()

    private fun isCurrentTrackLiked(): Boolean {
        val trackId = playerController.playbackState.value.currentTrackId ?: return false
        return trackId in likesRepository.likedTrackIds.value
    }

    /** Handles the two custom session commands the notification's like/download
     * buttons issue (Media3's standard transport commands only cover
     * play/pause/skip/seek), plus - now that [PlaybackService] is a [MediaLibraryService] -
     * the Android Auto/Assistant browse tree ([BrowseTree]) and the queue resolution that
     * lets a car-tapped browse item actually play (`onSetMediaItems`/`onAddMediaItems`).
     * Reuses [PlayerController.nowPlayingTrack] for the like/download commands - the same
     * TrackResultDto the Player screen's "Gefällt mir"/"Herunterladen" menu items
     * already act on (see PlayerViewModel.toggleLike/onDownloadClicked) - rather than
     * reconstructing a track from the session's own MediaItem/MediaMetadata, which
     * doesn't carry every field (e.g. webpageUrl) DownloadRepository's caching wants. */
    private val sessionCallback = object : MediaLibrarySession.Callback {
        /** The session is exported, so any installed app can bind and connect a
         * controller. Only [isTrustedController]s - System UI, the notification,
         * Android Auto, the Assistant and this app's own PlayerController - get full
         * player control, the like/download commands and the browse tree (likes,
         * playlists, downloads, search). Anyone else gets transport controls only
         * (play/pause, skip, seek): no library commands - otherwise any app could
         * read the user's likes and playlists through the browse tree - and no
         * commands that replace or edit the queue. */
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (!isTrustedController(session, controller)) {
                // Transport (play/pause/skip/seek) stays open for watches, launchers and
                // other remote controls; replacing or editing the queue does not, or
                // any app could start whatever it likes in the user's player.
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_COMMANDS)
                    .setAvailablePlayerCommands(
                        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                            .removeAll(
                                Player.COMMAND_SET_MEDIA_ITEM,
                                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                                Player.COMMAND_SET_PLAYLIST_METADATA,
                                Player.COMMAND_SET_VOLUME,
                                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                            )
                            .build(),
                    )
                    .build()
            }
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_LIKE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_DOWNLOAD, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setCustomLayout(listOf(likeButton(isCurrentTrackLiked()), downloadButton()))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            // Media3 already refuses commands onConnect didn't grant; checked again here
            // so a later change to onConnect can't silently open these up.
            if (!isTrustedController(session, controller)) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            when (customCommand.customAction) {
                ACTION_LIKE -> serviceScope.launch {
                    playerController.nowPlayingTrack()?.let { likesRepository.toggle(it) }
                }
                ACTION_DOWNLOAD -> playerController.nowPlayingTrack()?.let { downloadRepository.startDownload(it) }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // params?.isRecent (Auto's "resume where the user left off" root request) is ignored -
        // this app has no separate "recent root" concept, so the normal root is a valid minimal
        // answer to it, same as [BrowseTree]'s own kdoc notes.
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Same belt-and-braces check as onCustomCommand; every other browse call
            // needs a root first, so gating this one closes the whole tree.
            if (!isTrustedController(session, browser)) {
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
            }
            return serviceScope.future { LibraryResult.ofItem(browseTree.libraryRoot(), params) }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = serviceScope.future {
            browseTree.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceScope.future {
            LibraryResult.ofItemList(browseTree.children(parentId, page, pageSize), params)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = serviceScope.future {
            val resultCount = browseTree.search(query)
            session.notifySearchResultChanged(browser, query, resultCount, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceScope.future {
            LibraryResult.ofItemList(browseTree.searchResults(query, page, pageSize), params)
        }

        // The in-app MediaController path (PlayerController) never calls setMediaItems/
        // addMediaItems on the controller directly - it resolves streams itself and issues
        // setMediaItems() with already-playable items - so overriding these two only affects
        // an *external* controller (Android Auto, Assistant, a future Wear/cast client)
        // handing back one of BrowseTree's stub media ids.
        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // The app's own PlayerController already sends resolved items (with a
            // URI). Only browse-tree stubs from external controllers need resolving -
            // re-resolving everything here re-ran every network lookup (YouTube
            // fallback searches included) before the player even received the item,
            // measured at 16s of extra start delay, and made player events lag the UI.
            if (mediaItems.all { it.localConfiguration != null }) {
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
            }
            return serviceScope.future { browseTree.buildQueue(mediaItems, startIndex, startPositionMs) }
        }

        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            // See onSetMediaItems: in-app items arrive resolved - pass them through.
            if (mediaItems.all { it.localConfiguration != null }) return Futures.immediateFuture(mediaItems)
            return serviceScope.future { browseTree.resolvePlayable(mediaItems).toMutableList() }
        }
    }

    /** This app's own MediaController and Media3's notification controller, anything
     * the system vouches for ([MediaSession.ControllerInfo.isTrusted]: holders of
     * MEDIA_CONTENT_CONTROL such as System UI's media controls), Android Auto /
     * Automotive, and the Google packages that drive voice and car playback. A
     * package name alone is only trusted if it really belongs to the connecting uid. */
    private fun isTrustedController(session: MediaSession, controller: MediaSession.ControllerInfo): Boolean {
        if (controller.isTrusted) return true
        if (session.isMediaNotificationController(controller)) return true
        if (session.isAutomotiveController(controller) || session.isAutoCompanionController(controller)) return true
        val pkg = controller.packageName
        if (pkg != packageName && pkg !in TRUSTED_CONTROLLER_PACKAGES) return false
        return packageManager.getPackagesForUid(controller.uid)?.contains(pkg) == true
    }

    override fun onCreate() {
        super.onCreate()

        setMediaNotificationProvider(musicNotificationProvider)

        // Hi-res / best-effort-fidelity path: request float PCM processing where the
        // device supports it, which avoids some internal integer resampling/quantization
        // steps. This is NOT true bit-perfect output -- Android's mixer still sits
        // between the app and the DAC on stock, unrooted devices -- see the disclaimer
        // shown next to the Settings toggle that drives this.
        // The audio sink is overridden purely to splice a TeeAudioProcessor into the
        // processing chain: it passes every decoded PCM buffer through untouched while
        // also handing a read-only copy to audioVisualizerController, which is what
        // makes the Player's Visualizer overlay react to the actual audio. Doing it
        // here (rather than via android.media.audiofx.Visualizer, which this used to
        // use) needs no RECORD_AUDIO permission - see AudioVisualizerController's kdoc.
        // enableFloatOutput/enableAudioTrackPlaybackParams are forwarded from the
        // defaults so the hi-res float-output path below still works as before.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                .setAudioProcessors(
                    arrayOf(TeeAudioProcessor(silenceMeter), eqAudioProcessor, TeeAudioProcessor(audioVisualizerController), reverbAudioProcessor),
                )
                .build()
        }.apply {
            if (readHiResAudioSetting()) {
                setEnableAudioFloatOutput(true)
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Was tuned to 1s/5s/250ms/500ms to prioritize "sound in <1s" over buffering
        // stability, but that left too little cushion for real-world wifi/4G hiccups -
        // a brief network dip drains the entire 5s max buffer and stalls playback with
        // no room to recover. Media3's own defaults are 50s/50s/2.5s/5s; this splits the
        // difference: still starts in ~1.5s (barely perceptible), but keeps enough
        // buffered ahead (15-30s) to absorb typical mobile-network hiccups without
        // audible stalls, and waits for a fuller 3s cushion after a rebuffer before
        // resuming so a flaky connection doesn't stutter play/pause/play in a loop.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .build()

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            // handleAudioFocus = true: Media3 automatically ducks on transient focus loss
            // (e.g. a nav prompt) and pauses/resumes on more severe focus changes (e.g. a
            // phone call), instead of us hand-rolling AudioManager focus requests.
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .build()
        // "Zur Warteschlange hinzufügen" stays the next track with shuffle on too
        exoPlayer.setShuffleOrder(PlayNextShuffleOrder())
        player = exoPlayer

        // EQ, visualizer tap and 3D-sound are all in-process audio processors in the
        // sink above - no platform effect bound to an audio session.
        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            // While paused no PCM buffers flow, so the spectrum would otherwise freeze
            // on whatever the final frame happened to be - leaving the overlay stuck
            // mid-pose. Zeroing it lets the UI settle into its rest shape, and makes
            // the visual strictly "live while playing, at rest while not".
            override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
                if (!isPlaying) audioVisualizerController.reset()
            }
        })

        crossfadeController = CrossfadeController(
            context = this,
            mainPlayer = exoPlayer,
            mediaSourceFactory = mediaSourceFactory,
            renderersFactory = object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                    DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(tailEqAudioProcessor)).build()
            },
            onTailStarted = { id ->
                val known = loudnessCache[id] ?: eqAudioProcessor.measured()?.takeIf { it.first == id }?.second
                tailEqAudioProcessor.startTrack(id, known)
            },
            scope = serviceScope,
            trackAnalysisDao = trackAnalysisDao,
            masterVolume = { playerController.sleepFadeVolume.value },
        )
        // The sleep timer's fade-out. During a crossfade the ramp applies it itself
        // (masterVolume above) - writing it here too would undo the ramp's levels.
        serviceScope.launch {
            playerController.sleepFadeVolume.collect { v ->
                if (crossfadeController?.controlsMainVolume != true) exoPlayer.volume = v
            }
        }
        // Cancels any in-flight fade once something outside CrossfadeController's own
        // control moves the main player on. User commands (skip/seek/previous, whether
        // from the app or the notification/lock screen) are caught where they enter -
        // see userCommandPlayer below - so the fade's own advance, which goes straight
        // to exoPlayer, never cancels itself and no time window is needed to tell the
        // two apart.
        exoPlayer.addListener(object : Player.Listener {
            // playWhenReady distinguishes a real pause from the brief not-playing gap
            // while the next item buffers right after a fade's own advance - cancelling
            // on the latter killed every fade before it was audible.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && !exoPlayer.playWhenReady) crossfadeController?.cancelFade()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                onTrackChangedForLoudness(mediaItem)
                // The current item itself was replaced (a new queue, a source switch).
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) crossfadeController?.cancelFade()
            }

            // SOURCE_UPDATE (e.g. an HLS live window refreshing) is not a queue change
            // and must NOT cancel a fade. A real playlist edit only does when it
            // replaces one of the two tracks being faded - see onPlaylistChanged.
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) crossfadeController?.onPlaylistChanged()
            }
        })
        serviceScope.launch {
            // Crossfade trigger check - see CrossfadeController.poll's kdoc for why
            // this is a cheap no-op whenever the setting is off (the default).
            while (isActive) {
                delay(CROSSFADE_POLL_INTERVAL_MS)
                crossfadeController?.poll(settingsRepository.crossfadeSecondsCached * 1000L) {
                    playerController.markCurrentTrackCompletedForCrossfade()
                }
                skipTrailingSilence(exoPlayer)
            }
        }

        playerController.visualizerPump = { audioVisualizerController.pumpNextFrame() }
        serviceScope.launch {
            playerController.visualizerDemand.collect { demand ->
                audioVisualizerController.enabled = demand
                if (!demand) audioVisualizerController.reset()
            }
        }
        serviceScope.launch {
            audioVisualizerController.frames.collect { frame -> playerController.updateVisualizerFrame(frame) }
        }
        // Watchdog for the cases where PCM silently stops reaching the visualizer tap
        // while playback continues (see AudioVisualizerController.hasGoneStale) - the
        // overlay settles into its rest state instead of freezing on the last frame.
        serviceScope.launch {
            while (isActive) {
                delay(STALE_CHECK_INTERVAL_MS)
                if (audioVisualizerController.hasGoneStale()) audioVisualizerController.reset()
            }
        }

        serviceScope.launch {
            settingsRepository.loudnessNormalization.collect { on ->
                eqAudioProcessor.setNormalization(on)
                tailEqAudioProcessor.setNormalization(on)
            }
        }
        serviceScope.launch {
            settingsRepository.eqProfile.collect { profile ->
                eqAudioProcessor.setProfile(profile)
                tailEqAudioProcessor.setProfile(profile)
            }
        }
        // Loudness compensation needs the listening level. The EQ's volume scale is
        // cubic (gain = v^3), so the stream's real attenuation in dB maps to v = 10^(dB/60).
        serviceScope.launch {
            val audioManager = getSystemService(AudioManager::class.java)
            while (isActive) {
                val v = runCatching {
                    val index = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val db = audioManager.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                    if (index == 0) 0f else 10f.pow(db.coerceAtMost(0f) / 60f)
                }.getOrDefault(1f)
                eqAudioProcessor.setVolume(v)
                tailEqAudioProcessor.setVolume(v)
                delay(VOLUME_POLL_MS)
            }
        }
        serviceScope.launch {
            settingsRepository.sound3dPreset.collect { presetName ->
                val preset = runCatching { Sound3dPreset.valueOf(presetName) }.getOrDefault(Sound3dPreset.DISABLED)
                sound3dController.applyPreset(preset)
            }
        }
        // Glance closes an idle widget session after ~45 s, and a closed session no
        // longer recomposes from playbackState - the widget froze on whatever it showed
        // last until someone tapped it. Poke it on every track/play/like change instead
        // (updateAll restarts the session when it is gone, and is cheap when it isn't).
        serviceScope.launch {
            playerController.playbackState
                .map { Triple(it.currentTrackId, it.isPlaying, it.artworkUrl) }
                .distinctUntilChanged()
                .collect { runCatching { PlaybackWidget().updateAll(this@PlaybackService) } }
        }
        serviceScope.launch {
            likesRepository.likedTrackIds
                .collect { runCatching { PlaybackWidget().updateAll(this@PlaybackService) } }
        }
        // The widget polls its own position while its Glance session runs, but Glance
        // closes an idle session after ~45 s: poke it every few seconds while music
        // plays and the screen is on (nobody sees it otherwise), and only if a widget
        // is actually placed.
        serviceScope.launch {
            val power = getSystemService(android.os.PowerManager::class.java)
            val glance = androidx.glance.appwidget.GlanceAppWidgetManager(this@PlaybackService)
            while (isActive) {
                delay(WIDGET_TICK_MS)
                val p = player ?: continue
                if (!p.isPlaying || power?.isInteractive != true) continue
                val placed = runCatching { glance.getGlanceIds(PlaybackWidget::class.java).isNotEmpty() }.getOrDefault(false)
                if (placed) runCatching { PlaybackWidget().updateAll(this@PlaybackService) }
            }
        }
        serviceScope.launch {
            settingsRepository.volumeBoostDb.collect { db ->
                eqAudioProcessor.setBoost(db.toDouble())
                tailEqAudioProcessor.setBoost(db.toDouble())
            }
        }
        serviceScope.launch {
            settingsRepository.sound3dWetPercent.collect { percent ->
                reverbAudioProcessor.wetScale = percent / 100f
            }
        }

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaLibrarySession.Builder(this, userCommandPlayer(exoPlayer), sessionCallback)
            .setSessionActivity(sessionActivity)
            .build()
        // The widget (and the notification's like state) read PlayerController's
        // state, which only follows the player once it is connected. Playback can
        // start without the app's UI ever opening (headset, Bluetooth, the
        // notification after a process restart) - connect from here as well, or the
        // widget keeps showing the track from before.
        playerController.connectInBackground()

        // Keeps the notification's heart icon in sync with the actual liked state
        // (filled vs outline) as it changes - either from this same notification's
        // like button, or from the Player screen's own "Gefällt mir" toggle, since
        // both act on the same LikesRepository. Re-issuing setCustomLayout() is how
        // Media3 expects a custom command button's icon to be updated after the
        // fact (there's no separate "update button" API).
        serviceScope.launch {
            combine(playerController.playbackState, likesRepository.likedTrackIds) { state, likedIds ->
                state.currentTrackId != null && state.currentTrackId in likedIds
            }.distinctUntilChanged().collect { liked ->
                mediaSession?.setCustomLayout(listOf(likeButton(liked), downloadButton()))
            }
        }
    }

    /** The Hi-Res setting, read from storage rather than SettingsRepository's cache:
     * that cache starts out false and is filled asynchronously, so on a cold start (the
     * service created together with the app process) it had usually not arrived yet
     * and the setting was silently ignored. It can only be applied while the player is
     * built - hence the short blocking read, bounded so a slow disk can't stall the
     * service's start. */
    private fun readHiResAudioSetting(): Boolean =
        runBlocking { withTimeoutOrNull(HI_RES_READ_TIMEOUT_MS) { settingsRepository.hiResAudio.first() } }
            ?: settingsRepository.hiResAudioCached

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    /** The player the media session drives: every command from a controller - the
     * app's own PlayerController as well as the notification, lock screen and Android
     * Auto - arrives through it, so a skip, seek or stop cancels a running crossfade
     * right away, before the player even moves. CrossfadeController advances the
     * ExoPlayer directly and is never seen here. */
    private fun userCommandPlayer(exoPlayer: ExoPlayer): Player = object : ForwardingPlayer(exoPlayer) {
        private fun userMoved() {
            crossfadeController?.cancelFade()
        }

        override fun seekToDefaultPosition() { userMoved(); super.seekToDefaultPosition() }
        override fun seekToDefaultPosition(mediaItemIndex: Int) { userMoved(); super.seekToDefaultPosition(mediaItemIndex) }
        override fun seekTo(positionMs: Long) { userMoved(); super.seekTo(positionMs) }
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) { userMoved(); super.seekTo(mediaItemIndex, positionMs) }
        override fun seekBack() { userMoved(); super.seekBack() }
        override fun seekForward() { userMoved(); super.seekForward() }
        // The PC's live mix is one endless item: Next/Previous from the notification,
        // headset or Android Auto skip on the PC, as the app's own buttons do.
        private val skipCommands = intArrayOf(
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
        private fun pcLive() = playerController.isPlayingPcLive
        private fun pcSkip(forward: Boolean): Boolean {
            if (!pcLive()) return false
            playerController.pcLiveSkip?.invoke(forward)
            return true
        }
        override fun getAvailableCommands(): Player.Commands =
            if (pcLive()) super.getAvailableCommands().buildUpon().addAll(*skipCommands).build() else super.getAvailableCommands()
        override fun isCommandAvailable(command: Int): Boolean =
            (pcLive() && command in skipCommands) || super.isCommandAvailable(command)
        override fun seekToPreviousMediaItem() { if (pcSkip(false)) return; userMoved(); super.seekToPreviousMediaItem() }
        override fun seekToPrevious() { if (pcSkip(false)) return; userMoved(); super.seekToPrevious() }
        override fun seekToNextMediaItem() { if (pcSkip(true)) return; userMoved(); super.seekToNextMediaItem() }
        override fun seekToNext() { if (pcSkip(true)) return; userMoved(); super.seekToNext() }
        override fun stop() { userMoved(); super.stop() }
    }

    /** Stores the loudness measured for the track that just ended and tells the
     * normaliser which track plays now (with its stored loudness, if heard before). */
    private fun onTrackChangedForLoudness(mediaItem: MediaItem?) {
        eqAudioProcessor.measured()?.let { (id, lufs) -> loudnessCache.put(id, lufs) }
        val id = mediaItem?.mediaId?.takeIf { it.isNotEmpty() } ?: return
        eqAudioProcessor.startTrack(id, loudnessCache[id])
        serviceScope.launch(Dispatchers.IO) { loudnessCache.save() }
    }

    override fun onDestroy() {
        eqAudioProcessor.measured()?.let { (id, lufs) -> loudnessCache.put(id, lufs) }
        loudnessCache.save()
        // Releases the secondary player (if a fade happened to be running) before the
        // main player and scope go away, rather than leaking it.
        crossfadeController?.cancelFade()
        crossfadeController = null
        // reset() before cancelling the scope, so the zeroed spectrum it publishes
        // still reaches the collector above rather than being dropped on the floor.
        playerController.visualizerPump = null
        audioVisualizerController.reset()
        audioVisualizerController.release()
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }

    /** Gapless playback: ExoPlayer already joins tracks without a gap, but many uploads
     * end in seconds of dead air. Once [SilenceMeter] shows the end of the current track
     * is silent, the player moves straight on to the next one. With crossfade on, its
     * own mix points handle the ends instead. */
    private fun skipTrailingSilence(p: ExoPlayer) {
        if (settingsRepository.crossfadeSecondsCached > 0 || crossfadeController?.isFading == true) return
        if (!p.isPlaying || p.repeatMode == Player.REPEAT_MODE_ONE || !p.hasNextMediaItem()) return
        val duration = p.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val remaining = duration - p.currentPosition
        if (remaining !in 1..TAIL_WINDOW_MS) return
        if (silenceMeter.silentMs < TAIL_SILENCE_MS) return
        silenceMeter.reset()
        playerController.markCurrentTrackCompletedForCrossfade()
        p.seekToNextMediaItem()
    }

    companion object {
        private const val STALE_CHECK_INTERVAL_MS = 250L
        private const val VOLUME_POLL_MS = 1_000L
        private const val HI_RES_READ_TIMEOUT_MS = 500L

        /** How often queued spectra are released to the UI - one display frame at 60Hz. */

        /** How often the crossfade trigger condition is checked - see
         * CrossfadeController.poll's kdoc. */
        private const val CROSSFADE_POLL_INTERVAL_MS = 200L
        private const val WIDGET_TICK_MS = 10_000L
        /** Silence this long in the decoded audio is surely audible already (the decoder
         * runs at most the AudioTrack buffer, ≤ 0.75 s, ahead). */
        private const val TAIL_SILENCE_MS = 1_200L
        /** Only a track's last seconds count as its end; a pause further in is music. */
        private const val TAIL_WINDOW_MS = 12_000L
        private const val ACTION_LIKE = "dev.schlubbe.musicagent.ACTION_LIKE"
        private const val ACTION_DOWNLOAD = "dev.schlubbe.musicagent.ACTION_DOWNLOAD"

        /** See [isTrustedController]. */
        private val TRUSTED_CONTROLLER_PACKAGES = setOf(
            "com.google.android.projection.gearhead", // Android Auto
            "com.google.android.googlequicksearchbox", // Google Assistant
            "com.android.systemui",
        )
    }
}
