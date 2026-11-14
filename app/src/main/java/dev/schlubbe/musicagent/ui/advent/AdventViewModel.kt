package dev.schlubbe.musicagent.ui.advent

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.schlubbe.musicagent.BuildConfig
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.playback.PlayerController
import dev.schlubbe.musicagent.podcast.PodcastEpisode
import dev.schlubbe.musicagent.podcast.PodcastRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import javax.inject.Inject

/** Where opening a door leads. */
sealed class AdventTarget {
    data object Player : AdventTarget()
    data class Show(val showId: String) : AdventTarget()
    data class Playlist(val source: String, val sourceId: String) : AdventTarget()
}

data class AdventUiState(
    val today: LocalDate = adventToday(),
    val openAll: Boolean = DEBUG_OPEN_ALL,
    val opened: Set<Int> = emptySet(),
    val covers: Map<Int, String> = emptyMap(),
    // the door whose card is shown
    val selected: Int? = null,
    val busy: Int? = null,
    val error: String? = null,
    val target: AdventTarget? = null,
) {
    /** Days whose door may be opened: in December only up to today. */
    fun canOpen(day: Int): Boolean = openAll || (today.month == Month.DECEMBER && day <= today.dayOfMonth)

    fun daysUntil(day: Int): Long =
        java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.of(today.year, Month.DECEMBER, day))
}

// debug builds open every door and show the calendar all year, for testing
private val DEBUG_OPEN_ALL = BuildConfig.DEBUG

// German calendar days, wherever the phone is
internal fun adventToday(): LocalDate = LocalDate.now(ZoneId.of("Europe/Berlin"))

/** First Sunday of Advent: four Sundays before Christmas, the fourth on or before Dec 24. */
internal fun firstAdvent(year: Int): LocalDate =
    LocalDate.of(year, Month.DECEMBER, 24)
        .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY))
        .minusWeeks(3)

/** The calendar is offered from the first Sunday of Advent to New Year's Eve. */
fun adventSeason(today: LocalDate = adventToday(), always: Boolean = DEBUG_OPEN_ALL): Boolean =
    always || !today.isBefore(firstAdvent(today.year)) // runs until Dec 31 by itself

@HiltViewModel
class AdventViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val podcasts: PodcastRepository,
    private val search: SearchRepository,
    private val playerController: PlayerController,
) : ViewModel() {
    private val prefs = context.getSharedPreferences("advent", Context.MODE_PRIVATE)
    // one calendar per season; its year keys what was opened
    private val season = adventToday().year

    private val _uiState = MutableStateFlow(
        AdventUiState(
            opened = prefs.getStringSet("opened_$season", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet(),
            covers = ADVENT_DOORS.mapNotNull { door -> prefs.getString(coverKey(door), null)?.let { door.day to it } }.toMap(),
        ),
    )
    val uiState: StateFlow<AdventUiState> = _uiState.asStateFlow()

    init {
        // open doors whose cover is not cached (new content, cleared cache)
        _uiState.value.let { state -> state.opened.filter { it !in state.covers }.forEach(::loadCover) }
    }

    /** A tap on a door: the first time it swings open (and its cover is fetched), then
     * its card shows. Doors of later days stay shut. */
    fun onDoorTapped(day: Int) {
        val state = _uiState.value
        if (!state.canOpen(day)) return
        if (day !in state.opened) {
            val opened = state.opened + day
            prefs.edit().putStringSet("opened_$season", opened.map { it.toString() }.toSet()).apply()
            _uiState.update { it.copy(opened = opened) }
            loadCover(day)
        } else {
            _uiState.update { it.copy(selected = day) }
        }
    }

    fun onCardDismissed() = _uiState.update { it.copy(selected = null, error = null) }

    fun onTargetHandled() = _uiState.update { it.copy(target = null, selected = null) }

    private fun loadCover(day: Int) {
        val door = ADVENT_DOORS.first { it.day == day }
        viewModelScope.launch {
            val url = runCatching {
                when (val c = door.content) {
                    is AdventContent.ArdEpisode -> podcasts.ardEpisode(c.itemId)?.imageUrl ?: podcasts.loadShow(c.showId).show.imageUrl
                    is AdventContent.Show -> podcasts.loadShow(c.showId).show.imageUrl
                    is AdventContent.YouTube -> c.cover
                    is AdventContent.Playlist -> playlistFor(c.query)?.thumbnailUrl
                }
            }.getOrNull()?.takeIf { it.startsWith("http") } ?: return@launch
            prefs.edit().putString(coverKey(door), url).apply()
            _uiState.update { it.copy(covers = it.covers + (day to url)) }
        }
    }

    // keyed by the content too, so a door that gets new content fetches its new cover
    private fun coverKey(door: AdventDoor) = "cover_${door.day}_${door.content.hashCode()}"

    /** "Mehr davon": the show or channel behind a door. */
    fun openMore(day: Int) {
        val showId = ADVENT_DOORS.first { it.day == day }.content.showId ?: return
        _uiState.update { it.copy(target = AdventTarget.Show(showId)) }
    }

    /** YouTube Music's playlists are the better curated ones; SoundCloud if it has none. */
    private suspend fun playlistFor(query: String) =
        runCatching { search.searchPlaylists(query, source = "ytmusic", limit = 3) }.getOrDefault(emptyList()).firstOrNull()
            ?: search.searchPlaylists(query, limit = 3).firstOrNull()

    /** "Anhören" on a door's card. */
    fun listen(day: Int) {
        val door = ADVENT_DOORS.first { it.day == day }
        _uiState.update { it.copy(busy = day, error = null) }
        viewModelScope.launch {
            try {
                val target = when (val c = door.content) {
                    is AdventContent.ArdEpisode -> {
                        val episode = podcasts.ardEpisode(c.itemId)
                        if (episode != null) {
                            playerController.playTrack(episode.toTrack())
                            AdventTarget.Player
                        } else {
                            AdventTarget.Show(c.showId) // taken down: the show still has more
                        }
                    }
                    is AdventContent.Show -> AdventTarget.Show(c.showId)
                    is AdventContent.YouTube -> {
                        playerController.playTrack(
                            PodcastEpisode(
                                showId = c.showId,
                                showTitle = c.channel,
                                title = door.title,
                                description = door.teaser,
                                audioUrl = c.url,
                                imageUrl = c.cover,
                                durationSec = c.durationSec,
                                publishedAt = null,
                                link = c.url,
                            ).toTrack(),
                        )
                        AdventTarget.Player
                    }
                    is AdventContent.Playlist -> playlistFor(c.query)
                        ?.let { AdventTarget.Playlist(it.source, it.sourceId) }
                        ?: throw IllegalStateException("Keine Playlist gefunden")
                }
                _uiState.update { it.copy(busy = null, target = target) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = null, error = "Gerade nicht erreichbar – bist du online?") }
            }
        }
    }
}
