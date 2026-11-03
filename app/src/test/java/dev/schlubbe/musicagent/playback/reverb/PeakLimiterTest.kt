package dev.schlubbe.musicagent.playback.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PeakLimiterTest {

    private fun sine(frames: Int, amplitude: Float) =
        FloatArray(frames) { amplitude * sin(2 * PI * 440 * it / 48_000).toFloat() }

    @Test
    fun loudSignalNeverExceedsCeiling() {
        val limiter = PeakLimiter(ceiling = 0.9f)
        val left = sine(48_000, 2.5f)
        val right = sine(48_000, 1.8f)
        limiter.process(left, right, left.size)
        val peak = maxOf(left.maxOf { abs(it) }, right.maxOf { abs(it) })
        assertTrue("peak $peak over ceiling", peak <= 0.9f + 1e-4f)
    }

    @Test
    fun quietSignalPassesUnchangedAfterLatency() {
        val limiter = PeakLimiter(lookahead = 64)
        val input = sine(4_800, 0.5f)
        val left = input.copyOf()
        val right = input.copyOf()
        limiter.process(left, right, left.size)
        for (i in 63 until input.size) assertEquals(input[i - 63], left[i], 1e-5f)
    }

    @Test
    fun singleSpikeIsCaughtByLookahead() {
        val limiter = PeakLimiter(lookahead = 64, ceiling = 0.9f)
        val left = FloatArray(1_000) { if (it == 500) 3f else 0.3f }
        val right = left.copyOf()
        limiter.process(left, right, left.size)
        assertTrue(left.all { abs(it) <= 0.9f + 1e-4f })
    }

    @Test
    fun drainReturnsBufferedTailInOrder() {
        val limiter = PeakLimiter(lookahead = 4)
        val left = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
        val right = left.copyOf()
        limiter.process(left, right, left.size)
        assertEquals(0.2f, left.last(), 1e-6f)
        val (tail, _) = limiter.drain()
        assertEquals(listOf(0.3f, 0.4f, 0.5f), tail.toList())
    }
}
