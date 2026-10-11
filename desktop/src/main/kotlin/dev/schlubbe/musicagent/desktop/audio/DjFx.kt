package dev.schlubbe.musicagent.desktop.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A deck parameter that transitions automate. Values are in the units a DJ mixer
 * shows: fader 0..1, EQ in dB (at or below [EQ_KILL_DB] the band is cut completely),
 * filter cutoffs in Hz (the extremes switch the filter off), echo send 0..1.
 */
enum class FxParam(val key: String, val neutral: Double, val min: Double, val max: Double, val logScale: Boolean = false, val description: String) {
    VOLUME("volume", 1.0, 0.0, 1.0, description = "channel fader, 0..1"),
    EQ_LOW("eq_low", 0.0, EQ_KILL_DB, 6.0, description = "bass below 200 Hz in dB (-40 = kill)"),
    EQ_MID("eq_mid", 0.0, EQ_KILL_DB, 6.0, description = "mids 200 Hz - 2.5 kHz in dB (-40 = kill)"),
    EQ_HIGH("eq_high", 0.0, EQ_KILL_DB, 6.0, description = "highs above 2.5 kHz in dB (-40 = kill)"),
    HIGHPASS("highpass", 20.0, 20.0, 20_000.0, logScale = true, description = "high-pass cutoff in Hz (20 = off)"),
    LOWPASS("lowpass", 20_000.0, 20.0, 20_000.0, logScale = true, description = "low-pass cutoff in Hz (20000 = off)"),
    ECHO("echo", 0.0, 0.0, 1.0, description = "send into a beat-synced echo, 0..1; the echo keeps ringing after the fader closes"),
    ECHO_FEEDBACK("echo_feedback", 0.55, 0.0, 0.9, description = "echo repeats, 0..0.9"),
    ;

    companion object {
        fun byKey(k: String) = entries.firstOrNull { it.key == k }
    }
}

const val EQ_KILL_DB = -40.0

/** Shape of the segment that ends at a point. */
enum class Curve(val key: String) {
    LINEAR("linear"),
    /** Cosine S-curve: slow start, slow end. */
    SMOOTH("smooth"),
    /** Fast start, slow end (sin): an equal-power fade-in. */
    EASE_OUT("ease_out"),
    /** Slow start, fast end (1-cos): an equal-power fade-out. */
    EASE_IN("ease_in"),
    /** Holds the previous value, then jumps at the point. */
    STEP("step"),
    ;

    fun shape(t: Double): Double = when (this) {
        LINEAR -> t
        SMOOTH -> 0.5 - 0.5 * cos(PI * t)
        EASE_OUT -> sin(t * PI / 2)
        EASE_IN -> 1 - cos(t * PI / 2)
        STEP -> if (t >= 1.0) 1.0 else 0.0
    }

    companion object {
        fun byKey(k: String) = entries.firstOrNull { it.key == k }
    }
}

data class LanePoint(val beat: Double, val value: Double, val curve: Curve = Curve.LINEAR)

enum class DeckRole { OUT, IN }

/** Automation of one parameter of one deck, in beats from the start of a transition. */
data class Lane(val deck: DeckRole, val param: FxParam, val points: List<LanePoint>) {
    fun valueAt(beat: Double): Double {
        val p = points
        if (p.isEmpty()) return param.neutral
        if (beat <= p.first().beat) return p.first().value
        if (beat >= p.last().beat) return p.last().value
        val i = p.indexOfLast { it.beat <= beat }
        val a = p[i]
        val b = p[i + 1]
        val t = b.curve.shape(((beat - a.beat) / (b.beat - a.beat)).coerceIn(0.0, 1.0))
        return if (param.logScale) exp(ln(a.value) + (ln(b.value) - ln(a.value)) * t) else a.value + (b.value - a.value) * t
    }
}

