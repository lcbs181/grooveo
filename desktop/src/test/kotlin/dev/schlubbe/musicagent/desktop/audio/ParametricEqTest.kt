package dev.schlubbe.musicagent.desktop.audio

import dev.schlubbe.musicagent.playback.eq.*

import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MatchedDesignTest {
    private val fs = SAMPLE_RATE

    private fun analogDb(type: FilterType, f0: Double, g: Double, q: Double, f: Double) =
        MatchedDesign.prototype(type, g, q).magDb(2 * PI * f / fs, 2 * PI * f0 / fs)

    @Test fun `matched filters follow the analog prototype across the band`() {
        val cases = listOf(
            Triple(FilterType.PEAK, 1000.0, 6.0), Triple(FilterType.PEAK, 12000.0, -8.0), Triple(FilterType.PEAK, 16000.0, 9.0),
            Triple(FilterType.LOW_SHELF, 80.0, 8.0), Triple(FilterType.HIGH_SHELF, 9000.0, 6.0),
            Triple(FilterType.LOW_PASS, 14000.0, 0.0), Triple(FilterType.HIGH_PASS, 30.0, 0.0),
        )
        for ((type, f0, g) in cases) {
            val q = if (type == FilterType.PEAK) 1.2 else 0.707
            val bq = MatchedDesign.design(type, f0, g, q, fs)
            for (f in EqProfile.logFrequencies(60, 20.0, 20000.0)) {
                val err = abs(bq.responseDb(2 * PI * f / fs) - analogDb(type, f0, g, q, f))
                val tol = if (type == FilterType.LOW_PASS || type == FilterType.HIGH_PASS) 1.2 else 0.8
                assertTrue(err < tol, "$type f0=$f0 at $f Hz deviates ${"%.2f".format(err)} dB")
            }
        }
    }

    @Test fun `matched bell keeps its shape near Nyquist where the bilinear one cramps`() {
        val f0 = 15000.0
        val w = 2 * PI * 18000 / fs
        val analog = analogDb(FilterType.PEAK, f0, 9.0, 1.0, 18000.0)
        val matched = MatchedDesign.design(FilterType.PEAK, f0, 9.0, 1.0, fs).responseDb(w)
        val blt = MatchedDesign.blt(FilterType.PEAK, 2 * PI * f0 / fs, 9.0, 1.0).responseDb(w)
        assertTrue(abs(matched - analog) < abs(blt - analog) / 3, "matched=$matched blt=$blt analog=$analog")
    }

    @Test fun `bass filters are exact at their key points`() {
        val ls = MatchedDesign.design(FilterType.LOW_SHELF, 100.0, 8.0, 0.707, fs)
        assertEquals(8.0, ls.responseDb(2 * PI * 20 / fs), 0.15)
        assertEquals(4.0, ls.responseDb(2 * PI * 100 / fs), 0.05)
        assertEquals(0.0, ls.responseDb(2 * PI * 5000 / fs), 0.05)
        val pk = MatchedDesign.design(FilterType.PEAK, 50.0, 6.0, 1.4, fs)
        assertEquals(6.0, pk.responseDb(2 * PI * 50 / fs), 0.02)
        assertTrue(pk.isStable && ls.isStable)
    }

    @Test fun `steep high-pass slopes`() {
        for (slope in 1..4) {
            val b = EqBand(FilterType.HIGH_PASS, 100.0, q = 0.7071, slope = slope)
            assertEquals(-3.0, b.responseDb(100.0), 0.3, "slope $slope at fc")
            assertEquals(-24.0 * slope, b.responseDb(25.0), 1.5 * slope, "slope $slope two octaves down")
        }
        assertEquals(listOf(0.7071), MatchedDesign.butterworthQs(1).map { (it * 1e4).toInt() / 1e4 })
    }
}

class ParametricEqTest {
    @Test fun `flat profile is flat apart from the subsonic filter`() {
        val p = EqProfile.flat()
        EqProfile.logFrequencies(50, 40.0).forEach { assertEquals(0.0, p.responseDb(it), 0.1) }
        assertTrue(p.responseDb(15.0) < -3)
        EqProfile.logFrequencies(50).forEach { assertEquals(0.0, p.copy(subsonic = false).responseDb(it), 1e-4) }
    }

    @Test fun `apo text round trip and steep pass export`() {
        val p = EqProfile.PRESETS[2]
        val back = EqProfile.parseApo(p.toApoText(), p.name)
        p.bands.zip(back.bands).forEach { (x, y) ->
            assertEquals(x.type, y.type); assertEquals(x.freq, y.freq, 0.01); assertEquals(x.gainDb, y.gainDb, 0.01); assertEquals(x.q, y.q, 0.01)
        }
        assertEquals(p.preampDb, back.preampDb, 0.01)
        val steep = EqProfile(bands = listOf(EqBand(FilterType.HIGH_PASS, 30.0, slope = 3)))
        assertEquals(3, Regex("HPQ").findAll(steep.toApoText()).count())
    }

