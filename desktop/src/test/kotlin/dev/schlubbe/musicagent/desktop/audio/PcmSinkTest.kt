package dev.schlubbe.musicagent.desktop.audio

import java.lang.reflect.Proxy
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertTrue

class PcmSinkTest {
    /** A line that takes [accept] bytes per write (the full write when null), counts what it took and runs [during] in each write. */
    private class FakeLine(val accept: Int?, val during: () -> Unit = {}) {
        @Volatile var open = true
        @Volatile var taken = 0L
        val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)) { _, m, args ->
            when (m.name) {
                "write" -> during().let { _ -> minOf(accept ?: args[2] as Int, args[2] as Int) }.also { taken += it }
                "isOpen" -> open
                "close" -> { open = false; null }
                "getBufferSize" -> 4096 * 4
                "available" -> 4096 * 4
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> null
            }
        } as SourceDataLine
    }

    @Test fun `a lost device keeps the real-time pace and is reopened`() {
        val dead = FakeLine(accept = 0)
        val good = FakeLine(accept = null)
        var opens = 0
        val sink = JavaSoundSink(openLine = { _, _ -> if (opens++ == 0) dead.line else good.line })
        val block = FloatArray(1024 * 2)
        val t0 = System.nanoTime()
        var blocks = 0
        while (good.taken == 0L && blocks < 200) { sink.write(block, 1024); blocks++ }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(good.taken > 0, "never reopened")
        assertTrue(!dead.open, "the dead line was not closed")
        // every block the dead line refused took about its duration (21 ms) instead of nothing
        val refused = blocks - 1
        assertTrue(ms >= refused * 1024 * 1000L / 48_000 * 8 / 10, "ran unpaced: $refused blocks in $ms ms")
    }

    @Test fun `a flush cutting a write short is not a lost device`() {
        lateinit var sink: JavaSoundSink
        // a seek flushes the line while the mixer is blocked in write
        val half = FakeLine(accept = 2048) { sink.flush() }
        var opens = 0
        sink = JavaSoundSink(openLine = { _, _ -> opens++; half.line })
        val block = FloatArray(1024 * 2)
        repeat(6) { sink.write(block, 1024) }
        assertTrue(opens == 1, "reopened $opens times")
    }
}
