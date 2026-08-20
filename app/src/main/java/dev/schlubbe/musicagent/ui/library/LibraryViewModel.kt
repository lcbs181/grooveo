package dev.schlubbe.musicagent.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.DownloadEntity
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.LikeOutDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.remote.dto.toTrackResultDto
import dev.schlubbe.musicagent.data.repository.DownloadRepository
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.PlaylistRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.download.DownloadWorker
import dev.schlubbe.musicagent.playback.PlayerController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DownloadUiItem(
    val entity: DownloadEntity,
    val livePct: Int?,
    // Metadata isn't stored on DownloadEntity itself (see DownloadRepository comment)
    // - looked up from the same local track cache search/play populate. Null only for
    // downloads that predate DownloadRepository caching this on startDownload().
    val track: TrackEntity? = null,
)

/** Same field-for-field shape as [dev.schlubbe.musicagent.ui.home.HomeViewModel]'s
 * private converter of the same name - kept per-file rather than shared, matching
 * this codebase's existing convention for this kind of small, ViewModel-local glue.
 * Not private (unlike that one) since LibraryScreen.kt, in the same package, needs
 * it too to build the Downloads tab's row actions. */
internal fun TrackEntity.toTrackResultDto(): TrackResultDto = TrackResultDto(
    source = source,
    sourceId = sourceId,
    title = title,
    artist = artist,
    album = album,
    durationSec = durationSec,
    thumbnailUrl = thumbnailUrl,
    webpageUrl = webpageUrl,
)

enum class LibraryTab { DOWNLOADS, LIKES, PLAYLISTS }

data class LibraryUiState(
    val selectedTab: LibraryTab = LibraryTab.DOWNLOADS,
    val likes: List<LikeOutDto> = emptyList(),
    val isLoadingLikes: Boolean = false,
    val playlists: List<PlaylistOutDto> = emptyList(),
    val isLoadingPlaylists: Boolean = false,
    val trackPendingPlaylistAdd: TrackResultDto? = null,
    // Set once an artist (resolved by name from a track's "Zum Künstler" action) is
    // ready to open - the screen navigates on seeing this, then calls
    // onArtistNavigated() to clear it back to null.
    val artistNavTarget: Pair<String, String>? = null,
    val artistLookupError: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    downloadDao: DownloadDao,
    workManager: WorkManager,
    private val trackDao: TrackDao,
    private val playerController: PlayerController,
    private val likesRepository: LikesRepository,
    private val playlistRepository: PlaylistRepository,
    private val downloadRepository: DownloadRepository,
    private val searchRepository: SearchRepository,
) : ViewModel() {

    val downloads: StateFlow<List<DownloadUiItem>> = downloadDao.observeAll()
        .flatMapLatest { entities ->
            if (entities.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(entities.map { entity -> entity.toLiveFlow(workManager) }) { it.toList() }
            }
        }
        .map { items -> items.map { it.copy(track = trackDao.getById(it.entity.trackId)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val likedTrackIds: StateFlow<Set<String>> = likesRepository.likedTrackIds

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    fun selectTab(tab: LibraryTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
        when (tab) {
            LibraryTab.LIKES -> refreshLikes()
            LibraryTab.PLAYLISTS -> refreshPlaylists()
            LibraryTab.DOWNLOADS -> Unit
        }
    }

    fun refreshLikes() {
        _uiState.value = _uiState.value.copy(isLoadingLikes = true)
        viewModelScope.launch {
            runCatching { likesRepository.refresh() }
                .onSuccess { likes -> _uiState.value = _uiState.value.copy(likes = likes, isLoadingLikes = false) }
                .onFailure { _uiState.value = _uiState.value.copy(isLoadingLikes = false) }
        }
    }

    fun refreshPlaylists() {
        _uiState.value = _uiState.value.copy(isLoadingPlaylists = true)
        viewModelScope.launch {
            runCatching { playlistRepository.list() }
                .onSuccess { playlists ->
                    _uiState.value = _uiState.value.copy(playlists = playlists, isLoadingPlaylists = false)
                }
                .onFailure { _uiState.value = _uiState.value.copy(isLoadingPlaylists = false) }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            runCatching { playlistRepository.create(name) }
            refreshPlaylists()
        }
    }

    fun playLikedTrack(like: LikeOutDto) {
        viewModelScope.launch {
            val likes = _uiState.value.likes
            val queue = likes.map { it.track.toTrackResultDto() }
            val index = likes.indexOfFirst { it.track.id == like.track.id }
            if (index >= 0) {
                playerController.playQueue(queue, index)
            } else {
                playerController.playTrack(like.track.toTrackResultDto())
            }
        }
    }

    fun unlike(like: LikeOutDto) {
        viewModelScope.launch {
            runCatching { likesRepository.unlike(like.track.toTrackResultDto()) }
            refreshLikes()
        }
    }

    /** Plays a completed download with full metadata when available (the normal
     * case since DownloadRepository.startDownload caches it) so the Player screen
     * shows the same controls it would from any other entry point - falls back to
     * the bare-uri path only for a download that predates that caching. */
    fun playDownload(item: DownloadUiItem) {
        val uri = item.entity.mediaStoreUri ?: return
        viewModelScope.launch {
            val track = item.track?.toTrackResultDto()
            if (track != null) {
                playerController.playLocalDownload(track, uri)
            } else {
                playerController.playFromUri(uri)
            }
        }
    }

    fun onDownloadClicked(track: TrackResultDto) {
        downloadRepository.startDownload(track)
    }

    fun onPauseDownloadClicked(trackId: String) {
        downloadRepository.pauseDownload(trackId)
    }

    fun onResumeDownloadClicked(trackId: String) {
        downloadRepository.resumeDownload(trackId)
    }

    fun onRetryDownloadClicked(trackId: String) {
        downloadRepository.retryDownload(trackId)
    }

    fun onDownloadLikeToggled(track: TrackResultDto) {
        viewModelScope.launch { runCatching { likesRepository.toggle(track) } }
    }

    // Used by both the swipe-right gesture and the overflow menu's "Zur Warteschlange
    // hinzufügen" action on the likes rows — appends without touching current playback.
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

    private fun DownloadEntity.toLiveFlow(workManager: WorkManager) =
        if (state == DownloadState.DOWNLOADING) {
            workManager.getWorkInfosForUniqueWorkFlow(trackId).map { infos ->
                val pct = infos.firstOrNull()?.progress?.getInt(DownloadWorker.PROGRESS_KEY, -1)
                DownloadUiItem(this, pct?.takeIf { it >= 0 })
            }
        } else {
            flowOf(DownloadUiItem(this, null))
        }
}
