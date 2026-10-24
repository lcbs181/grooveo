package dev.schlubbe.musicagent.desktop.playback

import dev.schlubbe.musicagent.desktop.audio.BeatGrid
import dev.schlubbe.musicagent.desktop.audio.MusicalKey
import dev.schlubbe.musicagent.desktop.data.TrackAnalysis
import kotlin.test.Test
import kotlin.test.assertEquals

class TransitionScoreTest {
    private fun a(bpm: Double, camelot: String) =
        TrackAnalysis(0, 0, grid = BeatGrid(bpm, 0.0, 0, 1f, 1.0), key = MusicalKey(camelot, camelot, 1.0), version = TrackAnalysis.CURRENT_VERSION)

    @Test fun `camelot neighbours mix, distant keys do not`() {
        assertEquals(1.0, TransitionScore.key("8A", "8A"))
        assertEquals(1.0, TransitionScore.key("8A", "9A"))
        assertEquals(1.0, TransitionScore.key("8A", "8B"))
        assertEquals(0.5, TransitionScore.key("8A", "10A"))
        assertEquals(0.0, TransitionScore.key("8A", "2B"))
        assertEquals(0.5, TransitionScore.key(null, "2B"))
    }

    @Test fun `half and double time count as the same tempo`() {
        assertEquals(1.0, TransitionScore.tempo(128.0, 64.0))
        assertEquals(1.0, TransitionScore.tempo(128.0, 126.0))
        assertEquals(0.0, TransitionScore.tempo(128.0, 100.0))
    }

    @Test fun `the batch is ordered to flow from the playing track`() {
        val an = mapOf("far" to a(90.0, "2B"), "near" to a(127.0, "9A"), "none" to null)
        val out = TransitionScore.order(a(128.0, "8A"), listOf("far", "none", "near"), an::get)
        assertEquals("near", out.first())
    }

    @Test fun `without analyses the recommender order stays`() {
        assertEquals(listOf("a", "b", "c"), TransitionScore.order(null, listOf("a", "b", "c")) { null })
    }
}
