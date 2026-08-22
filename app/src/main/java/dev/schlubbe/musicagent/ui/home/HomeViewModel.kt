package dev.schlubbe.musicagent.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.LikeOutDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.remote.dto.toTrackResultDto
import dev.schlubbe.musicagent.data.repository.DownloadRepository
import dev.schlubbe.musicagent.data.repository.EventReporter
import dev.schlubbe.musicagent.data.repository.FeedItem
import dev.schlubbe.musicagent.data.repository.FeedRepository
import dev.schlubbe.musicagent.data.repository.FollowRepository
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.PlaylistRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.BuildConfig
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import dev.schlubbe.musicagent.playback.PlayerController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val SHELF_LIMIT = 6
private const val RECENTLY_PLAYED_LIMIT = 10
private const val TOP_ARTIST_LIMIT = 8
private const val FEATURED_CHART_COUNT = 5
private const val MIX_POOL_MINIMUM = 5
private const val CONTINUE_GRID_LIMIT = 4
private const val MOOD_MIX_SEARCH_LIMIT = 25
private const val STATION_POOL_LIMIT = 25
private const val GENRE_SHELF_LIMIT = 3

/** Home's "Trends nach Genre" chips. Unlike [MoodFilter] (a keyword search,
 * since no mood metadata exists anywhere in the extraction), these map onto
 * SoundCloud's *real* genre chart identifiers, so each chip yields a genuine
 * per-genre trending chart. [slug] is what goes into `soundcloud:genres:<slug>`. */
enum class GenreFilter(val label: String, val slug: String) {
    HOUSE("House", "house"),
    DUB_TECHNO("Dub Techno", "dubtechno"),
    AMBIENT("Ambient", "ambient"),
    BASS("Bass", "bass"),
    LO_FI("Lo-Fi", "lofi"),
}

/** Mood filter chips on the Mix row -- each (besides "Alle") maps to a plain
 * on-device search keyword rather than real mood/genre metadata (SoundCloud/
 * YouTube's extraction surfaces neither), so tapping one is a real search-backed
 * action, not just a decorative filter. */
enum class MoodFilter(val label: String, val searchKeyword: String?) {
    ALL("Alle", null),
    FOCUS("Fokus", "focus music"),
    CHILL("Chill", "chill music"),
    WORKOUT("Workout", "workout music"),
    PARTY("Party", "party music"),
}

data class ResumeTrack(
    val title: String,
    val artist: String?,
    val artworkUrl: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val statusLabel: String,
)

/** Small, fixed set of resume-card labels rather than always "Weiter hören":
 * actively playing gets its own label, and a track that's essentially at its
 * start (skipped back to 0, or just queued) reads oddly as "keep listening" so
 * it falls back to "Zuletzt gespielt" instead. No new signal needed - just
 * [ResumeTrack.isPlaying]/positionMs, already tracked for the progress bar. */
private fun resumeStatusLabel(isPlaying: Boolean, positionMs: Long): String = when {
    isPlaying -> "Läuft gerade"
    positionMs < 5_000L -> "Zuletzt gespielt"
    else -> "Weiter hören"
}

/** One entry of Home's "Neu von Künstlern, die du hörst" avatar rail. [source]/
 * [sourceId] are only known for real follows ([FollowRepository]) - the frequency-
 * based fallback used before the user has followed anyone only has a name (from
 * play/like history, see [HomeViewModel.recomputeTopArtists]), so [sourceId] and
 * [thumbnailUrl] stay null there and are resolved on tap instead (best-effort name
 * search, same as [HomeViewModel.onTrackArtistClicked]). */
data class TopArtist(
    val name: String,
    val source: String,
    val sourceId: String?,
    val thumbnailUrl: String?,
)

/** A "Deine Mixes" auto-generated mood mix card. [badge]/[title]/[subtitle] are
 * fixed per [pool]'s mood; tapping shuffle-plays [pool] the same way every other
 * shelf on Home starts playback. [pool] itself comes from whatever real signal
 * exists per mood (see [HomeViewModel.loadMixCards]) - no fabricated tempo/energy
 * value, since the on-device data has no such field. */
data class MixCard(
    val badge: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val pool: List<TrackResultDto>,
)

private enum class DayPart { NIGHT, MORNING, DAY, EVENING }

private fun dayPartFor(hour: Int): DayPart = when (hour) {
    in 5..10 -> DayPart.MORNING
    in 11..17 -> DayPart.DAY
    in 18..22 -> DayPart.EVENING
    else -> DayPart.NIGHT
}

private enum class MeteorologicalSeason { WINTER, SPRING, SUMMER, AUTUMN }

