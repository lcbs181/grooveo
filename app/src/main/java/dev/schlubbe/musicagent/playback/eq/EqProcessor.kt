package dev.schlubbe.musicagent.playback.eq

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Real-time EQ chain, all in double precision:
 *
 *   preamp → subsonic HP → parametric bands → loudness shelves → bass enhancer → look-ahead limiter
 *
 * Parameter changes are click-free: when the filter layout is unchanged, biquad
 * coefficients are interpolated per sample across one block (Direct Form I keeps
 * this stable and is the most precise form for low-frequency filters); layout
 * changes cross-fade from the old chain to the new one. [setProfile]/[setVolume]
 * may be called from any thread; [process] runs on the audio thread.
 */
class EqProcessor(private val fs: Double = DEFAULT_SAMPLE_RATE) {
    private val pendingProfile = AtomicReference<EqProfile?>(null)
    @Volatile private var volume = 0.8f
    private var profile: EqProfile = EqProfile.flat()
    private var chain = Chain(emptyList())
    private var fadingOut: Chain? = null
    private var preamp = 1.0
    private var loudnessKey = Double.NaN
    private val enhancer = BassEnhancer(fs)
    private val dynBass = DynamicEqBand.bass(fs)
    private val tameHarsh = DynamicEqBand.harsh(fs)
    private val limiter = LookaheadLimiter(fs)

    fun setProfile(p: EqProfile) = pendingProfile.set(p)
    fun setVolume(v: Float) { volume = v }

    /** Keeps the limiter on although the profile has it off: an upstream gain (loudness
     * normalisation) is boosting the signal and could otherwise clip. */
    @Volatile var forceLimiter = false

    /** Filter sections of the current settings (subsonic, bands, loudness), in processing order. */
    internal fun sectionsFor(p: EqProfile, vol: Float): List<Biquad> {
        if (!p.enabled) return emptyList()
        val s = mutableListOf<Biquad>()
        if (p.subsonic) s += EqProfile.SUBSONIC.sections(fs)
        p.bands.forEach { s += it.sections(fs) }
        if (p.loudness) {
            val (bass, treble) = loudnessGains(vol)
            s += MatchedDesign.design(FilterType.LOW_SHELF, 100.0, bass, 0.7, fs)
            s += MatchedDesign.design(FilterType.HIGH_SHELF, 10000.0, treble, 0.7, fs)
        }
        return s
    }

    /** In-place on interleaved stereo [buf] of [frames] frames. */
    fun process(buf: FloatArray, frames: Int) {
        val newProfile = pendingProfile.getAndSet(null)
        val vol = volume
        val lk = if ((newProfile ?: profile).loudness) loudnessGains(vol).first else Double.NaN
        if (newProfile != null || (lk != loudnessKey && !(lk.isNaN() && loudnessKey.isNaN()) && abs(lk - loudnessKey) > 0.1)) {
            newProfile?.let { profile = it }
            loudnessKey = lk
            val target = sectionsFor(profile, vol)
            if (target.size == chain.size) chain.retarget(target)
            else { fadingOut = chain; chain = Chain(target) }
            enhancer.configure(if (profile.enabled) profile.bassEnhance else 0.0, profile.bassEnhanceFreq)
            dynBass.amount = if (profile.enabled) profile.dynamicBass else 0.0
            tameHarsh.amount = if (profile.enabled) profile.tameHarsh else 0.0
        }
        val targetPre = if (profile.enabled) 10.0.pow(profile.preampDb / 20) else 1.0
        val old = fadingOut
        for (i in 0 until frames) {
            val t = (i + 1).toDouble() / frames
            val g = preamp + (targetPre - preamp) * t
            val l = buf[2 * i] * g
            val r = buf[2 * i + 1] * g
            chain.step(l, r, t)
            var ol = chain.outL
            var or = chain.outR
            if (old != null) {
                old.step(l, r, 1.0)
                ol = old.outL * (1 - t) + ol * t
                or = old.outR * (1 - t) + or * t
            }
            dynBass.step(ol, or)
            tameHarsh.step(dynBass.outL, dynBass.outR)
            enhancer.step(tameHarsh.outL, tameHarsh.outR)
            buf[2 * i] = enhancer.outL.toFloat()
            buf[2 * i + 1] = enhancer.outR.toFloat()
        }
        chain.commit()
        preamp = targetPre
        fadingOut = null
        if ((profile.enabled && profile.limiter) || forceLimiter) limiter.process(buf, frames) else limiter.reset()
    }

