package dev.schlubbe.musicagent.ui.advent

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventSeasonTest {
    @Test
    fun firstAdventDates() {
        assertEquals(LocalDate.of(2026, 11, 29), firstAdvent(2026))
        assertEquals(LocalDate.of(2027, 11, 28), firstAdvent(2027))
        // Dec 24 on a Sunday is the fourth Sunday of Advent
        assertEquals(LocalDate.of(2023, 12, 3), firstAdvent(2023))
        assertEquals(LocalDate.of(2028, 12, 3), firstAdvent(2028))
    }

    @Test
    fun doorsOnlyUpToToday() {
        val state = AdventUiState(today = LocalDate.of(2026, 12, 5), openAll = false)
        assertTrue(state.canOpen(5))
        assertFalse(state.canOpen(6))
        assertFalse(AdventUiState(today = LocalDate.of(2026, 11, 30), openAll = false).canOpen(1))
    }

    @Test
    fun seasonFromFirstAdventToNewYearsEve() {
        assertFalse(adventSeason(LocalDate.of(2026, 11, 28), always = false))
        assertTrue(adventSeason(LocalDate.of(2026, 11, 29), always = false))
        assertTrue(adventSeason(LocalDate.of(2026, 12, 31), always = false))
        assertFalse(adventSeason(LocalDate.of(2027, 1, 1), always = false))
    }
}
