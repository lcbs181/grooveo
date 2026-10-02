package dev.schlubbe.musicagent.playback.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParametricEqAudioProcessorTest {
    private fun configured(encoding: Int, channels: Int = 2, rate: Int = 44100) = ParametricEqAudioProcessor().apply {
        configure(AudioProcessor.AudioFormat(rate, channels, encoding))
        flush()
    }

    private fun sine16(freq: Double, frames: Int, channels: Int, rate: Int, amp: Double = 0.25): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) repeat(channels) { b.putShort((amp * sin(2 * PI * freq * i / rate) * 32767).toInt().toShort()) }
        b.flip(); return b
    }

    /** Feeds [input] in 4096-byte chunks, collects the output as floats of channel 0. */
    private fun run(p: ParametricEqAudioProcessor, input: ByteBuffer, float: Boolean, channels: Int): FloatArray {
        val out = mutableListOf<Float>()
        while (input.hasRemaining()) {
            val chunk = input.slice().order(ByteOrder.LITTLE_ENDIAN).limit(minOf(4096, input.remaining())) as ByteBuffer
            input.position(input.position() + chunk.remaining())
            p.queueInput(chunk)
            val o = p.output.order(ByteOrder.LITTLE_ENDIAN)
            var k = 0
            while (o.hasRemaining()) {
                val v = if (float) o.float else o.short / 32768f
                if (k++ % channels == 0) out += v
            }
        }
        return out.toFloatArray()
    }

    private fun rmsDb(x: FloatArray, from: Int) = 20 * log10(sqrt(x.drop(from).sumOf { it.toDouble() * it } / (x.size - from)))

    @Test fun `flat profile is transparent for 16 bit`() {
        val p = configured(C.ENCODING_PCM_16BIT).apply { setProfile(EqProfile(enabled = false)) }
        val input = sine16(1000.0, 4410, 2, 44100)
        val copy = ByteBuffer.allocate(input.remaining()).order(ByteOrder.LITTLE_ENDIAN).put(input.duplicate()).flip() as ByteBuffer
        val out = run(p, input, float = false, channels = 2)
        for (i in out.indices) assertEquals(copy.getShort(i * 4) / 32768f, out[i], 1e-6f)
    }

    @Test fun `peak band boosts its frequency at the device sample rate`() {
        val boost = EqProfile(subsonic = false, limiter = false, bands = listOf(EqBand(FilterType.PEAK, 100.0, 6.0, 1.0)))
        for (rate in listOf(44100, 48000)) {
            val dry = run(configured(C.ENCODING_PCM_16BIT, rate = rate).apply { setProfile(EqProfile(enabled = false)) }, sine16(100.0, rate, 2, rate), false, 2)
            val wet = run(configured(C.ENCODING_PCM_16BIT, rate = rate).apply { setProfile(boost) }, sine16(100.0, rate, 2, rate), false, 2)
            assertEquals(6.0, rmsDb(wet, rate / 2) - rmsDb(dry, rate / 2), 0.3, "rate $rate")
        }
    }

    @Test fun `mono and float input are processed`() {
        val p = configured(C.ENCODING_PCM_FLOAT, channels = 1).apply {
            setProfile(EqProfile(subsonic = false, limiter = false, bands = listOf(EqBand(FilterType.PEAK, 1000.0, -12.0, 1.0))))
        }
        val n = 44100
        val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) b.putFloat((0.5 * sin(2 * PI * 1000 * i / 44100.0)).toFloat())
        b.flip()
        val out = run(p, b, float = true, channels = 1)
        assertEquals(n, out.size)
        assertEquals(20 * log10(0.5 / sqrt(2.0)) - 12, rmsDb(out, n / 2), 0.3)
    }

    @Test fun `limiter keeps 16 bit output from clipping`() {
        val p = configured(C.ENCODING_PCM_16BIT).apply {
            setProfile(EqProfile(subsonic = false, bands = listOf(EqBand(FilterType.PEAK, 100.0, 12.0, 1.0))))
        }
        val out = run(p, sine16(100.0, 44100, 2, 44100, amp = 0.9), false, 2)
        assertTrue(out.drop(4410).all { abs(it) <= 0.95f }, "peak ${out.maxOf { abs(it) }}")
    }

    @Test fun `unsupported formats are rejected`() {
        assertFailsWith<AudioProcessor.UnhandledAudioFormatException> {
            ParametricEqAudioProcessor().configure(AudioProcessor.AudioFormat(44100, 6, C.ENCODING_PCM_16BIT))
        }
        assertFailsWith<AudioProcessor.UnhandledAudioFormatException> {
            ParametricEqAudioProcessor().configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_24BIT))
        }
    }

    @Test fun `pcm16 conversion clamps`() {
        assertEquals(32767, ParametricEqAudioProcessor.toPcm16(2f).toInt())
        assertEquals(-32768, ParametricEqAudioProcessor.toPcm16(-2f).toInt())
    }
}