// Meteorological (not astronomical) seasons, standard for German-language usage -
// each starts on the 1st of its first month rather than a shifting solstice date.
private fun seasonFor(month: Int): MeteorologicalSeason = when (month) {
    12, 1, 2 -> MeteorologicalSeason.WINTER
    3, 4, 5 -> MeteorologicalSeason.SPRING
    6, 7, 8 -> MeteorologicalSeason.SUMMER
    else -> MeteorologicalSeason.AUTUMN
}

/** Gregorian Easter Sunday via the Meeus/Jones/Butcher algorithm - the date shifts
 * every year (22 March-25 April), so Karfreitag/Ostern/Ostermontag can't be
 * hardcoded like the fixed-date holidays below. */
private fun easterSunday(year: Int): LocalDate {
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

/** Deterministic-per-day pick from [pool] (via the date's epoch day), so the
 * greeting varies day to day and across pools without flickering between
 * recompositions of the same day, and without needing a stored random seed. */
private fun <T> pickForDay(date: LocalDate, pool: List<T>): T {
    val index = ((date.toEpochDay() % pool.size) + pool.size).toInt() % pool.size
    return pool[index]
}

/** Exact-date German holidays (Neujahr/Heiligabend/Weihnachten/Silvester) plus the
 * Easter-anchored ones (Karfreitag/Ostern/Ostermontag) - checked before the plain
 * time-of-day/season pool below, since a holiday greeting is more specific and more
 * fun than "Guten Tag" once a year. */
private fun holidayGreetingFor(date: LocalDate): String? {
    val year = date.year
    val easter = easterSunday(year)
    return when (date) {
        LocalDate.of(year, 1, 1) -> pickForDay(date, listOf("Frohes neues Jahr!", "Ein gutes neues Jahr!"))
        LocalDate.of(year, 12, 24) -> pickForDay(date, listOf("Frohe Weihnachten!", "Schönen Heiligabend!"))
        LocalDate.of(year, 12, 25), LocalDate.of(year, 12, 26) ->
            pickForDay(date, listOf("Frohe Weihnachten!", "Schöne Feiertage!"))
        LocalDate.of(year, 12, 31) -> pickForDay(date, listOf("Guten Rutsch!", "Bis nächstes Jahr!"))
        easter.minusDays(2) -> pickForDay(date, listOf("Schönen Karfreitag"))
        easter -> pickForDay(date, listOf("Frohe Ostern!", "Schöne Ostertage!"))
        easter.plusDays(1) -> pickForDay(date, listOf("Schönen Ostermontag!"))
        else -> null
    }
}

private fun greetingPoolFor(part: DayPart, season: MeteorologicalSeason): List<String> = when (part) {
    DayPart.MORNING -> when (season) {
        MeteorologicalSeason.WINTER -> listOf("Guten Morgen", "Guten Morgen, frostig heute", "Einen warmen guten Morgen")
        MeteorologicalSeason.SPRING -> listOf("Guten Morgen", "Guten Morgen, der Frühling ruft", "Frischer Morgen")
        MeteorologicalSeason.SUMMER -> listOf("Guten Morgen", "Guten Morgen, schon warm draußen", "Sonniger Morgen")
        MeteorologicalSeason.AUTUMN -> listOf("Guten Morgen", "Guten Morgen, herbstlich heute", "Kühler Morgen")
    }
    DayPart.DAY -> when (season) {
        MeteorologicalSeason.WINTER -> listOf("Guten Tag", "Schönen, kalten Tag", "Hallo")
        MeteorologicalSeason.SPRING -> listOf("Guten Tag", "Schönen Frühlingstag", "Hallo")
        MeteorologicalSeason.SUMMER -> listOf("Guten Tag", "Schönen Sommertag", "Hallo")
        MeteorologicalSeason.AUTUMN -> listOf("Guten Tag", "Schönen Herbsttag", "Hallo")
    }
    DayPart.EVENING -> listOf("Guten Abend", "Schönen Abend", "Feierabend?")
    DayPart.NIGHT -> listOf("Noch wach?", "Gute Nacht", "Nachtschwärmer-Modus an")
}

/** Home's greeting: a real German holiday takes priority over the plain time-of-
 * day/season pool, and [profileName] (if set) is appended to either. A pure
 * function of [now]/[profileName] (no hidden `LocalDateTime.now()`/DataStore reads
 * inside), so it stays easy to reason about/unit-test rather than scattering this
 * logic inline in the ViewModel. */
internal fun greetingFor(now: LocalDateTime, profileName: String?): String {
    val date = now.toLocalDate()
    val base = holidayGreetingFor(date) ?: pickForDay(date, greetingPoolFor(dayPartFor(now.hour), seasonFor(now.monthValue)))
    return if (!profileName.isNullOrBlank()) "$base, $profileName" else base
}

data class HomeUiState(
    val profileName: String = "",
    val greetingText: String = greetingFor(LocalDateTime.now(), null),
    val statLine: String = "",
    val charts: List<TrackResultDto> = emptyList(),
    val isChartsLoading: Boolean = false,
    // "Im Fokus" - purely editorial/curated (top chart tracks) again; the
    // algorithmic personalized pool moved to mixCards' "Fokus-Mix" below.
    val featuredItems: List<TrackResultDto> = emptyList(),
    val mixCards: List<MixCard> = emptyList(),
    // "Empfehlung des Tages": one stable-per-day pick, see updateDailyPick().
    val dailyPick: TrackResultDto? = null,
    val feed: List<FeedItem> = emptyList(),
    val isFeedLoading: Boolean = false,
    val playlists: List<PlaylistOutDto> = emptyList(),
    val isPlaylistsLoading: Boolean = false,
    val recentlyPlayed: List<TrackEntity> = emptyList(),
    val likes: List<LikeOutDto> = emptyList(),
    val isLikesLoading: Boolean = false,
    val resumeTrack: ResumeTrack? = null,
    val topArtists: List<TopArtist> = emptyList(),
    val showMixControls: Boolean = true,
    val showFeatured: Boolean = true,
    val showNewUploads: Boolean = true,
    val isMixLoading: Boolean = false,
    val showWhatsNewBanner: Boolean = false,
    val latestVersionLabel: String = BuildConfig.VERSION_NAME,
    // Track pending an "Zu Playlist hinzufügen" pick, mirroring SearchViewModel's
    // exact same field/dialog pattern (AddToPlaylistDialog is shared, this state
    // shape isn't, since every screen owns its own UI state).
    val trackPendingPlaylistAdd: TrackResultDto? = null,
    // Set once a track-row/avatar "Zum Künstler" action (or a name-based artist
    // lookup for the "Neu von Künstlern" shelf) resolves - the screen navigates on
    // seeing this, then calls onArtistNavigated() to clear it back to null. Same
    // pattern as SearchViewModel.artistNavTarget.
    val artistNavTarget: Pair<String, String>? = null,
    val artistLookupError: String? = null,
    val downloadMessage: String? = null,
    // "Trends nach Genre": the selected chip and that genre's real SoundCloud
    // trending chart. Loaded lazily on first Home composition and again whenever
    // the chip changes, rather than fetching all five genres up front.
    val selectedGenre: GenreFilter = GenreFilter.HOUSE,
    val genreTracks: List<TrackResultDto> = emptyList(),
    val isGenreLoading: Boolean = false,
    // Drives the coral "Offline" badge in Home's app bar.
    val dataSaverMode: Boolean = false,
    // Home's PromoCard about the SoundCloud-HLS download limitation; sticks
    // dismissed, same flag pattern as the Library import banner.
    val scPromoDismissed: Boolean = false,
)

/** Converts a locally-cached [TrackEntity] back into a [TrackResultDto] for playback
 * (and, since the share feature, sharing a real link for it too). */
private fun TrackEntity.toTrackResultDto(): TrackResultDto = TrackResultDto(
    source = source,
    sourceId = sourceId,
    title = title,
    artist = artist,
    album = album,
    durationSec = durationSec,
    thumbnailUrl = thumbnailUrl,
    webpageUrl = webpageUrl,
    genre = genre,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val feedRepository: FeedRepository,
    private val playlistRepository: PlaylistRepository,
    private val likesRepository: LikesRepository,
    private val trackDao: TrackDao,
    private val playerController: PlayerController,
    private val eventReporter: EventReporter,
    private val settingsRepository: SettingsRepository,
    private val followRepository: FollowRepository,
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // A live Room query -- no explicit reload needed, it just emits whenever a
        // track is played anywhere in the app.
        viewModelScope.launch {
            trackDao.observeRecentlyPlayed(RECENTLY_PLAYED_LIMIT).collect { tracks ->
                _uiState.value = _uiState.value.copy(recentlyPlayed = tracks, statLine = weeklyListenStatLine(tracks))
                recomputeTopArtists()
            }
        }
        viewModelScope.launch {
            settingsRepository.profileName.collect { name ->
                _uiState.value = _uiState.value.copy(
                    profileName = name,
                    greetingText = greetingFor(LocalDateTime.now(), name.ifBlank { null }),
                )
            }
        }
        viewModelScope.launch {
            settingsRepository.showMixControls.collect { show ->
                _uiState.value = _uiState.value.copy(showMixControls = show)
            }
        }
        viewModelScope.launch {
            settingsRepository.showFeatured.collect { show ->
                _uiState.value = _uiState.value.copy(showFeatured = show)
            }
        }
        viewModelScope.launch {
            settingsRepository.showNewUploads.collect { show ->
                _uiState.value = _uiState.value.copy(showNewUploads = show)
            }
        }
        viewModelScope.launch {
            settingsRepository.lastSeenVersionCode.collect { seen ->
                _uiState.value = _uiState.value.copy(showWhatsNewBanner = seen in 1 until BuildConfig.VERSION_CODE)
            }
        }
        viewModelScope.launch {
            settingsRepository.dataSaverMode.collect { on ->
                _uiState.value = _uiState.value.copy(dataSaverMode = on)
            }
        }
        viewModelScope.launch {
            settingsRepository.homeScPromoDismissed.collect { dismissed ->
                _uiState.value = _uiState.value.copy(scPromoDismissed = dismissed)
            }
        }
        // Resume card: polls the shared player's live position while a track is
        // loaded (PlaybackUiState itself only emits on play/pause/track-change, not
        // every second of playback) -- a lightweight ticker is simpler here than
        // plumbing a new high-frequency Flow through PlayerController for a single
        // progress bar.
        viewModelScope.launch {
            playerController.playbackState.collect { state ->
                _uiState.value = _uiState.value.copy(
                    resumeTrack = state.title?.let { title ->
                        val positionMs = playerController.currentPositionMs()
                        ResumeTrack(
                            title = title,
                            artist = state.artist,
                            artworkUrl = state.artworkUrl,
                            isPlaying = state.isPlaying,
                            positionMs = positionMs,
                            durationMs = state.durationMs,
                            statusLabel = resumeStatusLabel(state.isPlaying, positionMs),
                        )
                    },
                )
            }
        }
        viewModelScope.launch { runCatching { followRepository.refresh() }.onSuccess { recomputeTopArtists() } }
        viewModelScope.launch {
            while (true) {
                delay(1000)
                _uiState.value.resumeTrack?.let { current ->
                    val positionMs = playerController.currentPositionMs()
                    _uiState.value = _uiState.value.copy(
                        resumeTrack = current.copy(
                            positionMs = positionMs,
                            statusLabel = resumeStatusLabel(current.isPlaying, positionMs),
                        ),
                    )
                }
            }
        }
    }

    /** "Diese Woche: X Std. Y Min gehört" -- estimated from the last-accessed
     * cache's own timestamps rather than a real play-history log (none exists;
     * [dev.schlubbe.musicagent.data.local.entity.TrackEntity] dedups by track,
     * it doesn't record one row per play), so this undercounts replays of the
     * same track within the window. Good enough for a soft home-screen stat. */
    private fun weeklyListenStatLine(tracks: List<TrackEntity>): String {
        val weekAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        val totalSec = tracks.filter { it.lastAccessedAt >= weekAgo }.sumOf { it.durationSec ?: 0 }
        if (totalSec <= 0) return ""
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        return if (hours > 0) "Diese Woche: $hours Std. $minutes Min gehört" else "Diese Woche: $minutes Min gehört"
    }

    /** Powers the "Neu von Künstlern" shelf. Prefers real follows (see
     * FollowRepository) since those are an explicit user action - a real follow
     * carries its own (source, sourceId, thumbnailUrl), so tapping it navigates
     * straight to the artist page and shows a real avatar. Falls back to a
     * frequency-based signal (likes weighted 3x, plays 1x - the same weighting
     * FeedRepository uses for its own "familiar" half) so the shelf isn't empty
     * before the user has followed anyone - those entries only have a name+source
     * (no id/thumbnail yet), resolved on tap by [onArtistAvatarClicked]. Doesn't
     * track real new-upload state either way - the "new" ring is decorative until
     * that exists. */
    private fun recomputeTopArtists() {
        val followed = followRepository.followedArtists.value
        if (followed.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(
                topArtists = followed.take(TOP_ARTIST_LIMIT).map {
                    TopArtist(name = it.name, source = it.source, sourceId = it.sourceId, thumbnailUrl = it.thumbnailUrl)
                },
            )
            return
        }
        val playCounts = _uiState.value.recentlyPlayed.mapNotNull { track -> track.artist?.let { track.source to it } }
            .groupingBy { it }.eachCount()
        val likeCounts = _uiState.value.likes.mapNotNull { like -> like.track.artist?.let { like.track.source to it } }
            .groupingBy { it }.eachCount()
        val combined = (playCounts.keys + likeCounts.keys).associateWith { key ->
            (playCounts[key] ?: 0) + (likeCounts[key] ?: 0) * 3
        }
        _uiState.value = _uiState.value.copy(
            topArtists = combined.entries.sortedByDescending { it.value }.take(TOP_ARTIST_LIMIT).map { (key, _) ->
                TopArtist(name = key.second, source = key.first, sourceId = null, thumbnailUrl = null)
            },
        )
    }

    /** Reloads every shelf backed by a suspend call. Called from the screen on first
     * composition and every time it's revisited (e.g. after coming back from the
     * Player), so it reflects likes/playlist edits made elsewhere. */
    fun refresh() {
        loadCharts()
        loadFeed()
        loadPlaylists()
        loadLikes()
        loadMixCards()
        loadGenreTracks(_uiState.value.selectedGenre)
    }

    /** "Trends nach Genre" chip tap. Reloads only that shelf; the chip genuinely
     * reselects the list rather than filtering an already-fetched set, since each
     * genre is a separate SoundCloud chart. */
    fun onGenreSelected(genre: GenreFilter) {
        if (genre == _uiState.value.selectedGenre && _uiState.value.genreTracks.isNotEmpty()) return
        _uiState.value = _uiState.value.copy(selectedGenre = genre)
        loadGenreTracks(genre)
    }

    fun dismissScPromo() {
        viewModelScope.launch { settingsRepository.setHomeScPromoDismissed(true) }
    }

    private fun loadGenreTracks(genre: GenreFilter) {
        _uiState.value = _uiState.value.copy(isGenreLoading = true)
        viewModelScope.launch {
            runCatching { searchRepository.getTrendingByGenre(genre.slug, limit = GENRE_SHELF_LIMIT) }
                .onSuccess { tracks ->
                    // Guard against a slow response for a genre the user has since
                    // switched away from overwriting the current one.
                    if (_uiState.value.selectedGenre == genre) {
                        _uiState.value = _uiState.value.copy(genreTracks = tracks, isGenreLoading = false)
                    }
                }
                .onFailure { e ->
                    android.util.Log.w("HomeViewModel", "loadGenreTracks(${genre.slug}) failed", e)
                    if (_uiState.value.selectedGenre == genre) {
                        _uiState.value = _uiState.value.copy(genreTracks = emptyList(), isGenreLoading = false)
                    }
                }
        }
    }

    // Global trending charts, not tied to any local history - what a freshly
    // installed app has to show on Home before there's any play/like history to
    // build a feed from at all. YouTube-Music-only: SoundCloud's own "trending"
    // charts skew toward tracks with locked/DRM-only transcodings often enough
    // that mixing them in here made Charts unreliable to tap into.
    private fun loadCharts() {
        _uiState.value = _uiState.value.copy(isChartsLoading = true)
        viewModelScope.launch {
            runCatching { searchRepository.getTrending(source = "ytmusic") }
                .onSuccess { tracks ->
                    _uiState.value = _uiState.value.copy(
                        charts = tracks,
                        isChartsLoading = false,
                        // "Im Fokus": purely editorial/curated again, no algorithmic
                        // card mixed in (that now lives in "Deine Mixes", see
                        // loadMixCards()).
                        featuredItems = tracks.take(FEATURED_CHART_COUNT),
                    )
                    updateDailyPick()
                }
                .onFailure { e ->
                    android.util.Log.w("HomeViewModel", "loadCharts failed", e)
                    _uiState.value = _uiState.value.copy(isChartsLoading = false)
                }
        }
    }

    /** "Empfehlung des Tages": one spotlight pick, stable across the day (same
     * [pickForDay] trick the greeting uses, so it doesn't flicker between
     * refreshes) rather than a fresh random pick each time. Prefers the "Für dich"
     * affinity pool (already algorithmic/personalized, no fabricated stats needed
     * for the subtitle - just the track's own artist/source) and falls back to the
     * editorial charts pool before either has loaded. Called after both loadCharts
     * and loadFeed succeed, whichever lands second wins with the richer pool. */
    private fun updateDailyPick() {
        val pool = _uiState.value.feed.map { it.track }.ifEmpty { _uiState.value.charts }
        if (pool.isEmpty()) return
        _uiState.value = _uiState.value.copy(dailyPick = pickForDay(LocalDate.now(), pool))
    }

    /** "Deine Mixes": 3 mood-based auto-generated pools.
     * - Fokus-Mix reuses [FeedRepository.getPersonalizedMix] verbatim (the same
     *   top-artist/genre-affinity pool that used to live in "Im Fokus"'s "Dein
     *   Mix" card) - the only pool actually backed by the user's own taste signal.
     * - Chill-/Party-Mix reuse the exact same on-device search keywords
     *   [MoodFilter.CHILL]/[MoodFilter.PARTY] already use for the mood chips
     *   below "Mix starten" - there's no real energy/tempo signal in the local
     *   data to bias a pool by, so a mood-keyword search is the honest option
     *   rather than a fabricated one. */
    private fun loadMixCards() {
        viewModelScope.launch {
            val focusPool = runCatching { feedRepository.getPersonalizedMix() }.getOrDefault(emptyList())
            val chillPool = runCatching { searchRepository.search(MoodFilter.CHILL.searchKeyword!!, limit = MOOD_MIX_SEARCH_LIMIT) }
                .getOrDefault(emptyList())
            val partyPool = runCatching { searchRepository.search(MoodFilter.PARTY.searchKeyword!!, limit = MOOD_MIX_SEARCH_LIMIT) }
                .getOrDefault(emptyList())

            val cards = buildList {
                if (focusPool.size >= MIX_POOL_MINIMUM) {
                    add(
                        MixCard(
                            badge = "FOKUS",
                            title = "Fokus-Mix",
                            subtitle = "Persönlich für dich",
                            thumbnailUrl = focusPool.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl,
                            pool = focusPool,
                        ),
                    )
                }
                if (chillPool.isNotEmpty()) {
                    add(
                        MixCard(
                            badge = "CHILL",
                            title = "Chill-Mix",
                            subtitle = "Entspannt durch den Tag",
                            thumbnailUrl = chillPool.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl,
                            pool = chillPool,
                        ),
                    )
                }
                if (partyPool.isNotEmpty()) {
                    add(
                        MixCard(
                            badge = "PARTY",
                            title = "Party-Mix",
                            subtitle = "Energie für deine Playlist",
                            thumbnailUrl = partyPool.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl,
                            pool = partyPool,
                        ),
                    )
                }
            }
            _uiState.value = _uiState.value.copy(mixCards = cards)
        }
    }

    private fun loadFeed() {
        _uiState.value = _uiState.value.copy(isFeedLoading = true)
        viewModelScope.launch {
            runCatching { feedRepository.getFeed() }
                .onSuccess { items ->
                    val shelf = items.take(SHELF_LIMIT)
                    _uiState.value = _uiState.value.copy(feed = shelf, isFeedLoading = false)
                    shelf.forEach { eventReporter.feedImpression(it.track) }
                    updateDailyPick()
                }
                .onFailure { _uiState.value = _uiState.value.copy(isFeedLoading = false) }
        }
    }

    private fun loadPlaylists() {
        _uiState.value = _uiState.value.copy(isPlaylistsLoading = true)
        viewModelScope.launch {
            runCatching { playlistRepository.list() }
                .onSuccess { playlists ->
                    _uiState.value = _uiState.value.copy(
                        playlists = playlists.take(SHELF_LIMIT),
                        isPlaylistsLoading = false,
                    )
                }
                .onFailure { _uiState.value = _uiState.value.copy(isPlaylistsLoading = false) }
        }
    }

    private fun loadLikes() {
        _uiState.value = _uiState.value.copy(isLikesLoading = true)
        viewModelScope.launch {
            runCatching { likesRepository.refresh() }
                .onSuccess { likes ->
                    _uiState.value = _uiState.value.copy(
                        likes = likes.take(SHELF_LIMIT),
                        isLikesLoading = false,
                    )
                    recomputeTopArtists()
                }
                .onFailure { _uiState.value = _uiState.value.copy(isLikesLoading = false) }
        }
    }

    fun onChartTrackClicked(track: TrackResultDto) {
        viewModelScope.launch {
            val queue = _uiState.value.charts
            val index = queue.indexOfFirst { it.source == track.source && it.sourceId == track.sourceId }
            if (index >= 0) playerController.playQueue(queue, index) else playerController.playTrack(track)
        }
    }

    fun onFeedTrackClicked(item: FeedItem) {
        eventReporter.feedClick(item.track)
        viewModelScope.launch {
            val queue = _uiState.value.feed.map { it.track }
            val index = queue.indexOfFirst { it.source == item.track.source && it.sourceId == item.track.sourceId }
            if (index >= 0) playerController.playQueue(queue, index) else playerController.playTrack(item.track)
        }
    }

    fun onRecentlyPlayedClicked(track: TrackEntity) {
        viewModelScope.launch {
            val queue = _uiState.value.recentlyPlayed.map { it.toTrackResultDto() }
            val index = _uiState.value.recentlyPlayed.indexOfFirst { it.id == track.id }
            if (index >= 0) {
                playerController.playQueue(queue, index)
            } else {
                playerController.playTrack(track.toTrackResultDto())
            }
        }
    }

    fun onLikeClicked(like: LikeOutDto) {
        viewModelScope.launch {
            val queue = _uiState.value.likes.map { it.track.toTrackResultDto() }
            val index = _uiState.value.likes.indexOfFirst { it.track.id == like.track.id }
            if (index >= 0) {
                playerController.playQueue(queue, index)
            } else {
                playerController.playTrack(like.track.toTrackResultDto())
            }
        }
    }

    /** Called both when the banner's × is tapped (dismiss only) and when the banner
     * itself is tapped to open the full What's New screen -- either way there's
     * nothing more to surface for this version once it's been seen once. */
    fun onWhatsNewBannerSeen() {
        viewModelScope.launch { settingsRepository.setLastSeenVersionCode(BuildConfig.VERSION_CODE) }
    }

    fun onResumeCardClicked() {
        viewModelScope.launch { playerController.togglePlayPause() }
    }

    /** "Mix starten" with a mood chip selected: a real on-device search using that
     * mood's keyword (see [MoodFilter]), queued and shuffled. "Alle" (or the plain
     * Mix-starten button) instead shuffles the Charts+Für-dich shelves already
     * loaded, needing no extra network round-trip. */
    fun onMoodChipClicked(mood: MoodFilter) {
        _uiState.value = _uiState.value.copy(isMixLoading = true)
        viewModelScope.launch {
            val queue = if (mood.searchKeyword == null) {
                (_uiState.value.charts + _uiState.value.feed.map { it.track }).shuffled()
            } else {
                runCatching { searchRepository.search(mood.searchKeyword, limit = 25) }
                    .getOrDefault(emptyList())
                    .shuffled()
            }
            _uiState.value = _uiState.value.copy(isMixLoading = false)
            if (queue.isNotEmpty()) playerController.playQueue(queue, 0)
        }
    }

    /** "Deine Mixes" card tap: shuffle-plays its whole pool, same "starts playback
     * the same way other shelves do" contract as every other tap on Home. */
    fun onMixCardClicked(mix: MixCard) {
        viewModelScope.launch {
            if (mix.pool.isNotEmpty()) playerController.playQueue(mix.pool.shuffled(), 0)
        }
    }

    /** "Empfehlung des Tages" tap: plays the pick queued from whichever shelf its
     * pool came from (see [updateDailyPick]) so skip-next continues into that
     * shelf's other tracks, same as tapping the pick straight from its own shelf
     * would. */
    fun onDailyPickClicked() {
        val track = _uiState.value.dailyPick ?: return
        eventReporter.feedClick(track)
        viewModelScope.launch {
            val feedQueue = _uiState.value.feed.map { it.track }
            val queue = if (feedQueue.any { it.source == track.source && it.sourceId == track.sourceId }) {
                feedQueue
            } else {
                _uiState.value.charts
            }
            val index = queue.indexOfFirst { it.source == track.source && it.sourceId == track.sourceId }
            if (index >= 0) playerController.playQueue(queue, index) else playerController.playTrack(track)
        }
    }

    /** "Sender entdecken" tile tap: a lightweight artist-seeded "station" - a
     * fresh, shuffled search for that artist's own tracks (same search call
     * [FeedRepository.getFeed] already uses for its "familiar" half), not a real
     * radio/similar-artist algorithm (no such signal exists on-device). */
    fun onStationClicked(artist: TopArtist) {
        viewModelScope.launch {
            val tracks = runCatching { searchRepository.search(artist.name, source = artist.source, limit = STATION_POOL_LIMIT) }
                .getOrDefault(emptyList())
                .shuffled()
            if (tracks.isNotEmpty()) playerController.playQueue(tracks, 0)
        }
    }

    // --- Artist navigation (avatar rail + "Zum Künstler" on any track's overflow
    // menu) - same best-effort name-search resolution SearchViewModel.
    // onTrackArtistClicked uses, since a name search can match a different
    // account with the same display name (common on SoundCloud), not necessarily
    // the actual uploader.

    /** "Neu von Künstlern" avatar tap. Real follows already carry a (source,
     * sourceId) - navigate straight there. The frequency-based fallback (see
     * [recomputeTopArtists]) only has a name, so it's resolved the same way
     * [onTrackArtistClicked] resolves a track's artist name. */
    fun onArtistAvatarClicked(artist: TopArtist) {
        if (artist.sourceId != null) {
            _uiState.value = _uiState.value.copy(artistNavTarget = artist.source to artist.sourceId)
            return
        }
        resolveArtistByName(artist.name, artist.source)
    }

    fun onTrackArtistClicked(track: TrackResultDto) {
        val name = track.artist ?: return
        resolveArtistByName(name, track.source)
    }

    private fun resolveArtistByName(name: String, source: String) {
        viewModelScope.launch {
            runCatching { searchRepository.searchArtists(name, source = source, limit = 1) }
                .onSuccess { artists ->
                    val artist = artists.firstOrNull()
                    _uiState.value = if (artist != null) {
                        _uiState.value.copy(artistNavTarget = artist.source to artist.sourceId)
                    } else {
                        _uiState.value.copy(artistLookupError = "Künstler \"$name\" nicht gefunden")
                    }
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(artistLookupError = "Künstler konnte nicht geladen werden")
                }
        }
    }

    fun onArtistNavigated() {
        _uiState.value = _uiState.value.copy(artistNavTarget = null)
    }

    fun onArtistLookupErrorShown() {
        _uiState.value = _uiState.value.copy(artistLookupError = null)
    }

    // --- Per-track overflow menu (Charts/Für dich/Zuletzt gehört/Deine Likes
    // shelves) - same action set as every other track row in the app (Search,
    // Library, Playlist detail): add to playlist, add to queue, download, share.
    // "Zum Künstler" reuses onTrackArtistClicked above.

    fun onAddToQueueClicked(track: TrackResultDto) {
        viewModelScope.launch { runCatching { playerController.addToQueue(track) } }
    }

    fun onDownloadClicked(track: TrackResultDto) {
        downloadRepository.startDownload(track)
    }

    fun onAddToPlaylistClicked(track: TrackResultDto) {
        _uiState.value = _uiState.value.copy(trackPendingPlaylistAdd = track)
        viewModelScope.launch {
            runCatching { playlistRepository.list() }.onSuccess { playlists ->
                _uiState.value = _uiState.value.copy(playlists = playlists)
            }
        }
    }

    fun dismissAddToPlaylist() {
        _uiState.value = _uiState.value.copy(trackPendingPlaylistAdd = null)
    }

    fun onPlaylistPicked(playlistId: String) {
        val track = _uiState.value.trackPendingPlaylistAdd ?: return
        viewModelScope.launch {
            runCatching { playlistRepository.addTrack(playlistId, track) }
            _uiState.value = _uiState.value.copy(trackPendingPlaylistAdd = null)
        }
    }

    fun onCreatePlaylistAndAdd(name: String) {
        val track = _uiState.value.trackPendingPlaylistAdd ?: return
        viewModelScope.launch {
            runCatching {
                val playlist = playlistRepository.create(name)
                playlistRepository.addTrack(playlist.id, track)
            }
            _uiState.value = _uiState.value.copy(trackPendingPlaylistAdd = null)
            loadPlaylists()
        }
    }

    /** "Deine Likes" shelf overflow menu's "Nicht mehr gefällt mir". */
    fun onUnlikeClicked(like: LikeOutDto) {
        viewModelScope.launch {
            runCatching { likesRepository.unlike(like.track.toTrackResultDto()) }
            loadLikes()
        }
    }

    // --- "Deine Playlists" card actions: a direct download-all icon, plus a
    // "..." menu with the same add-to-queue/delete actions PlaylistDetailScreen's
    // own overflow menu already offers for a playlist.

    fun onDownloadPlaylistClicked(playlistId: String) {
        viewModelScope.launch {
            val tracks = runCatching { playlistRepository.get(playlistId) }.getOrNull()?.tracks
                ?.map { it.track.toTrackResultDto() }
                ?: return@launch
            if (tracks.isEmpty()) return@launch
            downloadRepository.startDownloadAll(tracks)
            _uiState.value = _uiState.value.copy(downloadMessage = "${tracks.size} Titel werden heruntergeladen")
        }
    }

    fun onAddPlaylistToQueueClicked(playlistId: String) {
        viewModelScope.launch {
            val tracks = runCatching { playlistRepository.get(playlistId) }.getOrNull()?.tracks
                ?.map { it.track.toTrackResultDto() }
                ?: return@launch
            tracks.forEach { runCatching { playerController.addToQueue(it) } }
        }
    }

    fun onDeletePlaylistClicked(playlistId: String) {
        viewModelScope.launch {
            runCatching { playlistRepository.delete(playlistId) }
            loadPlaylists()
        }
    }

    fun onDownloadMessageShown() {
        _uiState.value = _uiState.value.copy(downloadMessage = null)
    }
}
