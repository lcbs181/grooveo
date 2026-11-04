package dev.schlubbe.musicagent.ui.spotify

import dev.schlubbe.musicagent.data.spotify.SpotifyExport
import dev.schlubbe.musicagent.data.spotify.SpotifyTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class AmazonCsvTest {

    private fun track(title: String, artist: String, album: String? = null) =
        SpotifyTrack(id = "amazon:$title", title = title, artists = listOf(artist), album = album, durationMs = null)

    @Test
    fun roundTripsThroughTheExportReader() {
        val csv = AmazonCsv.write(
            listOf(
                AmazonPlaylist("Meine Likes", listOf(track("Twoh", "B-Tight", "Heisse Ware"))),
                AmazonPlaylist("Party, laut", listOf(track("Song \"mit\" Zitat", "Tyler, The Creator"), track("Cool", "Dua Lipa"))),
            ),
        )
        val lib = SpotifyExport.read("amazon-music.csv", csv.byteInputStream())
        assertEquals(listOf("Twoh"), lib.likes.map { it.title })
        assertEquals(listOf("B-Tight"), lib.likes.single().artists)
        val (playlist, tracks) = lib.playlists.single()
        assertEquals("Party, laut", playlist.name)
        assertEquals(listOf("Song \"mit\" Zitat", "Cool"), tracks.map { it.title })
        assertEquals(listOf("Tyler, The Creator"), tracks.first().artists)
    }
}
