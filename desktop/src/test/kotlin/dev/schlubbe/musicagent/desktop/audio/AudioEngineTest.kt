package dev.schlubbe.musicagent.desktop.audio

import dev.schlubbe.musicagent.playback.eq.*

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deck producing a constant value per source (value = source id's length) for [seconds]. */
class FakeDeck(private val seconds: Double) : Deck {
    @Volatile override var source: AudioSource? = null
    @Volatile private var pos = 0L
    @Volatile override var error: String? = null
    val totalFrames get() = (seconds * SAMPLE_RATE).toLong()
    override fun load(source: AudioSource) {
        this.source = source; pos = (source.startSec * SAMPLE_RATE).toLong()
        error = if (source.url == "bad") "kaputt" else null
    }
    override fun seek(sec: Double) { pos = (sec * SAMPLE_RATE).toLong() }
    override fun stop() { source = null; pos = 0 }
    override fun read(out: FloatArray, frames: Int): Int {
        val s = source ?: return 0
        if (error != null) return 0
        val n = minOf(frames.toLong(), totalFrames - pos).toInt().coerceAtLeast(0)
        val v = (s.id.substringAfterLast(':').toFloatOrNull() ?: 1f) / 10f
        for (i in 0 until n * 2) out[i] = v
        pos += n
        return n
    }
    override fun available() = (totalFrames - pos).toInt()
    override val ended get() = source != null && pos >= totalFrames
    override val positionSec get() = pos / SAMPLE_RATE
    override val durationSec get() = if (source != null) seconds else null
    override fun close() {}
}

/** Sink that records the first channel of everything written (no real-time pacing). */
class CaptureSink : PcmSink {
    val samples = CopyOnWriteArrayList<Float>()
    override fun write(buf: FloatArray, frames: Int) { for (i in 0 until frames) samples += buf[2 * i]; Thread.sleep(1) }
    override fun start() {}
    override fun stop() {}
    override fun flush() {}
    override val latencySec = 0.0
    override fun close() {}
}

class AudioEngineTest {
    private val sink = CaptureSink()
    private val decks = mutableListOf<FakeDeck>()
    private val events = CopyOnWriteArrayList<String>()
    private val engine = AudioEngine({ FakeDeck(1.0).also { decks += it } }, sink).apply {
        volume = 1f
        setEq(EqProfile(enabled = false)) // fake decks emit DC; keep the chain transparent
        listener = object : EngineListener {
            override fun onStarted(source: AudioSource, automatic: Boolean) { events += "start:${source.id}:$automatic" }
            override fun onEnded(source: AudioSource) { events += "end:${source.id}" }
            override fun onError(source: AudioSource, message: String) { events += "error:${source.id}:$message" }
        }
    }

    @AfterTest fun tearDown() = engine.close()

    private fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) { check(System.currentTimeMillis() < end) { "timeout; events=$events" }; Thread.sleep(5) }
    }

    @Test fun `plays to the end and reports it`() {
        engine.play(AudioSource("5", "x"))
        waitFor { "end:5" in events }
        assertEquals(listOf("start:5:false", "end:5"), events.toList())
        // one second of audio at 0.5 → cubed volume 1 → softclip leaves 0.5
        assertTrue(sink.samples.size >= 48000)
        assertEquals(0.5f, sink.samples[100], 1e-6f)
        assertEquals(false, engine.state.value.playing)
    }

    @Test fun `gapless hand-over to preloaded deck`() {
        engine.play(AudioSource("5", "x"))
        engine.preloadNext(AudioSource("7", "y"))
        waitFor { "end:7" in events }
        assertEquals(listOf("start:5:false", "start:7:true", "end:7"), events.toList())
        val s = sink.samples
        val firstSeven = s.indexOfFirst { it > 0.65f }
        // no silence between the two tracks
        assertTrue(s.subList(0, firstSeven).all { it > 0.45f }, "gap before hand-over")
    }

    @Test fun `crossfade blends with equal power`() {
        engine.crossfadeSec = 1
        engine.mixOutSec = 0.5
        engine.play(AudioSource("4", "x"))
        engine.preloadNext(AudioSource("8", "y"))
        waitFor { "end:8" in events }
        assertEquals(listOf("start:4:false", "start:8:true", "end:8"), events.toList())
        // somewhere mid-fade both contribute: 0.4*cos + 0.8*sin exceeds either alone
        assertTrue(sink.samples.any { it > 0.4f + 0.01f && it < 0.8f - 0.01f }, "no blended samples")
    }

    @Test fun `error is reported`() {
        engine.play(AudioSource("3", "bad"))
        waitFor { events.any { it.startsWith("error:3") } }
        assertEquals("error:3:kaputt", events.last())
    }

    @Test fun `seek moves position`() {
        engine.play(AudioSource("2", "x"))
        engine.pause()
        engine.seek(0.75)
        waitFor { decks[0].positionSec >= 0.75 }
    }

    @Test fun `eq is applied to the output`() {
        engine.setEq(EqProfile(limiter = false, subsonic = false, preampDb = -6.0206, bands = emptyList()))
        engine.play(AudioSource("5", "x"))
        waitFor { sink.samples.size > 4000 }
        assertEquals(0.25f, sink.samples[3000], 1e-3f)
    }

    @Test fun `buffering clears once audio flows`() {
        engine.play(AudioSource("5", "x"))
        waitFor { sink.samples.size > 4000 }
        waitFor { !engine.state.value.buffering }
        engine.seek(0.5)
        waitFor(2000) { !engine.state.value.buffering && engine.state.value.playing }
    }

    @Test fun `volume uses cubic curve`() {
        engine.volume = 0.5f
        engine.play(AudioSource("5", "x"))
        waitFor { sink.samples.size > 2000 }
        assertEquals(0.5f * 0.125f, sink.samples[1000], 1e-6f)
    }
}
