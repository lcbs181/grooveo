package dev.schlubbe.musicagent.desktop.audio

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.nio.ShortBuffer
import kotlin.math.min
import kotlin.math.sqrt

data class MixPoints(val mixInMs: Long, val mixOutMs: Long)

/**
 * "Übergänge analysieren": finds where a track's body starts (mix-in) and ends
 * (mix-out) from its loudness envelope, so crossfades skip silent intros/outros.
 * Same algorithm and constants as the Android TrackAnalyzer; decoding uses FFmpeg,
 * so on desktop it also works directly on streams, not only downloads.
 */
object TrackAnalyzer {
    const val FRAME_MS = 50L
    const val EDGE_WINDOW_MS = 30_000L
    const val MAX_EDGE_SKIP_MS = 15_000L
    const val MIN_BODY_MS = 20_000L
    const val MIN_SUSTAIN_FRAMES = 6
    const val INTRO_THRESHOLD = 0.22f
    const val OUTRO_THRESHOLD = 0.30f
    private const val RATE = 11025

    fun analyze(url: String, headers: Map<String, String> = emptyMap()): MixPoints? {
        val g = FFmpegFrameGrabber(url).apply {
            sampleRate = RATE; audioChannels = 1; sampleFormat = avutil.AV_SAMPLE_FMT_S16
            if (headers.isNotEmpty()) setOption("headers", headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" })
        }
        return try {
            g.start()
            val totalMs = g.lengthInTime / 1000
            val edge = EDGE_WINDOW_MS
            if (totalMs <= 0 || totalMs < 3 * edge) {
                val env = envelope(g, Long.MAX_VALUE)
                computeMixPoints(env, 0, env, 0, env.maxOrNull() ?: 0f, if (totalMs > 0) totalMs else env.size * FRAME_MS)
            } else {
                val intro = envelope(g, edge)
                g.setAudioTimestamp((totalMs / 2 - edge / 2) * 1000)
                val middle = envelope(g, edge)
                val outroStart = totalMs - edge
                g.setAudioTimestamp(outroStart * 1000)
                val outro = envelope(g, Long.MAX_VALUE)
                val peak = maxOf(intro.maxOrNull() ?: 0f, middle.maxOrNull() ?: 0f, outro.maxOrNull() ?: 0f)
                computeMixPoints(intro, 0, outro, outroStart, peak, totalMs)
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { g.close() }
        }
    }

    /** RMS per 50 ms frame for the next [durationMs] of audio. */
    private fun envelope(g: FFmpegFrameGrabber, durationMs: Long): List<Float> {
        val frameSamples = (RATE * FRAME_MS / 1000).toInt()
        val maxSamples = if (durationMs == Long.MAX_VALUE) Long.MAX_VALUE else RATE * durationMs / 1000
        val env = mutableListOf<Float>()
        var acc = 0.0
        var cnt = 0
        var total = 0L
        while (total < maxSamples) {
            val f = g.grabSamples() ?: break
            val sb = f.samples?.firstOrNull() as? ShortBuffer ?: continue
            for (i in sb.position() until sb.limit()) {
                val v = sb.get(i).toDouble()
                acc += v * v
                if (++cnt == frameSamples) { env += sqrt(acc / cnt).toFloat(); acc = 0.0; cnt = 0 }
                if (++total >= maxSamples) break
            }
        }
        return env
    }

    fun computeMixPoints(intro: List<Float>, introStartMs: Long, outro: List<Float>, outroStartMs: Long, peak: Float, totalMs: Long): MixPoints? {
        if (totalMs < MIN_BODY_MS || intro.isEmpty() || outro.isEmpty() || peak <= 0f) return null
        val mixIn = introStartMs + findMixIn(intro, peak) * FRAME_MS
        val mixOut = outroStartMs + findMixOut(outro, peak) * FRAME_MS
        if (mixOut - mixIn < MIN_BODY_MS) return null
        return MixPoints(mixIn, mixOut)
    }

    private fun findMixIn(env: List<Float>, peak: Float): Int {
        val threshold = peak * INTRO_THRESHOLD
        val cap = min(env.size, (MAX_EDGE_SKIP_MS / FRAME_MS).toInt())
        var run = 0
        for (i in 0 until cap) {
            if (env[i] >= threshold) { if (++run >= MIN_SUSTAIN_FRAMES) return (i - MIN_SUSTAIN_FRAMES + 1).coerceAtLeast(0) } else run = 0
        }
        return 0
    }

    private fun findMixOut(env: List<Float>, peak: Float): Int {
        val threshold = peak * OUTRO_THRESHOLD
        val cap = min(env.size, (MAX_EDGE_SKIP_MS / FRAME_MS).toInt())
        val last = env.size - 1
        var run = 0
        for (i in last downTo (last - cap + 1).coerceAtLeast(0)) {
            if (env[i] >= threshold) { if (++run >= MIN_SUSTAIN_FRAMES) return (i + MIN_SUSTAIN_FRAMES - 1).coerceAtMost(last) } else run = 0
        }
        return last
    }
}