/** Second-order section, transposed direct form II, double precision; RBJ designs. */
internal class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private val z1 = DoubleArray(2)
    private val z2 = DoubleArray(2)

    fun set(type: Char, f: Double, q: Double, rate: Double = SAMPLE_RATE) {
        val w = 2 * PI * f / rate
        val alpha = sin(w) / (2 * q)
        val c = cos(w)
        val a0 = 1 + alpha
        when (type) {
            'L' -> { b0 = (1 - c) / 2; b1 = 1 - c; b2 = (1 - c) / 2 }
            'H' -> { b0 = (1 + c) / 2; b1 = -(1 + c); b2 = (1 + c) / 2 }
            else -> { b0 = 1 - alpha; b1 = -2 * c; b2 = 1 + alpha } // all-pass
        }
        a1 = -2 * c / a0; a2 = (1 - alpha) / a0
        b0 /= a0; b1 /= a0; b2 /= a0
    }

    fun process(x: Double, ch: Int): Double {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y
    }

    fun reset() { z1.fill(0.0); z2.fill(0.0) }
}

/**
 * DJ-mixer style 3-band isolator: Linkwitz-Riley 24 dB/oct crossovers at 200 Hz and
 * 2.5 kHz. The low band also runs through the 2.5 kHz crossover's all-pass, so with
 * all bands at 0 dB the bands sum to a flat magnitude response, and a killed band is
 * fully gone (unlike shelving EQs).
 */
internal class Isolator {
    private val lp1 = arrayOf(Biquad(), Biquad())
    private val hp1 = arrayOf(Biquad(), Biquad())
    private val lp2 = arrayOf(Biquad(), Biquad())
    private val hp2 = arrayOf(Biquad(), Biquad())
    private val ap2 = Biquad()
    private val g = DoubleArray(3) { 1.0 }
    private val target = DoubleArray(3) { 1.0 }

    init {
        val q = 1 / sqrt(2.0)
        lp1.forEach { it.set('L', LOW_HZ, q) }; hp1.forEach { it.set('H', LOW_HZ, q) }
        lp2.forEach { it.set('L', HIGH_HZ, q) }; hp2.forEach { it.set('H', HIGH_HZ, q) }
        ap2.set('A', HIGH_HZ, q)
    }

    fun setGainsDb(low: Double, mid: Double, high: Double) {
        target[0] = dbToGain(low); target[1] = dbToGain(mid); target[2] = dbToGain(high)
    }

    val neutral: Boolean get() = (0..2).all { g[it] == 1.0 && target[it] == 1.0 }

    fun process(buf: FloatArray, from: Int, frames: Int) {
        for (i in from until from + frames) {
            for (k in 0..2) g[k] += (target[k] - g[k]) * SMOOTH
            for (ch in 0..1) {
                val x = buf[2 * i + ch].toDouble()
                val low = ap2.process(lp1[1].process(lp1[0].process(x, ch), ch), ch)
                val rest = hp1[1].process(hp1[0].process(x, ch), ch)
                val mid = lp2[1].process(lp2[0].process(rest, ch), ch)
                val high = hp2[1].process(hp2[0].process(rest, ch), ch)
                buf[2 * i + ch] = (low * g[0] + mid * g[1] + high * g[2]).toFloat()
            }
        }
        for (k in 0..2) if (kotlin.math.abs(g[k] - target[k]) < 1e-6) g[k] = target[k]
    }

    fun reset() {
        (lp1 + hp1 + lp2 + hp2).forEach { it.reset() }; ap2.reset()
        g.fill(1.0); target.fill(1.0)
    }

    companion object {
        const val LOW_HZ = 200.0
        const val HIGH_HZ = 2500.0
        fun dbToGain(db: Double) = if (db <= EQ_KILL_DB) 0.0 else 10.0.pow(db / 20)
    }
}

/**
 * Sweepable resonant filter (Simper/Cytomic TPT state-variable filter), as on DJ
 * mixers. Cutoff glides per sample in the log domain, so automation does not zipper.
 */
