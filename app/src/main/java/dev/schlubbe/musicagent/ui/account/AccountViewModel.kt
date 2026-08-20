package dev.schlubbe.musicagent.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.FollowedArtistEntity
import dev.schlubbe.musicagent.data.repository.FollowRepository
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.PlaylistRepository
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class AccountUiState(
    val profileName: String = "",
    val profileColorStyle: String = "auto",
    val playlistCount: Int = 0,
    val likeCount: Int = 0,
    val following: List<FollowedArtistEntity> = emptyList(),
    val statLine: String = "",
)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val playlistRepository: PlaylistRepository,
    private val likesRepository: LikesRepository,
    private val followRepository: FollowRepository,
    private val trackDao: TrackDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.profileName.collect { name ->
                _uiState.value = _uiState.value.copy(profileName = name)
            }
        }
        viewModelScope.launch {
            settingsRepository.profileColorStyle.collect { style ->
                _uiState.value = _uiState.value.copy(profileColorStyle = style)
            }
        }
        viewModelScope.launch {
            followRepository.followedArtists.collect { following ->
                _uiState.value = _uiState.value.copy(following = following)
            }
        }
        // Same "Diese Woche: X Std. Y Min gehört" estimate as HomeScreen's header --
        // duplicated here rather than shared since it's one small pure function.
        viewModelScope.launch {
            trackDao.observeRecentlyPlayed(50).collect { tracks ->
                val weekAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
                val totalSec = tracks.filter { it.lastAccessedAt >= weekAgo }.sumOf { it.durationSec ?: 0 }
                val statLine = if (totalSec <= 0) {
                    ""
                } else {
                    val hours = totalSec / 3600
                    val minutes = (totalSec % 3600) / 60
                    if (hours > 0) "Diese Woche: $hours Std. $minutes Min gehört" else "Diese Woche: $minutes Min gehört"
                }
                _uiState.value = _uiState.value.copy(statLine = statLine)
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { playlistRepository.list() }.onSuccess { playlists ->
                _uiState.value = _uiState.value.copy(playlistCount = playlists.size)
            }
        }
        viewModelScope.launch {
            runCatching { likesRepository.refresh() }.onSuccess { likes ->
                _uiState.value = _uiState.value.copy(likeCount = likes.size)
            }
        }
        viewModelScope.launch { runCatching { followRepository.refresh() } }
    }

    fun updateProfile(name: String, colorStyle: String) {
        viewModelScope.launch {
            settingsRepository.setProfileName(name)
            settingsRepository.setProfileColorStyle(colorStyle)
        }
    }
}
