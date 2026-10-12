package dev.schlubbe.musicagent.data.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTagsTest {
    @Test fun `quoted tags keep their spaces, machine tags and repeats go`() {
        assertEquals(
            listOf("Techno", "deep house", "berlin", "Hard Techno"),
            TrackTags.parse("\"deep house\" berlin soundcloud:source=web-record #techno \"Hard Techno\" a", "Techno"),
        )
        assertEquals(emptyList<String>(), TrackTags.parse(null, null))
        assertEquals(emptyList<String>(), TrackTags.parse("   ", ""))
    }

    @Test fun `the chips are the most frequent tags, named as spelled most often`() {
        val top = TrackTags.top(
            listOf(
                listOf("Deep House", "berlin"),
                listOf("deep house", "techno"),
                listOf("deep-house", "Techno"),
                listOf("Techno"),
            ),
        )
        // equally frequent tags in name order; each named as spelled most often
        assertEquals(listOf("Deep House", "Techno", "berlin"), top)
        assertTrue(TrackTags.has(listOf("Deep House"), "deep-house"))
        assertFalse(TrackTags.has(null, "techno"))
    }
}
