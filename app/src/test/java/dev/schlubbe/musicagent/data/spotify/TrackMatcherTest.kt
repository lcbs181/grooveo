package dev.schlubbe.musicagent.data.spotify

import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatcherTest {

    private fun spotify(title: String, vararg artists: String, sec: Int = 200) =
        SpotifyTrack("sp", title, artists.toList(), null, sec * 1000L)

    private fun hit(id: String, title: String, artist: String?, sec: Int?) =
        TrackResultDto("ytmusic", id, title, artist, null, sec, null, "https://music.youtube.com/watch?v=$id")

    @Test fun `the query drops version noise and adds the first artist`() {
        assertEquals("Bohemian Rhapsody Queen", TrackMatcher.query(spotify("Bohemian Rhapsody - Remastered 2011", "Queen")))
        assertEquals("Mockingbird Eminem", TrackMatcher.query(spotify("Mockingbird (feat. X)", "Eminem", "X")))
    }

    @Test fun `exact match with a remaster suffix and topic channel is found`() {
        val src = spotify("Bohemian Rhapsody - Remastered 2011", "Queen", sec = 354)
        val best = TrackMatcher.best(
            src,
            listOf(
                hit("a", "Don't Stop Me Now", "Queen", 209),
                hit("b", "Bohemian Rhapsody", "Queen - Topic", 355),
            ),
        )
        assertEquals("b", best?.sourceId)
    }

    @Test fun `duration closeness decides between versions`() {
        val src = spotify("Blinding Lights", "The Weeknd", sec = 200)
        val best = TrackMatcher.best(
            src,
            listOf(
                hit("long", "Blinding Lights", "The Weeknd", 262),
                hit("right", "Blinding Lights", "The Weeknd", 203),
            ),
        )
        assertEquals("right", best?.sourceId)
    }

    @Test fun `variants are not taken for the original`() {
        val src = spotify("Levitating", "Dua Lipa", sec = 203)
        val best = TrackMatcher.best(
            src,
            listOf(
                hit("remix", "Levitating (Remix)", "Dua Lipa", 203),
                hit("sped", "Levitating - Sped Up", "Dua Lipa", 160),
                hit("orig", "Levitating", "Dua Lipa", 204),
            ),
        )
        assertEquals("orig", best?.sourceId)
        // ...but a remix is fine when the Spotify track is that remix
        val remix = spotify("Levitating (Remix)", "Dua Lipa", sec = 203)
        assertEquals("remix", TrackMatcher.best(remix, listOf(hit("remix", "Levitating (Remix)", "Dua Lipa", 203)))?.sourceId)
    }

    @Test fun `unrelated hits are rejected instead of importing a wrong song`() {
        val src = spotify("Ohne dich", "Rammstein", sec = 271)
        assertNull(TrackMatcher.best(src, listOf(hit("x", "Du hast", "Rammstein", 234), hit("y", "Sonne", "Rammstein", 272))))
        assertNull(TrackMatcher.best(src, emptyList()))
    }

    @Test fun `artist in the video title and accents still match`() {
        val src = spotify("Café del Mar", "Energy 52", sec = 400)
        val best = TrackMatcher.best(src, listOf(hit("v", "Energy 52 - Cafe Del Mar", "Some Uploader", 405)))
        assertEquals("v", best?.sourceId)
    }

    @Test fun `duration score`() {
        assertEquals(1.0, TrackMatcher.durationScore(200, 210)!!, 1e-9)
        assertEquals(0.3, TrackMatcher.durationScore(200, 230)!!, 1e-9)
        assertEquals(0.0, TrackMatcher.durationScore(200, 260)!!, 1e-9)
        assertNull(TrackMatcher.durationScore(200, 600))
        assertEquals(0.5, TrackMatcher.durationScore(null, 200)!!, 1e-9)
    }

    @Test fun `artists match by name, not just by rank`() {
        fun a(id: String, name: String) = ArtistResultDto("ytmusic", id, name, null, null, "https://x/$id")
        assertEquals("2", TrackMatcher.bestArtist("Beyoncé", listOf(a("1", "Beyoncé Tribute Band Live"), a("2", "Beyonce")))?.sourceId)
        assertEquals("t", TrackMatcher.bestArtist("Cro", listOf(a("t", "CRO - Topic")))?.sourceId)
        assertNull(TrackMatcher.bestArtist("Apache 207", listOf(a("x", "Apache Indian"))))
    }

    @Test fun `similarity handles scripts without spaces`() {
        assertTrue(TrackMatcher.similarity(TrackMatcher.normalize("夜に駆ける"), TrackMatcher.normalize("夜に駆ける")) > 0.99)
        assertTrue(TrackMatcher.similarity("abc", "xyz") < 0.1)
    }
}
