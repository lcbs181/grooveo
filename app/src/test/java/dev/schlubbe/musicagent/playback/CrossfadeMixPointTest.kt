package dev.schlubbe.musicagent.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeMixPointTest {
    @Test fun `mix points are only trusted for files on the device`() {
        assertTrue(isLocalMediaUri("content"))
        assertTrue(isLocalMediaUri("file"))
        assertTrue(isLocalMediaUri("CONTENT"))
        assertFalse(isLocalMediaUri("https"))
        assertFalse(isLocalMediaUri("http"))
        assertFalse(isLocalMediaUri(null))
    }
}
