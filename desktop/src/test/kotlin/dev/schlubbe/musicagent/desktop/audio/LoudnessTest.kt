package dev.schlubbe.musicagent.desktop.audio

import dev.schlubbe.musicagent.playback.eq.*
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FS = 48000.0

/** Interleaved stereo sum of sines (amplitude, frequency). */
private fun tones(seconds: Double, vararg parts: Pair<Double, Double>, from: Int = 0): FloatArray {
    val n = (seconds * FS).toInt()
    return FloatArray(n * 2) { k ->
        val i = from + k / 2
        parts.sumOf { (a, f) -> a * sin(2 * PI * f * i / FS) }.toFloat()
    }
}

class LoudnessMeterTest {
    @Test fun `k-weighting matches the BS 1770 reference coefficients at 48 kHz`() {
        val m = LoudnessMeter(FS)
        val ref1 = doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285, -1.69065929318241, 0.73248077421585)
        val ref2 = doubleArrayOf(1.0, -2.0, 1.0, -1.99004745483398, 0.99007225036621)
        for (i in 0..4) assertEquals(ref1[i], m.stage1[i], 1e-6, "stage1[$i]")
        for (i in 0..4) assertEquals(ref2[i], m.stage2[i], 1e-6, "stage2[$i]")
    }

    @Test fun `stereo 1 kHz sine at -20 dBFS reads -20 LUFS`() {
        val m = LoudnessMeter(FS)
        val b = tones(5.0, 0.1 to 997.0)
        m.add(b, b.size / 2)
        assertEquals(-20.0, m.integrated()!!, 0.1)
    }

    @Test fun `silence is gated out`() {
        val m = LoudnessMeter(FS)
        val b = tones(5.0, 0.1 to 997.0)
        m.add(b, b.size / 2)
        val silence = FloatArray(b.size)
        m.add(silence, silence.size / 2)
        // only the 400 ms blocks straddling the end of the tone still count
        assertEquals(-20.0, m.integrated()!!, 0.2)
        m.reset()
        m.add(silence, silence.size / 2)
        assertNull(m.integrated())
    }
}

class LoudnessNormalizerTest {
    private fun run(n: LoudnessNormalizer, seconds: Double, amp: Double): FloatArray {
        val b = tones(seconds, amp to 997.0)
        for (off in b.indices step 2048) {
            val len = minOf(2048, b.size - off)
            val chunk = b.copyOfRange(off, off + len)
            n.process(chunk, len / 2)
            System.arraycopy(chunk, 0, b, off, len)
        }
        return b
    }

    @Test fun `known loudness applies the gain right away, capped`() {
        val n = LoudnessNormalizer(FS).apply { enabled = true }
        n.startTrack("a", -20.0)
        run(n, 0.2, 0.1)
        assertEquals(LoudnessNormalizer.DEFAULT_MAX_BOOST_DB, n.currentGainDb, 1e-9) // wants +10, capped at +8
        n.startTrack("b", -4.0)
        run(n, 0.2, 0.1)
        assertEquals(-6.0, n.currentGainDb, 1e-9)
    }

    @Test fun `unknown track is measured and levelled gradually`() {
        val n = LoudnessNormalizer(FS).apply { enabled = true }
        n.startTrack("a", null)
        run(n, 2.0, 0.5) // -6 LUFS: still measuring, unity gain
        assertEquals(0.0, n.currentGainDb, 1e-9)
        run(n, 6.0, 0.5)
        assertEquals(-4.0, n.currentGainDb, 0.05)
        assertNull(n.measuredLufs(), "too short to store")
        run(n, 14.0, 0.5)
        assertEquals(-6.0, n.measuredLufs()!!, 0.1)
    }

    @Test fun `next unknown track starts from the previous gain`() {
        val n = LoudnessNormalizer(FS).apply { enabled = true }
        n.startTrack("a", -4.0)
        run(n, 0.2, 0.5)
        n.startTrack("b", null)
        run(n, 1.0, 0.5)
        assertEquals(-6.0, n.currentGainDb, 1e-9)
    }

    @Test fun `disabled is transparent but still measures`() {
        val n = LoudnessNormalizer(FS)
        n.startTrack("a", null)
        val out = run(n, 25.0, 0.1)
        val ref = tones(25.0, 0.1 to 997.0)
        assertTrue(out.indices.all { out[it] == ref[it] })
        assertEquals(-20.0, n.measuredLufs()!!, 0.1)
    }
}

class LoudnessCacheTest {
    @Test fun `values survive a reload`() {
        val f = Files.createTempDirectory("grooveo-lc").resolve("loudness.json").toFile()
        LoudnessCache(f).apply { put("soundcloud:1", -8.25); put("ytmusic:x", -13.5); save() }
        val c = LoudnessCache(f)
        assertEquals(-8.25, c["soundcloud:1"]!!, 1e-6)
        assertEquals(-13.5, c["ytmusic:x"]!!, 1e-6)
        assertNull(c["nope"])
    }
}

class DynamicEqTest {
    private fun feed(b: DynamicEqBand, buf: FloatArray): FloatArray {
        val out = FloatArray(buf.size)
        for (i in 0 until buf.size / 2) {
            b.step(buf[2 * i].toDouble(), buf[2 * i + 1].toDouble())
            out[2 * i] = b.outL.toFloat(); out[2 * i + 1] = b.outR.toFloat()
        }
        return out
    }

    @Test fun `dynamic bass lifts a thin mix up to the maximum`() {
        val b = DynamicEqBand.bass(FS).apply { amount = 6.0 }
        feed(b, tones(8.0, 0.3 to 1000.0, 0.03 to 60.0)) // bass 20 dB below
        assertEquals(6.0, b.gainDb, 1e-9)
    }

    @Test fun `dynamic bass leaves a bass-heavy mix alone`() {
        val b = DynamicEqBand.bass(FS).apply { amount = 6.0 }
        feed(b, tones(8.0, 0.1 to 1000.0, 0.4 to 60.0))
        assertEquals(0.0, b.gainDb, 1e-9)
    }

    @Test fun `dynamic bass does not boost a break of a bass-heavy track`() {
        val b = DynamicEqBand.bass(FS).apply { amount = 6.0 }
        feed(b, tones(15.0, 0.1 to 1000.0, 0.4 to 60.0))
        feed(b, tones(2.0, 0.1 to 1000.0)) // 2 s break without bass
        assertTrue(b.gainDb < 1.0, "gain ${b.gainDb}")
    }

    @Test fun `harsh band dips only when 2-8 kHz jumps above the track's average`() {
        val h = DynamicEqBand.harsh(FS).apply { amount = 1.0 }
        feed(h, tones(12.0, 0.3 to 300.0, 0.02 to 5000.0))
        assertEquals(0.0, h.gainDb, 1e-9)
        feed(h, tones(0.3, 0.3 to 300.0, 0.25 to 5000.0))
        assertTrue(h.gainDb < -3.0, "gain ${h.gainDb}")
        assertTrue(h.gainDb >= -DynamicEqBand.HARSH_MAX_CUT_DB)
    }

    @Test fun `zero amount is bit transparent`() {
        val b = DynamicEqBand.bass(FS)
        val inp = tones(1.0, 0.3 to 1000.0, 0.03 to 60.0)
        val out = feed(b, inp)
        assertTrue(inp.indices.all { abs(inp[it] - out[it]) == 0f })
    }
}
