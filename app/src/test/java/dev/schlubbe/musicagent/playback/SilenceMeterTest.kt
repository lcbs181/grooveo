package dev.schlubbe.musicagent.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SilenceMeterTest {

    private fun floatBuffer(frames: Int, value: Float): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.nativeOrder())
        repeat(frames * 2) { b.putFloat(value) }
        b.flip()
        return b
    }

    private fun shortBuffer(frames: Int, value: Short): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 2 * 2).order(ByteOrder.nativeOrder())
        repeat(frames * 2) { b.putShort(value) }
        b.flip()
        return b
    }

    @Test
    fun countsSilenceAcrossBuffersAndResetsOnSound() {
        val meter = SilenceMeter()
        meter.flush(48_000, 2, C.ENCODING_PCM_FLOAT)
        meter.handleBuffer(floatBuffer(24_000, 0f))
        meter.handleBuffer(floatBuffer(24_000, 0.0001f))
        assertEquals(1_000L, meter.silentMs)
        meter.handleBuffer(floatBuffer(10, 0.2f))
        assertEquals(0L, meter.silentMs)
    }

    @Test
    fun soundInTheMiddleRestartsTheRun() {
        val meter = SilenceMeter()
        meter.flush(44_100, 2, C.ENCODING_PCM_16BIT)
        meter.handleBuffer(shortBuffer(44_100, 0))
        meter.handleBuffer(shortBuffer(1, 3000))
        meter.handleBuffer(shortBuffer(4_410, 5))
        assertEquals(100L, meter.silentMs)
    }

    @Test
    fun flushRestartsTheCount() {
        val meter = SilenceMeter()
        meter.flush(48_000, 2, C.ENCODING_PCM_FLOAT)
        meter.handleBuffer(floatBuffer(48_000, 0f))
        meter.flush(48_000, 2, C.ENCODING_PCM_FLOAT)
        assertEquals(0L, meter.silentMs)
    }
}
