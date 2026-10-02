package dev.schlubbe.musicagent.desktop.data

import com.sun.net.httpserver.HttpServer
import dev.schlubbe.musicagent.data.extract.ResolvedStream
import dev.schlubbe.musicagent.data.extract.di.extractionHttpClient
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

fun track(id: String, artist: String = "Artist $id", source: String = "soundcloud") =
    TrackResultDto(source, id, "Title $id", artist, null, 180, null, "https://example.com/$id")

class LibraryStoreTest {
    private val dir = Files.createTempDirectory("grooveo-store").toFile()

    @Test fun `likes, playlists and persistence`() {
        val s = LibraryStore(dir)
        s.toggleLike(track("a")); s.toggleLike(track("b")); s.toggleLike(track("a"))
        assertEquals(listOf("soundcloud:b"), s.current.likes.map { it.track.key })
        val p = s.createPlaylist("  ", listOf(track("1"), track("2"), track("1")))
        assertEquals("Neue Playlist", p.name)
        assertEquals(2, p.tracks.size)
        s.addToPlaylist(p.id, listOf(track("2"), track("3")))
        s.movePlaylistTrack(p.id, 2, 0)
        s.removeFromPlaylist(p.id, 1)
        assertEquals(listOf("3", "2"), s.current.playlists.single().tracks.map { it.track.sourceId })
        s.updatePlaylist(p.id) { it.copy(name = "Gym", moodTags = listOf("workout")) }

        val reloaded = LibraryStore(dir)
        assertEquals(s.current, reloaded.current)
        reloaded.deletePlaylist(p.id)
        assertTrue(reloaded.current.playlists.isEmpty())
    }

    @Test fun `history dedupes, searches capped, listen stats per week`() = runBlocking {
        val s = LibraryStore(dir)
        s.recordPlay(track("a")); s.recordPlay(track("b")); s.recordPlay(track("a"))
        assertEquals(listOf("a", "b"), s.current.history.map { it.track.sourceId })
        assertEquals(listOf("soundcloud:a", "soundcloud:b"), s.observeRecentlyPlayed(10).first().map { it.id })
        assertEquals("Title a", s.getById("soundcloud:a")?.title)
        repeat(30) { s.addSearch("q$it") }
        s.addSearch("Q29")
        assertEquals(LibraryStore.SEARCHES_MAX, s.current.searches.size)
        assertEquals("Q29", s.current.searches.first())
        val today = LocalDate.of(2026, 10, 2)
        s.addListenSeconds(100, today.toString()); s.addListenSeconds(50, today.minusDays(6).toString()); s.addListenSeconds(999, today.minusDays(7).toString())
        assertEquals(150, s.weeklyListenSeconds(today))
    }

    @Test fun `follows, saved playlists and downloads dao`() = runBlocking {
        val s = LibraryStore(dir)
        val a = FollowedArtist("soundcloud", "x", "X", null)
        s.toggleFollow(a); assertTrue(s.isFollowing("soundcloud", "x")); s.toggleFollow(a); assertFalse(s.isFollowing("soundcloud", "x"))
        val sp = SavedPlaylist("ytmusic", "PL1", "Mix", null, "me", 3, "u")
        s.toggleSaved(sp); assertTrue(s.isSaved("ytmusic", "PL1"))
        val f = File(dir, "a.mp3").apply { writeText("x") }
        s.upsertDownload(DownloadRecord(track("a"), DownloadState.COMPLETED, 100, f.path))
        assertEquals(f.path, s.getByTrackId("soundcloud:a")?.mediaStoreUri)
        f.delete()
        assertNull(s.getByTrackId("soundcloud:a")?.mediaStoreUri, "missing file must not be offered for playback")
    }

    @Test fun `migrates first desktop build files`() {
        File(dir, "liked.json").writeText("""[{"source":"soundcloud","source_id":"u/t","title":"T","artist":"A","duration_sec":170,"thumbnail_url":null,"webpage_url":"w","is_drm_protected":false}]""")
        File(dir, "history.json").writeText("""[{"source":"ytmusic","source_id":"v1","title":"H","artist":"B","webpage_url":"w2"}]""")
        File(dir, "played_at.json").writeText("""{"ytmusic:v1":1789988884271}""")
        File(dir, "searches.json").writeText("""["dua lipa"]""")
        val s = LibraryStore(dir)
        assertEquals("u/t", s.current.likes.single().track.sourceId)
        assertEquals(1789988884271, s.current.history.single().playedAt)
        assertEquals(listOf("dua lipa"), s.current.searches)
    }

    @Test fun `corrupt library is kept aside`() {
        File(dir, "library.json").writeText("{not json")
        val s = LibraryStore(dir)
        assertTrue(s.current.likes.isEmpty())
        assertTrue(File(dir, "library.json.bad").isFile)
    }
}

class SettingsAndBackupTest {
    private val dir = Files.createTempDirectory("grooveo-backup").toFile()

    @Test fun `settings normalize and persist`() {
        val f = File(dir, "settings.json")
        f.writeText("""{"dataSaverMode":true,"eqPreset":"BASS_BOOST","volume":7.0,"crossfadeSeconds":99}""") // old desktop format
        val r = SettingsRepository(f)
        assertTrue(r.current.dataSaverMode)
        assertEquals(1f, r.current.volume)
        assertEquals(12, r.current.crossfadeSeconds)
        assertEquals("Flach", r.current.eq.name)
        r.update { it.copy(profileName = "Lena") }
        r.flush()
        assertEquals("Lena", SettingsRepository(f).current.profileName)
    }

