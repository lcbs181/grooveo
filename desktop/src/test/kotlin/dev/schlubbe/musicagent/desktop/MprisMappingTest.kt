package dev.schlubbe.musicagent.desktop

import dev.schlubbe.musicagent.desktop.mpris.MprisMapping
import dev.schlubbe.musicagent.desktop.playback.RepeatMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MprisMappingTest {
    @Test fun `status and loop mapping`() {
        assertEquals("Playing", MprisMapping.playbackStatus(true, true))
        assertEquals("Paused", MprisMapping.playbackStatus(false, true))
        assertEquals("Stopped", MprisMapping.playbackStatus(false, false))
        RepeatMode.entries.forEach { assertEquals(it, MprisMapping.repeatFor(MprisMapping.loopStatus(it))) }
    }

    @Test fun `track paths are valid object paths and stable`() {
        val p = MprisMapping.trackPath("soundcloud:artist/some-track")
        assertTrue(Regex("^(/[A-Za-z0-9_]+)+$").matches(p.path), p.path)
        assertEquals(p.path, MprisMapping.trackPath("soundcloud:artist/some-track").path)
        assertNotEquals(p.path, MprisMapping.trackPath("ytmusic:abc").path)
        assertEquals("/org/mpris/MediaPlayer2/TrackList/NoTrack", MprisMapping.trackPath(null).path)
    }
}
