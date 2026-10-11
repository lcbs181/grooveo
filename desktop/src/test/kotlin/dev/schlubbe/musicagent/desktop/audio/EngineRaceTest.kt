package dev.schlubbe.musicagent.desktop.audio

import dev.schlubbe.musicagent.playback.eq.EqProfile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deck whose samples encode the source position ([left]/[right] of the time in seconds),
 * with gates that hold a load, a seek or the decoded audio back, and an optional slow read.
 */
class PosDeck(private val seconds: Double, private val left: (Double) -> Float, private val right: (Double) -> Float = left) : Deck {
    @Volatile override var source: AudioSource? = null
    @Volatile private var pos = 0L
    override val error: String? = null
    @Volatile var loadGate: CountDownLatch? = null
    @Volatile var seekGate: CountDownLatch? = null
    @Volatile var dataGate: CountDownLatch? = null
    /** Frames per read and a 1 ms delay per read (a slow decoder), 0 = instant. */
    @Volatile var slowChunk = 0
    private val total get() = (seconds * SAMPLE_RATE).toLong()
    private val held get() = (dataGate?.count ?: 0) > 0

    override fun load(source: AudioSource) { loadGate?.await(); this.source = source; pos = (source.startSec * SAMPLE_RATE).toLong() }
    override fun seek(sec: Double) { seekGate?.let { it.await(); return }; pos = (sec * SAMPLE_RATE).toLong() }
    override fun stop() { source = null; pos = 0 }
    override fun read(out: FloatArray, frames: Int): Int {
        if (source == null || held) return 0
        var want = frames
        if (slowChunk > 0) { Thread.sleep(1); want = minOf(want, slowChunk) }
        val n = minOf(want.toLong(), total - pos).toInt().coerceAtLeast(0)
        for (i in 0 until n) {
            val t = (pos + i) / SAMPLE_RATE
            out[2 * i] = left(t); out[2 * i + 1] = right(t)
        }
        pos += n
        return n
    }
    override fun available() = if (held || source == null) 0 else (total - pos).toInt()
    override val ended get() = source != null && pos >= total
    override val positionSec get() = pos / SAMPLE_RATE
    override val durationSec get() = if (source != null) seconds else null
    override fun close() {}
}

/** Records both channels. */
class TwoChannelCapture : PcmSink {
    private var data = FloatArray(1 shl 20)
    @Volatile var frames = 0
        private set
    @Synchronized override fun write(buf: FloatArray, frames: Int) {
        if ((this.frames + frames) * 2 > data.size) data = data.copyOf(data.size * 2)
        System.arraycopy(buf, 0, data, this.frames * 2, frames * 2)
        this.frames += frames
    }
    @Synchronized fun snapshot(): FloatArray = data.copyOf(frames * 2)
    override fun start() {}
    override fun stop() {}
    override fun flush() {}
    override val latencySec = 0.0
    override fun close() {}
}

class EngineRaceTest {
    private val sink = TwoChannelCapture()
    private val events = CopyOnWriteArrayList<String>()
    private lateinit var engine: AudioEngine

    @AfterTest fun tearDown() { if (::engine.isInitialized) engine.close() }

    private fun startEngine(vararg decks: Deck): AudioEngine {
        val q = ArrayDeque(decks.toList())
        engine = AudioEngine({ q.removeFirst() }, sink).apply {
            volume = 1f
            normalize = false
            setEq(EqProfile(enabled = false))
            listener = object : EngineListener {
                override fun onStarted(source: AudioSource, automatic: Boolean) { events += "start:${source.id}" }
                override fun onEnded(source: AudioSource) { events += "end:${source.id}" }
            }
        }
        return engine
    }

