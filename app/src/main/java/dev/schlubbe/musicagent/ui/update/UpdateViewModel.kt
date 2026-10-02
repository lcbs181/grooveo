package dev.schlubbe.musicagent.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.remote.dto.UpdateInfoDto
import dev.schlubbe.musicagent.data.repository.UpdateCheckResult
import dev.schlubbe.musicagent.data.repository.UpdateDownloadState
import dev.schlubbe.musicagent.data.repository.UpdateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val info: UpdateInfoDto) : UpdateUiState
    data class Downloading(val info: UpdateInfoDto, val progressPct: Int, val waitingForNetwork: Boolean = false) : UpdateUiState
    /** The APK is downloaded and verified; installing can be (re)tried any number of times. */
    data class ReadyToInstall(val info: UpdateInfoDto) : UpdateUiState
    data class Error(val message: String, val retry: UpdateInfoDto? = null) : UpdateUiState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    // The running check, or the progress poll of a download. Cancelling it never
    // stops the download itself: that runs in the system DownloadManager.
    private var job: Job? = null

    /** [silent]: the automatic once-per-launch check (see NavGraph) shouldn't pop a
     * dialog for "no update"/"unreachable" every single time the app opens - only
     * for an actual update. A manually-triggered check (the Settings screen's "Nach
     * Updates suchen" button) should always show *something* happened.
     * An update that is already downloaded, or still downloading, is shown as such. */
    fun checkForUpdate(silent: Boolean = false) {
        job?.cancel()
        if (!silent) _uiState.value = UpdateUiState.Checking
        job = viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { updateRepository.cleanupInstalledApks() } }
            val result = updateRepository.checkForUpdate()
            when (result) {
                is UpdateCheckResult.Available -> {
                    val state = withContext(Dispatchers.IO) { updateRepository.downloadState(result.info) }
                    when (state) {
                        is UpdateDownloadState.Done -> _uiState.value = UpdateUiState.ReadyToInstall(result.info)
                        is UpdateDownloadState.Running -> follow(result.info, install = true)
                        else -> _uiState.value = UpdateUiState.Available(result.info)
                    }
                }
                is UpdateCheckResult.UpToDate -> _uiState.value = if (silent) UpdateUiState.Idle else UpdateUiState.UpToDate
                is UpdateCheckResult.Error -> _uiState.value = if (silent) UpdateUiState.Idle else UpdateUiState.Error(result.message)
            }
        }
    }

    /** Downloads (or resumes following an earlier download of) [info], then opens the installer. */
    fun downloadAndInstall(info: UpdateInfoDto) {
        job?.cancel()
        job = viewModelScope.launch {
            _uiState.value = UpdateUiState.Downloading(info, 0)
            val started = runCatching { withContext(Dispatchers.IO) { updateRepository.startDownload(info) } }
            started.exceptionOrNull()?.let { _uiState.value = UpdateUiState.Error(it.message ?: "Download konnte nicht starten", info); return@launch }
            follow(info, install = true)
        }
    }

    private suspend fun follow(info: UpdateInfoDto, install: Boolean) {
        while (true) {
            when (val st = withContext(Dispatchers.IO) { updateRepository.downloadState(info) }) {
                is UpdateDownloadState.Running -> _uiState.value = UpdateUiState.Downloading(info, st.progressPct, st.waitingForNetwork)
                is UpdateDownloadState.Done -> {
                    _uiState.value = UpdateUiState.ReadyToInstall(info)
                    if (install) install(info)
                    return
                }
                is UpdateDownloadState.Failed -> { _uiState.value = UpdateUiState.Error(st.message, info); return }
                UpdateDownloadState.None -> { _uiState.value = UpdateUiState.Error("Download wurde abgebrochen", info); return }
            }
            delay(POLL_MS)
        }
    }

    /** Opens the system installer for the downloaded APK; downloads it first if it is missing. */
    fun install(info: UpdateInfoDto) {
        val file = updateRepository.downloadedApk(info) ?: return downloadAndInstall(info)
        runCatching { updateRepository.installApk(file) }
            .onFailure { _uiState.value = UpdateUiState.Error(it.message ?: "Installation konnte nicht starten", info) }
    }

    /** Hides the dialog; a running download continues in the background (system notification). */
    fun dismiss() {
        job?.cancel()
        _uiState.value = UpdateUiState.Idle
    }

    /** Stops the download and hides the dialog. */
    fun cancelDownload(info: UpdateInfoDto) {
        job?.cancel()
        runCatching { updateRepository.cancelDownload(info) }
        _uiState.value = UpdateUiState.Idle
    }

    private companion object {
        const val POLL_MS = 500L
    }
}
