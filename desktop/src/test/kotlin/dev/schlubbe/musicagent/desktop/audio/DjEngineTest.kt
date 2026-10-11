package dev.schlubbe.musicagent.desktop.audio

import dev.schlubbe.musicagent.playback.eq.EqProfile
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deck playing a short tone burst on every beat ([beatSec] apart, first at [firstSec]),
 * silence in between: a click track whose beat positions are known exactly.
 */
class BeatDeck(private val beatSec: Double, private val firstSec: Double, private val hz: Double, private val seconds: Double) : Deck {
    @Volatile override var source: AudioSource? = null
    @Volatile private var pos = 0L
    override var error: String? = null
    private val total get() = (seconds * SAMPLE_RATE).toLong()
    override fun load(source: AudioSource) { this.source = source; pos = (source.startSec * SAMPLE_RATE).toLong() }
    override fun seek(sec: Double) { pos = (sec * SAMPLE_RATE).toLong() }
    override fun stop() { source = null; pos = 0 }
    override fun read(out: FloatArray, frames: Int): Int {
        if (source == null) return 0
        val n = minOf(frames.toLong(), total - pos).toInt().coerceAtLeast(0)
        for (i in 0 until n) {
            val v = sample((pos + i) / SAMPLE_RATE)
            out[2 * i] = v; out[2 * i + 1] = v
        }
        pos += n
        return n
    }
    fun sample(t: Double): Float {
        if (t < firstSec) return 0f
        val inBeat = (t - firstSec) % beatSec
        if (inBeat >= BURST) return 0f
        return (0.3 * sin(PI * inBeat / BURST) * sin(2 * PI * hz * inBeat)).toFloat()
    }
    override fun available() = (total - pos).toInt()
    override val ended get() = source != null && pos >= total
    override val positionSec get() = pos / SAMPLE_RATE
    override val durationSec get() = if (source != null) seconds else null
    override fun close() {}

    companion object { const val BURST = 0.02 }
}

/** Records both channels, faster than real time. */
class StereoCapture : PcmSink {
    val left = CopyOnWriteArrayList<Float>()
    override fun write(buf: FloatArray, frames: Int) { val a = ArrayList<Float>(frames); for (i in 0 until frames) a += buf[2 * i]; left.addAll(a) }
    override fun start() {}
    override fun stop() {}
    override fun flush() {}
    override val latencySec = 0.0
    override fun close() {}
}

class DjEngineTest {
    private val sink = StereoCapture()
    private val events = CopyOnWriteArrayList<String>()
    private lateinit var engine: AudioEngine

    @AfterTest fun tearDown() { if (::engine.isInitialized) engine.close() }

