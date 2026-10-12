package dev.schlubbe.musicagent.desktop.audio

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveStreamTest {
    private val engine = AudioEngine({ FakeDeck(30.0) }, CaptureSink()).apply { volume = 1f }
    private val live = LiveStream(engine).also { engine.liveTap = it::tap }

    @AfterTest fun tearDown() { live.revokeAll(); engine.close() }

    /** Collects what [LiveStream.serve] writes, thread-safely. */
    private class Sink : OutputStream() {
        private val b = ByteArrayOutputStream()
        override fun write(x: Int) = synchronized(b) { b.write(x) }
        override fun write(x: ByteArray, off: Int, len: Int) = synchronized(b) { b.write(x, off, len) }
        fun bytes(): ByteArray = synchronized(b) { b.toByteArray() }
    }

    private fun serveInBackground(token: String, out: OutputStream): Thread =
        Thread { live.serve(token, out) }.apply { isDaemon = true; start() }

    @Test fun `the stream is AAC in ADTS frames, also while the PC is paused`() {
        val t = live.ticket(muteDevice = false)
        val out = Sink()
        val th = serveInBackground(t.token, out)
        Thread.sleep(800) // nothing plays: silence keeps the stream going
        engine.play(AudioSource("soundcloud:1", "mem://1"))
        Thread.sleep(800)
        live.revokeAll()
        th.join(3000)
        val b = out.bytes()
        assertTrue(b.size > 1000, "only ${b.size} bytes")
        // ADTS sync word
        assertEquals(0xFF, b[0].toInt() and 0xFF)
        assertEquals(0xF0, b[1].toInt() and 0xF0)
        assertFalse(th.isAlive)
    }

    @Test fun `an unknown ticket gets nothing`() {
        val out = Sink()
        assertFalse(live.serve("0".repeat(32), out))
        assertEquals(0, out.bytes().size)
        assertFalse(live.isValid("0".repeat(32)))
    }

    @Test fun `mute applies only while the phone listens`() {
        val t = live.ticket(muteDevice = true)
        assertFalse(engine.deviceMuted)
        val th = serveInBackground(t.token, Sink())
        val end = System.currentTimeMillis() + 2000
        while (!engine.deviceMuted && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(engine.deviceMuted)
        live.revokeAll()
        th.join(3000)
        assertFalse(engine.deviceMuted)
    }

    @Test fun `ring drops instead of overflowing`() {
        val r = LiveStream.FloatRing(8)
        r.write(FloatArray(6) { it.toFloat() }, 6)
        r.write(FloatArray(6) { 9f }, 6) // does not fit
        val dst = FloatArray(16)
        assertEquals(6, r.read(dst, 16))
        assertEquals(5f, dst[5])
        r.write(FloatArray(6) { 7f }, 6) // wraps around
        assertEquals(6, r.read(dst, 16))
        assertTrue(dst.take(6).all { it == 7f })
    }
}
