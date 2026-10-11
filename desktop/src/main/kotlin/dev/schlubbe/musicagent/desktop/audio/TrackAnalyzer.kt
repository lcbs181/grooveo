package dev.schlubbe.musicagent.desktop.audio

import org.bytedeco.ffmpeg.global.avutil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

data class MixPoints(val mixInMs: Long, val mixOutMs: Long)

/**
 * "Übergänge analysieren": finds where a track's body starts (mix-in) and ends
 * (mix-out) from its loudness envelope, so crossfades skip silent intros/outros,
 * and its tempo and beat grid (see BeatTracker). Mix points use the same algorithm
 * and constants as the Android TrackAnalyzer; decoding uses FFmpeg, so on desktop
 * it also works directly on streams, not only downloads.
 */
object TrackAnalyzer {
    const val FRAME_MS = 50L
    const val EDGE_WINDOW_MS = 30_000L
    const val MAX_EDGE_SKIP_MS = 15_000L
    const val MIN_BODY_MS = 20_000L
    const val MIN_SUSTAIN_FRAMES = 6
    const val INTRO_THRESHOLD = 0.22f
    const val OUTRO_THRESHOLD = 0.30f
    const val RATE = 22050
    /** Bass envelope resolution for placing the grid on the kick. */
    private const val BASS_FRAME_MS = 5

    data class Result(val mix: MixPoints?, val grid: BeatGrid?, val structure: TrackStructure? = null, val key: MusicalKey? = null)

    /**
     * [analyze] on [Dispatchers.IO] that stops decoding when the calling coroutine is
     * cancelled (and then throws its CancellationException instead of returning).
     */
    suspend fun analyzeCancellable(url: String, headers: Map<String, String> = emptyMap()): Result? = withContext(Dispatchers.IO) {
        val job = coroutineContext.job
        analyze(url, headers) { !job.isActive }.also { ensureActive() }
    }

    /**
     * Decodes the whole track once (mono, 22.05 kHz) and derives the mix points from
     * its loudness envelope and, when the aubio library is available, the beat grid.
     * Decoding stops (returning null) once [cancelled] says so.
     */
    fun analyze(url: String, headers: Map<String, String> = emptyMap(), cancelled: () -> Boolean = { false }): Result? {
        val g = FFmpegFrameGrabber(url).apply {
            sampleRate = RATE; audioChannels = 1; sampleFormat = avutil.AV_SAMPLE_FMT_FLT
            if (headers.isNotEmpty()) setOption("headers", headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" })
        }
        return try {
            g.start()
            analyzeSamples { sink ->
                while (true) {
                    if (cancelled()) throw Cancelled()
                    val f = g.grabSamples() ?: break
                    val fb = f.samples?.firstOrNull() as? FloatBuffer ?: continue
                    val n = fb.remaining()
                    val chunk = FloatArray(n)
                    fb.duplicate().get(chunk)
                    sink(chunk, n)
                }
            }
        } catch (e: Cancelled) {
            null
        } catch (e: Exception) {
            java.util.logging.Logger.getLogger("TrackAnalyzer").warning("analysis failed: $e")
            null
        } finally {
            runCatching { g.close() }
        }
    }

    /** Runs the analysis on mono [RATE] Hz samples that [feed] pushes into its sink. */
    fun analyzeSamples(feed: ((FloatArray, Int) -> Unit) -> Unit): Result {
        val env = FloatList()
        // frame ends in samples as Double: 50 ms and 5 ms are not whole samples at 22.05 kHz,
        // and rounding them would let the frames drift against real time
        val frameSamples = RATE * FRAME_MS / 1000.0
        var total = 0L
        var acc = 0.0
        var cnt = 0
        val bass = FloatList()
        val bassFrame = RATE * BASS_FRAME_MS / 1000.0
        val lpA = 1.0 - exp(-2.0 * PI * 120.0 / RATE)
        var lp1 = 0.0; var lp2 = 0.0
        var bAcc = 0.0
        var bCnt = 0
        // hi-hats and cymbals: what is left above a 3 kHz one-pole low-pass
        val high = FloatList()
        val hpA = 1.0 - exp(-2.0 * PI * 3000.0 / RATE)
        var lpHigh = 0.0
        var hAcc = 0.0
        val keys = KeyDetector(RATE)
        val tracker = if (BeatTracker.available) runCatching { BeatTracker(RATE) }.getOrNull() else null
        try {
            feed { chunk, n ->
                tracker?.process(chunk, n)
                keys.process(chunk, n)
                for (i in 0 until n) {
                    val v = chunk[i].toDouble()
                    acc += v * v
                    total++
                    cnt++
                    lpHigh += hpA * (v - lpHigh)
                    hAcc += (v - lpHigh) * (v - lpHigh)
                    if (total >= (env.size + 1) * frameSamples) {
                        env += sqrt(acc / cnt).toFloat(); high += sqrt(hAcc / cnt).toFloat()
                        acc = 0.0; hAcc = 0.0; cnt = 0
                    }
                    // two one-pole low-passes at 120 Hz: kick and bass only
                    lp1 += lpA * (v - lp1); lp2 += lpA * (lp1 - lp2)
                    bAcc += lp2 * lp2
                    bCnt++
                    if (total >= (bass.size + 1) * bassFrame) { bass += sqrt(bAcc / bCnt).toFloat(); bAcc = 0.0; bCnt = 0 }
                }
            }
            val loud = env.toFloatArray()
            val mix = mixPointsFromEnvelope(loud.asList())
            val onset = FloatArray(bass.size) { k -> if (k == 0) 0f else (bass[k] - bass[k - 1]).coerceAtLeast(0f) }
            val grid = tracker?.let { tr -> BeatTracker.fitGrid(tr.beatsMs(), tr.confidence(), onset, BASS_FRAME_MS.toDouble()) }
            val structure = grid?.let {
                val envs = StructureAnalyzer.Envelopes(
                    loud, high.toFloatArray(), FRAME_MS.toDouble(),
                    bass.toFloatArray(), onset, BASS_FRAME_MS.toDouble(),
                )
                runCatching { StructureAnalyzer.analyze(it, total * 1000.0 / RATE, envs) }.getOrNull()
            }
            return Result(mix, grid, structure, keys.result())
        } finally {
            tracker?.close()
        }
    }

    private class Cancelled : RuntimeException() { override fun fillInStackTrace() = this }

    /** A growable FloatArray: an hour of 5 ms frames as boxed Floats would take ~15 MB. */
    private class FloatList {
        private var a = FloatArray(4096)
        var size = 0
            private set
        operator fun get(i: Int) = a[i]
        operator fun plusAssign(v: Float) {
            if (size == a.size) a = a.copyOf(a.size * 2)
            a[size++] = v
        }
        fun toFloatArray() = a.copyOf(size)
    }

    /** Same windows as the Android analyzer: first, middle and last 30 s (or all of a short track). */
    private fun mixPointsFromEnvelope(env: List<Float>): MixPoints? {
        val totalMs = env.size * FRAME_MS
        val edge = (EDGE_WINDOW_MS / FRAME_MS).toInt()
        if (totalMs < 3 * EDGE_WINDOW_MS) return computeMixPoints(env, 0, env, 0, env.maxOrNull() ?: 0f, totalMs)
        val intro = env.subList(0, edge)
        val midStart = env.size / 2 - edge / 2
        val middle = env.subList(midStart, midStart + edge)
        val outro = env.subList(env.size - edge, env.size)
        val peak = maxOf(intro.max(), middle.max(), outro.max())
        return computeMixPoints(intro, 0, outro, totalMs - EDGE_WINDOW_MS, peak, totalMs)
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
