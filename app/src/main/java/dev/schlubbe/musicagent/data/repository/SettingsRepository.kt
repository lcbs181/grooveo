package dev.schlubbe.musicagent.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.schlubbe.musicagent.playback.EqPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {

    private val dataStore = context.dataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val BACKEND_BASE_URL = stringPreferencesKey("backend_base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val HI_RES_AUDIO = booleanPreferencesKey("hi_res_audio")
        val DATA_SAVER_MODE = booleanPreferencesKey("data_saver_mode")
        val EQ_PRESET = stringPreferencesKey("eq_preset")
    }

    val backendBaseUrl: Flow<String> = dataStore.data.map { it[Keys.BACKEND_BASE_URL] ?: "" }
    val apiKey: Flow<String> = dataStore.data.map { it[Keys.API_KEY] ?: "" }
    val hiResAudio: Flow<Boolean> = dataStore.data.map { it[Keys.HI_RES_AUDIO] ?: false }
    val dataSaverMode: Flow<Boolean> = dataStore.data.map { it[Keys.DATA_SAVER_MODE] ?: false }
    val eqPreset: Flow<EqPreset> = dataStore.data.map { prefs ->
        prefs[Keys.EQ_PRESET]?.let { name -> runCatching { EqPreset.valueOf(name) }.getOrNull() }
            ?: EqPreset.FLAT
    }

    // OkHttp interceptors run synchronously, so they read these caches rather than
    // suspending on the DataStore Flow directly. PlayerController/PlaybackService reads
    // for playback decisions (data-saver, hi-res, EQ) follow the same synchronous-cache
    // pattern since they're invoked from non-suspend-sensitive call sites too.
    private val backendBaseUrlCache = MutableStateFlow("")
    private val apiKeyCache = MutableStateFlow("")
    private val hiResAudioCache = MutableStateFlow(false)
    private val dataSaverModeCache = MutableStateFlow(false)
    private val eqPresetCache = MutableStateFlow(EqPreset.FLAT)

    val backendBaseUrlCached: String get() = backendBaseUrlCache.value
    val apiKeyCached: String get() = apiKeyCache.value
    val hiResAudioCached: Boolean get() = hiResAudioCache.value
    val dataSaverModeCached: Boolean get() = dataSaverModeCache.value
    val eqPresetCached: EqPreset get() = eqPresetCache.value

    init {
        scope.launch { backendBaseUrl.collect { backendBaseUrlCache.value = it } }
        scope.launch { apiKey.collect { apiKeyCache.value = it } }
        scope.launch { hiResAudio.collect { hiResAudioCache.value = it } }
        scope.launch { dataSaverMode.collect { dataSaverModeCache.value = it } }
        scope.launch { eqPreset.collect { eqPresetCache.value = it } }
    }

    suspend fun setBackendBaseUrl(url: String) {
        dataStore.edit { it[Keys.BACKEND_BASE_URL] = url }
    }

    suspend fun setApiKey(key: String) {
        dataStore.edit { it[Keys.API_KEY] = key }
    }

    suspend fun setHiResAudio(enabled: Boolean) {
        dataStore.edit { it[Keys.HI_RES_AUDIO] = enabled }
    }

    suspend fun setDataSaverMode(enabled: Boolean) {
        dataStore.edit { it[Keys.DATA_SAVER_MODE] = enabled }
    }

    suspend fun setEqPreset(preset: EqPreset) {
        dataStore.edit { it[Keys.EQ_PRESET] = preset.name }
    }
}
