package dev.schlubbe.musicagent.ui.artist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.repository.SearchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistFollowersUiState(
    val followers: List<ArtistResultDto> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val nextCursorUrl: String? = null,
)

// SoundCloud-only (see SearchRepository.getFollowersPage) - loads its first page on
// open, then further pages only as the list is scrolled (ArtistFollowersScreen calls
// loadMore() near the end of the visible list), never eagerly, to stay consistent
// with the rate-limit-conscious approach used throughout this on-device client.
@HiltViewModel
class ArtistFollowersViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val searchRepository: SearchRepository,
) : ViewModel() {

    private val sourceId: String = checkNotNull(savedStateHandle["sourceId"])

    private val _uiState = MutableStateFlow(ArtistFollowersUiState())
    val uiState: StateFlow<ArtistFollowersUiState> = _uiState.asStateFlow()

    init {
        loadFirstPage()
    }

    private fun loadFirstPage() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatching { searchRepository.getFollowersPage(sourceId, cursorUrl = null) }
                .onSuccess { page ->
                    _uiState.value = _uiState.value.copy(
                        followers = page.items,
                        nextCursorUrl = page.nextCursorUrl,
                        isLoading = false,
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        val cursor = state.nextCursorUrl ?: return
        if (state.isLoadingMore) return
        _uiState.value = state.copy(isLoadingMore = true)
        viewModelScope.launch {
            runCatching { searchRepository.getFollowersPage(sourceId, cursorUrl = cursor) }
                .onSuccess { page ->
                    _uiState.value = _uiState.value.copy(
                        followers = _uiState.value.followers + page.items,
                        nextCursorUrl = page.nextCursorUrl,
                        isLoadingMore = false,
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isLoadingMore = false)
                }
        }
    }
}
