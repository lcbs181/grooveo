package dev.schlubbe.musicagent.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.DownloadEntity
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.local.entity.SavedPlaylistEntity
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.LikeOutDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.remote.dto.toTrackResultDto
import dev.schlubbe.musicagent.data.repository.DownloadRepository
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.PlaylistRepository
import dev.schlubbe.musicagent.data.repository.SavedPlaylistRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.data.repository.SettingsRepository
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

// HOME is the landing/menu state (title + import banner + 3 chevron rows + the
// "Zuletzt gespielt"/"Wiedergabeverlauf" shelves) - the other three are reached
// by tapping a chevron row and each render with their own back header. This is
// in-screen state, not a NavGraph route change: the whole Library tab stays one
// nav destination, see LibraryScreen's BackHandler for the system-back wiring.
enum class LibraryTab { HOME, DOWNLOADS, LIKES, PLAYLISTS }

/** Which chip is active in the unified Library home content (LibraryScreen.kt's
 * `LibraryHomeContent`) - moved here (was a local `remember` inside that
 * composable) and into [LibraryUiState] so it survives the composable being torn
 * down and rebuilt (e.g. switching to another bottom-nav tab and back), the same
 * way [LibraryUiState.selectedTab] already does, instead of silently resetting to
 * [PLAYLISTS] every time. Playlists and Likes have a real ViewModel-backed data
 * source; Verlauf reuses the same recently-played source Home's own shelf uses;
 * Künstler has no followed-artist source wired to this ViewModel at all, so it
 * renders an honest empty state instead of inventing content. */
enum class LibraryChip(val label: String) {
    PLAYLISTS("Playlists"),
    LIKES("Likes"),
    VERLAUF("Verlauf"),
    KUENSTLER("Künstler"),
}

// Same magnitude as HomeViewModel's own recently-played query - enough to both
// slice a short circular rail and fill a scrollable history list underneath it.
private const val RECENTLY_PLAYED_LIMIT = 30

