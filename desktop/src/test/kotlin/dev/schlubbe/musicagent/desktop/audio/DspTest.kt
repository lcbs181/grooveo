package dev.schlubbe.musicagent.desktop.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DspTest {
    @Test fun `ring buffer is fifo and clear drops stale chunks`() {
        val ring = PcmRing(8)
        assertTrue(ring.write(byteArrayOf(1, 2, 3, 4, 5), 5, ring.generation))
        val out = ByteArray(3)
        assertEquals(3, ring.read(out, 3)); assertEquals(listOf<Byte>(1, 2, 3), out.toList())
        assertTrue(ring.write(byteArrayOf(6, 7, 8, 9, 10, 11), 6, ring.generation)) // wraps
        val all = ByteArray(8)
        assertEquals(8, ring.read(all, 8)); assertEquals((4..11).map { it.toByte() }, all.toList())
        val stale = ring.generation
        ring.clear()
        assertFalse(ring.write(byteArrayOf(1), 1, stale))
        assertEquals(0, ring.size())
    }

    @Test fun `spectrum peaks at the played frequency`() {
        val a = SpectrumAnalyzer()
        val buf = FloatArray(1024 * 2)
        var t = 0
        repeat(20) {
            for (i in 0 until 1024) { val v = (0.5 * sin(2 * PI * 1000 * t++ / 48000)).toFloat(); buf[2 * i] = v; buf[2 * i + 1] = v }
            a.push(buf, 1024)
        }
        val bands = a.latest.bands
        val peak = bands.indices.maxBy { bands[it] }
        val peakFreq = 35.0 * Math.pow(16000.0 / 35.0, peak / 256.0)
        assertTrue(peakFreq in 850.0..1150.0, "peak at $peakFreq Hz")
        assertTrue(a.latest.level > 0.5f)
        assertTrue(a.latest.bass < 0.3f)
    }

    @Test fun `kick detector fires on bass hits`() {
        val a = SpectrumAnalyzer()
        val buf = FloatArray(1024 * 2)
        var fired = false
        var t = 0
        repeat(48) { block ->
            for (i in 0 until 1024) {
                val inHit = (t % 24000) < 2400 // 50 ms 60 Hz burst twice a second
                val v = if (inHit) (0.8 * sin(2 * PI * 60 * t / 48000)).toFloat() else 0f
                t++
                buf[2 * i] = v; buf[2 * i + 1] = v
            }
            a.push(buf, 1024)
            if (block > 2 && a.latest.onset > 0.99f) fired = true
        }
        assertTrue(fired)
    }

    @Test fun `all reverb impulse responses load and add a tail`() {
        Sound3dPreset.entries.filter { it.asset != null }.forEach { p ->
            assertNotNull(ReverbStage.loadIr(p), p.name)
        }
        val stage = ReverbStage().apply { setPreset(Sound3dPreset.KIRCHE) }
        val impulse = FloatArray(ReverbStage.BLOCK * 2).also { it[0] = 1f; it[1] = 1f }
        stage.process(impulse)
        val silence = FloatArray(ReverbStage.BLOCK * 2)
        stage.process(silence)
        assertTrue(silence.any { it != 0f }, "no reverb tail")
    }

    @Test fun `soft clip is linear below knee and bounded`() {
        assertEquals(0.5f, softClip(0.5f))
        assertTrue(softClip(10f) < 1f)
        assertEquals(-softClip(3f), softClip(-3f))
    }
}

