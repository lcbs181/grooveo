package dev.schlubbe.musicagent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.remote.BackendApi
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import dev.schlubbe.musicagent.playback.EqPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data object Success : ConnectionTestState
    data class Error(val message: String) : ConnectionTestState
}

data class SettingsUiState(
    val backendBaseUrl: String = "",
    val apiKey: String = "",
    val connectionTestState: ConnectionTestState = ConnectionTestState.Idle,
    val hiResAudio: Boolean = false,
    val dataSaverMode: Boolean = false,
    val eqPreset: EqPreset = EqPreset.FLAT,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val backendApi: BackendApi,
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

    // "Backend" here only backs analytics events + update checks in this
    // backend-less variant (search/streaming/likes/playlists are all on-device) --
    // healthz is the right thing to check, not a search round-trip.
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
