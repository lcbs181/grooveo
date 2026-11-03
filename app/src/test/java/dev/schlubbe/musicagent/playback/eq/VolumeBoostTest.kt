package dev.schlubbe.musicagent.playback.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class VolumeBoostTest {

    private fun stereoSine(frames: Int, amplitude: Float) = FloatArray(frames * 2) {
        amplitude * sin(2 * PI * 440 * (it / 2) / 48_000).toFloat()
    }

    private fun peakAfter(processor: EqProcessor, input: FloatArray, skipFrames: Int = 4_800): Float {
        processor.process(input, input.size / 2)
        return (skipFrames * 2 until input.size).maxOf { abs(input[it]) }
    }

    @Test
    fun boostMakesQuietAudioLouder() {
        val eq = EqProcessor(48_000.0).apply { setProfile(EqProfile.flat()); boostDb = 6.0 }
        val peak = peakAfter(eq, stereoSine(48_000, 0.1f))
        assertEquals(0.1f * 1.995f, peak, 0.01f)
    }

    @Test
    fun boostedLoudAudioNeverClips() {
        val eq = EqProcessor(48_000.0).apply { setProfile(EqProfile.flat()); boostDb = 12.0 }
        val peak = peakAfter(eq, stereoSine(48_000, 0.9f))
        assertTrue("peak $peak", peak <= 0.95f)
    }

    @Test
    fun noBoostWithLimiterOff() {
        val eq = EqProcessor(48_000.0).apply { setProfile(EqProfile.flat().copy(limiter = false)); boostDb = 12.0 }
        val peak = peakAfter(eq, stereoSine(48_000, 0.1f))
        assertEquals(0.1f, peak, 0.002f)
    }
}
