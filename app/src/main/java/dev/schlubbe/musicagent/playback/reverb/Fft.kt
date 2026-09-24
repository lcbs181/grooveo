package dev.schlubbe.musicagent.playback.reverb

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Minimal iterative radix-2 Cooley-Tukey FFT, in place on parallel real/imaginary
 * arrays of length [n] (must be a power of two). Only used by [PartitionedConvolver]
 * for fast block convolution against a preset's impulse response - not a
 * general-purpose utility, and deliberately dependency-free (no reason to pull in a
 * whole DSP library for one FFT). */
object Fft {
    fun transform(real: FloatArray, imag: FloatArray, inverse: Boolean) {
        val n = real.size
        require(n and (n - 1) == 0) { "FFT size must be a power of two, was $n" }

        // Bit-reversal permutation - standard iterative-FFT setup step.
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var len = 2
        while (len <= n) {
            val half = len shr 1
            val angleStep = (if (inverse) 2.0 else -2.0) * PI / len
            val wr = cos(angleStep).toFloat()
            val wi = sin(angleStep).toFloat()
            var i = 0
            while (i < n) {
                var curWr = 1f
                var curWi = 0f
                for (k in 0 until half) {
                    val evenIdx = i + k
                    val oddIdx = i + k + half
                    val tr = real[oddIdx] * curWr - imag[oddIdx] * curWi
                    val ti = real[oddIdx] * curWi + imag[oddIdx] * curWr
                    real[oddIdx] = real[evenIdx] - tr
                    imag[oddIdx] = imag[evenIdx] - ti
                    real[evenIdx] += tr
                    imag[evenIdx] += ti
                    val nextWr = curWr * wr - curWi * wi
                    val nextWi = curWr * wi + curWi * wr
                    curWr = nextWr
                    curWi = nextWi
                }
                i += len
            }
            len = len shl 1
        }

        if (inverse) {
            val invN = 1f / n
            for (i in 0 until n) {
                real[i] *= invN
                imag[i] *= invN
            }
        }
    }
}
