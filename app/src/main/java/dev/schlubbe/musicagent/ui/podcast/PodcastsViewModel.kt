package dev.schlubbe.musicagent.ui.podcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.local.entity.PodcastProgressEntity
import dev.schlubbe.musicagent.data.local.entity.PodcastShowEntity
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.playback.PlayerController
import dev.schlubbe.musicagent.podcast.ForYouFeed
import dev.schlubbe.musicagent.podcast.ForYouItem
import dev.schlubbe.musicagent.podcast.PODCAST_SOURCE
import dev.schlubbe.musicagent.podcast.PodcastEpisode
import dev.schlubbe.musicagent.podcast.PodcastRepository
import dev.schlubbe.musicagent.podcast.PodcastShow
import dev.schlubbe.musicagent.podcast.ShowId
import dev.schlubbe.musicagent.podcast.ShowKind
import dev.schlubbe.musicagent.podcast.isWebUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One browse shelf of the discover page; [loaded] tells a slow source from an empty one. */
data class Shelf(
    val key: String,
    val title: String,
    val subtitle: String,
    val kind: ShowKind,
    val shows: List<PodcastShow> = emptyList(),
    val loaded: Boolean = false,
)

data class PodcastsUiState(
    // null = everything ("Alle")
    val kind: ShowKind? = null,
    val query: String = "",
    val results: List<PodcastShow> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
    val shelves: List<Shelf> = BROWSE_SHELVES,
    val forYou: List<ForYouItem> = emptyList(),
    val forYouLoaded: Boolean = false,
    val addingFeed: Boolean = false,
    val feedError: String? = null,
    // set once a feed URL was added: the screen opens it
    val openShowId: String? = null,
)

// fixed order of the discover page; the loaders below fill them in by key
private val BROWSE_SHELVES = listOf(
    Shelf("top", "Angesagte Podcasts", "Was gerade viele hören", ShowKind.PODCAST),
    Shelf("newHoerspiele", "Neue Hörspiele", "Gerade erschienen in der ARD Audiothek", ShowKind.HOERSPIEL),
    Shelf("newHoerbuecher", "Neue Hörbücher", "Lesungen und Hörbücher der ARD", ShowKind.HOERBUCH),
    Shelf("wissen", "Wissen", "Podcasts zum Schlauerwerden", ShowKind.PODCAST),
    Shelf("hoerspiele", "Große Hörspielreihen", "Krimis, Thriller und Fantasy in vielen Teilen", ShowKind.HOERSPIEL),
    Shelf("kinder", "Für Kinder", "Hörspiele und Geschichten für Kinder", ShowKind.HOERSPIEL),
    Shelf("comedy", "Comedy & Satire", "Zum Lachen", ShowKind.PODCAST),
    Shelf("klassiker", "Klassiker, frei vorgelesen", "Kafka, Fontane, Grimm und mehr bei LibriVox", ShowKind.HOERBUCH),
    Shelf("doku", "Dokus & Reportagen", "Echte Geschichten, gut erzählt", ShowKind.PODCAST),
)

