package dev.schlubbe.musicagent.playback.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

class LoudnessNormalizerPeakTest {
    private val fs = 48000.0

    private fun run(n: LoudnessNormalizer, seconds: Double, amp: Double): FloatArray {
        val frames = (seconds * fs).toInt()
        val b = FloatArray(frames * 2) { i -> (amp * sin(2 * PI * 997.0 * (i / 2) / fs)).toFloat() }
        for (off in b.indices step 2048) {
            val len = minOf(2048, b.size - off)
            val chunk = b.copyOfRange(off, off + len)
            n.process(chunk, len / 2)
            System.arraycopy(chunk, 0, b, off, len)
        }
        return b
    }

    @Test fun quietTrackIsOnlyRaisedAsFarAsItsPeaksAllow() {
        val n = LoudnessNormalizer(fs).apply { enabled = true }
        // 6 dB below the target, but its stored peak is at -3 dBFS: only 2 dB of room
        n.startTrack("dynamic", LoudnessNormalizer.DEFAULT_TARGET_LUFS - 6.0, -3.0)
        run(n, 0.2, 0.1)
        assertEquals(2.0, n.currentGainDb, 1e-9)
    }

    @Test fun louderPeakThanKnownTakesTheBoostBackAtOnce() {
        val n = LoudnessNormalizer(fs).apply { enabled = true }
        n.startTrack("a", LoudnessNormalizer.DEFAULT_TARGET_LUFS - 6.0)
        run(n, 0.2, 0.1) // peak -20 dBFS: the full boost fits
        assertEquals(6.0, n.currentGainDb, 1e-9)
        val out = run(n, 0.2, 0.7) // peak -3.1 dBFS
        assertEquals(-1.0 - 20 * log10(0.7), n.currentGainDb, 1e-3)
        assertTrue("no sample above -1 dBFS", out.maxOf { abs(it) } <= 10.0.pow(-1.0 / 20) + 1e-4)
    }

    @Test fun peakIsStoredWithTheLoudness() {
        val n = LoudnessNormalizer(fs).apply { enabled = true }
        n.startTrack("a", null)
        run(n, 21.0, 0.5)
        assertEquals(20 * log10(0.5), n.measuredPeakDb()!!, 0.01)
    }
}
