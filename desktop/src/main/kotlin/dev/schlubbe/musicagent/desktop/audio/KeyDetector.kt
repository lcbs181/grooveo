package dev.schlubbe.musicagent.desktop.audio

import dev.schlubbe.musicagent.playback.reverb.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A musical key, e.g. "A minor", with its Camelot wheel code ("8A") for harmonic mixing. */
data class MusicalKey(val name: String, val camelot: String, val confidence: Double) {
    companion object {
        val NOTES = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
        /** Camelot number of each major key by tonic pitch class (C = 8B ... F = 7B); minor is the relative minor's. */
        private val CAMELOT_MAJOR = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
        private val CAMELOT_MINOR = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)

        fun camelot(tonic: Int, minor: Boolean) = if (minor) "${CAMELOT_MINOR[tonic]}A" else "${CAMELOT_MAJOR[tonic]}B"

        /**
         * Harmonic distance on the Camelot wheel: 0 = same key, 1 = a neighbour (±1 hour
         * or the relative major/minor), larger = clashing. Null for unparsable codes.
         */
        fun camelotDistance(a: String, b: String): Int? {
            val (na, la) = parse(a) ?: return null
            val (nb, lb) = parse(b) ?: return null
            val hours = ((na - nb) % 12 + 12) % 12
            val around = minOf(hours, 12 - hours)
            return around + if (la != lb) 1 else 0
        }

        /** Semitones to transpose a track in [from] so it lands within one step of [to] (0 if already compatible). */
        fun shiftToMatch(from: String, to: String): Int {
            if ((camelotDistance(from, to) ?: 0) <= 1) return 0
            val (n, l) = parse(from) ?: return 0
            // one semitone up moves 7 hours on the wheel; try the smallest shifts first
            for (s in listOf(1, -1, 2, -2)) {
                val shifted = "${((n - 1 + 7 * s) % 12 + 12) % 12 + 1}$l"
                if ((camelotDistance(shifted, to) ?: 99) <= 1) return s
            }
            return 0
        }

        private fun parse(c: String): Pair<Int, Char>? {
            val n = c.dropLast(1).toIntOrNull() ?: return null
            val l = c.lastOrNull()?.uppercaseChar() ?: return null
            return if (n in 1..12 && (l == 'A' || l == 'B')) n to l else null
        }
    }
}

/**
 * Key detection: a chromagram (tonal spectral peaks from 65 Hz to 2.1 kHz, within
 * 30 dB of each frame's strongest, folded onto the 12 pitch classes, each frame
 * weighted equally) correlated with the
 * Krumhansl-Kessler major and minor key profiles in all 24 transpositions. Feed mono
 * samples at [rate] with [process]; [result] gives the best key.
 */
class KeyDetector(private val rate: Int) {
    private val frame = FloatArray(N)
    private var fill = 0
    private val chroma = DoubleArray(12)
    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() }
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val kMin = (65.0 * N / rate).toInt().coerceAtLeast(2)
    private val kMax = (2100.0 * N / rate).toInt().coerceAtMost(N / 2 - 2)

    fun process(samples: FloatArray, n: Int) {
        var i = 0
        while (i < n) {
            val take = minOf(n - i, N - fill)
            System.arraycopy(samples, i, frame, fill, take)
            fill += take; i += take
            if (fill == N) {
                analyzeFrame()
                // 50 % overlap
                System.arraycopy(frame, HOP, frame, 0, N - HOP)
                fill = N - HOP
            }
        }
    }

    private fun analyzeFrame() {
        var energy = 0.0
        for (k in 0 until N) { re[k] = frame[k] * window[k]; im[k] = 0f; energy += frame[k] * frame[k] }
        if (energy / N < 1e-6) return // silence
        Fft.transform(re, im, inverse = false)
        val mag = DoubleArray(N / 2) { sqrt((re[it] * re[it] + im[it] * im[it]).toDouble()) }
        var top = 0.0
        for (k in kMin until kMax) if (mag[k] > top) top = mag[k]
        val floor = top * PEAK_FLOOR
        val c = DoubleArray(12)
        for (k in kMin until kMax) {
            val m = mag[k]
            // tonal peaks only: local maxima within 30 dB of the frame's strongest one
            if (m < floor || m < mag[k - 1] || m < mag[k + 1]) continue
            // parabolic interpolation: the bins are coarse against semitones in the bass
            val a = mag[k - 1]; val bb = mag[k + 1]
            val d = (a - bb) / (2 * (a - 2 * m + bb)).let { if (it == 0.0) 1.0 else it }
            val f = (k + d.coerceIn(-0.5, 0.5)) * rate / N
            c[((12 * log2(f / 440.0)).roundToInt() + 9 + 120) % 12] += m
        }
        val sum = c.sum()
        if (sum <= 0) return
        for (p in 0 until 12) chroma[p] += c[p] / sum
    }

    fun result(): MusicalKey? {
        if (chroma.sum() <= 0) return null
        val scores = ArrayList<Triple<Int, Boolean, Double>>()
        for (tonic in 0 until 12) for (minor in listOf(false, true)) {
            val prof = if (minor) MINOR else MAJOR
            scores += Triple(tonic, minor, correlation(DoubleArray(12) { prof[(it - tonic + 12) % 12] }, chroma))
        }
        scores.sortByDescending { it.third }
        val (tonic, minor, best) = scores[0]
        val confidence = (best - scores[1].third).coerceIn(0.0, 1.0)
        return MusicalKey("${MusicalKey.NOTES[tonic]} ${if (minor) "minor" else "major"}", MusicalKey.camelot(tonic, minor), Math.round(confidence * 1000) / 1000.0)
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in 0 until 12) { num += (a[i] - ma) * (b[i] - mb); da += (a[i] - ma) * (a[i] - ma); db += (b[i] - mb) * (b[i] - mb) }
        return if (da == 0.0 || db == 0.0) 0.0 else num / sqrt(da * db)
    }

    companion object {
        const val N = 8192
        const val HOP = 4096
        /** -30 dB */
        private const val PEAK_FLOOR = 0.0316
        private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    }
}
