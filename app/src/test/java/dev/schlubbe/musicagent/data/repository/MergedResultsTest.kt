package dev.schlubbe.musicagent.data.repository

import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import org.junit.Assert.assertEquals
import org.junit.Test

class MergedResultsTest {
    private fun t(source: String, id: String, title: String, artist: String) =
        TrackResultDto(source, id, title, artist, null, null, null, "https://x/$id")
    private fun a(source: String, name: String, followers: String?) =
        ArtistResultDto(source, name, name, null, followers, "https://x/$name")

    @Test fun `same song from both sources appears once, YouTube Music first`() {
        val sc = listOf(
            t("soundcloud", "1", "Schüsse in die Luft (Official Video)", "Kraftklub"),
            t("soundcloud", "2", "Nur auf SoundCloud", "Kraftklub"),
        )
        val yt = listOf(
            t("ytmusic", "a", "Schüsse in die Luft", "Kraftklub - Topic"),
            t("ytmusic", "b", "Zwei Dosen Sprite (Live)", "Kraftklub"),
            t("ytmusic", "c", "Zwei Dosen Sprite", "Kraftklub"),
        )
        assertEquals(listOf("a", "b", "c", "2"), MergedResults.tracks(sc, yt).map { it.sourceId })
    }

    @Test fun `namesake soundcloud accounts are dropped`() {
        val sc = listOf(a("soundcloud", "Kraftklub", "17.4K"), a("soundcloud", "Michael Kraftklub", "6"), a("soundcloud", "Big DJ", "2.1K"), a("soundcloud", "kraftklub24", "1"))
        val yt = listOf(a("ytmusic", "Kraftklub", "436K"), a("ytmusic", "KUMMER", "10.1K"))
        assertEquals(listOf("ytmusic:Kraftklub", "ytmusic:KUMMER", "soundcloud:Big DJ"), MergedResults.artists("Kraftklub", sc, yt).map { "${it.source}:${it.name}" })
        assertEquals(listOf("soundcloud:kraftklub24"), MergedResults.artists("kraftklub24", sc.takeLast(1), emptyList()).map { "${it.source}:${it.name}" })
    }

    @Test fun `follower counts parse`() {
        assertEquals(17_400L, MergedResults.followers("17.4K"))
        assertEquals(1_200_000L, MergedResults.followers("1.2M"))
        assertEquals(6L, MergedResults.followers("6"))
        assertEquals(null, MergedResults.followers(null))
    }
}
