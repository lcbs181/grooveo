package dev.schlubbe.musicagent.playback.eq

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Biquad coefficients, normalised so a0 = 1: y = b0 x + b1 x1 + b2 x2 - a1 y1 - a2 y2. */
data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    /** Magnitude response in dB at normalised angular frequency [w] (radians per sample). */
    fun responseDb(w: Double): Double {
        val cw = cos(w); val c2w = cos(2 * w)
        val num = b0 * b0 + b1 * b1 + b2 * b2 + 2 * (b0 * b1 + b1 * b2) * cw + 2 * b0 * b2 * c2w
        val den = 1 + a1 * a1 + a2 * a2 + 2 * (a1 + a1 * a2) * cw + 2 * a2 * c2w
        return 10 * kotlin.math.log10((num / den).coerceAtLeast(1e-30))
    }

    val isStable: Boolean get() = kotlin.math.abs(a1) < 1 + a2 && kotlin.math.abs(a2) < 1

    companion object {
        val IDENTITY = Biquad(1.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Analog second-order prototype N(S)/D(S) in the normalised variable S = s/ω0,
 * N = n2 S² + n1 S + n0, D = d2 S² + d1 S + d0 (RBJ Audio-EQ-Cookbook prototypes).
 */
data class AnalogPrototype(val n2: Double, val n1: Double, val n0: Double, val d2: Double, val d1: Double, val d0: Double) {
    /** |H(iω)|² for analog angular frequency ω (same units as ω0). */
    fun magSq(w: Double, w0: Double): Double {
        val x = w / w0
        val nr = n0 - n2 * x * x; val ni = n1 * x
        val dr = d0 - d2 * x * x; val di = d1 * x
        return (nr * nr + ni * ni) / (dr * dr + di * di)
    }

    fun magDb(w: Double, w0: Double) = 10 * kotlin.math.log10(magSq(w, w0).coerceAtLeast(1e-30))
}

/**
 * Analog-matched biquad design after Martin Vicanek, "Matched Second Order Digital
 * Filters" (2016) — the method used by open-source EQs such as OnlyEQ and CAGEq.
 *
 * Poles come from impulse invariance (z = e^s, so resonances keep their analog
 * frequency and width), and the numerator is solved in Vicanek's φ-form
 *   |H|² = (B0 φ0 + B1 φ1 + B2 φ2) / (A0 φ0 + A1 φ1 + A2 φ2)
 * by matching the analog magnitude exactly at DC, at the centre frequency and at
 * Nyquist. Unlike the bilinear transform there is no "cramping" of bells and
 * shelves towards high frequencies. Falls back to the RBJ bilinear design in the
 * rare cases the matched numerator has no real solution.
 */
object MatchedDesign {
    fun prototype(type: FilterType, gainDb: Double, q: Double): AnalogPrototype {
        val a = 10.0.pow(gainDb / 40)
        val sa = sqrt(a)
        return when (type) {
            FilterType.PEAK -> AnalogPrototype(1.0, a / q, 1.0, 1.0, 1 / (a * q), 1.0)
            FilterType.LOW_SHELF -> AnalogPrototype(a, a * sa / q, a * a, a, sa / q, 1.0)
            FilterType.HIGH_SHELF -> AnalogPrototype(a * a, a * sa / q, a, 1.0, sa / q, a)
            FilterType.LOW_PASS -> AnalogPrototype(0.0, 0.0, 1.0, 1.0, 1 / q, 1.0)
            FilterType.HIGH_PASS -> AnalogPrototype(1.0, 0.0, 0.0, 1.0, 1 / q, 1.0)
            FilterType.NOTCH -> AnalogPrototype(1.0, 0.0, 1.0, 1.0, 1 / q, 1.0)
        }
    }

    fun design(type: FilterType, freq: Double, gainDb: Double, q: Double, fs: Double): Biquad {
        val w0 = 2 * PI * freq.coerceIn(1.0, fs * 0.49) / fs
        val proto = prototype(type, gainDb, q)
        val m = if (type == FilterType.PEAK) matchedPeak(proto, w0, 10.0.pow(gainDb / 20)) else matched(proto, w0)
        return m ?: blt(type, w0, gainDb, q)
    }

    private fun poles(p: AnalogPrototype, w0: Double): Pair<Double, Double> {
        val wp = w0 * sqrt(p.d0 / p.d2)
        val zeta = p.d1 * w0 / p.d2 / (2 * wp)
        val a2 = exp(-2 * zeta * wp)
        val a1 = if (zeta <= 1) -2 * exp(-zeta * wp) * cos(sqrt(1 - zeta * zeta) * wp)
        else -2 * exp(-zeta * wp) * cosh(sqrt(zeta * zeta - 1) * wp)
        return a1 to a2
    }

    /** Numerator from Vicanek's B coefficients, eq. (29); null when no real minimum-phase solution exists. */
    private fun numerator(bb0: Double, bb1: Double, bb2: Double, a1: Double, a2: Double): Biquad? {
        if (bb0 < 0 || bb1 < 0) return null
        val sb0 = sqrt(bb0); val sb1 = sqrt(bb1)
        val w = 0.5 * (sb0 + sb1)
        val disc = w * w + bb2
        if (disc < 0 || w <= 0) return null
        val b0 = 0.5 * (w + sqrt(disc))
        val b1 = 0.5 * (sb0 - sb1)
        val b2 = -bb2 / (4 * b0)
        return Biquad(b0, b1, b2, a1, a2).takeIf { it.isStable && listOf(b0, b1, b2).all(Double::isFinite) }
    }

    /** Vicanek 2016, section 4.4: unity at DC, gain G and zero slope at the centre frequency. */
    fun matchedPeak(p: AnalogPrototype, w0: Double, g: Double): Biquad? {
        val (a1, a2) = poles(p, w0)
        val aa0 = (1 + a1 + a2).pow(2); val aa1 = (1 - a1 + a2).pow(2); val aa2 = -4 * a2
        val phi1 = sin(w0 / 2).pow(2); val phi0 = 1 - phi1; val phi2 = 4 * phi0 * phi1
        val g2 = g * g
        val bb0 = aa0
        val r1 = (aa0 * phi0 + aa1 * phi1 + aa2 * phi2) * g2
        val r2 = (-aa0 + aa1 + 4 * (phi0 - phi1) * aa2) * g2
        val bb2 = (r1 - r2 * phi1 - bb0) / (4 * phi1 * phi1)
        val bb1 = r2 + bb0 + 4 * (phi1 - phi0) * bb2
        return numerator(bb0, bb1, bb2, a1, a2)
    }

    fun matched(p: AnalogPrototype, w0: Double): Biquad? {
        val (a1, a2) = poles(p, w0)
        val aa0 = (1 + a1 + a2).pow(2)
        val aa1 = (1 - a1 + a2).pow(2)
        val aa2 = -4 * a2
        fun den(phi1: Double): Double { val phi0 = 1 - phi1; return aa0 * phi0 + aa1 * phi1 + aa2 * 4 * phi0 * phi1 }
        // match at DC, Nyquist and the centre frequency (pulled below Nyquist when needed)
        val bb0 = p.magSq(0.0, w0) * aa0
        val bb1 = p.magSq(PI, w0) * aa1
        val wm = if (w0 < 0.9 * PI) w0 else 0.5 * w0
        val phi1 = sin(wm / 2).pow(2)
        val phi0 = 1 - phi1
        val phi2 = 4 * phi0 * phi1
        val bb2 = (p.magSq(wm, w0) * den(phi1) - bb0 * phi0 - bb1 * phi1) / phi2
        return numerator(bb0, bb1, bb2, a1, a2)
    }

    /** RBJ cookbook bilinear-transform design (reference and fallback). */
    fun blt(type: FilterType, w0: Double, gainDb: Double, q: Double): Biquad {
        val a = 10.0.pow(gainDb / 40)
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * q)
        val c = when (type) {
            FilterType.PEAK -> doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
            FilterType.LOW_SHELF -> { val s = 2 * sqrt(a) * alpha
                doubleArrayOf(a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - s),
                    (a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - s) }
            FilterType.HIGH_SHELF -> { val s = 2 * sqrt(a) * alpha
                doubleArrayOf(a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - s),
                    (a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - s) }
            FilterType.LOW_PASS -> doubleArrayOf((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
            FilterType.HIGH_PASS -> doubleArrayOf((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
            FilterType.NOTCH -> doubleArrayOf(1.0, -2 * cw, 1.0, 1 + alpha, -2 * cw, 1 - alpha)
        }
        return Biquad(c[0] / c[3], c[1] / c[3], c[2] / c[3], c[4] / c[3], c[5] / c[3])
    }

    /** Butterworth section Qs for a high/low-pass of [sections]×12 dB/oct. */
    fun butterworthQs(sections: Int): List<Double> {
        val n = 2 * sections
        return (1..sections).map { k -> 1 / (2 * cos((2 * k - 1) * PI / (2 * n))) }
    }
}
