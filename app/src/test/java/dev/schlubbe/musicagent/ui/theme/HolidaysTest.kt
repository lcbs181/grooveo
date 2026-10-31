package dev.schlubbe.musicagent.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class HolidaysTest {
    private fun on(y: Int, m: Int, d: Int) = holidayFor(LocalDate.of(y, m, d))

    @Test
    fun easterDates() {
        assertEquals(LocalDate.of(2026, 4, 5), easterSunday(2026))
        assertEquals(LocalDate.of(2027, 3, 28), easterSunday(2027))
        assertEquals(LocalDate.of(2025, 4, 20), easterSunday(2025))
    }

    @Test
    fun halloweenWeek() {
        assertEquals("Happy Halloween!", on(2026, 10, 31)?.greeting)
        assertEquals(HolidayLook.HALLOWEEN, on(2026, 10, 24)?.look)
        assertNull(on(2026, 10, 23))
    }

    @Test
    fun adventSundaysAndChristmas() {
        // 2026: 24 Dec is a Thursday, so the 4th Advent is 20 Dec and the 1st 29 Nov
        assertEquals("Schönen 1. Advent!", on(2026, 11, 29)?.greeting)
        assertEquals("Schönen 4. Advent!", on(2026, 12, 20)?.greeting)
        assertEquals("Schöne Adventszeit!", on(2026, 12, 1)?.greeting)
        assertEquals("Schönen Nikolaustag!", on(2026, 12, 6)?.greeting)
        assertNull(on(2026, 11, 28))
        assertEquals("Frohe Weihnachten!", on(2026, 12, 25)?.greeting)
        assertEquals(HolidayLook.CHRISTMAS, on(2026, 12, 28)?.look)
    }

    @Test
    fun newYearAndValentine() {
        assertEquals("Guten Rutsch!", on(2026, 12, 31)?.greeting)
        assertEquals(HolidayLook.NEW_YEAR, on(2027, 1, 1)?.look)
        assertEquals(HolidayLook.VALENTINE, on(2027, 2, 14)?.look)
    }

    @Test
    fun carnivalAndEasterWeek() {
        // Easter 2027 = 28 Mar: Weiberfastnacht 4 Feb, Rosenmontag 8 Feb, Veilchendienstag 9 Feb
        assertEquals("Helau und Alaaf!", on(2027, 2, 4)?.greeting)
        assertEquals("Schönen Rosenmontag!", on(2027, 2, 8)?.greeting)
        assertEquals(HolidayLook.CARNIVAL, on(2027, 2, 9)?.look)
        assertNull(on(2027, 2, 10))
        assertEquals("Frohe Ostern!", on(2027, 3, 28)?.greeting)
        assertEquals(HolidayLook.EASTER, on(2027, 3, 21)?.look)
        assertEquals("Schönen Ostermontag!", on(2027, 3, 29)?.greeting)
    }

    @Test
    fun greetingOnlyDays() {
        assertEquals("Schönen Muttertag!", on(2027, 5, 9)?.greeting)
        assertNull(on(2027, 5, 9)?.look)
        assertEquals("Schönen Tag der Deutschen Einheit!", on(2026, 10, 3)?.greeting)
        assertEquals("Schönen Martinstag!", on(2026, 11, 11)?.greeting)
    }
}
