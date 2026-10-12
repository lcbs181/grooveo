package dev.schlubbe.musicagent.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekSettleTest {
    @Test fun `the dropped position is kept while the player still reports the old one`() {
        // dragged from 10 s to 95 s; the first poll still sees 10.4 s
        assertFalse(seekSettled(polledMs = 10_400, targetMs = 95_000, sinceSeekMs = 300))
        // dragged backwards: the old position is ahead of the target
        assertFalse(seekSettled(polledMs = 120_000, targetMs = 30_000, sinceSeekMs = 300))
    }

    @Test fun `the player position takes over once it is near the target`() {
        assertTrue(seekSettled(polledMs = 95_000, targetMs = 95_000, sinceSeekMs = 100))
        // playback went on a little between seek and poll
        assertTrue(seekSettled(polledMs = 95_600, targetMs = 95_000, sinceSeekMs = 600))
        // the seek landed on a keyframe slightly before the target
        assertTrue(seekSettled(polledMs = 94_500, targetMs = 95_000, sinceSeekMs = 600))
    }

    @Test fun `a seek that never arrives stops holding the slider after a timeout`() {
        assertTrue(seekSettled(polledMs = 10_000, targetMs = 95_000, sinceSeekMs = 2_000))
    }
}
