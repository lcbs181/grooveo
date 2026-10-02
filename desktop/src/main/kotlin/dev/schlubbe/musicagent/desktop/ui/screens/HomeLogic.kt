package dev.schlubbe.musicagent.desktop.ui.screens

import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.repository.FeedRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import dev.schlubbe.musicagent.data.repository.filterForDiscovery
import dev.schlubbe.musicagent.desktop.data.FollowedArtist
import dev.schlubbe.musicagent.desktop.data.HistoryEntry
import dev.schlubbe.musicagent.desktop.data.LikedTrack
import dev.schlubbe.musicagent.desktop.data.key
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.LocalDateTime

/** Mood chips next to "Mix starten" (keyword searches, no mood metadata exists). */
enum class HomeMood(val label: String, val searchKeyword: String?) {
    ALL("Alle", null),
    FOCUS("Fokus", "focus music"),
    CHILL("Chill", "chill music"),
    WORKOUT("Workout", "workout music"),
    PARTY("Party", "party music"),
}

/** A "Deine Mixes" card; tapping shuffle-plays [pool]. */
data class HomeMix(val badge: String, val title: String, val subtitle: String, val thumbnailUrl: String?, val pool: List<TrackResultDto>)

/** "Neu von Künstlern" / "Sender entdecken" entry. [sourceId] is null for the
 * frequency-based fallback (only a name is known, resolved on click). */
data class HomeArtist(val name: String, val source: String, val sourceId: String?, val thumbnailUrl: String?)

/** Network calls the Home logic needs; an interface so the mix sourcing is unit-testable. */
interface HomeSources {
    val safetyFilter: Boolean
    suspend fun trendingByGenre(genre: String, limit: Int): List<TrackResultDto>
    suspend fun search(query: String, limit: Int, source: String = "all"): List<TrackResultDto>
    suspend fun personalizedMix(genres: Set<String>, artists: List<String>): List<TrackResultDto>
}

class RepoHomeSources(
    private val search: SearchRepository,
    private val feed: FeedRepository,
    private val settings: SettingsRepository,
) : HomeSources {
    override val safetyFilter: Boolean get() = settings.current.contentSafetyFilter
    override suspend fun trendingByGenre(genre: String, limit: Int) = search.getTrendingByGenre(genre, limit)
    override suspend fun search(query: String, limit: Int, source: String) = search.search(query, source = source, limit = limit)
    override suspend fun personalizedMix(genres: Set<String>, artists: List<String>) =
        feed.getPersonalizedMix(preferredGenres = genres, preferredArtists = artists)
}

/**
 * Non-UI logic of the Home screen, ported from the Android HomeViewModel:
 * greeting (time of day / season / holidays incl. Easter), daily rotation,
 * genre chip order, top artists and the "Deine Mixes" sourcing.
 */
class HomeLogic(private val sources: HomeSources) {

    /** "Deine Mixes": Gym (Hardstyle genre, else search), Fokus (personalised),
     * Chill (keyword search), Party (House+Bass genre, else search). Pools load
     * concurrently; a failing source just drops its card. */
    suspend fun mixCards(genres: Set<String>, artists: List<String>): List<HomeMix> = coroutineScope {
        val filter = sources.safetyFilter
        suspend fun safeSearch(q: String) = runCatching { sources.search(q, MOOD_MIX_SEARCH_LIMIT) }.getOrDefault(emptyList()).filterForDiscovery(filter)
        suspend fun genre(g: String, limit: Int) = runCatching { sources.trendingByGenre(g, limit) }.getOrDefault(emptyList())

        val gym = async { genre("Hardstyle", MOOD_MIX_SEARCH_LIMIT).ifEmpty { safeSearch("gym hardstyle mix") } }
        val focus = async { runCatching { sources.personalizedMix(genres, artists) }.getOrDefault(emptyList()) }
        val chill = async { safeSearch(HomeMood.CHILL.searchKeyword!!) }
        val party = async {
            val house = async { genre("House", MOOD_MIX_SEARCH_LIMIT / 2) }
            val bass = async { genre("Bass", MOOD_MIX_SEARCH_LIMIT / 2) }
            (house.await() + bass.await()).distinctBy { it.key }.ifEmpty { safeSearch(HomeMood.PARTY.searchKeyword!!) }
        }
        buildList {
            gym.await().takeIf { it.isNotEmpty() }?.let { add(mix("GYM", "Gym Hardstyle Mix🔱", "Power fürs Training", it)) }
            focus.await().takeIf { it.size >= MIX_POOL_MINIMUM }?.let { add(mix("FOKUS", "Fokus-Mix", "Persönlich für dich", it)) }
            chill.await().takeIf { it.isNotEmpty() }?.let { add(mix("CHILL", "Chill-Mix", "Entspannt durch den Tag", it)) }
            party.await().takeIf { it.isNotEmpty() }?.let { add(mix("PARTY", "Party-Mix", "Energie für deine Playlist", it)) }
        }
    }