    @Test fun `backup round trip replaces library`() {
        val store = LibraryStore(dir)
        val settings = SettingsRepository(File(dir, "settings.json"))
        store.toggleLike(track("a"))
        store.createPlaylist("P", listOf(track("1"), track("2")))
        store.toggleFollow(FollowedArtist("soundcloud", "x", "X", null))
        settings.update { it.copy(profileName = "Max", autoplayRadio = false) }
        val b = BackupManager(store, settings, File(dir, "backups"))
        val file = b.export()
        assertTrue(file.name.matches(Regex("backup_\\d{8}_\\d{6}\\.json")))

        store.mutate { LibraryData() }
        settings.update { it.copy(profileName = "", autoplayRadio = true) }
        b.import(file)
        assertEquals(listOf("a"), store.current.likes.map { it.track.sourceId })
        assertEquals(listOf("1", "2"), store.current.playlists.single().tracks.map { it.track.sourceId })
        assertEquals("X", store.current.followed.single().name)
        assertEquals("Max", settings.current.profileName)
        assertFalse(settings.current.autoplayRadio)
        assertEquals(listOf(file), b.listBackups())
    }

    @Test fun `imports a backup written by the Android app`() {
        val android = """
            {"version":1,"createdAt":"2026-09-20T10:00:00Z",
             "likes":[{"source":"ytmusic","sourceId":"abc","title":"Song","artist":"Band","album":null,"durationSec":200,"thumbnailUrl":null,"webpageUrl":"https://music.youtube.com/watch?v=abc"}],
             "playlists":[{"id":"p1","name":"Chill","createdAt":"2026-09-01T00:00:00Z","description":null,"accentColorKey":"accent","moodTags":["chill"],"tracks":[]}],
             "followedArtists":[],"savedPlaylists":[],
             "settings":{"hiResAudio":false,"dataSaverMode":false,"eqPreset":"FLAT","playerStyle":"bars","autoplayRadio":true,"contentSafetyFilter":true,"sound3dPreset":"KINO","downloadsWifiOnly":false,"notifyNewUploads":false,"showMixControls":true,"showFeatured":false,"showNewUploads":true,"autoBackup":false,"profileName":"Handy","profileColorStyle":"auto"}}
        """.trimIndent()
        val f = File(dir, "android.json").apply { writeText(android) }
        val store = LibraryStore(dir)
        val settings = SettingsRepository(File(dir, "settings.json"))
        BackupManager(store, settings, dir).import(f)
        assertEquals("Song", store.current.likes.single().track.title)
        assertEquals(listOf("chill"), store.current.playlists.single().moodTags)
        assertEquals("KINO", settings.current.sound3dPreset)
        assertEquals("bars", settings.current.playerStyle)
        assertFalse(settings.current.showFeatured)
    }

    @Test fun `rejects foreign json`() {
        val f = File(dir, "x.json").apply { writeText("""{"hello":1}""") }
        val r = runCatching { BackupManager(LibraryStore(dir), SettingsRepository(File(dir, "s.json")), dir).import(f) }
        assertTrue(r.isFailure)
    }
}

class DownloadManagerTest {
    private val dir = Files.createTempDirectory("grooveo-dl").toFile()

    @Test fun `extension detection`() {
        assertEquals("mp3", DownloadManager.extensionFor("audio/mpeg", "x"))
        assertEquals("webm", DownloadManager.extensionFor(null, "https://g/videoplayback?mime=audio%2Fwebm&x"))
        assertEquals("m4a", DownloadManager.extensionFor("audio/mp4; codecs=mp4a", "x"))
        assertEquals("opus", DownloadManager.extensionFor("audio/ogg", "x"))
    }

    @Test fun `update check compares versions`() {
        assertTrue(UpdateChecker.isNewer("v1.0.1", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.9"))
        assertFalse(UpdateChecker.isNewer("v1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("0.9", "1.0.0"))
    }

    /** Full download through a local HTTP server, resuming a partial file with a Range request. */
    @Test fun `downloads with range resume`() = runBlocking {
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        var rangeSeen: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/a.mp3") { ex ->
                val range = ex.requestHeaders.getFirst("Range")
                rangeSeen = range
                val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                ex.responseHeaders.add("Content-Type", "audio/mpeg")
                ex.sendResponseHeaders(if (from > 0) 206 else 200, (payload.size - from).toLong())
                ex.responseBody.use { it.write(payload, from, payload.size - from) }
            }
            start()
        }
        try {
            val store = LibraryStore(dir)
            val t = track("dl")
            // pre-create a third of the file to force a resume
            File(dir, "out").mkdirs()
            File(dir, "out/soundcloud_dl.part").writeBytes(payload.copyOf(100_000))
            val url = "http://127.0.0.1:${server.address.port}/a.mp3"
            val dm = DownloadManager(store, { ResolvedStream(url, isHls = false) }, extractionHttpClient(), { File(dir, "out") })
            dm.enqueue(t)
            val end = System.currentTimeMillis() + 10_000
            while (store.download(t.key)?.state != DownloadState.COMPLETED && System.currentTimeMillis() < end) Thread.sleep(20)
            val rec = assertNotNull(store.download(t.key))
            assertEquals(DownloadState.COMPLETED, rec.state, rec.error)
            assertEquals("bytes=100000-", rangeSeen)
            assertTrue(File(rec.filePath!!).readBytes().contentEquals(payload))
            assertTrue(rec.filePath!!.endsWith(".mp3"))
            dm.remove(t.key)
            assertNull(store.download(t.key))
            assertFalse(File(rec.filePath!!).exists())
        } finally {
            server.stop(0)
        }
    }
}