internal class SweepFilter(private val highpass: Boolean) {
    private val ic1 = DoubleArray(2)
    private val ic2 = DoubleArray(2)
    private var logCut = ln(off)
    private var targetLog = logCut
    private val k = 1 / 0.85 // Q 0.85: a little resonance at the cutoff
    private val off: Double get() = if (highpass) FxParam.HIGHPASS.min else FxParam.LOWPASS.max

    fun setCutoff(hz: Double) { targetLog = ln(hz.coerceIn(20.0, 20_000.0)) }

    val bypassed: Boolean get() = logCut == targetLog && logCut == ln(off)

    fun process(buf: FloatArray, from: Int, frames: Int) {
        if (bypassed) return
        var i = from
        while (i < from + frames) {
            val n = minOf(16, from + frames - i)
            logCut += (targetLog - logCut) * SMOOTH_16
            if (kotlin.math.abs(targetLog - logCut) < 1e-4) logCut = targetLog
            val g = tan(PI * exp(logCut).coerceAtMost(SAMPLE_RATE * 0.45) / SAMPLE_RATE)
            val a1 = 1 / (1 + g * (g + k))
            val a2 = g * a1
            val a3 = g * a2
            for (j in i until i + n) for (ch in 0..1) {
                val v0 = buf[2 * j + ch].toDouble()
                val v3 = v0 - ic2[ch]
                val v1 = a1 * ic1[ch] + a2 * v3
                val v2 = ic2[ch] + a2 * ic1[ch] + a3 * v3
                ic1[ch] = 2 * v1 - ic1[ch]
                ic2[ch] = 2 * v2 - ic2[ch]
                buf[2 * j + ch] = (if (highpass) v0 - k * v1 - v2 else v2).toFloat()
            }
            i += n
        }
        if (bypassed) { ic1.fill(0.0); ic2.fill(0.0) }
    }

    fun reset() { ic1.fill(0.0); ic2.fill(0.0); logCut = ln(off); targetLog = logCut }
}

/**
 * Beat-synced echo (dub delay): the repeats are band-limited (150 Hz - 4 kHz) so they
 * sit behind the incoming track. It is fed post-fader and keeps ringing after the
 * deck stops, which is what an echo-out transition needs.
 */
internal class Echo {
    private val buf = FloatArray(2 * MAX_FRAMES)
    private var w = 0
    var delayFrames = SAMPLE_RATE.toInt() / 2
    private var send = 0.0
    private var sendTarget = 0.0
    private var feedback = FxParam.ECHO_FEEDBACK.neutral
    private val lp = DoubleArray(2)
    private val hpState = DoubleArray(2)
    private val lpA = 1 - exp(-2 * PI * 4000.0 / SAMPLE_RATE)
    private val hpA = 1 - exp(-2 * PI * 150.0 / SAMPLE_RATE)
    /** Frames since anything audible went into the delay line; a full buffer of quiet means it died out. */
    private var quietFrames = MAX_FRAMES

    fun set(send: Double, feedback: Double) { sendTarget = send; this.feedback = feedback.coerceIn(0.0, 0.9) }

    val silent: Boolean get() = sendTarget == 0.0 && send == 0.0 && quietFrames >= MAX_FRAMES

    /** Takes [input] (the deck's post-fader signal) and adds the echo to [out]. */
    fun process(input: FloatArray, out: FloatArray, from: Int, frames: Int) {
        if (silent) return
        val d = delayFrames.coerceIn(1, MAX_FRAMES - 1)
        var loud = false
        for (i in from until from + frames) {
            send += (sendTarget - send) * SMOOTH
            if (kotlin.math.abs(send - sendTarget) < 1e-6) send = sendTarget
            val r = (w - d + MAX_FRAMES) % MAX_FRAMES
            for (ch in 0..1) {
                val delayed = buf[2 * r + ch].toDouble()
                lp[ch] += lpA * (delayed - lp[ch])
                hpState[ch] += hpA * (lp[ch] - hpState[ch])
                val wet = lp[ch] - hpState[ch]
                val v = input[2 * i + ch] * send + wet * feedback
                buf[2 * w + ch] = v.toFloat()
                out[2 * i + ch] += wet.toFloat()
                loud = loud || abs(v) > 1e-5
            }
            quietFrames = if (loud) 0 else quietFrames + 1
            loud = false
            w = (w + 1) % MAX_FRAMES
        }
    }

