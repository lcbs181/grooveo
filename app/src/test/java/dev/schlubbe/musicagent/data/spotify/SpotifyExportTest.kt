package dev.schlubbe.musicagent.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SpotifyExportTest {

    private val exportifyHeader =
        "\"Track URI\",\"Track Name\",\"Artist URI(s)\",\"Artist Name(s)\",\"Album URI\",\"Album Name\",\"Album Artist URI(s)\"," +
            "\"Album Artist Name(s)\",\"Album Release Date\",\"Album Image URL\",\"Disc Number\",\"Track Number\",\"Track Duration (ms)\"," +
            "\"Track Preview URL\",\"Explicit\",\"Popularity\",\"ISRC\",\"Added By\",\"Added At\"\n"

    private fun exportifyRow(id: String, name: String, artists: String, album: String, ms: Long, added: String) =
        "\"spotify:track:$id\",\"$name\",\"spotify:artist:x\",\"$artists\",\"spotify:album:y\",\"$album\",\"\",\"\",\"2020\",\"\",\"1\",\"1\"," +
            "\"$ms\",\"\",\"false\",\"50\",\"\",\"spotify:user:me\",\"$added\"\n"

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            files.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun read(name: String, bytes: ByteArray) = SpotifyExport.read(name, bytes.inputStream())

    @Test fun `exportify zip gives likes, playlists and guessed artists`() {
        val liked = exportifyHeader +
            exportifyRow("1111111111111111111111", "Song A", "Kraftklub", "Album", 200000, "2024-01-01T00:00:00Z") +
            exportifyRow("2222222222222222222222", "Song \"\"B\"\"", "Kraftklub, Tones And I", "Album", 180000, "2024-01-02T00:00:00Z")
        val chill = exportifyHeader +
            exportifyRow("3333333333333333333333", "Song C", "Kraftklub", "Other", 1000, "") +
            exportifyRow("4444444444444444444444", "Song D", "Tyler\\, The Creator", "Other", 1000, "")
        val lib = read(
            "spotify_playlists.zip",
            zip("liked.csv" to liked, "chill_vibes.csv" to chill, "saved_albums.csv" to "\"Album Name\"\n\"x\"\n"),
        )

        assertEquals(2, lib.likes.size)
        assertEquals("1111111111111111111111", lib.likes[0].id)
        assertEquals("Song \"B\"", lib.likes[1].title)
        assertEquals(listOf("Kraftklub", "Tones And I"), lib.likes[1].artists)
        assertEquals(180000L, lib.likes[1].durationMs)
        assertEquals("2024-01-02T00:00:00Z", lib.likes[1].addedAt)

        assertEquals(1, lib.playlists.size)
        assertEquals("Chill vibes", lib.playlists[0].first.name)
        assertEquals(listOf("Tyler, The Creator"), lib.playlists[0].second[1].artists)

        assertEquals(listOf("Kraftklub"), lib.artists.map { it.name })
    }

    @Test fun `exportify german headers are understood`() {
        val csv = "\"Track-URI\",\"Track-Name\",\"Künstler-URI(s)\",\"Künstlername(n)\",\"Album-URI\",\"Album-Name\",\"Album-Künstler-URI(s)\",\"Album-Künstlername(n)\",\"Track-Dauer (ms)\",\"Hinzugefügt am\"\n" +
            "\"spotify:track:1111111111111111111111\",\"Lied\",\"spotify:artist:x\",\"Annenmaykantereit\",\"\",\"Platte\",\"\",\"Andere\",\"123\",\"2024\"\n"
        val lib = read("lieblingssongs.csv", csv.toByteArray())
        assertEquals(1, lib.likes.size)
        val t = lib.likes[0]
        assertEquals("Lied", t.title)
        assertEquals(listOf("Annenmaykantereit"), t.artists)
        assertEquals("Platte", t.album)
        assertEquals(123L, t.durationMs)
    }

    @Test fun `tunemymusic csv groups by playlist column`() {
        val csv = "Track name,Artist name,Album,Playlist name,Type,ISRC,Spotify - id\n" +
            "Bad Habits,Ed Sheeran,=,Liked Songs,Favorite,X,6PQ88X9TkUIAUIZJHW2upE\n" +
            "Levitating,Dua Lipa,Future Nostalgia,Party,Playlist,Y,463CkQjx2Zk1yXoBuierM9\n" +
            "\"Song, with comma\",Dua Lipa,,Party,Playlist,Z,\n"
        val lib = read("My Spotify Library.csv", csv.toByteArray())
        assertEquals(listOf("Bad Habits"), lib.likes.map { it.title })
        assertEquals("6PQ88X9TkUIAUIZJHW2upE", lib.likes[0].id)
        assertEquals("Party", lib.playlists.single().first.name)
        assertEquals(listOf("Levitating", "Song, with comma"), lib.playlists.single().second.map { it.title })
        assertTrue(lib.playlists.single().second[1].id.startsWith("t:"))
    }

    @Test fun `spotify account data zip`() {
        val library = """{"tracks":[{"artist":"Kraftklub","album":"Keine Nacht","track":"Songs für Liam","uri":"spotify:track:1111111111111111111111"}],
            "albums":[],"artists":[{"name":"Kraftklub","uri":"spotify:artist:a"},{"name":"Provinz","uri":"spotify:artist:b"}]}"""
        val playlists = """{"playlists":[{"name":"Sommer","lastModifiedDate":"2024-01-01","items":[
            {"track":{"trackName":"Wenn ich tanze","artistName":"Provinz","albumName":"X","trackUri":"spotify:track:2222222222222222222222"},"episode":null,"addedDate":"2023-06-01"},
            {"track":null,"episode":{"episodeName":"Pod"},"addedDate":"2023-06-01"}]}]}"""
        val lib = read(
            "my_spotify_data.zip",
            zip(
                "Spotify Account Data/YourLibrary.json" to library,
                "Spotify Account Data/Playlist1.json" to playlists,
                "Spotify Account Data/StreamingHistory_music_0.json" to "[]",
            ),
        )
        assertEquals(listOf("Songs für Liam"), lib.likes.map { it.title })
        assertEquals("Sommer", lib.playlists.single().first.name)
        assertEquals(1, lib.playlists.single().second.size)
        assertEquals("2023-06-01", lib.playlists.single().second[0].addedAt)
        assertEquals(listOf("Kraftklub", "Provinz"), lib.artists.map { it.name })
    }

    @Test fun `unknown files give an empty library`() {
        assertTrue(read("notes.txt", "hello world\nfoo".toByteArray()).isEmpty)
        assertTrue(read("x.zip", zip("readme.md" to "hi")).isEmpty)
    }

    @Test fun `csv parser handles quotes, newlines and semicolons`() {
        val rows = SpotifyExport.Csv.parse("a;b;c\r\n\"x;1\";\"line\nbreak\";\"say \"\"hi\"\"\"\r\n")
        assertEquals(listOf(listOf("a", "b", "c"), listOf("x;1", "line\nbreak", "say \"hi\"")), rows)
    }

    @Test fun `helpers`() {
        assertEquals("My chill mix", SpotifyExport.prettyFileName("my_chill_mix (1)"))
        assertEquals("abcdefghij1234567890AB", SpotifyExport.idFrom("spotify:track:abcdefghij1234567890AB", "x", emptyList()))
        assertEquals("t:song|a,b", SpotifyExport.idFrom(null, "Song", listOf("A", "B")))
        assertNull(SpotifyExport.Columns.detect(listOf("foo", "bar")))
    }
}
