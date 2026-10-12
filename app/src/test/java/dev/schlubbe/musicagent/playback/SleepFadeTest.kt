package dev.schlubbe.musicagent.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepFadeTest {
    @Test fun `the sleep fade runs from full volume to silence`() {
        assertEquals(1f, sleepFadeVolume(0, SLEEP_FADE_MS), 0f)
        assertEquals(0f, sleepFadeVolume(SLEEP_FADE_MS, SLEEP_FADE_MS), 0f)
        assertEquals(0f, sleepFadeVolume(SLEEP_FADE_MS + 500, SLEEP_FADE_MS), 0f)
        assertEquals(0.25f, sleepFadeVolume(SLEEP_FADE_MS / 2, SLEEP_FADE_MS), 1e-6f)
    }

    @Test fun `the sleep fade only ever gets quieter`() {
        var last = 1f
        for (t in 0..SLEEP_FADE_MS step SLEEP_FADE_STEP_MS) {
            val v = sleepFadeVolume(t, SLEEP_FADE_MS)
            assertTrue(v <= last)
            last = v
        }
    }
}
