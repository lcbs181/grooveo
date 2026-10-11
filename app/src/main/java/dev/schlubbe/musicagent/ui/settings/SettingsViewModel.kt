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
import dev.schlubbe.musicagent.playback.eq.EqProfile
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
    /** Finished, with what happened ("Gespeichert in …"). */
    data class Info(val message: String) : BackupState
    data class Error(val message: String) : BackupState
}

data class SettingsUiState(
    // Backend / Über
    val backendBaseUrl: String = "",
    val apiKey: String = "",
    val connectionTestState: ConnectionTestState = ConnectionTestState.Idle,
    // Wiedergabe
    val hiResAudio: Boolean = false,
    val eqProfile: EqProfile = EqProfile.flat(),
    val eqUserPresets: List<EqProfile> = emptyList(),
    val loudnessNormalization: Boolean = true,
    val playerStyle: String = "waveform",
    val autoplayRadio: Boolean = false,
    val contentSafetyFilter: Boolean = true,
    val crossfadeSeconds: Int = 0,
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
    /** Folder for backups outside the app (survives uninstalling), null when none is chosen. */
    val backupFolderName: String? = null,
    /** A backup file the user picked to import; the dialog asks how. */
    val restoreFile: android.net.Uri? = null,
    // Sync mit PC
    val syncPaired: Boolean = false,
    val syncRunning: Boolean = false,
    val syncMessage: String? = null,
    val syncFailed: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val backendApi: BackendApi,
    private val backupManager: BackupManager,
    private val syncClient: dev.schlubbe.musicagent.data.sync.SyncClient,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = _uiState.value.copy(backupFolderName = backupManager.folderName())
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
            settingsRepository.eqProfile.collect { profile ->
                _uiState.value = _uiState.value.copy(eqProfile = profile)
            }
        }
        viewModelScope.launch {
            settingsRepository.loudnessNormalization.collect { on ->
                _uiState.value = _uiState.value.copy(loudnessNormalization = on)
            }
        }
        viewModelScope.launch {
            settingsRepository.eqUserPresets.collect { presets ->
                _uiState.value = _uiState.value.copy(eqUserPresets = presets)
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
            settingsRepository.contentSafetyFilter.collect { enabled ->
                _uiState.value = _uiState.value.copy(contentSafetyFilter = enabled)
            }
        }
        viewModelScope.launch {
            settingsRepository.crossfadeSeconds.collect { seconds ->
                _uiState.value = _uiState.value.copy(crossfadeSeconds = seconds)
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

    /** Updates the UI immediately; the write reaches the audio processor through
     * SettingsRepository.eqProfile (PlaybackService collects it). */
    fun onEqProfileChanged(profile: EqProfile) {
        _uiState.value = _uiState.value.copy(eqProfile = profile)
        viewModelScope.launch { settingsRepository.setEqProfile(profile) }
    }

    /** Saves the current profile under [name], replacing a saved profile of the same name. */
    fun onSaveEqPreset(name: String) {
        val p = _uiState.value.eqProfile.copy(name = name)
        val list = _uiState.value.eqUserPresets.filterNot { it.name == name } + p
        _uiState.value = _uiState.value.copy(eqProfile = p, eqUserPresets = list)
        viewModelScope.launch {
            settingsRepository.setEqProfile(p)
            settingsRepository.setEqUserPresets(list)
        }
    }

    fun onLoudnessNormalizationChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(loudnessNormalization = enabled)
        viewModelScope.launch { settingsRepository.setLoudnessNormalization(enabled) }
    }

    fun onDeleteEqPreset(name: String) {
        val list = _uiState.value.eqUserPresets.filterNot { it.name == name }
        _uiState.value = _uiState.value.copy(eqUserPresets = list)
        viewModelScope.launch { settingsRepository.setEqUserPresets(list) }
    }

    fun onPlayerStyleChanged(style: String) {
        _uiState.value = _uiState.value.copy(playerStyle = style)
        viewModelScope.launch { settingsRepository.setPlayerStyle(style) }
    }

    fun onAutoplayRadioChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoplayRadio = enabled)
        viewModelScope.launch { settingsRepository.setAutoplayRadio(enabled) }
    }

    fun onContentSafetyFilterChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(contentSafetyFilter = enabled)
        viewModelScope.launch { settingsRepository.setContentSafetyFilter(enabled) }
    }

    fun onCrossfadeSecondsChanged(seconds: Int) {
        _uiState.value = _uiState.value.copy(crossfadeSeconds = seconds)
        viewModelScope.launch { settingsRepository.setCrossfadeSeconds(seconds) }
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
                    _uiState.value = _uiState.value.copy(
                        backupState = backupManager.folderName()?.let { BackupState.Info("Gesichert, auch im Ordner „$it“") } ?: BackupState.Done,
                    )
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

    /** Stores the pairing from the PC's QR code (or typed code) and syncs right away. */
    fun pairWithPc(text: String) {
        if (!syncClient.pair(text)) {
            _uiState.value = _uiState.value.copy(syncFailed = true, syncMessage = "Das ist kein Grooveo-Kopplungscode.")
            return
        }
        _uiState.value = _uiState.value.copy(syncPaired = true)
        syncWithPc()
    }

    fun unpairPc() {
        syncClient.unpair()
        _uiState.value = _uiState.value.copy(syncPaired = false, syncMessage = null, syncFailed = false)
    }

    /** Syncs likes, playlists and follows with the paired PC. */
    fun syncWithPc() {
        if (_uiState.value.syncRunning) return
        _uiState.value = _uiState.value.copy(syncRunning = true, syncMessage = null, syncFailed = false)
        viewModelScope.launch {
            syncClient.sync().fold(
                onSuccess = { _uiState.value = _uiState.value.copy(syncRunning = false, syncMessage = "Synchronisiert – Handy und PC sind jetzt gleich.") },
                onFailure = { _uiState.value = _uiState.value.copy(syncRunning = false, syncFailed = true, syncMessage = it.message ?: "Sync fehlgeschlagen") },
            )
        }
    }

    fun clearSyncMessage() {
        _uiState.value = _uiState.value.copy(syncPaired = syncClient.isPaired, syncMessage = null, syncFailed = false)
    }

    /** The folder the user picked for backups (null = only inside the app). */
    fun setBackupFolder(uri: android.net.Uri?) {
        runCatching { backupManager.setFolder(uri) }
            .onSuccess {
                _uiState.value = _uiState.value.copy(backupFolderName = backupManager.folderName())
                if (uri != null) backupNow()
            }
            .onFailure { e -> _uiState.value = _uiState.value.copy(backupState = BackupState.Error("Ordner nicht nutzbar: ${e.message}")) }
    }

    /** "Als Datei speichern": writes a backup into the file the user created. */
    fun exportTo(uri: android.net.Uri) {
        _uiState.value = _uiState.value.copy(backupState = BackupState.Running)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                backupState = backupManager.exportTo(uri).fold(
                    onSuccess = { BackupState.Info("Sicherung gespeichert") },
                    onFailure = { e -> BackupState.Error(e.message ?: "Speichern fehlgeschlagen") },
                ),
            )
        }
    }

    fun onRestoreFilePicked(uri: android.net.Uri) {
        _uiState.value = _uiState.value.copy(restoreFile = uri)
    }

    fun dismissRestoreFile() {
        _uiState.value = _uiState.value.copy(restoreFile = null)
    }

    /** Imports the picked file: [merge] adds what is missing, otherwise the file replaces the library. */
    fun confirmRestoreFile(merge: Boolean) {
        val uri = _uiState.value.restoreFile ?: return
        _uiState.value = _uiState.value.copy(restoreFile = null, backupState = BackupState.Running)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                backupState = backupManager.restoreFrom(uri, merge).fold(
                    onSuccess = { BackupState.Info(if (merge) "Sicherung übernommen – nichts wurde gelöscht" else "Sicherung wiederhergestellt") },
                    onFailure = { e -> BackupState.Error(e.message ?: "Import fehlgeschlagen") },
                ),
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
