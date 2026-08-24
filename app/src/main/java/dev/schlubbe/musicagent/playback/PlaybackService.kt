package dev.schlubbe.musicagent.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.schlubbe.musicagent.MainActivity
import dev.schlubbe.musicagent.R
import dev.schlubbe.musicagent.data.repository.DownloadRepository
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

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

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val equalizerController = EqualizerController()
    private val sound3dController = Sound3dController()
    private val audioVisualizerController = AudioVisualizerController()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
     * play/pause/skip/seek). Reuses [PlayerController.nowPlayingTrack] - the same
     * TrackResultDto the Player screen's "Gefällt mir"/"Herunterladen" menu items
     * already act on (see PlayerViewModel.toggleLike/onDownloadClicked) - rather than
     * reconstructing a track from the session's own MediaItem/MediaMetadata, which
     * doesn't carry every field (e.g. webpageUrl) DownloadRepository's caching wants. */
    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_LIKE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_DOWNLOAD, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
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
            when (customCommand.customAction) {
                ACTION_LIKE -> serviceScope.launch {
                    playerController.nowPlayingTrack()?.let { likesRepository.toggle(it) }
                }
                ACTION_DOWNLOAD -> playerController.nowPlayingTrack()?.let { downloadRepository.startDownload(it) }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
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
                .setAudioProcessors(arrayOf(TeeAudioProcessor(audioVisualizerController)))
                .build()
        }.apply {
            if (settingsRepository.hiResAudioCached) {
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
        player = exoPlayer

        // Equalizer/3D-sound are genuine AudioEffects bound to the audio session (and
        // unlike the Visualizer effect they need no RECORD_AUDIO). The visualizer is
        // not in this list: it reads the PCM stream via the TeeAudioProcessor
        // installed in the audio sink above, so it has no session to attach to.
        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                equalizerController.attach(audioSessionId)
                sound3dController.attach(audioSessionId)
            }

            // While paused no PCM buffers flow, so the spectrum would otherwise freeze
            // on whatever the final frame happened to be - leaving the overlay stuck
            // mid-pose. Zeroing it lets the UI settle into its rest shape, and makes
            // the visual strictly "live while playing, at rest while not".
            override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
                if (!isPlaying) audioVisualizerController.reset()
            }
        })
        // The session id may already be assigned by the time we attach the listener above.
        equalizerController.attach(exoPlayer.audioSessionId)
        sound3dController.attach(exoPlayer.audioSessionId)
        serviceScope.launch {
            audioVisualizerController.bands.collect { bands -> playerController.updateVisualizerBands(bands) }
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
            settingsRepository.eqPreset.collect { preset -> equalizerController.applyPreset(preset) }
        }
        serviceScope.launch {
            settingsRepository.customEqGains.collect { gains -> equalizerController.applyCustomGains(gains) }
        }
        serviceScope.launch {
            settingsRepository.sound3dPreset.collect { presetName ->
                val preset = runCatching { Sound3dPreset.valueOf(presetName) }.getOrDefault(Sound3dPreset.DISABLED)
                sound3dController.applyPreset(preset)
            }
        }

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivity)
            .setCallback(sessionCallback)
            .build()

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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        // reset() before cancelling the scope, so the zeroed spectrum it publishes
        // still reaches the collector above rather than being dropped on the floor.
        audioVisualizerController.reset()
        serviceScope.cancel()
        equalizerController.release()
        sound3dController.release()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }

    companion object {
        private const val STALE_CHECK_INTERVAL_MS = 250L
        private const val ACTION_LIKE = "dev.schlubbe.musicagent.ACTION_LIKE"
        private const val ACTION_DOWNLOAD = "dev.schlubbe.musicagent.ACTION_DOWNLOAD"
    }
}
