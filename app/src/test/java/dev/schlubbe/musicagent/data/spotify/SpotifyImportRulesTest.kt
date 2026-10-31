package dev.schlubbe.musicagent.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyImportRulesTest {

    @Test fun `playlist names never collide with existing ones`() {
        assertEquals("Chill", SpotifyImportRules.playlistName("Chill", listOf("Lernen")))
        assertEquals("Chill (Spotify)", SpotifyImportRules.playlistName("Chill", listOf("chill")))
        assertEquals("Chill (Spotify 2)", SpotifyImportRules.playlistName("Chill", listOf("Chill", "Chill (Spotify)")))
        assertEquals("Spotify-Playlist", SpotifyImportRules.playlistName("  ", emptyList()))
    }

    @Test fun `not-found labels name title and artists`() {
        assertEquals("Song – A, B", SpotifyImportRules.label(SpotifyTrack("1", "Song", listOf("A", "B"), null, null)))
        assertEquals("Song", SpotifyImportRules.label(SpotifyTrack("1", "Song", emptyList(), null, null)))
    }
}