    companion object {
        /**
         * Equal-loudness compensation (simplified ISO 226): at -30 dB listening level
         * the ear needs roughly +10 dB at 100 Hz and +3.5 dB at 10 kHz to keep the
         * reference tonal balance. Volume uses the engine's cubic curve.
         */
        fun loudnessGains(volume: Float): Pair<Double, Double> {
            val att = -20 * log10((volume.toDouble().coerceIn(0.001, 1.0)).pow(3))
            return (att * 0.33).coerceIn(0.0, 12.0) + 0.0 to (att * 0.12).coerceIn(0.0, 5.0) + 0.0
        }
    }

    /** Cascade of Direct-Form-I biquads (stereo) with per-sample coefficient interpolation. */
    private class Chain(sections: List<Biquad>) {
        private var from = sections.map { it.toArray() }.toTypedArray()
        private var to = from
        val size get() = to.size
        private val xl1 = DoubleArray(to.size); private val xl2 = DoubleArray(to.size)
        private val yl1 = DoubleArray(to.size); private val yl2 = DoubleArray(to.size)
        private val xr1 = DoubleArray(to.size); private val xr2 = DoubleArray(to.size)
        private val yr1 = DoubleArray(to.size); private val yr2 = DoubleArray(to.size)
        var outL = 0.0; var outR = 0.0
        private var interpolating = false

        fun retarget(target: List<Biquad>) {
            from = to
            to = target.map { it.toArray() }.toTypedArray()
            interpolating = true
        }

        fun commit() { from = to; interpolating = false }

        fun step(inL: Double, inR: Double, t: Double) {
            var l = inL; var r = inR
            for (k in to.indices) {
                val c = to[k]
                val b0: Double; val b1: Double; val b2: Double; val a1: Double; val a2: Double
                if (interpolating) {
                    val f = from[k]
                    b0 = f[0] + (c[0] - f[0]) * t; b1 = f[1] + (c[1] - f[1]) * t; b2 = f[2] + (c[2] - f[2]) * t
                    a1 = f[3] + (c[3] - f[3]) * t; a2 = f[4] + (c[4] - f[4]) * t
                } else { b0 = c[0]; b1 = c[1]; b2 = c[2]; a1 = c[3]; a2 = c[4] }
                val yl = b0 * l + b1 * xl1[k] + b2 * xl2[k] - a1 * yl1[k] - a2 * yl2[k]
                xl2[k] = xl1[k]; xl1[k] = l; yl2[k] = yl1[k]; yl1[k] = flushDenormal(yl)
                val yr = b0 * r + b1 * xr1[k] + b2 * xr2[k] - a1 * yr1[k] - a2 * yr2[k]
                xr2[k] = xr1[k]; xr1[k] = r; yr2[k] = yr1[k]; yr1[k] = flushDenormal(yr)
                l = yl; r = yr
            }
            outL = l; outR = r
        }

        private fun Biquad.toArray() = doubleArrayOf(b0, b1, b2, a1, a2)
        private fun flushDenormal(v: Double) = if (abs(v) < 1e-25) 0.0 else v
    }
}

/**
 * Psychoacoustic bass enhancer (same principle as Calf/LSP bass enhancers and
 * "MaxxBass"): the band below [freq] is saturated to create its 2nd/3rd
 * harmonics, which the ear fuses into the missing fundamental. Bass becomes
 * audible and "full" even on headphones/speakers that can't reproduce it, without
 * a big low-frequency boost that would eat headroom.
 */