    @Test fun `parses real AutoEQ file`() {
        val text = """
            Preamp: -6.4 dB
            Filter 1: ON LSC Fc 105 Hz Gain 5.6 dB Q 0.70
            Filter 2: ON PK Fc 2410 Hz Gain -3.1 dB Q 2.12
            Filter 3: OFF PK Fc 6000 Hz Gain 2,5 dB Q 1.5
            Filter 4: ON HSC Fc 10000 Hz Gain -1.2 dB Q 0.70
            Filter 5: ON HP Fc 25 Hz
        """.trimIndent()
        val p = EqProfile.parseApo(text)
        assertEquals(-6.4, p.preampDb, 1e-9)
        assertEquals(listOf(FilterType.LOW_SHELF, FilterType.PEAK, FilterType.PEAK, FilterType.HIGH_SHELF, FilterType.HIGH_PASS), p.bands.map { it.type })
        assertEquals(false, p.bands[2].enabled)
        assertEquals(2.5, p.bands[2].gainDb, 1e-9)
        assertFailsWith<IllegalArgumentException> { EqProfile.parseApo("nothing here") }
    }

    @Test fun `presets have headroom and bass presets cut mud`() {
        EqProfile.PRESETS.forEach { p ->
            val max = EqProfile.logFrequencies(256).maxOf { p.responseDb(it) }
            assertTrue(max <= 0.05, "${p.name} peaks at $max dB")
        }
        val bass = EqProfile.PRESETS.first { it.name == "Bass-Boost" }
        assertTrue(bass.responseDb(60.0) - bass.responseDb(1000.0) > 8, "bass lift")
        assertTrue(bass.responseDb(250.0) < bass.responseDb(120.0) - 3, "mud region is tamed")
    }

    @Test fun `normalized repairs old and broken values`() {
        val p = EqProfile(preampDb = -99.0, bassEnhance = Double.NaN, bassEnhanceFreq = 0.0,
            bands = List(30) { EqBand(freq = 5.0, gainDb = 40.0, q = 0.0, slope = 0) }).normalized()
        assertEquals(EqProfile.MAX_BANDS, p.bands.size)
        assertEquals(-24.0, p.preampDb)
        assertEquals(1, p.bands[0].slope)
        assertEquals(0.0, p.bassEnhance); assertEquals(90.0, p.bassEnhanceFreq)
    }
}

class EqProcessorTest {
    private val block = AudioEngine.BLOCK

    /** Feeds a sine, returns output gain in dB measured after settling. */
    private fun measure(eq: EqProcessor, freq: Double, amp: Double = 0.1, blocks: Int = 40): Double {
        var t = 0; var sum = 0.0; var cnt = 0
        repeat(blocks) { bi ->
            val buf = FloatArray(block * 2)
            for (i in 0 until block) { val v = (amp * sin(2 * PI * freq * t++ / SAMPLE_RATE)).toFloat(); buf[2 * i] = v; buf[2 * i + 1] = v }
            eq.process(buf, block)
            if (bi >= blocks / 2) for (i in 0 until block) { sum += buf[2 * i] * buf[2 * i]; cnt++ }
        }
        return 20 * log10(sqrt(sum / cnt) / (amp / sqrt(2.0)))
    }

    @Test fun `processor output matches the designed curve`() {
        val p = EqProfile(limiter = false, subsonic = true, preampDb = -4.0, bands = listOf(
            EqBand(FilterType.LOW_SHELF, 105.0, 6.0, 0.7), EqBand(FilterType.PEAK, 55.0, 4.0, 1.3),
            EqBand(FilterType.PEAK, 250.0, -3.0, 1.0), EqBand(FilterType.HIGH_SHELF, 9000.0, 3.0, 0.7),
        ))
        for (f in listOf(30.0, 55.0, 120.0, 250.0, 1000.0, 12000.0)) {
            val eq = EqProcessor().apply { setProfile(p) }
            assertEquals(p.responseDb(f), measure(eq, f), 0.25, "at $f Hz")
        }
    }

