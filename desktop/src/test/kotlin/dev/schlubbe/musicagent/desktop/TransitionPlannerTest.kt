package dev.schlubbe.musicagent.desktop

import dev.schlubbe.musicagent.desktop.audio.BeatGrid
import dev.schlubbe.musicagent.desktop.audio.Cue
import dev.schlubbe.musicagent.desktop.audio.DeckRole
import dev.schlubbe.musicagent.desktop.audio.FxParam
import dev.schlubbe.musicagent.desktop.audio.Lane
import dev.schlubbe.musicagent.desktop.audio.LanePoint
import dev.schlubbe.musicagent.desktop.audio.MusicalKey
import dev.schlubbe.musicagent.desktop.audio.Section
import dev.schlubbe.musicagent.desktop.audio.TrackStructure
import dev.schlubbe.musicagent.desktop.data.TrackAnalysis
import dev.schlubbe.musicagent.desktop.playback.TransitionPlanner
import dev.schlubbe.musicagent.desktop.playback.TransitionRecipe
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TransitionPlannerTest {
    /** A 96-bar dance track: 16-bar intro, body, 16-bar outro; bar 1 at [firstMs]. */
    private fun track(bpm: Double, firstMs: Double = 100.0, camelot: String = "8A", introBars: Int = 16, outroBars: Int = 16): TrackAnalysis {
        val beat = 60_000 / bpm
        val bar = 4 * beat
        fun at(b: Int) = (firstMs + (b - 1) * bar).toLong()
        val sections = listOf(
            Section(1, introBars, at(1), "intro", 0.6),
            Section(1 + introBars, 96 - introBars - outroBars, at(1 + introBars), "drop", 1.0),
            Section(97 - outroBars, outroBars, at(97 - outroBars), "outro", 0.6),
        )
        val cues = listOf(Cue("mix_in", at(1), 1), Cue("drop", at(1 + introBars), 1 + introBars), Cue("outro_start", at(97 - outroBars), 97 - outroBars),
            Cue("mix_out", at(97 - outroBars), 97 - outroBars), Cue("last_beat", at(97), 97))
        return TrackAnalysis(0, at(97), grid = BeatGrid(bpm, firstMs, 0, 1f, 1.0), structure = TrackStructure(sections, cues, 96),
            key = MusicalKey("x", camelot, 0.5), version = TrackAnalysis.CURRENT_VERSION)
    }

    private fun onDownbeat(sec: Double, a: TrackAnalysis): Boolean {
        val g = a.grid!!
        val bar = 4 * g.beatMs / 1000
        val r = ((sec - g.firstBeatMs / 1000) % bar + bar) % bar
        return r < 1e-6 || bar - r < 1e-6
    }

    @Test fun `auto picks a synced blend that ends before the outgoing beat ends`() {
        val out = track(128.0)
        val inn = track(124.0, firstMs = 350.0)
        val p = assertNotNull(TransitionPlanner.plan(out, inn, "b", TransitionRecipe(), positionSec = 10.0, outDurationSec = 190.0, canSync = true))
        assertEquals("blend", p.plan.style)
        assertTrue(p.plan.sync)
        assertEquals(64.0, p.plan.lengthBeats) // 16 bars: the outro and intro are 16 bars long
        assertTrue(onDownbeat(p.plan.outStartSec, out), "start ${p.plan.outStartSec} is not on a downbeat")
        // starts at the outro (bar 81), which leaves exactly 16 bars
        assertEquals((100 + 80 * 4 * 60_000 / 128.0) / 1000, p.plan.outStartSec, 1e-6)
        assertEquals(0.35, p.plan.inCueSec, 1e-6) // incoming bar 1
        assertEquals(60 / 124.0, p.plan.inBeatSec, 1e-9)
        assertEquals(32.0, p.plan.releaseBeats)
        // the incoming bass is cut until the swap
        val bass = p.plan.lanes.first { it.deck == DeckRole.IN && it.param == FxParam.EQ_LOW }
        assertTrue(bass.valueAt(10.0) <= -40)
    }

    @Test fun `far apart tempos fall back to an unsynced echo out`() {
        val p = assertNotNull(TransitionPlanner.plan(track(128.0), track(100.0), "b", TransitionRecipe(), 10.0, 190.0, canSync = true))
        assertEquals("echo_out", p.plan.style)
        assertFalse(p.plan.sync)
        assertTrue(p.summary["note"].toString().contains("no sync"))
        // unsynced, the incoming deck runs at its own rate for 4 outgoing beats until the
        // cut: what is heard then is a downbeat of the incoming track
        val heard = p.plan.inCueSec + 4 * p.plan.outBeatSec
        assertEquals(0.0, ((heard - 0.1) % 2.4 + 2.4) % 2.4, 1e-6)
        assertEquals(Math.round(heard * 100) / 100.0, p.summary["incoming_from_sec"])
    }

    @Test fun `synced echo out pre-rolls by incoming beats`() {
        val p = assertNotNull(TransitionPlanner.plan(track(128.0), track(126.0, firstMs = 3000.0), "b", TransitionRecipe(style = "echo_out"), 10.0, 190.0, canSync = true))
        assertTrue(p.plan.sync)
        // stretched to the outgoing tempo, 4 outgoing beats play 4 incoming beats
        val heard = p.plan.inCueSec + 4 * p.plan.inBeatSec
        assertEquals(3.0, heard, 1e-6)
    }

    @Test fun `half tempo wrap moves the incoming cue by one real bar`() {
        // the cue is at 0.1 s, too early for the pre-roll: it moves to the next incoming bar
        val p = assertNotNull(TransitionPlanner.plan(track(140.0), track(71.0), "b", TransitionRecipe(style = "echo_out"), 10.0, 190.0, canSync = true))
        assertTrue(p.plan.sync)
        val heard = p.plan.inCueSec + 4 * p.plan.inBeatSec
        val inBar = 4 * 60 / 71.0
        assertEquals(0.1 + inBar, heard, 1e-6)
        assertTrue(p.plan.inCueSec >= 0)
    }

    @Test fun `unknown end of the outgoing track means no plan`() {
        val g = BeatGrid(128.0, 100.0, 0, 1f, 1.0)
        val noStructure = TrackAnalysis(0, 100_000, grid = g)
        assertEquals(null, TransitionPlanner.plan(noStructure, track(128.0), "b", TransitionRecipe(), 10.0, null, true))
        val p = assertNotNull(TransitionPlanner.plan(noStructure, track(128.0), "b", TransitionRecipe(), 10.0, 200.0, true))
        assertTrue(p.plan.outStartSec + p.plan.lengthBeats * p.plan.outBeatSec <= 200.0 + 1e-6)
    }

    @Test fun `empty structures plan without errors`() {
        val empty = TrackStructure(emptyList(), emptyList(), 0)
        val a = TrackAnalysis(0, 100_000, grid = BeatGrid(124.0, 100.0, 0, 1f, 1.0), structure = empty)
        val p = assertNotNull(TransitionPlanner.plan(a, a.copy(), "b", TransitionRecipe(), 10.0, 200.0, true))
        assertTrue(onDownbeat(p.plan.outStartSec, a))
        assertNotNull(TransitionPlanner.plan(a, a.copy(), "b", TransitionRecipe(style = "echo_out", inCue = "first_beat"), 10.0, 200.0, true))
    }

    @Test fun `transitions are fitted before the outgoing last beat`() {
        val out = track(120.0, firstMs = 0.0) // 2 s bars, last beat at 192 s
        // automatic: 182 s lead leaves 5 bars, the 16-bar blend shrinks to them
        val p = assertNotNull(TransitionPlanner.plan(out, track(120.0), "b", TransitionRecipe(), positionSec = 180.0, outDurationSec = 200.0, canSync = true))
        assertEquals(182.0, p.plan.outStartSec, 1e-9)
        assertEquals(5 * 4.0, p.plan.lengthBeats)
        // a live transition without a length shrinks as well
        val now = assertNotNull(TransitionPlanner.plan(out, track(120.0), "b", TransitionRecipe(style = "blend", outCue = "next_bar"), 180.0, 200.0, true))
        assertEquals(5, now.summary["bars"])
        // explicit cue and length that do not fit, or a cue past the end: errors
        assertFailsWith<IllegalStateException> { TransitionPlanner.plan(out, track(120.0), "b", TransitionRecipe(bars = 16, outCue = "next_bar"), 180.0, 200.0, true) }
        assertFailsWith<IllegalStateException> { TransitionPlanner.plan(out, track(120.0), "b", TransitionRecipe(outCue = "bar:999"), 10.0, 200.0, true) }
        // echo out keeps its length and only needs its start bar
        val echo = assertNotNull(TransitionPlanner.plan(out, track(120.0), "b", TransitionRecipe(style = "echo_out"), 187.5, 200.0, true))
        assertEquals(190.0, echo.plan.outStartSec, 1e-9)
        assertEquals(8.0, echo.plan.lengthBeats)
        // nothing fits any more: plain crossfade
        assertEquals(null, TransitionPlanner.plan(out, track(120.0), "b", TransitionRecipe(), 190.5, 200.0, true))
    }

    @Test fun `a named outgoing cue means its next occurrence`() {
        val base = track(120.0, firstMs = 0.0)
        val s = base.structure!!
        val out = base.copy(structure = s.copy(cues = (s.cues + Cue("drop", 128_000, 65)).sortedBy { it.ms }))
        val p = assertNotNull(TransitionPlanner.plan(out, track(120.0), "b", TransitionRecipe(bars = 4, outCue = "drop"), 10.0, 200.0, true))
        assertEquals(32.0, p.plan.outStartSec, 1e-9) // bar 17, not the last drop at bar 65
        val later = assertNotNull(TransitionPlanner.plan(out, track(120.0), "b", TransitionRecipe(bars = 4, outCue = "drop"), 40.0, 200.0, true))
        assertEquals(128.0, later.plan.outStartSec, 1e-9)
    }

    @Test fun `half tempo tracks sync on every other beat`() {
        val p = assertNotNull(TransitionPlanner.plan(track(140.0), track(71.0), "b", TransitionRecipe(style = "bass_swap"), 10.0, 190.0, canSync = true))
        assertTrue(p.plan.sync)
        assertEquals(60 / 142.0, p.plan.inBeatSec, 1e-9)
    }

    @Test fun `harmonic mixing transposes a clashing key`() {
        val p = assertNotNull(TransitionPlanner.plan(track(128.0, camelot = "8A"), track(126.0, camelot = "3A"), "b", TransitionRecipe(harmonic = true), 10.0, 190.0, canSync = true))
        assertEquals(-1f, p.plan.semitones)
    }

    @Test fun `live transition starts on the next bar after the lead time, custom lanes replace the style's`() {
        val out = track(120.0, firstMs = 0.0)
        val custom = Lane(DeckRole.OUT, FxParam.HIGHPASS, listOf(LanePoint(0.0, 20.0), LanePoint(16.0, 800.0)))
        val p = assertNotNull(TransitionPlanner.plan(out, track(120.0), "b",
            TransitionRecipe(style = "filter", bars = 4, outCue = "next_bar", lanes = listOf(custom)), positionSec = 30.3, outDurationSec = 200.0, canSync = true))
        // lead time 2 s: 32.3 s rounds up to the bar at 34 s (2 s bars at 120 BPM)
        assertEquals(34.0, p.plan.outStartSec, 1e-9)
        assertEquals(16.0, p.plan.lengthBeats)
        assertEquals(listOf(custom), p.plan.lanes.filter { it.deck == DeckRole.OUT && it.param == FxParam.HIGHPASS })
        assertTrue(p.plan.lanes.any { it.deck == DeckRole.IN && it.param == FxParam.LOWPASS })
    }

    @Test fun `incoming lanes return to neutral after the transition`() {
        val inLane = Lane(DeckRole.IN, FxParam.LOWPASS, listOf(LanePoint(0.0, 300.0), LanePoint(8.0, 2000.0)))
        val p = assertNotNull(TransitionPlanner.plan(track(120.0), track(120.0), "b", TransitionRecipe(style = "fade", bars = 2, lanes = listOf(inLane)), 10.0, 200.0, true))
        val l = p.plan.lanes.first { it.deck == DeckRole.IN && it.param == FxParam.LOWPASS }
        assertEquals(20_000.0, l.valueAt(100.0))
        assertTrue(abs(p.plan.endBeat - 12.0) < 1e-9)
    }

    @Test fun `no grid means no plan`() {
        val plain = TrackAnalysis(1000, 100_000)
        assertEquals(null, TransitionPlanner.plan(plain, track(120.0), "b", TransitionRecipe(), 0.0, 100.0, true))
    }
}