    /** "Mix starten": "Alle" shuffles the already loaded [fallback] shelves, a mood runs its keyword search. */
    suspend fun moodQueue(mood: HomeMood, fallback: List<TrackResultDto>): List<TrackResultDto> {
        val kw = mood.searchKeyword ?: return fallback.distinctBy { it.key }.shuffled()
        return runCatching { sources.search(kw, MOOD_MIX_SEARCH_LIMIT) }.getOrDefault(emptyList())
            .filterForDiscovery(sources.safetyFilter).shuffled()
    }

    /** "Sender entdecken": a shuffled search for that artist's tracks. */
    suspend fun stationQueue(artist: HomeArtist): List<TrackResultDto> =
        runCatching { sources.search(artist.name, STATION_POOL_LIMIT, artist.source) }.getOrDefault(emptyList())
            .filterForDiscovery(sources.safetyFilter).shuffled()

    companion object {
        const val MOOD_MIX_SEARCH_LIMIT = 25
        const val MIX_POOL_MINIMUM = 5
        const val STATION_POOL_LIMIT = 25
        const val CHART_OVERFETCH_COUNT = 60
        const val CHART_DISPLAY_COUNT = 20
        const val FEATURED_CHART_COUNT = 5
        const val TOP_ARTIST_LIMIT = 10
        val DEFAULT_GENRES = listOf("House", "Dub Techno", "Ambient", "Bass", "Lo-Fi")

        private fun mix(badge: String, title: String, subtitle: String, pool: List<TrackResultDto>) =
            HomeMix(badge, title, subtitle, pool.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl, pool)

        // ---------------- greeting ----------------

        enum class DayPart { NIGHT, MORNING, DAY, EVENING }
        enum class Season { WINTER, SPRING, SUMMER, AUTUMN }

        fun dayPartFor(hour: Int): DayPart = when (hour) {
            in 5..10 -> DayPart.MORNING
            in 11..17 -> DayPart.DAY
            in 18..22 -> DayPart.EVENING
            else -> DayPart.NIGHT
        }

        /** Meteorological seasons (German usage). */
        fun seasonFor(month: Int): Season = when (month) {
            12, 1, 2 -> Season.WINTER
            3, 4, 5 -> Season.SPRING
            6, 7, 8 -> Season.SUMMER
            else -> Season.AUTUMN
        }

        /** Gregorian Easter Sunday (Meeus/Jones/Butcher). */
        fun easterSunday(year: Int): LocalDate {
            val a = year % 19
            val b = year / 100
            val c = year % 100
            val d = b / 4
            val e = b % 4
            val f = (b + 8) / 25
            val g = (b - f + 1) / 3
            val h = (19 * a + b - d - g + 15) % 30
            val i = c / 4
            val k = c % 4
            val l = (32 + 2 * e + 2 * i - h - k) % 7
            val m = (a + 11 * h + 22 * l) / 451
            val month = (h + l - 7 * m + 114) / 31
            val day = ((h + l - 7 * m + 114) % 31) + 1
            return LocalDate.of(year, month, day)
        }

        /** Deterministic pick per day (no flicker between recompositions/refreshes). */
        fun <T> pickForDay(date: LocalDate, pool: List<T>): T {
            require(pool.isNotEmpty())
            val index = ((date.toEpochDay() % pool.size) + pool.size).toInt() % pool.size
            return pool[index]
        }

        /** A per-day deterministic window of [windowSize] items out of [pool]. */
        fun <T> rotateForDay(date: LocalDate, pool: List<T>, windowSize: Int): List<T> {
            if (pool.size <= windowSize) return pool
            return pool.shuffled(java.util.Random(date.toEpochDay())).take(windowSize)
        }

        fun holidayGreetingFor(date: LocalDate): String? {
            val year = date.year
            val easter = easterSunday(year)
            return when (date) {
                LocalDate.of(year, 1, 1) -> pickForDay(date, listOf("Frohes neues Jahr!", "Ein gutes neues Jahr!"))
                LocalDate.of(year, 12, 24) -> pickForDay(date, listOf("Frohe Weihnachten!", "Schönen Heiligabend!"))
                LocalDate.of(year, 12, 25), LocalDate.of(year, 12, 26) -> pickForDay(date, listOf("Frohe Weihnachten!", "Schöne Feiertage!"))
                LocalDate.of(year, 12, 31) -> pickForDay(date, listOf("Guten Rutsch!", "Bis nächstes Jahr!"))
                easter.minusDays(2) -> "Schönen Karfreitag"
                easter -> pickForDay(date, listOf("Frohe Ostern!", "Schöne Ostertage!"))
                easter.plusDays(1) -> "Schönen Ostermontag!"
                else -> null
            }
        }

        fun greetingPoolFor(part: DayPart, season: Season): List<String> = when (part) {
            DayPart.MORNING -> when (season) {
                Season.WINTER -> listOf("Guten Morgen", "Guten Morgen, frostig heute", "Einen warmen guten Morgen")
                Season.SPRING -> listOf("Guten Morgen", "Guten Morgen, der Frühling ruft", "Frischer Morgen")
                Season.SUMMER -> listOf("Guten Morgen", "Guten Morgen, schon warm draußen", "Sonniger Morgen")
                Season.AUTUMN -> listOf("Guten Morgen", "Guten Morgen, herbstlich heute", "Kühler Morgen")
            }
            DayPart.DAY -> when (season) {
                Season.WINTER -> listOf("Guten Tag", "Schönen, kalten Tag", "Hallo")
                Season.SPRING -> listOf("Guten Tag", "Schönen Frühlingstag", "Hallo")
                Season.SUMMER -> listOf("Guten Tag", "Schönen Sommertag", "Hallo")
                Season.AUTUMN -> listOf("Guten Tag", "Schönen Herbsttag", "Hallo")
            }
            DayPart.EVENING -> listOf("Guten Abend", "Schönen Abend", "Feierabend?")
            DayPart.NIGHT -> listOf("Noch wach?", "Gute Nacht", "Nachtschwärmer-Modus an")
        }

        /** Holiday greeting beats the time-of-day/season pool; the profile name is appended. */
        fun greetingFor(now: LocalDateTime, profileName: String?): String {
            val date = now.toLocalDate()
            val base = holidayGreetingFor(date) ?: pickForDay(date, greetingPoolFor(dayPartFor(now.hour), seasonFor(now.monthValue)))
            return if (!profileName.isNullOrBlank()) "$base, ${profileName.trim()}" else base
        }

        // ---------------- small helpers ----------------

        fun resumeStatusLabel(isPlaying: Boolean, positionMs: Long): String = when {
            isPlaying -> "Läuft gerade"
            positionMs < 5_000L -> "Zuletzt gespielt"
            else -> "Weiter hören"
        }

        /** "Diese Woche: X Std. Y Min gehört", empty below one minute. */
        fun weeklyStatLine(totalSec: Long): String {
            if (totalSec < 60) return ""
            val hours = totalSec / 3600
            val minutes = (totalSec % 3600) / 60
            return if (hours > 0) "Diese Woche: $hours Std. $minutes Min gehört" else "Diese Woche: $minutes Min gehört"
        }

        /** Onboarding genre picks first (reusing a default chip on a case-insensitive match), then the remaining defaults. */
        fun genreChips(preferred: List<String>): List<String> {
            val picked = preferred.map { raw -> DEFAULT_GENRES.firstOrNull { it.equals(raw, true) } ?: raw }.distinctBy { it.lowercase() }
            return picked + DEFAULT_GENRES.filterNot { d -> picked.any { it.equals(d, true) } }
        }

        /** Followed artists, else the most frequent artists in history (1x) and likes (3x). */
        fun topArtists(followed: List<FollowedArtist>, history: List<HistoryEntry>, likes: List<LikedTrack>, limit: Int = TOP_ARTIST_LIMIT): List<HomeArtist> {
            if (followed.isNotEmpty()) return followed.take(limit).map { HomeArtist(it.name, it.source, it.sourceId, it.thumbnailUrl) }
            val score = HashMap<Pair<String, String>, Int>()
            val cover = HashMap<Pair<String, String>, String?>()
            fun count(t: TrackResultDto, w: Int) {
                val a = t.artist?.takeIf { it.isNotBlank() } ?: return
                val k = t.source to a
                score[k] = (score[k] ?: 0) + w
                if (cover[k] == null) cover[k] = t.thumbnailUrl
            }
            history.forEach { count(it.track, 1) }
            likes.forEach { count(it.track, 3) }
            return score.entries.sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }.thenBy { it.key.second })
                .take(limit).map { (k, _) -> HomeArtist(k.second, k.first, null, cover[k]) }
        }
    }
}
