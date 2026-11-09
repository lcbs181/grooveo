package dev.schlubbe.musicagent.ui.podcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.local.entity.PodcastProgressEntity
import dev.schlubbe.musicagent.data.local.entity.PodcastShowEntity
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.playback.PlayerController
import dev.schlubbe.musicagent.podcast.PODCAST_SOURCE
import dev.schlubbe.musicagent.podcast.PodcastRepository
import dev.schlubbe.musicagent.podcast.PodcastShow
import dev.schlubbe.musicagent.podcast.ShowId
import dev.schlubbe.musicagent.podcast.ShowKind
import dev.schlubbe.musicagent.podcast.isWebUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PodcastsUiState(
    val kind: ShowKind = ShowKind.PODCAST,
    val query: String = "",
    val results: List<PodcastShow> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
    val topPodcasts: List<PodcastShow> = emptyList(),
    val hoerspiele: List<PodcastShow> = emptyList(),
    val kinder: List<PodcastShow> = emptyList(),
    val hoerbuecher: List<PodcastShow> = emptyList(),
    val klassiker: List<PodcastShow> = emptyList(),
    val browseLoading: Boolean = false,
    val addingFeed: Boolean = false,
    val feedError: String? = null,
    // set once a feed URL was added: the screen opens it
    val openShowId: String? = null,
)

@HiltViewModel
class PodcastsViewModel @Inject constructor(
    private val repository: PodcastRepository,
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

    fun loadBrowse() {
        _uiState.update { it.copy(browseLoading = true) }
        viewModelScope.launch {
            val top = runCatching { repository.topPodcasts() }.getOrDefault(emptyList())
            val hoer = runCatching { repository.ardCategory(PodcastRepository.ARD_HOERSPIEL) }.getOrDefault(emptyList())
            val kids = runCatching { repository.ardCategory(PodcastRepository.ARD_KINDER) }.getOrDefault(emptyList())
            _uiState.update { it.copy(topPodcasts = top, hoerspiele = hoer, kinder = kids) }
            val books = runCatching { repository.ardRecentShows(PodcastRepository.ARD_HOERBUCH, ShowKind.HOERBUCH) }.getOrDefault(emptyList())
            val classics = runCatching { repository.libriVoxClassics() }.getOrDefault(emptyList())
            _uiState.update { it.copy(hoerbuecher = books, klassiker = classics, browseLoading = false) }
        }
    }

    fun setKind(kind: ShowKind) {
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
                }
                _uiState.update { it.copy(results = results, searching = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(searching = false, error = "Suche fehlgeschlagen – bist du online?") }
            }
        }
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
}