    fun reset() { buf.fill(0f); lp.fill(0.0); hpState.fill(0.0); send = 0.0; sendTarget = 0.0; quietFrames = MAX_FRAMES }

    companion object {
        const val MAX_FRAMES = 4 * 48_000
    }
}

/** Everything between a deck and the mix bus: isolator EQ, high-/low-pass, fader. */
class DeckFx {
    private val iso = Isolator()
    private val hpf = SweepFilter(highpass = true)
    private val lpf = SweepFilter(highpass = false)
    internal val echo = Echo()
    private val values = DoubleArray(FxParam.entries.size) { FxParam.entries[it].neutral }
    private var gain = 1.0
    private var gainTarget = 1.0
    private val post = FloatArray(BLOCK_FRAMES * 2)

    fun set(p: FxParam, v: Double) {
        val c = v.coerceIn(p.min, p.max)
        values[p.ordinal] = c
        when (p) {
            FxParam.VOLUME -> gainTarget = c
            FxParam.EQ_LOW, FxParam.EQ_MID, FxParam.EQ_HIGH ->
                iso.setGainsDb(values[FxParam.EQ_LOW.ordinal], values[FxParam.EQ_MID.ordinal], values[FxParam.EQ_HIGH.ordinal])
            FxParam.HIGHPASS -> hpf.setCutoff(c)
            FxParam.LOWPASS -> lpf.setCutoff(c)
            FxParam.ECHO, FxParam.ECHO_FEEDBACK -> echo.set(values[FxParam.ECHO.ordinal], values[FxParam.ECHO_FEEDBACK.ordinal])
        }
    }

    fun get(p: FxParam) = values[p.ordinal]

    /** Processes [frames] frames of [buf] in place (deck output to post-fader) and adds the echo into [mix]. */
    fun process(buf: FloatArray, mix: FloatArray, from: Int, frames: Int) {
        if (!iso.neutral) iso.process(buf, from, frames)
        hpf.process(buf, from, frames)
        lpf.process(buf, from, frames)
        if (gain != 1.0 || gainTarget != 1.0) {
            for (i in from until from + frames) {
                gain += (gainTarget - gain) * SMOOTH
                buf[2 * i] = (buf[2 * i] * gain).toFloat(); buf[2 * i + 1] = (buf[2 * i + 1] * gain).toFloat()
            }
            if (kotlin.math.abs(gain - gainTarget) < 1e-6) gain = gainTarget
        }
        echo.process(buf, mix, from, frames)
    }

    /** Keeps the echo ringing while the deck itself is silent. */
    fun processTail(mix: FloatArray, frames: Int) {
        if (echo.silent) return
        post.fill(0f, 0, frames * 2)
        echo.process(post, mix, 0, frames)
    }

    /** All parameters neutral at once (new track); the echo tail keeps ringing unless [clearEcho]. */
    fun reset(clearEcho: Boolean) {
        iso.reset(); hpf.reset(); lpf.reset()
        FxParam.entries.forEachIndexed { i, p -> values[i] = p.neutral }
        gain = 1.0; gainTarget = 1.0
        if (clearEcho) echo.reset() else echo.set(0.0, values[FxParam.ECHO_FEEDBACK.ordinal])
    }

    companion object {
        const val BLOCK_FRAMES = 1024
    }
}

/** One-pole smoothing per sample, ~5 ms at 48 kHz. */
private val SMOOTH = 1 - exp(-1.0 / (0.005 * SAMPLE_RATE))
private val SMOOTH_16 = 1 - exp(-16.0 / (0.005 * SAMPLE_RATE))
