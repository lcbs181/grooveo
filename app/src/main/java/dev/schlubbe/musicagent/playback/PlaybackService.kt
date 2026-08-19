package dev.schlubbe.musicagent.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import dev.schlubbe.musicagent.MainActivity
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var mediaSourceFactory: MediaSource.Factory

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val equalizerController = EqualizerController()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // Hi-res / best-effort-fidelity path: request float PCM processing where the
        // device supports it, which avoids some internal integer resampling/quantization
        // steps. This is NOT true bit-perfect output -- Android's mixer still sits
        // between the app and the DAC on stock, unrooted devices -- see the disclaimer
        // shown next to the Settings toggle that drives this.
        val renderersFactory = DefaultRenderersFactory(this).apply {
            if (settingsRepository.hiResAudioCached) {
                setEnableAudioFloatOutput(true)
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Aggressively tuned for low latency: Media3's defaults are 50s/50s/2.5s/5s,
        // we use 1s/5s/250ms/500ms to prioritize "sound in <1s" over buffering stability
        // on home wifi/4G. The trade-off: occasional rebuffering if network hiccups, but
        // the common case (playing a single track on stable network) feels instant. On weak
        // networks, the EQ preset + sleep timer logic will naturally be more responsive
        // since they don't wait for huge buffers to populate.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 1_000,        // 1s minimum (was 15s!)
                /* maxBufferMs = */ 5_000,        // 5s max (was 30s)
                /* bufferForPlaybackMs = */ 250,  // 250ms before playing (was 500ms)
                /* bufferForPlaybackAfterRebufferMs = */ 500,
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

        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                equalizerController.attach(audioSessionId)
            }
        })
        // The session id may already be assigned by the time we attach the listener above.
        equalizerController.attach(exoPlayer.audioSessionId)

        serviceScope.launch {
            settingsRepository.eqPreset.collect { preset -> equalizerController.applyPreset(preset) }
        }

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        serviceScope.cancel()
        equalizerController.release()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }
}
