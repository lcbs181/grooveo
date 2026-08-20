package dev.schlubbe.musicagent.ui.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.remote.dto.PlaylistDetailOutDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistTrackOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.remote.dto.toTrackResultDto
import dev.schlubbe.musicagent.data.repository.DownloadRepository
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.PlaylistRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.playback.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistDetailUiState(
    val playlist: PlaylistDetailOutDto? = null,
    val isLoading: Boolean = true,
    val likedTrackIds: Set<String> = emptySet(),
    val playlists: List<PlaylistOutDto> = emptyList(),
    val trackPendingPlaylistAdd: TrackResultDto? = null,
    // Set once an artist (resolved by name from a track's "Zum Künstler" action) is
    // ready to open - the screen navigates on seeing this, then calls
    // onArtistNavigated() to clear it back to null.
    val artistNavTarget: Pair<String, String>? = null,
    val artistLookupError: String? = null,
    val downloadPlaylistMessage: String? = null,
)

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistRepository: PlaylistRepository,
    private val playerController: PlayerController,
    private val downloadRepository: DownloadRepository,
    private val likesRepository: LikesRepository,
    private val searchRepository: SearchRepository,
) : ViewModel() {

    private val playlistId: String = checkNotNull(savedStateHandle["playlistId"])

    private val _uiState = MutableStateFlow(PlaylistDetailUiState())
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            likesRepository.likedTrackIds.collect { ids ->
                _uiState.value = _uiState.value.copy(likedTrackIds = ids)
            }
        }
        viewModelScope.launch { runCatching { likesRepository.refresh() } }
    }

    fun load() {
        viewModelScope.launch {
            runCatching { playlistRepository.get(playlistId) }
                .onSuccess { playlist -> _uiState.value = PlaylistDetailUiState(playlist = playlist, isLoading = false) }
                .onFailure { _uiState.value = _uiState.value.copy(isLoading = false) }
        }
    }

    fun playTrack(item: PlaylistTrackOutDto) {
        viewModelScope.launch {
            val tracks = _uiState.value.playlist?.tracks
            val queue = tracks?.map { it.track.toTrackResultDto() }
            val index = tracks?.indexOf(item) ?: -1
            if (queue != null && index >= 0) {
                playerController.playQueue(queue, index)
            } else {
                playerController.playTrack(item.track.toTrackResultDto())
            }
        }
    }

    fun removeTrack(item: PlaylistTrackOutDto) {
        viewModelScope.launch {
            runCatching { playlistRepository.removeTrack(playlistId, item.track.toTrackResultDto()) }
                .onSuccess { playlist -> _uiState.value = _uiState.value.copy(playlist = playlist) }
        }
    }

    fun moveTrack(item: PlaylistTrackOutDto, delta: Int) {
        val tracks = _uiState.value.playlist?.tracks ?: return
        val fromIndex = tracks.indexOf(item)
        val toIndex = fromIndex + delta
        if (fromIndex < 0 || toIndex < 0 || toIndex >= tracks.size) return

        val reordered = tracks.toMutableList()
        reordered.add(toIndex, reordered.removeAt(fromIndex))
        val trackIds = reordered.map { it.track.id }

        viewModelScope.launch {
            runCatching { playlistRepository.reorder(playlistId, trackIds) }
                .onSuccess { playlist -> _uiState.value = _uiState.value.copy(playlist = playlist) }
        }
    }

    fun updateDetails(name: String, description: String?, accentColorKey: String?, moodTags: List<String>) {
        viewModelScope.launch {
            runCatching { playlistRepository.updateDetails(playlistId, name, description, accentColorKey, moodTags) }
            load()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            runCatching { playlistRepository.delete(playlistId) }
            onDeleted()
        }
    }

    fun onDownloadClicked(track: TrackResultDto) {
        downloadRepository.startDownload(track)
    }

    fun onDownloadPlaylistClicked() {
        val tracks = _uiState.value.playlist?.tracks?.map { it.track.toTrackResultDto() } ?: return
        if (tracks.isEmpty()) return
        downloadRepository.startDownloadAll(tracks)
        _uiState.value = _uiState.value.copy(downloadPlaylistMessage = "${tracks.size} Titel werden heruntergeladen")
    }

    fun onDownloadPlaylistMessageShown() {
        _uiState.value = _uiState.value.copy(downloadPlaylistMessage = null)
    }

    fun onLikeToggled(track: TrackResultDto) {
        viewModelScope.launch { runCatching { likesRepository.toggle(track) } }
    }

    // Used by both the swipe-right gesture and the overflow menu's "Zur Warteschlange
    // hinzufügen" action on playlist rows — appends without touching current playback.
    fun onAddToQueueClicked(track: TrackResultDto) {
        viewModelScope.launch { runCatching { playerController.addToQueue(track) } }
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

    // The "Zum Künstler" action on a track row only has an artist name, not an id -
    // resolve it to a real artist page via a 1-result artist search on the same source.
    // Best-effort: a name search can match a different account with the same display
    // name (common on SoundCloud), not necessarily the actual uploader.
    fun onTrackArtistClicked(track: TrackResultDto) {
        val name = track.artist
        if (name.isNullOrBlank()) return
        viewModelScope.launch {
            runCatching { searchRepository.searchArtists(name, source = track.source, limit = 1) }
                .onSuccess { artists ->
                    val artist = artists.firstOrNull()
                    _uiState.value = if (artist != null) {
                        _uiState.value.copy(artistNavTarget = artist.source to artist.sourceId)
                    } else {
                        _uiState.value.copy(artistLookupError = "Künstler „$name“ nicht gefunden")
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
}