class BassEnhancer(private val fs: Double) {
    private var amount = 0.0
    private var lp = emptyList<Biquad>()
    private var hp = emptyList<Biquad>()
    private val st = Array(2) { Array(4) { DoubleArray(4) } } // [channel][section][x1,x2,y1,y2]
    var outL = 0.0; var outR = 0.0

    fun configure(amount: Double, freq: Double) {
        if (amount == this.amount && lp.isNotEmpty()) return
        this.amount = amount
        // isolate the sub band, then keep only the generated harmonics above it
        lp = MatchedDesign.butterworthQs(2).map { MatchedDesign.design(FilterType.LOW_PASS, freq, 0.0, it, fs) }
        hp = MatchedDesign.butterworthQs(2).map { MatchedDesign.design(FilterType.HIGH_PASS, freq * 1.2, 0.0, it, fs) }
    }

    fun step(l: Double, r: Double) {
        if (amount <= 0.0) { outL = l; outR = r; return }
        outL = l + amount * harmonics(0, l)
        outR = r + amount * harmonics(1, r)
    }

    private fun harmonics(ch: Int, x: Double): Double {
        var v = x
        for (k in lp.indices) v = biquad(lp[k], st[ch][k], v)
        val d = v * 4.0
        val sat = d / (1 + abs(d))           // odd harmonics (soft saturation)
        var h = 0.6 * sat + 0.4 * abs(sat)   // + even harmonics (rectification)
        for (k in hp.indices) h = biquad(hp[k], st[ch][2 + k], h) // drop fundamental and DC
        return h * 0.9
    }

    private fun biquad(c: Biquad, s: DoubleArray, x: Double): Double {
        val y = c.b0 * x + c.b1 * s[0] + c.b2 * s[1] - c.a1 * s[2] - c.a2 * s[3]
        s[1] = s[0]; s[0] = x; s[3] = s[2]; s[2] = if (abs(y) < 1e-25) 0.0 else y
        return y
    }
}

/**
 * Stereo-linked look-ahead peak limiter. Gain reduction is planned 5 ms ahead
 * (sliding-window minimum of the required gain) so it is fully in place when a
 * peak arrives — no clipping — then held 50 ms and released slowly (200 ms) so the limiter never
 * follows individual bass cycles, which is what makes cheap limiters distort bass.
 */
class LookaheadLimiter(fs: Double, private val ceiling: Double = 10.0.pow(-0.5 / 20)) {
    private val look = (fs * 0.005).toInt()
    private val size = look + 1
    /** Gain is held for 50 ms (longer than a 20 Hz cycle) before releasing, so it never tracks the bass waveform. */
    private val window = look + (fs * 0.05).toInt()
    private val delayL = DoubleArray(size)
    private val delayR = DoubleArray(size)
    private val req = DoubleArray(size) { 1.0 }
    private var gain = 1.0
    private val attack = 1 - exp(-4.6 / look)
    private val release = 1 - exp(-1 / (0.2 * fs))
    // sliding-window minimum: deque of absolute sample numbers with non-decreasing req
    private val dqIdx = LongArray(window + 1)
    private val dqVal = DoubleArray(window + 1)
    private var head = 0
    private var count = 0
    private var n = 0L

