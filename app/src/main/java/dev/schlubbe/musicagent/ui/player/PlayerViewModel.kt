package dev.schlubbe.musicagent.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import dev.schlubbe.musicagent.data.repository.DownloadRepository
import dev.schlubbe.musicagent.data.repository.FollowRepository
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.PlaylistRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import dev.schlubbe.musicagent.playback.EqPreset
import dev.schlubbe.musicagent.playback.PlaybackUiState
import dev.schlubbe.musicagent.playback.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerArtistNavState(
    // Set once an artist (resolved by name from the now-playing track's artist
    // label) is ready to open - the screen navigates on seeing this, then calls
    // onArtistNavigated() to clear it back to null. Same pattern as
    // SearchViewModel/PlaylistDetailViewModel's "Zum Künstler" resolution.
    val artistNavTarget: Pair<String, String>? = null,
    val artistLookupError: String? = null,
)

data class PlayerAddToPlaylistState(
    val pending: Boolean = false,
    val playlists: List<PlaylistOutDto> = emptyList(),
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerController: PlayerController,
    private val likesRepository: LikesRepository,
    private val downloadRepository: DownloadRepository,
    private val searchRepository: SearchRepository,
    private val settingsRepository: SettingsRepository,
    private val playlistRepository: PlaylistRepository,
    private val followRepository: FollowRepository,
) : ViewModel() {

    val playbackState: StateFlow<PlaybackUiState> = playerController.playbackState
    val vizVariant: StateFlow<String> = playerController.vizVariant
    val visualizerBands: StateFlow<FloatArray> = playerController.visualizerBands
    fun setVizVariant(variant: String) = playerController.setVizVariant(variant)

    val eqPreset: StateFlow<EqPreset> = settingsRepository.eqPreset
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EqPreset.FLAT)

    fun setEqPreset(preset: EqPreset) {
        viewModelScope.launch { settingsRepository.setEqPreset(preset) }
    }

    val playerStyle: StateFlow<String> = settingsRepository.playerStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "waveform")
    val sleepTimerEndAtMs: StateFlow<Long?> = playerController.sleepTimerEndAtMs

    val isLiked: StateFlow<Boolean> = combine(
        playerController.playbackState,
        likesRepository.likedTrackIds,
    ) { playback, likedIds -> playback.currentTrackId != null && playback.currentTrackId in likedIds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Whether the now-playing track's artist is followed. Matched by *name*,
     * because playback state carries only an artist name -- TrackResultDto has no
     * artist id -- and FollowedArtistEntity stores the name alongside the id. This
     * is the same best-effort name resolution [onArtistClicked] already relies on,
     * so a duplicate-named SoundCloud account can read as followed when it isn't;
     * that's a known limitation of having no artist id on a track, not a new one. */
    val isCurrentArtistFollowed: StateFlow<Boolean> = combine(
        playerController.playbackState,
        followRepository.followedArtists,
    ) { playback, followed ->
        val artist = playback.artist
        !artist.isNullOrBlank() && followed.any { it.name.equals(artist, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Follows/unfollows the now-playing artist. Resolves the artist id with the
     * same 1-result search [onArtistClicked] uses, since a track only knows the
     * name. Silently no-ops when the name can't be resolved rather than surfacing
     * an error for a secondary control. */
    fun toggleFollowCurrentArtist() {
        val track = playerController.nowPlayingTrack() ?: return
        val name = track.artist
        if (name.isNullOrBlank()) return
        viewModelScope.launch {
            runCatching { searchRepository.searchArtists(name, source = track.source, limit = 1) }
                .getOrNull()
                ?.firstOrNull()
                ?.let { artist ->
                    followRepository.toggle(artist.source, artist.sourceId, artist.name, artist.thumbnailUrl)
                    followRepository.refresh()
                }
        }
    }

    init {
        viewModelScope.launch { runCatching { followRepository.refresh() } }
    }

    private val _artistNavState = MutableStateFlow(PlayerArtistNavState())
    val artistNavState: StateFlow<PlayerArtistNavState> = _artistNavState.asStateFlow()

    fun togglePlayPause() {
        viewModelScope.launch { playerController.togglePlayPause() }
    }

    fun seekTo(positionMs: Long) {
        viewModelScope.launch { playerController.seekTo(positionMs) }
    }

    fun skipToNext() {
        viewModelScope.launch { playerController.skipToNext() }
    }

    fun skipToPrevious() {
        viewModelScope.launch { playerController.skipToPrevious() }
    }

    fun playQueueItem(index: Int) {
        viewModelScope.launch { playerController.skipToQueueIndex(index) }
    }

    fun toggleLike() {
        val track = playerController.nowPlayingTrack() ?: return
        viewModelScope.launch { runCatching { likesRepository.toggle(track) } }
    }

    fun toggleSource() {
        viewModelScope.launch { playerController.toggleSource() }
    }

    fun toggleShuffle() {
        viewModelScope.launch { playerController.toggleShuffle() }
    }

    fun cycleRepeatMode() {
        viewModelScope.launch { playerController.cycleRepeatMode() }
    }

    fun onDownloadClicked() {
        val track = playerController.nowPlayingTrack() ?: return
        downloadRepository.startDownload(track)
    }

    fun currentTrackWebpageUrl(): String? = playerController.nowPlayingTrack()?.webpageUrl

    // The now-playing artist label is only a name, not an id - resolve it to a real
    // artist page via a 1-result artist search on the same source, same best-effort
    // pattern as SearchViewModel/PlaylistDetailViewModel's "Zum Künstler".
    fun onArtistClicked() {
        val track = playerController.nowPlayingTrack() ?: return
        val name = track.artist
        if (name.isNullOrBlank()) return
        viewModelScope.launch {
            runCatching { searchRepository.searchArtists(name, source = track.source, limit = 1) }
                .onSuccess { artists ->
                    val artist = artists.firstOrNull()
                    _artistNavState.value = if (artist != null) {
                        _artistNavState.value.copy(artistNavTarget = artist.source to artist.sourceId)
                    } else {
                        _artistNavState.value.copy(artistLookupError = "Künstler „$name“ nicht gefunden")
                    }
                }
                .onFailure {
                    _artistNavState.value = _artistNavState.value.copy(
                        artistLookupError = "Künstler konnte nicht geladen werden",
                    )
                }
        }
    }

    fun onArtistNavigated() {
        _artistNavState.value = _artistNavState.value.copy(artistNavTarget = null)
    }

    fun onArtistLookupErrorShown() {
        _artistNavState.value = _artistNavState.value.copy(artistLookupError = null)
    }

    fun currentPositionMs(): Long = playerController.currentPositionMs()

    fun currentDurationMs(): Long = playerController.currentDurationMs()

    fun startSleepTimer(minutes: Int) = playerController.startSleepTimer(minutes)

    fun cancelSleepTimer() = playerController.cancelSleepTimer()

    private val _addToPlaylistState = MutableStateFlow(PlayerAddToPlaylistState())
    val addToPlaylistState: StateFlow<PlayerAddToPlaylistState> = _addToPlaylistState.asStateFlow()

    // Part of the Player screen's shared overflow action sheet (see the design
    // handoff's "…" menu spec) - the now-playing track otherwise had no way to be
    // saved into a playlist from the Player screen itself, unlike every other
    // track row in the app.
    fun onAddToPlaylistClicked() {
        if (playerController.nowPlayingTrack() == null) return
        _addToPlaylistState.value = _addToPlaylistState.value.copy(pending = true)
        viewModelScope.launch {
            runCatching { playlistRepository.list() }.onSuccess { playlists ->
                _addToPlaylistState.value = _addToPlaylistState.value.copy(playlists = playlists)
            }
        }
    }

    fun dismissAddToPlaylist() {
        _addToPlaylistState.value = _addToPlaylistState.value.copy(pending = false)
    }

    fun onPlaylistPicked(playlistId: String) {
        val track = playerController.nowPlayingTrack() ?: return
        viewModelScope.launch {
            runCatching { playlistRepository.addTrack(playlistId, track) }
            _addToPlaylistState.value = _addToPlaylistState.value.copy(pending = false)
        }
    }

    fun onCreatePlaylistAndAdd(name: String) {
        val track = playerController.nowPlayingTrack() ?: return
        viewModelScope.launch {
            runCatching {
                val playlist = playlistRepository.create(name)
                playlistRepository.addTrack(playlist.id, track)
            }
            _addToPlaylistState.value = _addToPlaylistState.value.copy(pending = false)
        }
    }
}
