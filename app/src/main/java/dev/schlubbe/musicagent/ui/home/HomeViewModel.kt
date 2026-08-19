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
import dev.schlubbe.musicagent.data.repository.EventReporter
import dev.schlubbe.musicagent.data.repository.FeedItem
import dev.schlubbe.musicagent.data.repository.FeedRepository
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.PlaylistRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.playback.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SHELF_LIMIT = 6
private const val RECENTLY_PLAYED_LIMIT = 10

data class HomeUiState(
    val charts: List<TrackResultDto> = emptyList(),
    val isChartsLoading: Boolean = false,
    val feed: List<FeedItem> = emptyList(),
    val isFeedLoading: Boolean = false,
    val playlists: List<PlaylistOutDto> = emptyList(),
    val isPlaylistsLoading: Boolean = false,
    val recentlyPlayed: List<TrackEntity> = emptyList(),
    val likes: List<LikeOutDto> = emptyList(),
    val isLikesLoading: Boolean = false,
)

/** Converts a locally-cached [TrackEntity] back into a [TrackResultDto] for playback.
 * TrackEntity doesn't persist webpage_url since nothing reads that field at runtime
 * (it only ever gets serialized back out to the backend on search results), so an
 * empty placeholder here is safe. */
private fun TrackEntity.toTrackResultDto(): TrackResultDto = TrackResultDto(
    source = source,
    sourceId = sourceId,
    title = title,
    artist = artist,
    album = album,
    durationSec = durationSec,
    thumbnailUrl = thumbnailUrl,
    webpageUrl = "",
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // A live Room query -- no explicit reload needed, it just emits whenever a
        // track is played anywhere in the app.
        viewModelScope.launch {
            trackDao.observeRecentlyPlayed(RECENTLY_PLAYED_LIMIT).collect { tracks ->
                _uiState.value = _uiState.value.copy(recentlyPlayed = tracks)
            }
        }
    }

    /** Reloads every shelf backed by a suspend call. Called from the screen on first
     * composition and every time it's revisited (e.g. after coming back from the
     * Player), so it reflects likes/playlist edits made elsewhere. */
    fun refresh() {
        loadCharts()
        loadFeed()
        loadPlaylists()
        loadLikes()
    }

    // Global trending charts, not tied to any local history - what a freshly
    // installed app has to show on Home before there's any play/like history to
    // build a feed from at all.
    private fun loadCharts() {
        _uiState.value = _uiState.value.copy(isChartsLoading = true)
        viewModelScope.launch {
            runCatching { searchRepository.getTrending() }
                .onSuccess { tracks ->
                    _uiState.value = _uiState.value.copy(charts = tracks, isChartsLoading = false)
                }
                .onFailure { _uiState.value = _uiState.value.copy(isChartsLoading = false) }
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
}
