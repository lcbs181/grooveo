package dev.schlubbe.musicagent.playback

import android.media.audiofx.Visualizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow
import kotlin.math.sqrt

/** Number of frequency bands the raw FFT capture is reduced to - the UI's
 * Visualizer variants read this many normalized (0f..1f) values, independent of
 * how many real device-specific FFT bins backed them. */
const val VISUALIZER_BAND_COUNT = 32

/**
 * Wraps the platform [Visualizer] audio effect, attached to the current ExoPlayer's
 * audio session id - same attach-is-idempotent-per-session pattern as
 * [EqualizerController]/[Sound3dController]. Captures the actual playing waveform's
 * FFT in real time and reduces it to [VISUALIZER_BAND_COUNT] normalized magnitude
 * values, so the Player's Visualizer overlay is genuinely audio-reactive instead of
 * a decorative pseudo-random loop.
 *
 * No RECORD_AUDIO permission is needed: attaching to a specific, already-owned
 * non-zero audio session (this app's own ExoPlayer instance, the same session id
 * [EqualizerController]/[Sound3dController] already attach to without any recording
 * permission) only taps that session's own output. RECORD_AUDIO is only required
 * for capturing the device's overall audio mix (session id 0) or another app's
 * audio via the separate, more restrictive AudioPlaybackCapture API - neither
 * applies here.
 */
class AudioVisualizerController {
    private var visualizer: Visualizer? = null
    private var attachedSessionId: Int = 0

    private val _bands = MutableStateFlow(FloatArray(VISUALIZER_BAND_COUNT))
    val bands: StateFlow<FloatArray> = _bands.asStateFlow()

    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0 || audioSessionId == attachedSessionId) return
        release()
        attachedSessionId = audioSessionId
        visualizer = runCatching {
            Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(viz: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit

                        override fun onFftDataCapture(viz: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                            fft ?: return
                            _bands.value = reduceToBands(fft)
                        }
                    },
                    // Half the device's max rate is plenty smooth for a decorative
                    // overlay and halves the callback/CPU overhead of going full rate.
                    Visualizer.getMaxCaptureRate() / 2,
                    false,
                    true,
                )
                enabled = true
            }
        }.onFailure {
            Log.w("AudioVisualizerController", "Failed to attach Visualizer to session $audioSessionId", it)
        }.getOrNull()
    }

    fun release() {
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
        attachedSessionId = 0
    }

    /** Android's FFT byte layout ([Visualizer.getFft]'s own kdoc): `fft[0]` = DC
     * component (real only), `fft[1]` = Nyquist component (real only), then
     * interleaved (real, imaginary) pairs for every bin in between. Bins are pooled
     * into [VISUALIZER_BAND_COUNT] groups on an exponential (not linear) curve -
     * bass bins each get closer to their own band since low-frequency energy is
     * both perceptually dominant and where a music track's actual variation lives;
     * treble bins are coarsely pooled since individual high bins carry little
     * independently useful signal for a visual. */
    private fun reduceToBands(fft: ByteArray): FloatArray {
        val n = fft.size / 2
        if (n < 2) return FloatArray(VISUALIZER_BAND_COUNT)
        val magnitudes = FloatArray(n)
        magnitudes[0] = kotlin.math.abs(fft[0].toFloat())
        for (i in 1 until n) {
            val re = fft[2 * i].toFloat()
            val im = if (2 * i + 1 < fft.size) fft[2 * i + 1].toFloat() else 0f
            magnitudes[i] = sqrt(re * re + im * im)
        }
        val bands = FloatArray(VISUALIZER_BAND_COUNT)
        for (b in 0 until VISUALIZER_BAND_COUNT) {
            val start = n.toDouble().pow(b.toDouble() / VISUALIZER_BAND_COUNT).toInt().coerceIn(0, n - 1)
            val end = n.toDouble().pow((b + 1.0) / VISUALIZER_BAND_COUNT).toInt().coerceIn(start + 1, n)
            var sum = 0f
            for (i in start until end) sum += magnitudes[i]
            // Empirical normalization divisor for signed-byte FFT magnitudes (max
            // possible magnitude is ~180 for a full-scale signal, but real music
            // rarely approaches that on every bin) - clamped to 1f either way, so a
            // wrong constant only shifts how "hot" the visual reads, never breaks it.
            bands[b] = (sum / (end - start) / 40f).coerceIn(0f, 1f)
        }
        return bands
    }
}
