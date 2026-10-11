package dev.schlubbe.musicagent.data.backup

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupParseTest {
    private val gson = Gson()

    @Test fun `a full backup reads back as written`() {
        val p = BackupPayload(
            createdAt = "2026-10-11T10:00:00Z",
            likes = listOf(BackupTrackDto("soundcloud", "1", "Titel", "A", null, 200, null, "https://x", "2026-01-01T00:00:00Z")),
            playlists = listOf(BackupPlaylistDto("p", "Lernen", "2026-01-01T00:00:00Z", null, null, listOf("ruhig"), emptyList())),
            settings = BackupSettingsDto(false, false, "FLAT", null, null, "classic", true, true, "OFF", false, false, true, true, true, true, "Ich", "accent"),
        )
        assertEquals(p, parseBackup(gson, gson.toJson(p)))
    }

    @Test fun `missing lists and settings and a byte order mark are tolerated`() {
        val p = parseBackup(gson, "﻿" + """{"createdAt":"2026-10-11T10:00:00Z","playlists":[{"id":"p","name":"X","createdAt":"t"}]}""")
        assertTrue(p.likes.isEmpty() && p.followedArtists.isEmpty() && p.savedPlaylists.isEmpty())
        assertEquals("X", p.playlists.single().name)
        assertTrue(p.playlists.single().tracks.isEmpty() && p.playlists.single().moodTags.isEmpty())
        @Suppress("SENSELESS_COMPARISON")
        assertNull(p.settings as BackupSettingsDto?)
    }

    @Test fun `other files are refused with a clear message`() {
        listOf("{}", "kein json", "[1,2]").forEach { text ->
            val e = assertThrows(IllegalStateException::class.java) { parseBackup(gson, text) }
            assertTrue(e.message!!, "keine Grooveo-Sicherung" in e.message!!)
        }
    }
}