    @Test fun `live changes interpolate without clicks`() {
        val eq = EqProcessor().apply { setProfile(EqProfile(limiter = false, subsonic = false, bands = listOf(EqBand(FilterType.PEAK, 60.0, 0.0, 1.0)))) }
        var t = 0
        var prev = 0f
        var maxJump = 0f
        repeat(60) { bi ->
            if (bi % 3 == 0) eq.setProfile(EqProfile(limiter = false, subsonic = false, bands = listOf(EqBand(FilterType.PEAK, 60.0, (bi % 12).toDouble(), 1.0))))
            val buf = FloatArray(block * 2)
            for (i in 0 until block) { val v = (0.2 * sin(2 * PI * 60 * t++ / SAMPLE_RATE)).toFloat(); buf[2 * i] = v; buf[2 * i + 1] = v }
            eq.process(buf, block)
            for (i in 0 until block) { maxJump = maxOf(maxJump, abs(buf[2 * i] - prev)); prev = buf[2 * i] }
        }
        // a 60 Hz sine at ≤0.8 changes at most ~0.0063 per sample; a click would be far larger
        assertTrue(maxJump < 0.02f, "discontinuity $maxJump")
    }

    @Test fun `limiter keeps heavy bass boost below the ceiling without distortion`() {
        val eq = EqProcessor().apply { setProfile(EqProfile(bands = listOf(EqBand(FilterType.LOW_SHELF, 120.0, 12.0, 0.7)))) }
        var t = 0; var peak = 0.0
        val out = DoubleArray(block * 20)
        repeat(40) { bi ->
            val buf = FloatArray(block * 2)
            for (i in 0 until block) { val v = (0.7 * sin(2 * PI * 50 * t++ / SAMPLE_RATE)).toFloat(); buf[2 * i] = v; buf[2 * i + 1] = v }
            eq.process(buf, block)
            for (i in 0 until block) { peak = maxOf(peak, abs(buf[2 * i].toDouble())); if (bi >= 20) out[(bi - 20) * block + i] = buf[2 * i].toDouble() }
        }
        assertTrue(peak <= 0.945, "peak $peak above -0.5 dBFS")
        // settled limiter = steady gain: total harmonic distortion stays tiny
        val whole = out.copyOf(19_200) // exactly 20 cycles, no spectral leakage
        assertTrue(thd(whole, 50.0) < 0.002, "THD ${thd(whole, 50.0)}")
    }

    @Test fun `bass enhancer adds harmonics above the sub band`() {
        val base = EqProfile(limiter = false, subsonic = false, bands = emptyList())
        fun energyAt(p: EqProfile, f: Double): Double {
            val eq = EqProcessor().apply { setProfile(p) }
            var t = 0
            val x = DoubleArray(block * 20)
            repeat(40) { bi ->
                val buf = FloatArray(block * 2)
                for (i in 0 until block) { val v = (0.3 * sin(2 * PI * 45 * t++ / SAMPLE_RATE)).toFloat(); buf[2 * i] = v; buf[2 * i + 1] = v }
                eq.process(buf, block)
                if (bi >= 20) for (i in 0 until block) x[(bi - 20) * block + i] = buf[2 * i].toDouble()
            }
            return goertzel(x, f)
        }
        val plain = energyAt(base, 135.0)
        val enhanced = energyAt(base.copy(bassEnhance = 0.6, bassEnhanceFreq = 90.0), 135.0)
        assertTrue(enhanced > plain * 20, "3rd harmonic: $plain -> $enhanced")
    }

    @Test fun `loudness compensation grows as volume drops`() {
        assertEquals(0.0, EqProcessor.loudnessGains(1f).first, 1e-9); assertEquals(0.0, EqProcessor.loudnessGains(1f).second, 1e-9)
        val (b30, t30) = EqProcessor.loudnessGains(0.316f) // ≈ -30 dB
        assertEquals(10.0, b30, 0.5); assertEquals(3.6, t30, 0.3)
        assertEquals(12.0, EqProcessor.loudnessGains(0.05f).first)
    }

    @Test fun `disabled eq is bit transparent`() {
        val eq = EqProcessor().apply { setProfile(EqProfile.PRESETS[2].copy(enabled = false)) }
        val buf = FloatArray(block * 2) { (it % 7) * 0.01f }
        val copy = buf.copyOf()
        eq.process(buf, block)
        assertTrue(buf.contentEquals(copy))
    }

    private fun goertzel(x: DoubleArray, f: Double): Double {
        val w = 2 * PI * f / SAMPLE_RATE
        var s1 = 0.0; var s2 = 0.0
        for (v in x) { val s = v + 2 * kotlin.math.cos(w) * s1 - s2; s2 = s1; s1 = s }
        return (s1 * s1 + s2 * s2 - 2 * kotlin.math.cos(w) * s1 * s2) / (x.size.toDouble() * x.size)
    }

    private fun thd(x: DoubleArray, f: Double): Double {
        val fund = goertzel(x, f)
        val harm = (2..6).sumOf { goertzel(x, f * it) }
        return sqrt(harm / fund)
    }
}

fun hasBinary(name: String) = runCatching { ProcessBuilder(name, "-version").start().waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)