    private fun waitFor(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) { check(System.currentTimeMillis() < end) { "timeout; events=$events" }; Thread.sleep(2) }
    }

    private fun plan(outStart: Double, inCue: Double, lengthBeats: Double = 8.0, lanes: List<Lane> = emptyList()) =
        TransitionPlan("b", outStart, 0.5, inCue, 0.5, lengthBeats, lanes, sync = false, releaseBeats = null, style = "test")

    @Test fun `a transition never starts on the previous load of a deck that is reloading`() {
        val a = PosDeck(60.0, { 0f })
        val b = PosDeck(60.0, { t -> (t / 100).toFloat() })
        startEngine(a, b)
        val muteOut = listOf(Lane(DeckRole.OUT, FxParam.VOLUME, listOf(LanePoint(0.0, 0.0))))
        engine.play(AudioSource("a", "x"))
        engine.preloadNext(AudioSource("b", "y"), plan(50.0, 3.0, lanes = muteOut))
        waitFor { b.source != null && a.positionSec > 1.0 }
        // the new plan's load hangs (a slow decoder open) while its start time passes
        b.loadGate = CountDownLatch(1)
        val outStart = a.positionSec + 2.0
        engine.setTransition(plan(outStart, 10.0, lanes = muteOut))
        waitFor { a.positionSec > outStart + 1.0 }
        assertFalse("start:b" in events, "started on the old load: $events")
        b.loadGate!!.countDown()
        waitFor { "start:b" in events }
        val startFrame = sink.frames
        waitFor { sink.frames > startFrame + 2 * SAMPLE_RATE }
        val heard = sink.snapshot().filterIndexed { i, v -> i % 2 == 0 && v != 0f }
        assertTrue(heard.isNotEmpty())
        // the incoming deck plays from the new cue (10 s), never from the old one (3 s)
        assertTrue(heard.min() >= 0.0999f, "heard incoming audio from ${heard.min() * 100} s")
    }

    @Test fun `an unsynced transition waits for decoded audio instead of stalling the mixer`() {
        val a = PosDeck(60.0, { 0.2f })
        val b = PosDeck(60.0, { 0.4f }).apply { dataGate = CountDownLatch(1) }
        startEngine(a, b)
        engine.play(AudioSource("a", "x"))
        engine.preloadNext(AudioSource("b", "y"), plan(2.0, 0.0))
        // the outgoing track keeps playing past the planned start
        waitFor { a.positionSec > 4.0 }
        assertFalse("start:b" in events)
        assertEquals(TransitionStatus.Phase.ARMED, engine.transition.value.phase)
        b.dataGate!!.countDown()
        waitFor { "start:b" in events }
    }

    @Test fun `a preload that lands right after a hand-over is not stopped`() {
        val a = PosDeck(2.0, { 0.1f })
        val b = PosDeck(2.0, { 0.2f })
        startEngine(a, b)
        engine.play(AudioSource("a", "x"))
        engine.preloadNext(AudioSource("b", "y"))
        waitFor { b.source != null && a.positionSec > 0.2 }
        // keep the control thread busy until the gapless hand-over to b has happened
        a.seekGate = CountDownLatch(1)
        engine.seek(0.3)
        engine.preloadNext(AudioSource("c", "z"))
        waitFor { "start:b" in events }
        a.seekGate!!.countDown()
        // c was loaded into a's deck after the hand-over and must follow b
        waitFor { "start:c" in events || "end:b" in events }
        assertTrue("start:c" in events, "c lost: $events")
    }

    @Test fun `pausing during a transition keeps both decks in step`() {
        // left channel: outgoing position, right channel: incoming position
        val a = PosDeck(60.0, { t -> (t / 100).toFloat() }, { 0f }).apply { slowChunk = 256 }
        val b = PosDeck(60.0, { 0f }, { t -> (t / 100).toFloat() })
        startEngine(a, b)
        engine.play(AudioSource("a", "x"))
        engine.preloadNext(AudioSource("b", "y"), plan(1.0, 5.0, lengthBeats = 8.0))
        waitFor { engine.transition.value.phase == TransitionStatus.Phase.RUNNING }
        val rnd = Random(1)
        val end = System.currentTimeMillis() + 20_000
        while (engine.transition.value.phase == TransitionStatus.Phase.RUNNING && System.currentTimeMillis() < end) {
            engine.pause()
            Thread.sleep(rnd.nextLong(0, 3))
            engine.resume()
            Thread.sleep(rnd.nextLong(0, 6))
        }
        val x = sink.snapshot()
        val diffs = (0 until x.size / 2).filter { x[2 * it] > 0f && x[2 * it + 1] > 0f }.map { x[2 * it + 1] - x[2 * it] }
        assertTrue(diffs.size > SAMPLE_RATE, "transition too short: ${diffs.size}")
        // incoming minus outgoing source position stays the same (4 s) the whole time
        val spreadFrames = (diffs.max() - diffs.min()) * 100 * SAMPLE_RATE
        assertTrue(spreadFrames < 10, "decks drifted apart by $spreadFrames frames")
    }
}
