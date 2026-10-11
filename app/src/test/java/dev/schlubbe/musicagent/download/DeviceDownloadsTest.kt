package dev.schlubbe.musicagent.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceDownloadsTest {
    private val known = listOf(
        DeviceDownloads.Known("soundcloud:1", "Daft Punk", "One More Time"),
        DeviceDownloads.Known("ytmusic:2", "AC/DC", "Back In Black"),
        DeviceDownloads.Known("ytmusic:3", null, "Ohne Künstler"),
        DeviceDownloads.Known("soundcloud:9", "Daft Punk", "One More Time"),
    )
    private val index = DeviceDownloads.index(known)

    @Test fun `files are matched by the name the download got`() {
        assertEquals("soundcloud:1", index[DeviceDownloads.key("Daft Punk - One More Time.m4a")]?.id)
        // the first known track wins over a later one with the same name
        assertEquals("soundcloud:1", index[DeviceDownloads.key("daft punk - one more time.mp3")]?.id)
        // characters not allowed in file names were replaced when downloading
        assertEquals("ytmusic:2", index[DeviceDownloads.key("AC_DC - Back In Black.m4a")]?.id)
        assertEquals("ytmusic:3", index[DeviceDownloads.key("Ohne Künstler.weba")]?.id)
        // Android numbers a second file with the same name
        assertEquals("soundcloud:1", index[DeviceDownloads.key("Daft Punk - One More Time (1).m4a")]?.id)
        assertNull(index[DeviceDownloads.key("Unbekannt - Irgendwas.m4a")])
    }

    @Test fun `artist and title are read from the file name`() {
        assertEquals("Daft Punk" to "One More Time", DeviceDownloads.parse("Daft Punk - One More Time (2).m4a"))
        assertEquals("A" to "B - Remix", DeviceDownloads.parse("A - B - Remix.mp3"))
        assertEquals(null to "Nur Titel", DeviceDownloads.parse("Nur Titel.ogg"))
    }
}