    fun process(buf: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            val l = buf[2 * i].toDouble(); val r = buf[2 * i + 1].toDouble()
            val peak = maxOf(abs(l), abs(r))
            val need = if (peak > ceiling) ceiling / peak else 1.0
            val w = (n % size).toInt()
            delayL[w] = l; delayR[w] = r; req[w] = need
            while (count > 0 && dqVal[(head + count - 1) % dqIdx.size] >= need) count--
            dqIdx[(head + count) % dqIdx.size] = n; dqVal[(head + count) % dqIdx.size] = need; count++
            while (dqIdx[head] <= n - window) { head = (head + 1) % dqIdx.size; count-- }
            val target = dqVal[head]
            gain += (target - gain) * (if (target < gain) attack else release)
            val out = ((n + 1) % size).toInt() // written `look` samples ago
            val g = if (n >= look) minOf(gain, req[out]) else 0.0
            buf[2 * i] = (delayL[out] * g).toFloat()
            buf[2 * i + 1] = (delayR[out] * g).toFloat()
            n++
        }
    }

    fun reset() {
        if (n == 0L) return
        delayL.fill(0.0); delayR.fill(0.0); req.fill(1.0)
        gain = 1.0; head = 0; count = 0; n = 0
    }
}

/**
 * Dynamic EQ band: one filter whose gain follows the music instead of being fixed.
 * A sidechain isolates the watched frequency range and compares its energy with the
 * full-band energy - the spectral balance, independent of the track's overall level.
 * [curve] maps the short-term balance (fast envelopes) and the track's long-term
 * balance (seconds) to the filter gain. Coefficients are redesigned every [UPDATE]
 * samples when the gain moved; boosts rise slowly, cuts and boost reductions are fast.
 *
 * - [bass]: "Dynamischer Bass", a low shelf that fills thin mixes up towards
 *   [BASS_TARGET_DB]. Measured on real tracks, bass-heavy pop/rap/EDM sits at
 *   -3..-0.5 dB and gets nothing, thin mixes (singer-songwriter, acoustic,
 *   classical) sit at -5..-10 dB. Because the long-term balance counts too, the
 *   breaks of a bass-heavy track are not lifted (no boom when the bass returns).
 * - [harsh]: "Schärfe zähmen", a bell around 4.5 kHz that dips only while the
 *   2.5-8 kHz range rises above the track's own average (sharp S sounds, shrill
 *   synths), like a de-esser. Typical music sits at -11..-7 dB there.
 */