    private fun waitFor(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) { check(System.currentTimeMillis() < end) { "timeout; events=$events" }; Thread.sleep(5) }
    }

    /** Energy-weighted centre (output frame) of each burst in [from, to). */
    private fun burstCentres(x: List<Float>, from: Int, to: Int): List<Double> {
        val res = mutableListOf<Double>()
        var i = from
        while (i < to) {
            if (abs(x[i]) > 0.01f) {
                var e = 0.0; var c = 0.0; var j = i
                var quiet = 0
                while (j < to && quiet < 600) { val v = x[j].toDouble(); if (abs(v) > 0.003) quiet = 0 else quiet++; e += v * v; c += v * v * j; j++ }
                res += c / e
                i = j
            } else i++
        }
        return res
    }

    @Test fun `isolator is flat with all bands at 0 dB and kills a band completely`() {
        fun gainDb(hz: Double, low: Double, mid: Double, high: Double): Double {
            val fx = DeckFx()
            fx.set(FxParam.EQ_LOW, low); fx.set(FxParam.EQ_MID, mid); fx.set(FxParam.EQ_HIGH, high)
            val n = 48_000
            val buf = FloatArray(n * 2) { (sin(2 * PI * hz * (it / 2) / SAMPLE_RATE) * 0.5).toFloat() }
            val sink = FloatArray(n * 2)
            var i = 0
            while (i < n) { fx.process(buf, sink, i, minOf(1024, n - i)); i += 1024 }
            val rms = sqrt((n / 2 until n).sumOf { buf[2 * it].toDouble() * buf[2 * it] } / (n / 2))
            return 20 * log10(rms / (0.5 / sqrt(2.0)))
        }
        for (hz in listOf(40.0, 200.0, 1000.0, 2500.0, 8000.0)) assertEquals(0.0, gainDb(hz, 0.0, 0.0, 0.0), 0.1, "flat at $hz Hz")
        assertTrue(gainDb(50.0, EQ_KILL_DB, 0.0, 0.0) < -40, "bass kill")
        assertEquals(0.0, gainDb(6000.0, EQ_KILL_DB, 0.0, 0.0), 0.2)
        assertTrue(gainDb(8000.0, 0.0, 0.0, EQ_KILL_DB) < -35, "high kill")
    }

    @Test fun `lanes interpolate with curves and log scale for filters`() {
        val vol = Lane(DeckRole.OUT, FxParam.VOLUME, listOf(LanePoint(0.0, 1.0), LanePoint(8.0, 0.0, Curve.EASE_IN)))
        assertEquals(1.0, vol.valueAt(-1.0)); assertEquals(0.0, vol.valueAt(9.0))
        assertEquals(kotlin.math.cos(PI / 4), vol.valueAt(4.0), 1e-9) // equal power at the middle
        val hp = Lane(DeckRole.OUT, FxParam.HIGHPASS, listOf(LanePoint(0.0, 20.0), LanePoint(4.0, 2000.0)))
        assertEquals(200.0, hp.valueAt(2.0), 1e-6) // geometric middle
        val step = Lane(DeckRole.IN, FxParam.EQ_LOW, listOf(LanePoint(0.0, EQ_KILL_DB), LanePoint(8.0, 0.0, Curve.STEP)))
        assertEquals(EQ_KILL_DB, step.valueAt(7.99)); assertEquals(0.0, step.valueAt(8.0))
    }

    @Test fun `synced transition locks the incoming beats onto the outgoing beats`() {
        assertTrue(TimeStretcher.available, "native library must be built")
        val outBeat = 60.0 / 126 // outgoing 126 BPM
        val inBeat = 60.0 / 120 // incoming 120 BPM, stretched by 5 %
        val decks = ArrayDeque(listOf(BeatDeck(outBeat, 0.25, 300.0, 40.0), BeatDeck(inBeat, 0.1, 3000.0, 60.0)))
        engine = AudioEngine({ decks.removeFirst() }, sink).apply {
            volume = 1f
            normalize = false
            setEq(EqProfile(enabled = false))
            listener = object : EngineListener {
                override fun onStarted(source: AudioSource, automatic: Boolean) { events += "start:${source.id}" }
            }
        }
        val outStart = 0.25 + 16 * outBeat // beat 16 of the outgoing track
        val inCue = 0.1 + 4 * inBeat // beat 4 of the incoming track
        val length = 16.0
        val plan = TransitionPlan(
            "b", outStart, outBeat, inCue, inBeat, length,
            // the outgoing deck is muted at the start, so only the incoming bursts are heard
            listOf(Lane(DeckRole.OUT, FxParam.VOLUME, listOf(LanePoint(0.0, 0.0)))),
            sync = true, releaseBeats = 8.0, style = "test",
        )
        engine.play(AudioSource("a", "x"))
        engine.preloadNext(AudioSource("b", "y"), plan)
        waitFor { "start:b" in events }
        waitFor { engine.transition.value.phase == TransitionStatus.Phase.NONE && sink.left.size > ((outStart + 40 * outBeat) * SAMPLE_RATE) }
        val x = sink.left.toList()
        val startFrame = (outStart * SAMPLE_RATE).toInt()
        // during the transition every incoming burst must sit on an outgoing beat
        val centres = burstCentres(x, startFrame + (2 * outBeat * SAMPLE_RATE).toInt(), startFrame + ((length - 1.5) * outBeat * SAMPLE_RATE).toInt())
        assertTrue(centres.size >= 10, "found ${centres.size} bursts")
        val burstCentre = BeatDeck.BURST / 2 * SAMPLE_RATE // within a beat, as heard at the synced tempo
        val errorsMs = centres.map { c ->
            val beatPos = (c - 0.25 * SAMPLE_RATE - burstCentre * (outBeat / inBeat)) / (outBeat * SAMPLE_RATE)
            (beatPos - Math.round(beatPos)) * outBeat * 1000
        }
        assertTrue(errorsMs.all { abs(it) < 4.0 }, "beat errors ms: ${errorsMs.map { "%.1f".format(it) }}")
        // and the incoming track plays at the outgoing tempo
        val spacing = centres.zipWithNext { a, b -> b - a }.average() / SAMPLE_RATE
        assertEquals(outBeat, spacing, 0.002)
        // after the release it is back at its own tempo
        assertEquals(1.0, engine.currentRate, 1e-9)
    }

    @Test fun `echo keeps ringing after the outgoing fader closed`() {
        val decks = ArrayDeque(listOf(BeatDeck(0.5, 0.0, 500.0, 20.0), BeatDeck(0.5, 0.0, 500.0, 20.0)))
        engine = AudioEngine({ decks.removeFirst() }, sink).apply {
            volume = 1f; normalize = false; setEq(EqProfile(enabled = false))
            listener = object : EngineListener { override fun onStarted(source: AudioSource, automatic: Boolean) { events += "start:${source.id}" } }
        }
        val plan = TransitionPlan(
            "b", 4.0, 0.5, 0.0, 0.5, 8.0,
            listOf(
                Lane(DeckRole.OUT, FxParam.ECHO, listOf(LanePoint(0.0, 0.0), LanePoint(1.0, 1.0), LanePoint(2.0, 0.0, Curve.STEP))),
                Lane(DeckRole.OUT, FxParam.VOLUME, listOf(LanePoint(0.0, 1.0), LanePoint(2.0, 0.0, Curve.STEP))),
                Lane(DeckRole.IN, FxParam.VOLUME, listOf(LanePoint(0.0, 0.0))),
            ),
            sync = false, releaseBeats = null, echoBeats = 0.75,
        )
        engine.play(AudioSource("a", "x"))
        engine.preloadNext(AudioSource("b", "y"), plan)
        waitFor { sink.left.size > 9 * SAMPLE_RATE }
        val x = sink.left.toList()
        // beats 3..6 after the start: both decks are silent, only echo repeats remain
        val tail = x.subList(((4.0 + 1.6) * SAMPLE_RATE).toInt(), ((4.0 + 3.0) * SAMPLE_RATE).toInt())
        assertTrue(tail.maxOf { abs(it) } > 0.02, "no echo tail")
    }
}
