package dev.schlubbe.musicagent.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.schlubbe.musicagent.data.backup.BackupManager
import dev.schlubbe.musicagent.data.remote.BackendApi
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import dev.schlubbe.musicagent.playback.EqPreset
import dev.schlubbe.musicagent.playback.Sound3dPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data object Success : ConnectionTestState
    data class Error(val message: String) : ConnectionTestState
}

sealed interface BackupState {
    data object Idle : BackupState
    data object Running : BackupState
    data object Done : BackupState
    data class Error(val message: String) : BackupState
}

data class SettingsUiState(
    // Backend / Über
    val backendBaseUrl: String = "",
    val apiKey: String = "",
    val connectionTestState: ConnectionTestState = ConnectionTestState.Idle,
    // Wiedergabe
    val hiResAudio: Boolean = false,
    val eqPreset: EqPreset = EqPreset.FLAT,
    val playerStyle: String = "waveform",
    val autoplayRadio: Boolean = false,
    // 3D-Sound
    val sound3dPreset: Sound3dPreset = Sound3dPreset.DISABLED,
    // Downloads
    val dataSaverMode: Boolean = false,
    val downloadsWifiOnly: Boolean = false,
    val cacheSizeMb: Long = 0L,
    // Benachrichtigungen
    val notifyNewUploads: Boolean = false,
    // Startseite personalisieren
    val showMixControls: Boolean = true,
    val showFeatured: Boolean = true,
    val showNewUploads: Boolean = true,
    // Updates & Sicherungen
    val autoBackup: Boolean = false,
    val lastBackupText: String? = null,
    val backupState: BackupState = BackupState.Idle,
    val showRestoreConfirm: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val backendApi: BackendApi,
    private val backupManager: BackupManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.backendBaseUrl.collect { url ->
                _uiState.value = _uiState.value.copy(backendBaseUrl = url)
            }
        }
        viewModelScope.launch {
            settingsRepository.apiKey.collect { key ->
                _uiState.value = _uiState.value.copy(apiKey = key)
            }
        }
        viewModelScope.launch {
            settingsRepository.hiResAudio.collect { enabled ->
                _uiState.value = _uiState.value.copy(hiResAudio = enabled)
            }
        }
        viewModelScope.launch {
            settingsRepository.dataSaverMode.collect { enabled ->
                _uiState.value = _uiState.value.copy(dataSaverMode = enabled)
            }
        }
        viewModelScope.launch {
            settingsRepository.eqPreset.collect { preset ->
                _uiState.value = _uiState.value.copy(eqPreset = preset)
            }
        }
        viewModelScope.launch {
            settingsRepository.playerStyle.collect { style ->
                _uiState.value = _uiState.value.copy(playerStyle = style)
            }
        }
        viewModelScope.launch {
            settingsRepository.autoplayRadio.collect { enabled ->
                _uiState.value = _uiState.value.copy(autoplayRadio = enabled)
            }
        }
        viewModelScope.launch {
            settingsRepository.sound3dPreset.collect { name ->
                val preset = runCatching { Sound3dPreset.valueOf(name) }.getOrDefault(Sound3dPreset.DISABLED)
                _uiState.value = _uiState.value.copy(sound3dPreset = preset)
            }
        }
        viewModelScope.launch {
            settingsRepository.downloadsWifiOnly.collect { enabled ->
                _uiState.value = _uiState.value.copy(downloadsWifiOnly = enabled)
            }
        }
        viewModelScope.launch {
            settingsRepository.notifyNewUploads.collect { enabled ->
                _uiState.value = _uiState.value.copy(notifyNewUploads = enabled)
            }
        }
        viewModelScope.launch {
            settingsRepository.showMixControls.collect { show ->
                _uiState.value = _uiState.value.copy(showMixControls = show)
            }
        }
        viewModelScope.launch {
            settingsRepository.showFeatured.collect { show ->
                _uiState.value = _uiState.value.copy(showFeatured = show)
            }
        }
        viewModelScope.launch {
            settingsRepository.showNewUploads.collect { show ->
                _uiState.value = _uiState.value.copy(showNewUploads = show)
            }
        }
        viewModelScope.launch {
            settingsRepository.autoBackup.collect { enabled ->
                _uiState.value = _uiState.value.copy(autoBackup = enabled)
            }
        }
        viewModelScope.launch {
            settingsRepository.lastBackupAt.collect { iso ->
                _uiState.value = _uiState.value.copy(lastBackupText = iso?.let(::formatBackupTimestamp))
            }
        }
        refreshCacheSize()
    }

    fun onBackendBaseUrlChanged(url: String) {
        _uiState.value = _uiState.value.copy(backendBaseUrl = url)
        viewModelScope.launch { settingsRepository.setBackendBaseUrl(url) }
    }

    fun onApiKeyChanged(key: String) {
        _uiState.value = _uiState.value.copy(apiKey = key)
        viewModelScope.launch { settingsRepository.setApiKey(key) }
    }

    fun onHiResAudioChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(hiResAudio = enabled)
        viewModelScope.launch { settingsRepository.setHiResAudio(enabled) }
    }

    fun onDataSaverModeChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(dataSaverMode = enabled)
        viewModelScope.launch { settingsRepository.setDataSaverMode(enabled) }
    }

    fun onEqPresetChanged(preset: EqPreset) {
        _uiState.value = _uiState.value.copy(eqPreset = preset)
        viewModelScope.launch { settingsRepository.setEqPreset(preset) }
    }

    fun onPlayerStyleChanged(style: String) {
        _uiState.value = _uiState.value.copy(playerStyle = style)
        viewModelScope.launch { settingsRepository.setPlayerStyle(style) }
    }

    fun onAutoplayRadioChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoplayRadio = enabled)
        viewModelScope.launch { settingsRepository.setAutoplayRadio(enabled) }
    }

    fun onSound3dPresetChanged(preset: Sound3dPreset) {
        _uiState.value = _uiState.value.copy(sound3dPreset = preset)
        viewModelScope.launch { settingsRepository.setSound3dPreset(preset.name) }
    }

    fun onDownloadsWifiOnlyChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(downloadsWifiOnly = enabled)
        viewModelScope.launch { settingsRepository.setDownloadsWifiOnly(enabled) }
    }

    fun onNotifyNewUploadsChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(notifyNewUploads = enabled)
        viewModelScope.launch { settingsRepository.setNotifyNewUploads(enabled) }
    }

    fun onShowMixControlsChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(showMixControls = enabled)
        viewModelScope.launch { settingsRepository.setShowMixControls(enabled) }
    }

    fun onShowFeaturedChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(showFeatured = enabled)
        viewModelScope.launch { settingsRepository.setShowFeatured(enabled) }
    }

    fun onShowNewUploadsChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(showNewUploads = enabled)
        viewModelScope.launch { settingsRepository.setShowNewUploads(enabled) }
    }

    fun onAutoBackupChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoBackup = enabled)
        viewModelScope.launch { settingsRepository.setAutoBackup(enabled) }
    }

    // Real Coil disk-cache size (the on-device "Zwischenspeicher" this refers to -
    // not user downloads, which are intentional data the user asked for and
    // shouldn't be swept up by a cache-clear action).
    @OptIn(ExperimentalCoilApi::class)
    private fun refreshCacheSize() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.imageLoader.diskCache?.size ?: 0L }.getOrDefault(0L)
            }
            _uiState.value = _uiState.value.copy(cacheSizeMb = bytes / (1024 * 1024))
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { context.imageLoader.diskCache?.clear() }
            }
            refreshCacheSize()
        }
    }

    // Real local backup: serializes playlists/likes/follows/saved-playlists/
    // settings to context.filesDir/backups/backup_<timestamp>.json via
    // BackupManager, which round-trips through the existing repositories only.
    fun backupNow() {
        _uiState.value = _uiState.value.copy(backupState = BackupState.Running)
        viewModelScope.launch {
            val result = backupManager.createBackup()
            result.fold(
                onSuccess = {
                    settingsRepository.setLastBackupAt(Instant.now().toString())
                    _uiState.value = _uiState.value.copy(backupState = BackupState.Done)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        backupState = BackupState.Error(e.message ?: "Sicherung fehlgeschlagen"),
                    )
                },
            )
        }
    }

    /** Fires the Android share sheet for the most recently written local backup
     * file (content:// URI via FileProvider). No-op with an Error state if no
     * local backup exists yet. */
    fun shareBackup() {
        val shared = backupManager.shareLatestBackup()
        if (!shared) {
            _uiState.value = _uiState.value.copy(
                backupState = BackupState.Error("Keine Sicherung zum Teilen vorhanden — zuerst sichern."),
            )
        }
    }

    fun onRestoreClicked() {
        _uiState.value = _uiState.value.copy(showRestoreConfirm = true)
    }

    fun dismissRestoreConfirm() {
        _uiState.value = _uiState.value.copy(showRestoreConfirm = false)
    }

    // Real restore: reads the most recent local backup JSON and repopulates
    // Room (via the existing repositories' insert/upsert methods) + DataStore
    // settings via BackupManager.
    fun confirmRestore() {
        _uiState.value = _uiState.value.copy(showRestoreConfirm = false, backupState = BackupState.Running)
        viewModelScope.launch {
            val result = backupManager.restoreLatest()
            _uiState.value = _uiState.value.copy(
                backupState = result.fold(
                    onSuccess = { BackupState.Done },
                    onFailure = { e -> BackupState.Error(e.message ?: "Wiederherstellung fehlgeschlagen") },
                ),
            )
        }
    }

    private fun formatBackupTimestamp(iso: String): String? =
        runCatching {
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()))
        }.getOrNull()

    // "Backend" here only backs analytics events + update checks in this
    // backend-less variant -- search/streaming/likes/playlists are all on-device,
    // no login concept exists in this app at all.
    fun testConnection() {
        _uiState.value = _uiState.value.copy(connectionTestState = ConnectionTestState.Testing)
        viewModelScope.launch {
            val result = runCatching { backendApi.healthz() }
            _uiState.value = _uiState.value.copy(
                connectionTestState = result.fold(
                    onSuccess = { ConnectionTestState.Success },
                    onFailure = { ConnectionTestState.Error(it.message ?: "Unknown error") },
                ),
            )
        }
    }
}
