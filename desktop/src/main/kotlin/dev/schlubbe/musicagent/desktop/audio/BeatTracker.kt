package dev.schlubbe.musicagent.desktop.audio

import com.sun.jna.Pointer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Constant-tempo beat grid fitted to a track's beats.
 *
 * Beat n lies at `firstBeatMs + n * beatMs`; beats with `n % 4 == downbeatOffset`
 * start a bar (4/4 assumed). [stability] is the share of detected beats within
 * 40 ms of the grid: close to 1 for electronic music, lower for live drums or
 * tempo changes, where [beatsMs] keeps the raw beat times instead. Only the first
 * [BeatTracker.MAX_KEPT_BEATS] beats are kept (about 15 minutes at 128 BPM), so long
 * mixes do not bloat the library file; a list of that size is truncated.
 */
data class BeatGrid(
    val bpm: Double,
    val firstBeatMs: Double,
    val downbeatOffset: Int,
    val confidence: Float,
    val stability: Double,
    val beatsMs: List<Long>? = null,
) {
    val beatMs: Double get() = 60_000.0 / bpm
}

/**
 * Beat tracking with aubio (specflux onsets + autocorrelation tempo), from the
 * bundled libgrooveo_native (see NativeDsp). [available] is false when the native
 * library is missing, e.g. on an OS it was not built for; callers then skip BPM.
 */
class BeatTracker(sampleRate: Int, win: Int = WIN, hop: Int = HOP) : AutoCloseable {
    private val lib = NativeDsp.lib ?: error("libgrooveo_native not loaded")
    private var handle: Pointer? = lib.grooveo_beats_new(sampleRate, win, hop) ?: error("aubio tempo init failed")
    private var out = DoubleArray(64)
    private val beats = ArrayList<Double>()

    /** Feeds the next [n] mono samples. */
    fun process(samples: FloatArray, n: Int = samples.size) {
        val h = handle ?: return
        if (out.size < n / HOP + 2) out = DoubleArray(n / HOP + 2)
        val found = lib.grooveo_beats_process(h, samples, n, out, out.size)
        for (i in 0 until found) beats += out[i] * 1000.0
    }

    /** Beat times in ms detected so far. */
    fun beatsMs(): List<Double> = beats.toList()
    fun bpm(): Float = handle?.let { lib.grooveo_beats_bpm(it) } ?: 0f
    fun confidence(): Float = handle?.let { lib.grooveo_beats_confidence(it) } ?: 0f

    override fun close() {
        handle?.let { lib.grooveo_beats_free(it) }
        handle = null
    }

