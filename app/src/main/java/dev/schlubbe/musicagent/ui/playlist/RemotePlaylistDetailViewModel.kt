package dev.schlubbe.musicagent.ui.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import dev.schlubbe.musicagent.data.remote.dto.RemotePlaylistDetailDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.repository.DownloadRepository
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.PlaylistRepository
import dev.schlubbe.musicagent.data.repository.SavedPlaylistRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.playback.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RemotePlaylistDetailUiState(
    val detail: RemotePlaylistDetailDto? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isSaved: Boolean = false,
    val likedTrackIds: Set<String> = emptySet(),
    val playlists: List<PlaylistOutDto> = emptyList(),
    val trackPendingPlaylistAdd: TrackResultDto? = null,
    // Set once an artist (resolved by name from a track's "Zum Künstler" action) is
    // ready to open - the screen navigates on seeing this, then calls
    // onArtistNavigated() to clear it back to null.
    val artistNavTarget: Pair<String, String>? = null,
    val artistLookupError: String? = null,
    val downloadMessage: String? = null,
    val queueMessage: String? = null,
)

/** Backs the playlist/album browse screen reached from a Search result - a public
 * SoundCloud/YouTube playlist, distinct from [PlaylistDetailViewModel] which is
 * Room-backed for the user's own locally-created playlists. Re-fetches the
 * playlist's tracks live on every load (SearchRepository.getPlaylistDetail) rather
 * than persisting them - only the "saved" bookmark itself is local (see
 * [SavedPlaylistRepository]). */
@HiltViewModel
class RemotePlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val searchRepository: SearchRepository,
    private val playerController: PlayerController,
    private val downloadRepository: DownloadRepository,
    private val likesRepository: LikesRepository,
    private val playlistRepository: PlaylistRepository,
    private val savedPlaylistRepository: SavedPlaylistRepository,
) : ViewModel() {

    private val source: String = checkNotNull(savedStateHandle["source"])
    private val sourceId: String = checkNotNull(savedStateHandle["sourceId"])

    private val _uiState = MutableStateFlow(RemotePlaylistDetailUiState())
    val uiState: StateFlow<RemotePlaylistDetailUiState> = _uiState.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            likesRepository.likedTrackIds.collect { ids ->
                _uiState.value = _uiState.value.copy(likedTrackIds = ids)
            }
        }
        viewModelScope.launch { runCatching { likesRepository.refresh() } }
        viewModelScope.launch {
            savedPlaylistRepository.savedIds.collect { ids ->
                _uiState.value = _uiState.value.copy(isSaved = "$source:$sourceId" in ids)
            }
        }
        viewModelScope.launch { runCatching { savedPlaylistRepository.refresh() } }
    }

    fun load() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatching { searchRepository.getPlaylistDetail(source, sourceId) }
                .onSuccess { detail -> _uiState.value = _uiState.value.copy(detail = detail, isLoading = false) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(isLoading = false, error = e.message) }
        }
    }

    fun toggleSaved() {
        val detail = _uiState.value.detail ?: return
        viewModelScope.launch {
            runCatching {
                savedPlaylistRepository.toggle(
                    source = detail.source,
                    sourceId = detail.sourceId,
                    title = detail.title,
                    thumbnailUrl = detail.thumbnailUrl,
                    owner = detail.owner,
                    trackCount = detail.trackCount,
                    webpageUrl = detail.webpageUrl,
                )
            }
        }
    }

    fun playAll() {
        val tracks = _uiState.value.detail?.tracks ?: return
        if (tracks.isEmpty()) return
        viewModelScope.launch { playerController.playQueue(tracks, 0) }
    }

    fun playTrack(track: TrackResultDto) {
        val tracks = _uiState.value.detail?.tracks ?: return
        viewModelScope.launch {
            val index = tracks.indexOfFirst { it.source == track.source && it.sourceId == track.sourceId }
            if (index >= 0) playerController.playQueue(tracks, index) else playerController.playTrack(track)
        }
    }

    fun onDownloadAllClicked() {
        val tracks = _uiState.value.detail?.tracks ?: return
        if (tracks.isEmpty()) return
        downloadRepository.startDownloadAll(tracks)
        _uiState.value = _uiState.value.copy(downloadMessage = "${tracks.size} Titel werden heruntergeladen")
    }

    fun onDownloadMessageShown() {
        _uiState.value = _uiState.value.copy(downloadMessage = null)
    }

    fun onDownloadClicked(track: TrackResultDto) {
        downloadRepository.startDownload(track)
    }

    // Playlist-level parity with the per-track "Zur Warteschlange hinzufügen" action -
    // appends every track in order via the same single-track PlayerController.addToQueue
    // used elsewhere, rather than a dedicated batch API.
    fun onAddAllToQueueClicked() {
        val tracks = _uiState.value.detail?.tracks ?: return
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            tracks.forEach { track -> runCatching { playerController.addToQueue(track) } }
        }
        _uiState.value = _uiState.value.copy(queueMessage = "${tracks.size} Titel zur Warteschlange hinzugefügt")
    }

    fun onQueueMessageShown() {
        _uiState.value = _uiState.value.copy(queueMessage = null)
    }

    fun onLikeToggled(track: TrackResultDto) {
        viewModelScope.launch { runCatching { likesRepository.toggle(track) } }
    }

    // Used by both the swipe-right gesture and the overflow menu's "Zur Warteschlange
    // hinzufügen" action on every track row — appends without touching current playback.
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
