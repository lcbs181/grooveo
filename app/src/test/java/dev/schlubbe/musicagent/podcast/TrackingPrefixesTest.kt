package dev.schlubbe.musicagent.podcast

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingPrefixesTest {

    @Test
    fun stripsSinglePrefixes() {
        assertEquals("https://traffic.megaphone.fm/ABC.mp3?x=1", TrackingPrefixes.strip("https://dts.podtrac.com/redirect.mp3/traffic.megaphone.fm/ABC.mp3?x=1"))
        assertEquals("https://host.org/a/b.mp3", TrackingPrefixes.strip("https://chrt.fm/track/9A8B7C/host.org/a/b.mp3"))
        assertEquals("https://host.org/b.mp3", TrackingPrefixes.strip("https://op3.dev/e/host.org/b.mp3"))
        assertEquals("https://host.org/b.mp3", TrackingPrefixes.strip("https://op3.dev/e,pg=123/host.org/b.mp3"))
        assertEquals("https://host.org/b.mp3", TrackingPrefixes.strip("https://pdst.fm/e/https://host.org/b.mp3"))
    }

    @Test
    fun stripsChains() {
        assertEquals(
            "https://audio.example.com/ep1.mp3",
            TrackingPrefixes.strip("https://pdst.fm/e/chrt.fm/track/XYZ/dts.podtrac.com/redirect.mp3/audio.example.com/ep1.mp3"),
        )
    }

    @Test
    fun leavesOthersAlone() {
        assertEquals("https://traffic.megaphone.fm/ABC.mp3", TrackingPrefixes.strip("https://traffic.megaphone.fm/ABC.mp3"))
        assertEquals("https://chrt.fm/track/", TrackingPrefixes.strip("https://chrt.fm/track/"))
    }
}