    companion object {
        /** 1024/256 at 22.05 kHz: same 11.6 ms hop as aubio's 44.1 kHz default, finer bass resolution. */
        const val WIN = 1024
        const val HOP = 256
        val available: Boolean get() = NativeDsp.lib != null

        /** Beats this close to the grid count as on-grid. */
        const val ON_GRID_MS = 40.0
        /** Below this stability the raw beat list is kept as well. */
        const val KEEP_BEATS_BELOW = 0.9
        /** At most this many raw beats are kept in [BeatGrid.beatsMs]. */
        const val MAX_KEPT_BEATS = 2000
        /** Beats per segment of the coarse tempo scan. */
        private const val COARSE_SEGMENT = 64

        /** A kick peak this many times above the average bass onset counts as the beat. */
        const val KICK_PROMINENCE = 2.0

        /**
         * Fits a constant-tempo grid to aubio's [beats] (ms).
         *
         * aubio's beat times jitter by its 11.6 ms hop and its period is quantised to
         * whole hops, corrected every few beats, so a least-squares fit started from
         * the median interval drifts. aubio also slips between beat and offbeat
         * (hi-hats, snares) within a track. So the period is chosen by phase coherence
         * on the half-beat lattice, on a scan around the median interval, then refined
         * by least squares on the beats near that lattice. The scan is coarse to fine:
         * the coherence of a whole track peaks too sharply for coarse steps, so the
         * coarse pass sums it over short segments (a broad peak at the same place) and
         * only the neighbourhood of its best step is scanned finely over all beats.
         *
         * The phase comes from the kick: [bassOnset] (rise of the low-passed level per
         * [frameMs] frame) is folded over one beat period, and a clear peak sets where
         * the beat lies. Without a clear kick aubio's phase stays. Of the four beats in
         * a bar, the one with the strongest bass onsets is taken as the downbeat.
         */
        fun fitGrid(beats: List<Double>, confidence: Float, bassOnset: FloatArray, frameMs: Double): BeatGrid? {
            if (beats.size < 8) return null
            val intervals = beats.zipWithNext { a, b -> b - a }.filter { it in 250.0..1500.0 }.sorted()
            if (intervals.size < 4) return null
            val median = intervals[intervals.size / 2]
            // Coherence on the half-beat lattice (doubled angle): beats aubio put on the
            // offbeat then add up instead of cancelling the ones on the beat.
            fun coherence(half: Double): Pair<Double, Double> {
                var c = 0.0; var sn = 0.0
                for (b in beats) { val a = 2 * PI * b / half; c += cos(a); sn += sin(a) }
                return (c * c + sn * sn) to atan2(sn, c)
            }
            fun segmentCoherence(half: Double): Double {
                var total = 0.0; var c = 0.0; var sn = 0.0; var k = 0
                for (b in beats) {
                    val a = 2 * PI * b / half; c += cos(a); sn += sin(a)
                    if (++k == COARSE_SEGMENT) { total += c * c + sn * sn; c = 0.0; sn = 0.0; k = 0 }
                }
                return total + c * c + sn * sn
            }
            val lo = median * 0.96 / 2
            val hi = median * 1.04 / 2
            // segment peak is about half / (2 * COARSE_SEGMENT) wide: eight steps across it
            val coarseStep = median / 2 * 0.001
            var coarseBest = lo
            var bestSeg = -1.0
            var c = lo
            while (c <= hi) {
                val r = segmentCoherence(c)
                if (r > bestSeg) { bestSeg = r; coarseBest = c }
                c += coarseStep
            }
            // fine pass on the same 0.01 ms lattice as a full scan from lo
            val fineStep = 0.01
            val k0 = floor((maxOf(lo, coarseBest - 2 * coarseStep) - lo) / fineStep).toInt()
            val k1 = floor((minOf(hi, coarseBest + 2 * coarseStep) - lo) / fineStep).toInt()
            var half = median / 2
            var best = -1.0
            for (k in k0..k1) {
                val h = lo + k * fineStep
                val r = coherence(h).first
                if (r > best) { best = r; half = h }
            }
            var phase = coherence(half).second / (2 * PI) * half
            repeat(2) {
                val pts = beats.mapNotNull { b ->
                    val n = Math.round((b - phase) / half)
                    if (abs(b - (phase + n * half)) < half * 0.2) n.toDouble() to b else null
                }
                if (pts.size < 8) return null
                val mx = pts.sumOf { it.first } / pts.size
                val my = pts.sumOf { it.second } / pts.size
                val sxx = pts.sumOf { (it.first - mx) * (it.first - mx) }
                if (sxx == 0.0) return null
                half = pts.sumOf { (it.first - mx) * (it.second - my) } / sxx
                phase = my - half * mx
            }
            val period = 2 * half
            // aubio's beats on its own half-beat lattice mean "steady tempo"; the kick
            // placement below may sit a little off that lattice and does not count here
            val steady = beats.count { b ->
                val r = ((b - phase) % half + half) % half
                minOf(r, half - r) <= ON_GRID_MS
            }
            // the lattice phase may be the offbeat: start from the half most beats sit on
            val onHalf = beats.count { b -> Math.floorMod(Math.round((b - phase) / half), 2L) == 1L }
            if (onHalf * 2 > beats.size) phase += half
            val first = kickPhase(phase, period, bassOnset, frameMs).let { it - floor(it / period) * period }
            val bars = DoubleArray(4)
            var n = 0
            while (first + n * period <= beats.last()) {
                val k = ((first + n * period) / frameMs + 0.5).roundToInt()
                for (j in k - 2..k + 2) if (j in bassOnset.indices) bars[n % 4] += bassOnset[j].toDouble()
                n++
            }
            val stability = steady.toDouble() / beats.size
            return BeatGrid(
                bpm = 60_000.0 / period,
                firstBeatMs = first,
                downbeatOffset = (0 until 4).maxBy { bars[it] },
                confidence = confidence,
                stability = stability,
                beatsMs = if (stability < KEEP_BEATS_BELOW) beats.take(MAX_KEPT_BEATS).map { it.roundToLong() } else null,
            )
        }

        /** Phase of the kick within [period], near [phase] (aubio's beat or offbeat). */
        private fun kickPhase(phase: Double, period: Double, bassOnset: FloatArray, frameMs: Double): Double {
            val bins = 96
            val hist = DoubleArray(bins)
            for (k in bassOnset.indices) {
                // an onset in frame k means the level rose during the frame before
                val x = (((k - 0.5) * frameMs - phase) % period + period) % period
                hist[(x / period * bins).toInt().coerceAtMost(bins - 1)] += bassOnset[k].toDouble()
            }
            val sm = DoubleArray(bins) { 0.25 * hist[(it + bins - 1) % bins] + 0.5 * hist[it] + 0.25 * hist[(it + 1) % bins] }
            val peak = sm.indices.maxBy { sm[it] }
            val mean = sm.average()
            if (mean <= 0 || sm[peak] < KICK_PROMINENCE * mean) return phase
            val a = sm[(peak + bins - 1) % bins]
            val b = sm[peak]
            val c = sm[(peak + 1) % bins]
            val d = if (a - 2 * b + c != 0.0) 0.5 * (a - c) / (a - 2 * b + c) else 0.0
            return phase + (peak + 0.5 + d) / bins * period
        }
    }
}