data class LibraryUiState(
    val selectedTab: LibraryTab = LibraryTab.HOME,
    val selectedChip: LibraryChip = LibraryChip.PLAYLISTS,
    // Mirrors SettingsRepository's persisted flag so the landing menu's Spotify-
    // import banner stays dismissed across process restarts once closed.
    val importBannerDismissed: Boolean = false,
    val likes: List<LikeOutDto> = emptyList(),
    val isLoadingLikes: Boolean = false,
    val playlists: List<PlaylistOutDto> = emptyList(),
    val savedPlaylists: List<SavedPlaylistEntity> = emptyList(),
    val isLoadingPlaylists: Boolean = false,
    val trackPendingPlaylistAdd: TrackResultDto? = null,
    // Set once an artist (resolved by name from a track's "Zum Künstler" action) is
    // ready to open - the screen navigates on seeing this, then calls
    // onArtistNavigated() to clear it back to null.
    val artistNavTarget: Pair<String, String>? = null,
    val artistLookupError: String? = null,
    // Transient snackbar/toast text after "download all tracks" on a playlist row -
    // same "$n Titel werden heruntergeladen" wording as the playlist detail screens'
    // equivalent action, shown once then cleared via onDownloadPlaylistMessageShown().
    val downloadPlaylistMessage: String? = null,
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
    private val savedPlaylistRepository: SavedPlaylistRepository,
    private val settingsRepository: SettingsRepository,
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

    // Same source [dev.schlubbe.musicagent.ui.home.HomeViewModel] already uses for its
    // own "Zuletzt gehört" shelf (trackDao.observeRecentlyPlayed) - reused as-is rather
    // than adding a second query, since TrackEntity already carries everything both the
    // landing menu's circular rail and its "Wiedergabeverlauf" list need (title, artist,
    // durationSec, thumbnailUrl).
    val recentlyPlayed: StateFlow<List<TrackEntity>> = trackDao.observeRecentlyPlayed(RECENTLY_PLAYED_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.libraryImportBannerDismissed.collect { dismissed ->
                _uiState.value = _uiState.value.copy(importBannerDismissed = dismissed)
            }
        }
    }

    /** Opens one of the three chevron-row sub-views from the landing menu, triggering
     * that section's own refresh - the same refresh-on-open behavior the old segmented
     * control's [selectTab] used to do when switching tabs. */
    fun openSection(tab: LibraryTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
        when (tab) {
            LibraryTab.LIKES -> refreshLikes()
            LibraryTab.PLAYLISTS -> refreshPlaylists()
            LibraryTab.DOWNLOADS, LibraryTab.HOME -> Unit
        }
    }

    /** Returns from any sub-view to the landing menu - used by both the sub-view's own
     * back button and the system back gesture/button (see LibraryScreen's BackHandler),
     * without leaving the Library nav-graph destination itself. */
    fun backToHome() {
        _uiState.value = _uiState.value.copy(selectedTab = LibraryTab.HOME)
    }

    /** Switches the active chip in the unified Library home content - mirrors
     * [openSection]'s old "refresh on open" behavior for the two chips with a real
     * ViewModel-backed data source, now triggered from here instead of a
     * LaunchedEffect in the composable, since the selection itself lives here too.
     * The old LaunchedEffect(selectedChip) only fired on an actual value change
     * (Compose skips a re-key with the same value) - re-tapping the already-active
     * chip did nothing. Guarding here keeps that behavior instead of re-firing a
     * network refresh on every tap of an already-selected chip. */
    fun selectChip(chip: LibraryChip) {
        if (_uiState.value.selectedChip == chip) return
        _uiState.value = _uiState.value.copy(selectedChip = chip)
        when (chip) {
            LibraryChip.LIKES -> refreshLikes()
            LibraryChip.PLAYLISTS -> refreshPlaylists()
            LibraryChip.VERLAUF, LibraryChip.KUENSTLER -> Unit
        }
    }

    fun dismissImportBanner() {
        viewModelScope.launch { settingsRepository.setLibraryImportBannerDismissed(true) }
    }

    /** Same play-the-full-list-as-queue behavior as
     * [dev.schlubbe.musicagent.ui.home.HomeViewModel.onRecentlyPlayedClicked] - both the
     * circular rail and the "Wiedergabeverlauf" row list call this for a tapped track. */
    fun onRecentlyPlayedClicked(track: TrackEntity) {
        viewModelScope.launch {
            val queue = recentlyPlayed.value.map { it.toTrackResultDto() }
            val index = recentlyPlayed.value.indexOfFirst { it.id == track.id }
            if (index >= 0) {
                playerController.playQueue(queue, index)
            } else {
                playerController.playTrack(track.toTrackResultDto())
            }
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
        viewModelScope.launch {
            runCatching { savedPlaylistRepository.refresh() }
                .onSuccess { saved -> _uiState.value = _uiState.value.copy(savedPlaylists = saved) }
        }
    }

    /** Downloads every track of a local (Room-backed) playlist from its Library row,
     * without navigating into the detail screen first - fetches the full track list
     * the same way [dev.schlubbe.musicagent.ui.playlist.PlaylistDetailViewModel.onDownloadPlaylistClicked]
     * does, then reuses the same batched [DownloadRepository.startDownloadAll] call. */
    fun onDownloadPlaylistClicked(playlistId: String) {
        viewModelScope.launch {
            runCatching { playlistRepository.get(playlistId) }
                .onSuccess { detail ->
                    val tracks = detail.tracks.map { it.track.toTrackResultDto() }
                    if (tracks.isNotEmpty()) {
                        downloadRepository.startDownloadAll(tracks)
                        _uiState.value = _uiState.value.copy(
                            downloadPlaylistMessage = "${tracks.size} Titel werden heruntergeladen",
                        )
                    }
                }
        }
    }

    /** Same as [onDownloadPlaylistClicked] but for a saved remote (SoundCloud/YT Music)
     * playlist - mirrors [dev.schlubbe.musicagent.ui.playlist.RemotePlaylistDetailViewModel.onDownloadAllClicked],
     * fetching the live track list via SearchRepository since saved playlists persist
     * only the bookmark, not their contents. */
    fun onDownloadSavedPlaylistClicked(source: String, sourceId: String) {
        viewModelScope.launch {
            runCatching { searchRepository.getPlaylistDetail(source, sourceId) }
                .onSuccess { detail ->
                    val tracks = detail.tracks
                    if (tracks.isNotEmpty()) {
                        downloadRepository.startDownloadAll(tracks)
                        _uiState.value = _uiState.value.copy(
                            downloadPlaylistMessage = "${tracks.size} Titel werden heruntergeladen",
                        )
                    }
                }
        }
    }

    fun onDownloadPlaylistMessageShown() {
        _uiState.value = _uiState.value.copy(downloadPlaylistMessage = null)
    }

    fun onUnsavePlaylist(source: String, sourceId: String) {
        viewModelScope.launch {
            runCatching { savedPlaylistRepository.unsave(source, sourceId) }
            refreshPlaylists()
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
