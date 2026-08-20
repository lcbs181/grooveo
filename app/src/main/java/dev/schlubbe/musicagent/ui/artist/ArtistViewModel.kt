package dev.schlubbe.musicagent.ui.artist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.ArtistDetailDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.repository.DownloadRepository
import dev.schlubbe.musicagent.data.repository.FollowRepository
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.PlaylistRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.playback.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val artist: ArtistDetailDto? = null,
    val likedTrackIds: Set<String> = emptySet(),
    val playlists: List<PlaylistOutDto> = emptyList(),
    val trackPendingPlaylistAdd: TrackResultDto? = null,
    val navigateToFollowers: Boolean = false,
    val isFollowing: Boolean = false,
)

@HiltViewModel
class ArtistViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val trackDao: TrackDao,
    private val playerController: PlayerController,
    private val likesRepository: LikesRepository,
    private val playlistRepository: PlaylistRepository,
    private val downloadRepository: DownloadRepository,
    private val followRepository: FollowRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArtistUiState())
    val uiState: StateFlow<ArtistUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            likesRepository.likedTrackIds.collect { ids ->
                _uiState.value = _uiState.value.copy(likedTrackIds = ids)
            }
        }
        viewModelScope.launch {
            followRepository.followedIds.collect { ids ->
                val artist = _uiState.value.artist
                if (artist != null) {
                    _uiState.value = _uiState.value.copy(isFollowing = "${artist.source}:${artist.sourceId}" in ids)
                }
            }
        }
        viewModelScope.launch { runCatching { followRepository.refresh() } }
    }

    fun toggleFollow() {
        val artist = _uiState.value.artist ?: return
        viewModelScope.launch {
            followRepository.toggle(artist.source, artist.sourceId, artist.name, artist.thumbnailUrl)
        }
    }

    fun load(source: String, sourceId: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatching { searchRepository.getArtist(source, sourceId) }
                .onSuccess { artist ->
                    _uiState.value = _uiState.value.copy(
                        artist = artist,
                        isLoading = false,
                        isFollowing = "$source:$sourceId" in followRepository.followedIds.value,
                    )
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(isLoading = false, error = e.message) }
        }
    }

    private suspend fun cacheTrack(track: TrackResultDto) {
        trackDao.upsert(
            TrackEntity(
                id = "${track.source}:${track.sourceId}",
                source = track.source,
                sourceId = track.sourceId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationSec = track.durationSec,
                thumbnailUrl = track.thumbnailUrl,
                lastAccessedAt = System.currentTimeMillis(),
                webpageUrl = track.webpageUrl,
            ),
        )
    }

    // Mirrors SearchViewModel.onTrackClicked: tapping a row plays the rest of its
    // shelf (top or latest, whichever the row came from) as the "up next" queue.
    fun onTrackClicked(track: TrackResultDto, queue: List<TrackResultDto>) {
        viewModelScope.launch {
            cacheTrack(track)
            val index = queue.indexOfFirst { it.source == track.source && it.sourceId == track.sourceId }
            if (index >= 0) playerController.playQueue(queue, index) else playerController.playTrack(track)
        }
    }

    fun onLikeToggled(track: TrackResultDto) {
        viewModelScope.launch { runCatching { likesRepository.toggle(track) } }
    }

    fun onAddToQueueClicked(track: TrackResultDto) {
        viewModelScope.launch { runCatching { playerController.addToQueue(track) } }
    }

    fun onDownloadClicked(track: TrackResultDto) {
        downloadRepository.startDownload(track)
    }

    // Follower list only exists for SoundCloud - see SearchRepository.getFollowersPage.
    fun onFollowersClicked() {
        if (_uiState.value.artist?.source != "soundcloud") return
        _uiState.value = _uiState.value.copy(navigateToFollowers = true)
    }

    fun onFollowersNavigated() {
        _uiState.value = _uiState.value.copy(navigateToFollowers = false)
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
        }
    }
}
