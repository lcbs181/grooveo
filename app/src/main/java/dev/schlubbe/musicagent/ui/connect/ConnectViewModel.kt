package dev.schlubbe.musicagent.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.remote.BackendApi
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

/**
 * "Mit PC verbinden" (design_handoff_grooveo screen 12 / GrooveoApp.dc.html
 * lines 762-839). The handoff imagines a Windows companion app that
 * broadcasts itself on the LAN and hands out a rotating 6-digit pairing code
 * (see the bundle's README "PC-Backend view"). None of that exists in this
 * repo: there is no discovery protocol, no pairing endpoint, and no Windows
 * client to pair with.
 *
 * What *does* exist is this app's already-optional backend link
 * (SettingsRepository.backendBaseUrl/apiKey, DynamicBaseUrlInterceptor,
 * AuthInterceptor's X-API-Key header, BackendApi.healthz()) -- and the
 * handoff's own implementation note says a paired PC should be treated
 * exactly like a configured Backend-URL + API-Key. So this screen keeps the
 * design's visual language (found-host card, 6-digit code boxes, keypad) but
 * wires it to real, honest behaviour instead of a scripted always-succeeds
 * demo:
 *  - "Host"/"Port" are plain manual-entry fields, never a fabricated LAN scan
 *    result -- see ConnectScreen for the copy explaining why.
 *  - The 6-digit "Kopplungscode" the keypad builds is persisted as the API
 *    key. There is no server on the other end validating it as a pairing
 *    handshake; it is just the value AuthInterceptor will send as
 *    `X-API-Key`.
 *  - "Verbinden" performs a real `GET /healthz` against the entered address
 *    (via the existing BackendApi/OkHttp stack, same call SettingsScreen's
 *    "Test Connection" button already makes) before ever claiming to be
 *    connected. It can genuinely fail, and does when there's nothing at that
 *    address.
 *  - The design's four "Freigaben" toggles and its fabricated
 *    "1,4 GB transferred today" stat have no backing implementation anywhere
 *    in this app (no PC-side library sync, no remote stream branch, no
 *    remote-control channel, no transfer telemetry), so they are omitted
 *    here rather than rendered as dead switches / invented numbers -- see
 *    ConnectScreen's paired-state copy for what replaces them.
 */

enum class ConnectPhase { Idle, Connecting, Connected, Error }

data class ConnectUiState(
    /** True once a backend URL is persisted (set from this screen or from
     * Settings' own "Erweitert" fields -- both read/write the same
     * SettingsRepository keys, per the handoff's implementation note). */
    val paired: Boolean = false,
    /** host:port parsed from the persisted backendBaseUrl, for display only. */
    val pairedHostPort: String = "",
    val host: String = "",
    val port: String = "8080",
    val codeDigits: String = "",
    val phase: ConnectPhase = ConnectPhase.Idle,
    val errorMessage: String? = null,
    /** Live reachability of the already-paired backend: null = not checked
     * yet / unknown, otherwise the result of the last real /healthz call. */
    val existingReachable: Boolean? = null,
    val checkingExisting: Boolean = false,
    /** Increments on a real successful connect so the screen can fire the
     * one-shot confetti spray exactly once per success. */
    val connectSuccessEvent: Int = 0,
) {
    val nextBoxIndex: Int get() = codeDigits.length
    val canConnect: Boolean get() = host.isNotBlank() && codeDigits.length == 6 && phase != ConnectPhase.Connecting
}

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val backendApi: BackendApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectUiState())
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    private var hasAutoChecked = false

    init {
        viewModelScope.launch {
            settingsRepository.backendBaseUrl.collect { url ->
                val isPaired = url.isNotBlank()
                _uiState.value = _uiState.value.copy(
                    paired = isPaired,
                    pairedHostPort = if (isPaired) displayHostPort(url) else "",
                )
                if (isPaired && !hasAutoChecked) {
                    hasAutoChecked = true
                    recheck()
                }
                if (!isPaired) {
                    hasAutoChecked = false
                }
            }
        }
    }

    private fun displayHostPort(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        return "${parsed.host}:${parsed.port}"
    }

    fun onHostChanged(value: String) {
        _uiState.value = _uiState.value.copy(host = value, phase = ConnectPhase.Idle, errorMessage = null)
    }

    fun onPortChanged(value: String) {
        _uiState.value = _uiState.value.copy(
            port = value.filter { it.isDigit() }.take(5),
            phase = ConnectPhase.Idle,
            errorMessage = null,
        )
    }

    fun onDigitPress(digit: String) {
        val current = _uiState.value
        if (current.codeDigits.length >= 6) return
        _uiState.value = current.copy(
            codeDigits = current.codeDigits + digit,
            phase = ConnectPhase.Idle,
            errorMessage = null,
        )
    }

    fun onBackspace() {
        val current = _uiState.value
        if (current.codeDigits.isEmpty()) return
        _uiState.value = current.copy(codeDigits = current.codeDigits.dropLast(1))
    }

    /** Real connect attempt: persists the entered host/port + code as the app's
     * one backend URL + API key, then makes an actual `/healthz` request
     * through the normal Retrofit/OkHttp stack. Only flips to [ConnectPhase.Connected]
     * if that request genuinely succeeds. */
    fun onConnect() {
        val state = _uiState.value
        if (!state.canConnect) return
        val port = state.port.trim().ifBlank { "8080" }
        val url = "http://${state.host.trim()}:$port"
        val code = state.codeDigits
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(phase = ConnectPhase.Connecting, errorMessage = null)
            settingsRepository.setBackendBaseUrl(url)
            settingsRepository.setApiKey(code)
            // DynamicBaseUrlInterceptor / AuthInterceptor read SettingsRepository's
            // synchronous cache (backendBaseUrlCached / apiKeyCached), not this
            // suspend Flow directly -- give the repository's own internal
            // collector a moment to catch up with what was just written before
            // firing a real request through it. Best-effort, not a hard
            // guarantee; matches the repository's existing cache pattern.
            withTimeoutOrNull(2000) {
                settingsRepository.backendBaseUrl.first { it == url }
                settingsRepository.apiKey.first { it == code }
            }
            delay(75)
            val result = runCatching { backendApi.healthz() }
            _uiState.value = if (result.isSuccess) {
                _uiState.value.copy(
                    phase = ConnectPhase.Connected,
                    existingReachable = true,
                    codeDigits = "",
                    connectSuccessEvent = _uiState.value.connectSuccessEvent + 1,
                )
            } else {
                _uiState.value.copy(
                    phase = ConnectPhase.Error,
                    errorMessage = "PC unter $url nicht erreichbar.",
                )
            }
        }
    }

    /** Re-runs the real health check against whatever is currently persisted.
     * Used both automatically the first time the screen sees a paired backend,
     * and from the paired view's "Erneut prüfen" action. */
    fun recheck() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(checkingExisting = true)
            val result = runCatching { backendApi.healthz() }
            _uiState.value = _uiState.value.copy(checkingExisting = false, existingReachable = result.isSuccess)
        }
    }

    /** Real disconnect: clears the persisted backend URL + API key, which is
     * exactly what SettingsScreen's "Erweitert" fields would look like blank
     * too, since both read/write the same two SettingsRepository keys. */
    fun onDisconnect() {
        viewModelScope.launch {
            settingsRepository.setBackendBaseUrl("")
            settingsRepository.setApiKey("")
            _uiState.value = _uiState.value.copy(
                phase = ConnectPhase.Idle,
                existingReachable = null,
                codeDigits = "",
                host = "",
                port = "8080",
                errorMessage = null,
            )
        }
    }
}
