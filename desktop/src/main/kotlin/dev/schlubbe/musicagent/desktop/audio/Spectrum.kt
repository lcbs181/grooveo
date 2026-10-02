package dev.schlubbe.musicagent.desktop.audio

import dev.schlubbe.musicagent.playback.reverb.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** One analysed slice of what is currently audible. Arrays are owned by the frame (immutable by convention). */
class VisualizerFrame(
    val bands: FloatArray,
    /** 0..1 low-frequency energy (< 320 Hz). */
    val bass: Float,
    /** 0..1 broadband level. */
    val level: Float,
    /** 1 at a detected kick, decaying to 0. */
    val onset: Float,
) {
    companion object {
        val EMPTY = VisualizerFrame(FloatArray(SpectrumAnalyzer.BANDS), 0f, 0f, 0f)
    }
}

/**
 * Same analysis model as the Android visualizer tap (AudioVisualizerController):
 * 2048-point FFT reduced to 256 log-spaced bands (35 Hz..16 kHz), dB window
 * -75..-15, asymmetric attack/decay smoothing, plus a time-domain kick detector
 * (cascaded low-pass + fast/slow envelope rise).
 */
class SpectrumAnalyzer(private val sampleRate: Double = SAMPLE_RATE) {
    private val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }
    private val history = FloatArray(FFT_SIZE)
    private var histPos = 0
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val smoothed = FloatArray(BANDS)
    private val edges = IntArray(BANDS + 1) { i ->
        val f = 35.0 * (16000.0 / 35.0).pow(i / BANDS.toDouble())
        (f / sampleRate * FFT_SIZE).toInt().coerceIn(1, FFT_SIZE / 2 - 1)
    }
    private val bassBand = (0 until BANDS).last { edges[it] / FFT_SIZE.toDouble() * sampleRate < 320 }

    // kick detector state
    private var lp1 = 0f
    private var lp2 = 0f
    private var envFast = 0f
    private var envSlow = 0f
    private var onset = 0f
    private var refractory = 0
    private val lpCoef = (1 - exp(-2 * PI * 150 / sampleRate)).toFloat()
    private val fastCoef = (1 - exp(-1 / (0.005 * sampleRate))).toFloat()
    private val slowCoef = (1 - exp(-1 / (0.25 * sampleRate))).toFloat()

    @Volatile var latest: VisualizerFrame = VisualizerFrame.EMPTY
        private set

    /** Feeds interleaved stereo [buf] ([frames] frames) and publishes a new frame. */
    fun push(buf: FloatArray, frames: Int) {
        var sumSq = 0.0
        var kick = false
        for (i in 0 until frames) {
            val m = (buf[2 * i] + buf[2 * i + 1]) * 0.5f
            history[histPos] = m
            histPos = (histPos + 1) % FFT_SIZE
            sumSq += m * m
            lp1 += lpCoef * (m - lp1)
            lp2 += lpCoef * (lp1 - lp2)
            val a = kotlin.math.abs(lp2)
            envFast += fastCoef * (a - envFast)
            envSlow += slowCoef * (a - envSlow)
            if (refractory > 0) refractory--
            else if (envFast > envSlow * 1.6f && envFast > 0.02f) {
                kick = true
                refractory = (0.12 * sampleRate).toInt()
            }
        }
        onset = if (kick) 1f else onset * 0.82f
        for (i in 0 until FFT_SIZE) {
            re[i] = history[(histPos + i) % FFT_SIZE] * window[i]
            im[i] = 0f
        }
        Fft.transform(re, im, inverse = false)
        val bands = FloatArray(BANDS)
        var bass = 0f
        for (b in 0 until BANDS) {
            var peak = 0f
            for (k in edges[b]..max(edges[b], edges[b + 1] - 1)) peak = max(peak, re[k] * re[k] + im[k] * im[k])
            val db = 10 * log10(peak.toDouble().coerceAtLeast(1e-12)) - 20 * log10(FFT_SIZE / 4.0)
            val norm = ((db + 75) / 60).toFloat().coerceIn(0f, 1f)
            val prev = smoothed[b]
            smoothed[b] = prev + (norm - prev) * (if (norm > prev) 0.6f else 0.16f)
            bands[b] = smoothed[b]
            if (b <= bassBand) bass += smoothed[b]
        }
        // light neighbour diffusion so isolated bins don't flicker
        for (b in 1 until BANDS - 1) bands[b] = bands[b] * 0.7f + (bands[b - 1] + bands[b + 1]) * 0.15f
        val level = (sqrt(sumSq / frames.coerceAtLeast(1)) * 3).toFloat().coerceIn(0f, 1f)
        latest = VisualizerFrame(bands, (bass / (bassBand + 1)).coerceIn(0f, 1f), level, onset)
    }

    fun reset() {
        history.fill(0f); smoothed.fill(0f); onset = 0f
        latest = VisualizerFrame.EMPTY
    }

    companion object {
        const val FFT_SIZE = 2048
        const val BANDS = 256
    }
}
