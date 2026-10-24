package dev.schlubbe.musicagent.data.recommend

import dev.schlubbe.musicagent.data.local.entity.PlayEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSignalsTest {
    private val now = 1_000L * 24 * 3600_000
    private val day = 24 * 3600_000L

    private fun play(track: String, artist: String, ago: Long, reason: EndReason) =
        PlayEventEntity(trackId = track, artist = artist, startedAt = now - ago, listenedMs = 0, durationMs = 200_000, endReason = reason.name)

    @Test fun `ends are classified by how long the track was heard`() {
        assertEquals(EndReason.SKIP_EARLY, PlaybackSignals.classifyEnd(29_000, 180_000, natural = false))
        assertEquals(EndReason.SKIP_LATE, PlaybackSignals.classifyEnd(31_000, 180_000, natural = false))
        assertEquals(EndReason.COMPLETE, PlaybackSignals.classifyEnd(150_000, 180_000, natural = false))
        assertEquals(EndReason.COMPLETE, PlaybackSignals.classifyEnd(5_000, 180_000, natural = true))
        // a 60 s jingle heard for 20 s was given a fair chance
        assertEquals(EndReason.SKIP_LATE, PlaybackSignals.classifyEnd(20_000, 60_000, natural = false))
    }

    @Test fun `skipped artists lose affinity and early skipped tracks are remembered`() {
        val s = PlaybackSignals(
            listOf(
                play("a1", "Liked", day, EndReason.COMPLETE), play("a2", "Liked", 2 * day, EndReason.COMPLETE),
                play("b1", "Skipped", day, EndReason.SKIP_EARLY), play("b2", "Skipped", day, EndReason.SKIP_EARLY),
            ),
            now,
        )
        assertTrue(s.artistAffinity.getValue("liked") > 0)
        assertTrue(s.artistAffinity.getValue("skipped") < 0)
        assertEquals(setOf("b1", "b2"), s.skippedTracks)
        assertEquals(0.5, s.skipRatio("Skipped"), 1e-9)
        assertEquals(0.0, s.skipRatio("Liked"), 1e-9)
    }

    @Test fun `a track heard out after an early skip is no longer excluded`() {
        val s = PlaybackSignals(listOf(play("t", "A", 3 * day, EndReason.SKIP_EARLY), play("t", "A", day, EndReason.COMPLETE)), now)
        assertTrue(s.skippedTracks.isEmpty())
    }

    @Test fun `on repeat needs three full plays within a month`() {
        val s = PlaybackSignals(
            (1..3).map { play("r", "A", it * day, EndReason.COMPLETE) } + (1..2).map { play("x", "B", it * day, EndReason.COMPLETE) },
            now,
        )
        assertEquals(listOf("r"), s.onRepeat)
    }

    @Test fun `artist names meet under one key`() {
        assertEquals("daft punk", PlaybackSignals.normalizeArtist("Daft Punk - Topic"))
    }
}
