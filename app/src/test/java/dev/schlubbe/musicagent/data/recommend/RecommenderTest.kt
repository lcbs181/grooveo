package dev.schlubbe.musicagent.data.recommend

import dev.schlubbe.musicagent.data.local.entity.PlayEventEntity
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RecommenderTest {
    private val now = 1_000L * 24 * 3600_000

    private fun t(id: String, artist: String, title: String = "Song $id", sec: Int = 200) =
        TrackResultDto("ytmusic", id, title, artist, null, sec, null, "")

    private fun c(track: TrackResultDto, rank: Int = 0, origin: Origin = Origin.RELATED) = Candidate(track, rank, origin)

    private fun event(id: String, artist: String, reason: EndReason, ago: Long = 3600_000) =
        PlayEventEntity(trackId = "ytmusic:$id", artist = artist, startedAt = now - ago, listenedMs = 0, durationMs = 0, endReason = reason.name)

    private fun recommender(vararg events: PlayEventEntity) = Recommender(PlaybackSignals(events.toList(), now), Random(1))

    @Test fun `an early skipped track never comes back`() {
        val out = recommender(event("x", "A", EndReason.SKIP_EARLY)).rank(
            listOf(c(t("x", "A")), c(t("y", "B"))), emptySet(), emptySet(), emptyList(), 5,
        )
        assertEquals(listOf("y"), out.map { it.sourceId })
    }

    @Test fun `an artist the listener skips ranks below one they hear out`() {
        val events = (1..4).map { event("s$it", "Skipped", EndReason.SKIP_EARLY) } +
            (1..4).map { event("h$it", "Heard", EndReason.COMPLETE) }
        val out = recommender(*events.toTypedArray()).rank(
            listOf(c(t("a", "Skipped")), c(t("b", "Heard"))), emptySet(), emptySet(), emptyList(), 2,
        )
        assertEquals("b", out.first().sourceId)
    }

    @Test fun `no artist twice within three and at most twice within ten`() {
        val cands = (0 until 30).map { i -> c(t("$i", "Artist ${i % 3}"), rank = i / 3) } +
            (0 until 6).map { i -> c(t("x$i", "Other $i"), rank = 10) }
        val out = recommender().rank(cands, emptySet(), emptySet(), emptyList(), 10)
        val artists = out.map { it.artist }
        artists.windowed(3).forEach { w -> assertEquals(w.toString(), w.size, w.distinct().size) }
        artists.windowed(10).forEach { w -> assertTrue(w.groupingBy { it }.eachCount().values.all { it <= 2 }) }
    }

    @Test fun `re-uploads of one song and the queue are dropped`() {
        val out = recommender().rank(
            listOf(c(t("1", "Daft Punk", "One More Time")), c(t("2", "Daft Punk", "One More Time (Official Video)")), c(t("3", "B"))),
            emptySet(), setOf("ytmusic:3"), emptyList(), 5,
        )
        assertEquals(1, out.size)
    }

    @Test fun `loops and type beats are not music to play next`() {
        assertFalse(Recommender.isMusic(t("1", "A", "Lofi beats 1 hour")))
        assertFalse(Recommender.isMusic(t("2", "A", "Drake type beat")))
        assertFalse(Recommender.isMusic(t("3", "A", "Song", sec = 3 * 3600)))
        assertTrue(Recommender.isMusic(t("4", "A", "Hour of Need")))
    }

    @Test fun `the session's last artists are spaced from the first picks`() {
        val out = recommender().rank(
            listOf(c(t("a", "Same")), c(t("b", "Else"), rank = 5)), setOf("Same"), emptySet(), listOf("Same"), 2,
        )
        assertEquals("b", out.first().sourceId)
    }
}
