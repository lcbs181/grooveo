package dev.schlubbe.musicagent.desktop.ui

import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.desktop.data.FollowedArtist
import dev.schlubbe.musicagent.desktop.data.HistoryEntry
import dev.schlubbe.musicagent.desktop.data.LikedTrack
import dev.schlubbe.musicagent.desktop.ui.screens.HomeArtist
import dev.schlubbe.musicagent.desktop.ui.screens.HomeLogic
import dev.schlubbe.musicagent.desktop.ui.screens.HomeLogic.Companion.DayPart
import dev.schlubbe.musicagent.desktop.ui.screens.HomeLogic.Companion.Season
import dev.schlubbe.musicagent.desktop.ui.screens.HomeMood
import dev.schlubbe.musicagent.desktop.ui.screens.HomeSources
import dev.schlubbe.musicagent.desktop.ui.screens.formatListenTime
import dev.schlubbe.musicagent.desktop.ui.screens.lastSevenDays
import dev.schlubbe.musicagent.desktop.ui.screens.mergeArtists
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeLogicTest {
    private fun t(id: String, artist: String? = "A", source: String = "soundcloud") =
        TrackResultDto(source, id, "Titel $id", artist, null, 180, "https://img/$id", "https://x/$id")

    @Test fun easterDates() {
        assertEquals(LocalDate.of(2026, 4, 5), HomeLogic.easterSunday(2026))
        assertEquals(LocalDate.of(2027, 3, 28), HomeLogic.easterSunday(2027))
        assertEquals(LocalDate.of(2024, 3, 31), HomeLogic.easterSunday(2024))
        assertEquals(LocalDate.of(2025, 4, 20), HomeLogic.easterSunday(2025))
    }

    @Test fun dayPartBuckets() {
        assertEquals(DayPart.NIGHT, HomeLogic.dayPartFor(4))
        assertEquals(DayPart.MORNING, HomeLogic.dayPartFor(5))
        assertEquals(DayPart.MORNING, HomeLogic.dayPartFor(10))
        assertEquals(DayPart.DAY, HomeLogic.dayPartFor(11))
        assertEquals(DayPart.DAY, HomeLogic.dayPartFor(17))
        assertEquals(DayPart.EVENING, HomeLogic.dayPartFor(18))
        assertEquals(DayPart.EVENING, HomeLogic.dayPartFor(22))
        assertEquals(DayPart.NIGHT, HomeLogic.dayPartFor(23))
        assertEquals(DayPart.NIGHT, HomeLogic.dayPartFor(0))
    }

    @Test fun seasons() {
        assertEquals(Season.WINTER, HomeLogic.seasonFor(12))
        assertEquals(Season.WINTER, HomeLogic.seasonFor(2))
        assertEquals(Season.SPRING, HomeLogic.seasonFor(3))
        assertEquals(Season.SUMMER, HomeLogic.seasonFor(8))
        assertEquals(Season.AUTUMN, HomeLogic.seasonFor(10))
    }

    @Test fun greetingUsesPoolForBucket() {
        val morning = HomeLogic.greetingFor(LocalDateTime.of(2026, 10, 2, 8, 0), null)
        assertTrue(morning in HomeLogic.greetingPoolFor(DayPart.MORNING, Season.AUTUMN), morning)
        val night = HomeLogic.greetingFor(LocalDateTime.of(2026, 7, 14, 2, 30), null)
        assertTrue(night in HomeLogic.greetingPoolFor(DayPart.NIGHT, Season.SUMMER), night)
        val evening = HomeLogic.greetingFor(LocalDateTime.of(2026, 1, 14, 20, 0), "Lena")
        assertTrue(evening.endsWith(", Lena"), evening)
        assertTrue(evening.removeSuffix(", Lena") in HomeLogic.greetingPoolFor(DayPart.EVENING, Season.WINTER))
    }

    @Test fun holidayGreetings() {
        assertTrue(HomeLogic.greetingFor(LocalDateTime.of(2026, 4, 5, 9, 0), null) in listOf("Frohe Ostern!", "Schöne Ostertage!"))
        assertEquals("Schönen Karfreitag", HomeLogic.greetingFor(LocalDateTime.of(2026, 4, 3, 15, 0), null))
        assertEquals("Schönen Ostermontag!", HomeLogic.greetingFor(LocalDateTime.of(2027, 3, 29, 15, 0), null))
        assertTrue(HomeLogic.greetingFor(LocalDateTime.of(2026, 12, 31, 23, 0), null) in listOf("Guten Rutsch!", "Bis nächstes Jahr!"))
        assertEquals(null, HomeLogic.holidayGreetingFor(LocalDate.of(2026, 10, 2)))
    }

    @Test fun dailyRotationIsStablePerDayAndChangesAcrossDays() {
        val pool = (1..60).toList()
        val d = LocalDate.of(2026, 10, 2)
        val a = HomeLogic.rotateForDay(d, pool, 20)
        assertEquals(a, HomeLogic.rotateForDay(d, pool, 20))
        assertEquals(20, a.size)
        assertEquals(20, a.toSet().size)
        assertTrue(a.all { it in pool })
        assertTrue(a != HomeLogic.rotateForDay(d.plusDays(1), pool, 20))
        assertEquals(listOf(1, 2), HomeLogic.rotateForDay(d, listOf(1, 2), 20))
        assertEquals(HomeLogic.pickForDay(d, pool), HomeLogic.pickForDay(d, pool))
        assertTrue(HomeLogic.pickForDay(d, pool) != HomeLogic.pickForDay(d.plusDays(1), pool))
    }

    @Test fun statLineAndResumeLabel() {
        assertEquals("", HomeLogic.weeklyStatLine(30))
        assertEquals("Diese Woche: 12 Min gehört", HomeLogic.weeklyStatLine(12 * 60 + 5))
        assertEquals("Diese Woche: 2 Std. 3 Min gehört", HomeLogic.weeklyStatLine(2 * 3600 + 3 * 60))
        assertEquals("Läuft gerade", HomeLogic.resumeStatusLabel(true, 0))
        assertEquals("Zuletzt gespielt", HomeLogic.resumeStatusLabel(false, 1000))
        assertEquals("Weiter hören", HomeLogic.resumeStatusLabel(false, 60_000))
    }

    @Test fun genreChipsPutPicksFirst() {
        assertEquals(HomeLogic.DEFAULT_GENRES, HomeLogic.genreChips(emptyList()))
        assertEquals(listOf("Techno", "House", "Dub Techno", "Ambient", "Bass", "Lo-Fi"), HomeLogic.genreChips(listOf("Techno", "house")))
    }

    @Test fun topArtistsPreferFollowsElseWeightedFrequency() {
        val f = FollowedArtist("soundcloud", "42", "Follow", "thumb")
        assertEquals(listOf(HomeArtist("Follow", "soundcloud", "42", "thumb")), HomeLogic.topArtists(listOf(f), emptyList(), emptyList()))
        val history = listOf(HistoryEntry(t("1", "X"), 0), HistoryEntry(t("2", "X"), 0), HistoryEntry(t("3", "Y"), 0))
        val likes = listOf(LikedTrack(t("4", "Y"), 0))
        val top = HomeLogic.topArtists(emptyList(), history, likes)
        assertEquals(listOf("Y", "X"), top.map { it.name }) // Y = 1 + 3, X = 2
        assertEquals(null, top.first().sourceId)
    }

    @Test fun mixSourcingFallbacksAndFailures() = runTest {
        val fake = object : HomeSources {
            override val safetyFilter = false
            override suspend fun trendingByGenre(genre: String, limit: Int): List<TrackResultDto> = when (genre) {
                "Hardstyle" -> emptyList()
                "House" -> listOf(t("h1"), t("h2"))
                "Bass" -> error("network down")
                else -> emptyList()
            }
            override suspend fun search(query: String, limit: Int, source: String) = when (query) {
                "gym hardstyle mix" -> listOf(t("g1"), t("g2"))
                "chill music" -> error("boom")
                else -> listOf(t("s1"))
            }
            override suspend fun personalizedMix(genres: Set<String>, artists: List<String>) = listOf(t("p1"), t("p2"))
        }
        val cards = HomeLogic(fake).mixCards(emptySet(), emptyList())
        // gym via search fallback, focus dropped (< 5), chill failed, party from House only
        assertEquals(listOf("Gym Hardstyle Mix🔱", "Party-Mix"), cards.map { it.title })
        assertEquals(listOf("g1", "g2"), cards[0].pool.map { it.sourceId })
        assertEquals(listOf("h1", "h2"), cards[1].pool.map { it.sourceId })
        assertEquals("https://img/g1", cards[0].thumbnailUrl)

        val all = HomeLogic(fake).moodQueue(HomeMood.ALL, listOf(t("a"), t("a"), t("b")))
        assertEquals(setOf("a", "b"), all.map { it.sourceId }.toSet())
        assertEquals(2, all.size)
        assertEquals(listOf("s1"), HomeLogic(fake).moodQueue(HomeMood.PARTY, emptyList()).map { it.sourceId })
    }

    @Test fun accountAndOnboardingHelpers() {
        val today = LocalDate.of(2026, 10, 2)
        val days = lastSevenDays(mapOf("2026-10-02" to 120L, "2026-09-26" to 60L, "2026-09-25" to 999L), today)
        assertEquals(7, days.size)
        assertEquals(LocalDate.of(2026, 9, 26) to 60L, days.first())
        assertEquals(today to 120L, days.last())
        assertEquals("1 Std. 5 Min", formatListenTime(3900))
        assertEquals("< 1 Min", formatListenTime(20))
        assertEquals(listOf("Bausa", "Nina Chuba"), mergeArtists(listOf("Bausa"), " bausa, Nina Chuba ,, "))
    }
}
