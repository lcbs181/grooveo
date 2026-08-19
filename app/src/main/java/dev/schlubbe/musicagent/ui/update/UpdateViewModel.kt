package dev.schlubbe.musicagent.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.remote.dto.UpdateInfoDto
import dev.schlubbe.musicagent.data.repository.UpdateCheckResult
import dev.schlubbe.musicagent.data.repository.UpdateRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val info: UpdateInfoDto) : UpdateUiState
    data class Downloading(val progressPct: Int) : UpdateUiState
    data class ReadyToInstall(val info: UpdateInfoDto) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    // Tracked so dismiss() can actually stop an in-flight check/download instead of
    // just hiding the dialog while it keeps running underneath - the dialog for
    // both of those states used to have no cancel path at all (onDismissRequest and
    // confirmButton were both no-ops), so a slow/unreachable backend trapped the
    // user for as long as the network call took to fail.
    private var job: Job? = null

    /** [silent]: the automatic once-per-launch check (see NavGraph) shouldn't pop a
     * dialog for "no update"/"unreachable" every single time the app opens - only
     * for an actual [UpdateUiState.Available]. A manually-triggered check (the
     * Settings screen's "Nach Updates suchen" button) should always show *something*
     * happened, or tapping it looks broken/like nothing happened at all. */
    fun checkForUpdate(silent: Boolean = false) {
        job?.cancel()
        if (!silent) _uiState.value = UpdateUiState.Checking
        job = viewModelScope.launch {
            val result = updateRepository.checkForUpdate()
            _uiState.value = when (result) {
                is UpdateCheckResult.Available -> UpdateUiState.Available(result.info)
                is UpdateCheckResult.UpToDate -> if (silent) UpdateUiState.Idle else UpdateUiState.UpToDate
                is UpdateCheckResult.Error -> if (silent) UpdateUiState.Idle else UpdateUiState.Error(result.message)
            }
        }
    }

    fun downloadAndInstall(info: UpdateInfoDto) {
        job?.cancel()
        job = viewModelScope.launch {
            _uiState.value = UpdateUiState.Downloading(0)
            val result = runCatching {
                updateRepository.downloadApk(info.downloadUrl) { pct ->
                    _uiState.value = UpdateUiState.Downloading(pct)
                }
            }
            result.fold(
                onSuccess = { file ->
                    _uiState.value = UpdateUiState.ReadyToInstall(info)
                    updateRepository.installApk(file)
                },
                onFailure = { e ->
                    _uiState.value = UpdateUiState.Error(e.message ?: "Download fehlgeschlagen")
                },
            )
        }
    }

    fun dismiss() {
        job?.cancel()
        _uiState.value = UpdateUiState.Idle
    }
}
