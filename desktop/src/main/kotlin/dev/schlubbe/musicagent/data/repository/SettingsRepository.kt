package dev.schlubbe.musicagent.data.repository

import dev.schlubbe.musicagent.desktop.data.Settings
import dev.schlubbe.musicagent.desktop.data.readJson
import dev.schlubbe.musicagent.desktop.data.writeJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Desktop settings store, persisted as `settings.json`. Shares its FQN with the
 * Android SettingsRepository so the shared FeedRepository/SoundCloud sources link
 * against it.
 */
class SettingsRepository(private val file: File) {
    private val _state = MutableStateFlow(
        (readJson<Settings>(file, Settings::class.java) ?: Settings()).normalized(),
    )
    val state: StateFlow<Settings> = _state.asStateFlow()
    val current: Settings get() = _state.value

    private val writer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "settings-writer").apply { isDaemon = true } }
    private var pendingWrite: java.util.concurrent.ScheduledFuture<*>? = null

    /** Updates immediately; the file write is debounced (sliders update many times per second). */
    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        _state.update { transform(it).normalized() }
        pendingWrite?.cancel(false)
        pendingWrite = writer.schedule({ flush() }, 300, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun flush() {
        pendingWrite?.cancel(false)
        pendingWrite = null
        writeJson(file, _state.value)
    }

    // --- API used by shared sources ---
    val soundCloudClientId: Flow<String> = state.map { it.soundCloudClientId }.distinctUntilChanged()
    suspend fun setSoundCloudClientId(clientId: String) = update { it.copy(soundCloudClientId = clientId) }
    val contentSafetyFilterCached: Boolean get() = current.contentSafetyFilter
    val crossfadeSecondsCached: Int get() = current.crossfadeSeconds
}
