package dev.schlubbe.musicagent.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.AlbumResultDto
import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.repository.DownloadRepository
import dev.schlubbe.musicagent.data.repository.EventReporter
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.PlaylistRepository
import dev.schlubbe.musicagent.data.repository.SearchHistoryRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.playback.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val source: String = "all",
    val resultType: String = "tracks",
    val results: List<TrackResultDto> = emptyList(),
    val artistResults: List<ArtistResultDto> = emptyList(),
    val playlistResults: List<PlaylistResultDto> = emptyList(),
    val albumResults: List<AlbumResultDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val likedTrackIds: Set<String> = emptySet(),
    val playlists: List<PlaylistOutDto> = emptyList(),
    val trackPendingPlaylistAdd: TrackResultDto? = null,
    // Set once a playlist/album search result is tapped - the screen navigates to
    // the remote playlist browse screen on seeing this, then calls
    // onRemotePlaylistNavigated() to clear it back to null.
    val remotePlaylistNavTarget: Pair<String, String>? = null,
    // Set once an artist (from the artist tab, or resolved by name from a track's
    // "Zum Künstler" action) is ready to open - the screen navigates on seeing this,
    // then calls onArtistNavigated() to clear it back to null.
    val artistNavTarget: Pair<String, String>? = null,
    val artistLookupError: String? = null,
    val searchHistory: List<String> = emptyList(), // Recent query strings
    // Backs the per-result state pill the redesign puts on every row. Keyed by
    // "source:sourceId", matching DownloadRepository's own trackId format.
    val downloadStates: Map<String, DownloadState> = emptyMap(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val trackDao: TrackDao,
    private val downloadDao: DownloadDao,
    private val playerController: PlayerController,
    private val downloadRepository: DownloadRepository,
    private val likesRepository: LikesRepository,
    private val playlistRepository: PlaylistRepository,
    private val eventReporter: EventReporter,
    private val searchHistoryRepository: SearchHistoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            likesRepository.likedTrackIds.collect { ids ->
                _uiState.value = _uiState.value.copy(likedTrackIds = ids)
            }
        }
        viewModelScope.launch {
            searchHistoryRepository.history.collect { historyEntities ->
                _uiState.value = _uiState.value.copy(searchHistory = historyEntities.map { it.query })
            }
        }
        viewModelScope.launch {
            downloadDao.observeAll().collect { downloads ->
                _uiState.value = _uiState.value.copy(
                    downloadStates = downloads.associate { it.trackId to it.state },
                )
            }
        }
        viewModelScope.launch { runCatching { likesRepository.refresh() } }
        viewModelScope.launch { runCatching { searchHistoryRepository.refresh() } }
    }

    fun onQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun onSourceChanged(source: String) {
        _uiState.value = _uiState.value.copy(source = source)
        if (_uiState.value.query.isNotBlank()) runSearch()
    }

    fun onResultTypeChanged(resultType: String) {
        _uiState.value = _uiState.value.copy(resultType = resultType)
        if (_uiState.value.query.isNotBlank()) runSearch()
    }

    fun runSearch() {
        val query = _uiState.value.query
        if (query.isBlank()) return

        val source = _uiState.value.source
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            // Record search query into history
            runCatching { searchHistoryRepository.addQuery(query) }

            when (_uiState.value.resultType) {
                "artists" -> runCatching { searchRepository.searchArtists(query, source) }
                    .onSuccess { artists ->
                        _uiState.value = _uiState.value.copy(artistResults = artists, isLoading = false)
                        eventReporter.search(query)
                    }
                    .onFailure { e ->
                        _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                    }
                "playlists" -> runCatching { searchRepository.searchPlaylists(query, source) }
                    .onSuccess { playlists ->
                        _uiState.value = _uiState.value.copy(playlistResults = playlists, isLoading = false)
                        eventReporter.search(query)
                    }
                    .onFailure { e ->
                        _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                    }
                "albums" -> runCatching { searchRepository.searchAlbums(query, source) }
                    .onSuccess { albums ->
                        _uiState.value = _uiState.value.copy(albumResults = albums, isLoading = false)
                        eventReporter.search(query)
                    }
                    .onFailure { e ->
                        _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                    }
                else -> runCatching { searchRepository.search(query, source) }
                    .onSuccess { results ->
                        _uiState.value = _uiState.value.copy(results = results, isLoading = false)
                        eventReporter.search(query)
                    }
                    .onFailure { e ->
                        _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                    }
            }
        }
    }

    // A playlist/album search result now opens the browse screen instead of
    // immediately playing all its tracks - lets the user see what's in it (and
    // save/like it) before committing to playback.
    fun onPlaylistResultClicked(source: String, sourceId: String) {
        _uiState.value = _uiState.value.copy(remotePlaylistNavTarget = source to sourceId)
    }

    fun onAlbumResultClicked(source: String, sourceId: String) = onPlaylistResultClicked(source, sourceId)

    fun onRemotePlaylistNavigated() {
        _uiState.value = _uiState.value.copy(remotePlaylistNavTarget = null)
    }

    fun onArtistResultClicked(artist: ArtistResultDto) {
        _uiState.value = _uiState.value.copy(artistNavTarget = artist.source to artist.sourceId)
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
                genre = track.genre,
            ),
        )
    }

    // Clicking a row plays the rest of the currently displayed list as the
    // "up next" queue, not just the tapped track in isolation.
    fun onTrackClicked(track: TrackResultDto) {
        viewModelScope.launch {
            cacheTrack(track)
            val results = _uiState.value.results
            val index = results.indexOfFirst { it.source == track.source && it.sourceId == track.sourceId }
            if (index >= 0) playerController.playQueue(results, index) else playerController.playTrack(track)
        }
    }

    fun onDownloadClicked(track: TrackResultDto) {
        downloadRepository.startDownload(track)
    }

    // Used by both the swipe-right gesture and the overflow menu's "Zur Warteschlange
    // hinzufügen" action on every track row — appends without touching current playback.
    fun onAddToQueueClicked(track: TrackResultDto) {
        viewModelScope.launch { runCatching { playerController.addToQueue(track) } }
    }

    fun onLikeToggled(track: TrackResultDto) {
        viewModelScope.launch { runCatching { likesRepository.toggle(track) } }
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

    fun onHistoryQueryTapped(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        // Automatically run the search for the tapped history query
        viewModelScope.launch {
            runSearch()
        }
    }

    fun onHistoryQueryDeleted(query: String) {
        viewModelScope.launch {
            runCatching { searchHistoryRepository.deleteQuery(query) }
        }
    }
}