@HiltViewModel
class PodcastsViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val forYouFeed: ForYouFeed,
    private val playerController: PlayerController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PodcastsUiState())
    val uiState: StateFlow<PodcastsUiState> = _uiState.asStateFlow()

    val subscriptions: StateFlow<List<PodcastShowEntity>> = repository.observeSubscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val inProgress: StateFlow<List<PodcastProgressEntity>> = repository.observeInProgress()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var searchJob: Job? = null

    init {
        loadBrowse()
    }

    /** All shelves at once; each one shows up as soon as its source answered. */
    fun loadBrowse() {
        viewModelScope.launch {
            val items = runCatching { forYouFeed.load() }.getOrDefault(emptyList())
            _uiState.update { it.copy(forYou = items, forYouLoaded = true) }
        }
        val loaders: Map<String, suspend () -> List<PodcastShow>> = mapOf(
            "top" to { repository.topPodcasts() },
            "newHoerspiele" to { repository.ardRecentShows(PodcastRepository.ARD_HOERSPIEL, ShowKind.HOERSPIEL) },
            "newHoerbuecher" to { repository.ardRecentShows(PodcastRepository.ARD_HOERBUCH, ShowKind.HOERBUCH) },
            "wissen" to { repository.ardCategory(PodcastRepository.ARD_WISSEN, ShowKind.PODCAST) },
            "hoerspiele" to { repository.ardCategory(PodcastRepository.ARD_HOERSPIEL) },
            "kinder" to { repository.ardCategory(PodcastRepository.ARD_KINDER) },
            "comedy" to { repository.ardCategory(PodcastRepository.ARD_COMEDY, ShowKind.PODCAST) },
            "klassiker" to { repository.libriVoxClassics() },
            "doku" to { repository.ardCategory(PodcastRepository.ARD_DOKU, ShowKind.PODCAST) },
        )
        loaders.forEach { (key, load) ->
            viewModelScope.launch {
                val shows = try {
                    load().take(SHELF_SIZE)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                _uiState.update { state ->
                    state.copy(shelves = state.shelves.map { if (it.key == key) it.copy(shows = shows, loaded = true) else it })
                }
            }
        }
    }

    /** A genre tile: searches it right away, in the tile's kind. */
    fun browseGenre(query: String, kind: ShowKind) {
        _uiState.update { it.copy(kind = kind, query = query, results = emptyList()) }
        search(immediate = true)
    }

    fun play(episode: PodcastEpisode) {
        viewModelScope.launch {
            val show = runCatching { repository.loadShow(episode.showId) }.getOrNull()
            val index = show?.episodes?.indexOfFirst { it.audioUrl == episode.audioUrl } ?: -1
            val following = if (show != null && index >= 0) show.following(index).take(10) else emptyList()
            playerController.playQueue((listOf(episode) + following).map { it.toTrack() }, 0)
        }
    }

    fun setKind(kind: ShowKind?) {
        if (kind == _uiState.value.kind) return
        _uiState.update { it.copy(kind = kind, results = emptyList()) }
        search(immediate = true)
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
        search(immediate = false)
    }

    fun search(immediate: Boolean = true) {
        searchJob?.cancel()
        val query = _uiState.value.query.trim()
        if (query.length < 2) {
            _uiState.update { it.copy(results = emptyList(), searching = false, error = null) }
            return
        }
        searchJob = viewModelScope.launch {
            if (!immediate) delay(400)
            _uiState.update { it.copy(searching = true, error = null) }
            val kind = _uiState.value.kind
            try {
                val results = when (kind) {
                    ShowKind.PODCAST -> repository.searchPodcasts(query)
                    ShowKind.HOERSPIEL -> repository.searchHoerspiele(query)
                    ShowKind.HOERBUCH -> repository.searchHoerbuecher(query)
                    null -> searchEverything(query)
                }
                _uiState.update { it.copy(results = results, searching = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(searching = false, error = "Suche fehlgeschlagen – bist du online?") }
            }
        }
    }

    /** "Alle": the three searches side by side; one that fails is left out, unless all do. */
    private suspend fun searchEverything(query: String): List<PodcastShow> = coroutineScope {
        val parts = listOf(
            async { runCatching { repository.searchHoerspiele(query) } },
            async { runCatching { repository.searchHoerbuecher(query) } },
            async { runCatching { repository.searchPodcasts(query) } },
        ).awaitAll()
        if (parts.all { it.isFailure }) parts.first().getOrThrow()
        parts.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.id }
    }

    /** "RSS-Feed hinzufügen": checks that the address really is a podcast feed first. */
    fun addFeed(input: String) {
        val trimmed = input.trim()
        val url = if ("://" in trimmed) trimmed else "https://$trimmed"
        if (!isWebUrl(url)) {
            _uiState.update { it.copy(feedError = "Das ist keine Web-Adresse") }
            return
        }
        _uiState.update { it.copy(addingFeed = true, feedError = null) }
        viewModelScope.launch {
            try {
                val id = ShowId.Rss(url).toString()
                val loaded = repository.loadShow(id, forceRefresh = true)
                repository.subscribe(loaded.show, loaded.latestPublishedAt)
                _uiState.update { it.copy(addingFeed = false, openShowId = id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(addingFeed = false, feedError = e.message ?: "Feed konnte nicht geladen werden") }
            }
        }
    }

    fun clearFeedError() = _uiState.update { it.copy(feedError = null) }

    fun onShowOpened() = _uiState.update { it.copy(openShowId = null) }

    fun markHeard(progress: PodcastProgressEntity) {
        viewModelScope.launch { repository.markHeard(progress) }
    }

    fun forget(progress: PodcastProgressEntity) {
        viewModelScope.launch { repository.forgetProgress(progress.audioUrl) }
    }

    fun resume(progress: PodcastProgressEntity) {
        val track = TrackResultDto(
            source = PODCAST_SOURCE,
            sourceId = progress.audioUrl,
            title = progress.title,
            artist = progress.showTitle,
            album = progress.showId,
            durationSec = (progress.durationMs / 1000).toInt().takeIf { it > 0 },
            thumbnailUrl = progress.imageUrl,
            webpageUrl = progress.audioUrl,
        )
        viewModelScope.launch { playerController.playTrack(track) }
    }

    private companion object {
        const val SHELF_SIZE = 15
    }
}
