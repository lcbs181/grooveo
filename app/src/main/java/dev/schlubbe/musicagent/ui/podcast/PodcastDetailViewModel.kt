package dev.schlubbe.musicagent.ui.podcast

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.local.entity.PodcastProgressEntity
import dev.schlubbe.musicagent.data.repository.DownloadRepository
import dev.schlubbe.musicagent.playback.PlayerController
import dev.schlubbe.musicagent.podcast.PodcastEpisode
import dev.schlubbe.musicagent.podcast.PodcastRepository
import dev.schlubbe.musicagent.podcast.ShowWithEpisodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PodcastDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val data: ShowWithEpisodes? = null,
    val message: String? = null,
)

@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PodcastRepository,
    private val playerController: PlayerController,
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    // Navigation hands the route argument over still percent-encoded on some paths
    val showId: String = savedStateHandle.get<String>("showId").orEmpty()
        .let { if (':' in it) it else android.net.Uri.decode(it) }

    private val _uiState = MutableStateFlow(PodcastDetailUiState())
    val uiState: StateFlow<PodcastDetailUiState> = _uiState.asStateFlow()

    val isSubscribed: StateFlow<Boolean> = repository.observeSubscription(showId).map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val progress: StateFlow<Map<String, PodcastProgressEntity>> = repository.observeProgressForShow(showId)
        .map { list -> list.associateBy { it.audioUrl } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        load(forceRefresh = false)
    }

    fun load(forceRefresh: Boolean = true): Job {
        _uiState.update { it.copy(loading = it.data == null, error = null) }
        return viewModelScope.launch {
            try {
                val data = repository.loadShow(showId, forceRefresh)
                _uiState.update { it.copy(loading = false, data = data) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message ?: "Konnte nicht geladen werden") }
            }
        }
    }

    /** Plays [episode], then the episodes published after it (a series in order). */
    fun play(episode: PodcastEpisode) {
        val episodes = _uiState.value.data?.episodes ?: return
        val index = episodes.indexOf(episode).takeIf { it >= 0 } ?: return
        val heard = progress.value.filterValues { it.completed }.keys
        // the tapped one always, then the newer ones not heard yet
        val following = listOf(episode.toTrack()) +
            (index - 1 downTo 0).map { episodes[it] }.filter { it.audioUrl !in heard }.take(MAX_FOLLOWING).map { it.toTrack() }
        viewModelScope.launch { playerController.playQueue(following, 0) }
    }

    fun addToQueue(episode: PodcastEpisode) {
        viewModelScope.launch {
            runCatching { playerController.addToQueue(episode.toTrack()) }
            _uiState.update { it.copy(message = "Zur Warteschlange hinzugefügt") }
        }
    }

    fun download(episode: PodcastEpisode) {
        downloadRepository.startDownload(episode.toTrack())
        _uiState.update { it.copy(message = "Download gestartet") }
    }

    fun setPlayed(episode: PodcastEpisode, played: Boolean) {
        viewModelScope.launch { repository.setPlayed(episode, played) }
    }

    fun toggleSubscribed() {
        val data = _uiState.value.data ?: return
        viewModelScope.launch {
            if (isSubscribed.value) {
                repository.unsubscribe(showId)
            } else {
                repository.subscribe(data.show, data.episodes.firstOrNull()?.publishedAt)
            }
        }
    }

    fun onMessageShown() = _uiState.update { it.copy(message = null) }

    private companion object {
        const val MAX_FOLLOWING = 20
    }
}