class DynamicEqBand(
    private val fs: Double,
    private val type: FilterType,
    private val freq: Double,
    private val q: Double,
    private val sidechain: List<Biquad>,
    attackMs: Double,
    releaseMs: Double,
    private val riseDbPerS: Double,
    private val curve: (fastDb: Double, slowDb: Double, amount: Double) -> Double,
) {
    /** Strength: maximum boost in dB for [bass], 0..1 for [harsh]; 0 bypasses the band. */
    @Volatile var amount = 0.0
    var outL = 0.0; var outR = 0.0
    /** Gain currently applied, in dB. */
    var gainDb = 0.0
        private set
    /** Last short-term and long-term spectral balance (sidechain band vs full band) in dB. */
    var balanceDb = Double.NaN
        private set
    var longTermBalanceDb = Double.NaN
        private set

    private val attack = 1 - kotlin.math.exp(-1 / (attackMs * 0.001 * fs))
    private val release = 1 - kotlin.math.exp(-1 / (releaseMs * 0.001 * fs))
    private val slow = 1 - kotlin.math.exp(-1 / (SLOW_S * fs))
    private val rise = riseDbPerS * UPDATE / fs
    private val sc = Array(sidechain.size) { DoubleArray(4) }
    private var envBand = 0.0
    private var envFull = 0.0
    private var slowBand = 0.0
    private var slowFull = 0.0
    private var counter = 0
    private var coef = MatchedDesign.design(type, freq, 0.0, q, fs)
    private var designedDb = 0.0
    private val xl = DoubleArray(2); private val yl = DoubleArray(2)
    private val xr = DoubleArray(2); private val yr = DoubleArray(2)

    fun step(l: Double, r: Double) {
        val a = amount
        if (a <= 0.0 && designedDb == 0.0) { outL = l; outR = r; return }
        // sidechain on the mid signal
        val m = (l + r) * 0.5
        var b = m
        for (k in sidechain.indices) b = biquad(sidechain[k], sc[k], b)
        val mm = m * m; val bb = b * b
        envFull = follow(envFull, mm)
        envBand = follow(envBand, bb)
        slowFull += (mm - slowFull) * slow
        slowBand += (bb - slowBand) * slow
        if (++counter >= UPDATE) {
            counter = 0
            var target = gainDb
            if (a <= 0.0) target = 0.0
            else if (envFull >= 1e-9 && slowFull >= 1e-10) {
                balanceDb = 10 * kotlin.math.log10((envBand / envFull).coerceAtLeast(1e-12))
                longTermBalanceDb = 10 * kotlin.math.log10((slowBand / slowFull).coerceAtLeast(1e-12))
                target = curve(balanceDb, longTermBalanceDb, a)
            }
            // boosts grow slowly; anything that lowers the gain applies at once
            gainDb = if (target > gainDb && a > 0.0) minOf(target, gainDb + rise) else target
            if (abs(gainDb - designedDb) > 0.05 || (gainDb == 0.0 && designedDb != 0.0)) {
                coef = MatchedDesign.design(type, freq, gainDb, q, fs)
                designedDb = gainDb
            }
        }
        outL = filter(l, xl, yl)
        outR = filter(r, xr, yr)
    }

    private fun follow(env: Double, x: Double) = env + (x - env) * (if (x > env) attack else release)

    private fun filter(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        val c = coef
        val y = c.b0 * x + c.b1 * xs[0] + c.b2 * xs[1] - c.a1 * ys[0] - c.a2 * ys[1]
        xs[1] = xs[0]; xs[0] = x; ys[1] = ys[0]; ys[0] = if (abs(y) < 1e-25) 0.0 else y
        return y
    }

    private fun biquad(c: Biquad, s: DoubleArray, x: Double): Double {
        val y = c.b0 * x + c.b1 * s[0] + c.b2 * s[1] - c.a1 * s[2] - c.a2 * s[3]
        s[1] = s[0]; s[0] = x; s[3] = s[2]; s[2] = if (abs(y) < 1e-25) 0.0 else y
        return y
    }

    companion object {
        const val UPDATE = 32
        /** Time constant of the long-term balance, seconds. */
        const val SLOW_S = 10.0
        /** Bass (< 150 Hz) to full-band energy of a full-sounding mix; thinner mixes are lifted towards it. */
        const val BASS_TARGET_DB = -3.0
        /** How far (dB) the 2.5-8 kHz balance may rise above the track's average before the dip starts. */
        const val HARSH_MARGIN_DB = 3.0
        /** Bright tracks dip from this absolute balance on, whatever their average. */
        const val HARSH_CEILING_DB = -5.0
        const val HARSH_RATIO = 0.7
        const val HARSH_MAX_CUT_DB = 6.0

        fun bass(fs: Double) = DynamicEqBand(
            fs, FilterType.LOW_SHELF, 100.0, 0.7,
            MatchedDesign.butterworthQs(2).map { MatchedDesign.design(FilterType.LOW_PASS, 150.0, 0.0, it, fs) },
            attackMs = 20.0, releaseMs = 250.0, riseDbPerS = 2.0,
        ) { fast, slow, maxBoost -> (BASS_TARGET_DB - maxOf(fast, slow)).coerceIn(0.0, maxBoost) }

        fun harsh(fs: Double) = DynamicEqBand(
            fs, FilterType.PEAK, 4500.0, 0.8,
            listOf(
                MatchedDesign.design(FilterType.HIGH_PASS, 2500.0, 0.0, 0.7071, fs),
                MatchedDesign.design(FilterType.LOW_PASS, 8000.0, 0.0, 0.7071, fs),
            ),
            attackMs = 3.0, releaseMs = 80.0, riseDbPerS = 60.0,
        ) { fast, slow, amount ->
            val threshold = minOf(slow + HARSH_MARGIN_DB, HARSH_CEILING_DB)
            -((fast - threshold) * HARSH_RATIO).coerceIn(0.0, HARSH_MAX_CUT_DB * amount)
        }
    }
}
