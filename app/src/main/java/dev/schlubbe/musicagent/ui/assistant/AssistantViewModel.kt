package dev.schlubbe.musicagent.ui.assistant

import kotlinx.coroutines.Job
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.backup.SyncProtocol
import dev.schlubbe.musicagent.data.sync.RemoteAi
import dev.schlubbe.musicagent.data.sync.PcNowPlaying
import dev.schlubbe.musicagent.playback.PcLivePlayer
import dev.schlubbe.musicagent.playback.PlayerController
import dev.schlubbe.musicagent.playback.isPcLive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import dev.schlubbe.musicagent.data.sync.SyncClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AssistantUiState(
    val paired: Boolean = false,
    /** The PC's conversation; null until it answered once. */
    val remote: RemoteAi.State? = null,
    /** Why the PC cannot be reached right now. */
    val error: String? = null,
    val nowPlaying: PcNowPlaying? = null,
    /** Waiting for the PC to start the stream. */
    val startingLive: Boolean = false,
)

/**
 * The "KI" tab: a remote for the assistant of the paired PC ([RemoteAi]). The PC runs the model
 * and the tools and asks before deleting, FX and loudness changes; this side shows the
 * conversation (long-polling while the tab is open) and passes on the user's messages and answers.
 * A small player bar calls the PC's MCP tools directly.
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val sync: SyncClient,
    private val pcLive: PcLivePlayer,
    player: PlayerController,
) : ViewModel() {
    private val gson = Gson()
    private val _state = MutableStateFlow(AssistantUiState(paired = sync.isPaired))
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    /** Follows the PC's conversation until the caller is cancelled (the tab is left). */
    suspend fun follow() {
        var failures = 0
        while (true) {
            _state.update { it.copy(paired = sync.isPaired) }
            if (!sync.isPaired) { delay(2_000); continue }
            val since = _state.value.remote?.rev
            val r = request(RemoteAi.Request(RemoteAi.Op.STATE, since = since), RemoteAi.LONG_POLL_MS + 15_000)
            if (r == null) {
                failures++
                delay(minOf(15_000L, 2_000L * failures))
                continue
            }
            failures = 0
            if (!r.enabled) delay(5_000)
            // what plays may change with every step of the assistant
            if (r.rev != since) refreshNowPlaying()
        }
    }

    /** Refreshes what plays on the PC every few seconds while the tab is open. */
    suspend fun followPlayer() {
        while (true) {
            if (sync.isPaired && _state.value.error == null) refreshNowPlaying()
            delay(8_000)
        }
    }

    /** Pull to refresh: asks the PC for its conversation and what plays right away. */
    fun refresh(): Job = viewModelScope.launch {
        _state.update { it.copy(paired = sync.isPaired) }
        if (!sync.isPaired) return@launch
        request(RemoteAi.Request(RemoteAi.Op.STATE), OP_TIMEOUT_MS)
        refreshNowPlaying()
    }

    fun send(text: String) = op(RemoteAi.Request(RemoteAi.Op.SEND, text = text))
    fun answer(id: Long, allow: Boolean) = op(RemoteAi.Request(RemoteAi.Op.ANSWER, id = id, allow = allow))
    fun stop() = op(RemoteAi.Request(RemoteAi.Op.STOP))
    fun newConversation() = op(RemoteAi.Request(RemoteAi.Op.NEW))
    fun undo() = op(RemoteAi.Request(RemoteAi.Op.UNDO))
    fun openChat(id: String) = op(RemoteAi.Request(RemoteAi.Op.OPEN, chat = id))
    fun deleteChat(id: String) = op(RemoteAi.Request(RemoteAi.Op.DELETE, chat = id))

    /** Play/pause, next, previous on the PC. */
    /** Whether the PC's mix plays here, for the PC bar. */
    val listening: StateFlow<Boolean> = player.playbackState.map { it.isPcLive }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** "Auf dem Handy hören"; [muteDevice] silences the PC meanwhile. */
    fun listenOnPhone(muteDevice: Boolean) {
        if (_state.value.startingLive) return
        _state.update { it.copy(startingLive = true) }
        viewModelScope.launch {
            val r = pcLive.start(muteDevice)
            _state.update { it.copy(startingLive = false, error = r.exceptionOrNull()?.let { e -> e.message ?: "Stream konnte nicht starten" }) }
        }
    }

    fun stopListening() {
        viewModelScope.launch { pcLive.stop() }
    }

    fun playback(action: String) {
        viewModelScope.launch {
            tool("playback", JsonObject().apply { addProperty("action", action) })?.let(::applyNowPlaying)
        }
    }

    private fun op(r: RemoteAi.Request) {
        viewModelScope.launch { request(r, OP_TIMEOUT_MS) }
    }

    /** Sends [r] and takes over the state the PC answers with; null when the PC could not be reached. */
    private suspend fun request(r: RemoteAi.Request, timeoutMs: Long): RemoteAi.State? {
        val res = sync.call(SyncProtocol.AI_PATH, gson.toJson(r), timeoutMs)
            .mapCatching { gson.fromJson(it, RemoteAi.State::class.java) ?: error("Ungültige Antwort vom PC") }
        return res.fold(
            onSuccess = { s ->
                _state.update { cur ->
                    // an older answer (a long poll that started before an action) must not undo a newer one
                    val keep = cur.remote != null && s.enabled && cur.remote.enabled && s.rev < cur.remote.rev
                    cur.copy(remote = if (keep) cur.remote else s, error = if (s.enabled) null else s.message)
                }
                s
            },
            onFailure = { e -> _state.update { it.copy(error = e.message ?: "PC nicht erreichbar") }; null },
        )
    }

    private suspend fun refreshNowPlaying() {
        tool("get_now_playing", JsonObject())?.let(::applyNowPlaying)
    }

    private fun applyNowPlaying(o: JsonObject) {
        _state.update { it.copy(nowPlaying = PcNowPlaying.of(o)) }
    }

    /** Calls one of the PC's MCP tools; its JSON result, or null on any error. */
    private suspend fun tool(name: String, args: JsonObject): JsonObject? = sync.tool(name, args, OP_TIMEOUT_MS)

    private companion object {
        const val OP_TIMEOUT_MS = 20_000L
    }
}
