package dev.schlubbe.musicagent.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
        val PROFILE_NAME = stringPreferencesKey("profile_name")
        val PROFILE_COLOR_STYLE = stringPreferencesKey("profile_color_style")
        // Home "Startseite personalisieren" toggles (Einstellungen section) -- Home
        // itself just reads these three via the cached StateFlows below.
        val SHOW_MIX_CONTROLS = booleanPreferencesKey("show_mix_controls")
        val SHOW_FEATURED = booleanPreferencesKey("show_featured")
        val SHOW_NEW_UPLOADS = booleanPreferencesKey("show_new_uploads")
        val PLAYER_STYLE = stringPreferencesKey("player_style")
        val AUTOPLAY_RADIO = booleanPreferencesKey("autoplay_radio")
        val SOUND_3D_PRESET = stringPreferencesKey("sound_3d_preset")
        val DOWNLOADS_WIFI_ONLY = booleanPreferencesKey("downloads_wifi_only")
        val NOTIFY_NEW_UPLOADS = booleanPreferencesKey("notify_new_uploads")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        val LAST_BACKUP_AT = stringPreferencesKey("last_backup_at")
        // One flag per the README's own state-management note: "the What's-New banner
        // and the first-run tutorial share the same 'latest version' concept -- track
        // as one hasSeenVersion flag per app version, not two". 0 means "never run",
        // which is what gates the first-run onboarding flow vs. the What's-New banner.
        val LAST_SEEN_VERSION_CODE = intPreferencesKey("last_seen_version_code")
    }

    val backendBaseUrl: Flow<String> = dataStore.data.map { it[Keys.BACKEND_BASE_URL] ?: "" }
    val apiKey: Flow<String> = dataStore.data.map { it[Keys.API_KEY] ?: "" }
    val hiResAudio: Flow<Boolean> = dataStore.data.map { it[Keys.HI_RES_AUDIO] ?: false }
    val dataSaverMode: Flow<Boolean> = dataStore.data.map { it[Keys.DATA_SAVER_MODE] ?: false }
    val eqPreset: Flow<EqPreset> = dataStore.data.map { prefs ->
        prefs[Keys.EQ_PRESET]?.let { name -> runCatching { EqPreset.valueOf(name) }.getOrNull() }
            ?: EqPreset.FLAT
    }
    val profileName: Flow<String> = dataStore.data.map { it[Keys.PROFILE_NAME] ?: "" }
    val profileColorStyle: Flow<String> = dataStore.data.map { it[Keys.PROFILE_COLOR_STYLE] ?: "auto" }
    val showMixControls: Flow<Boolean> = dataStore.data.map { it[Keys.SHOW_MIX_CONTROLS] ?: true }
    val showFeatured: Flow<Boolean> = dataStore.data.map { it[Keys.SHOW_FEATURED] ?: true }
    val showNewUploads: Flow<Boolean> = dataStore.data.map { it[Keys.SHOW_NEW_UPLOADS] ?: true }
    // "waveform" or "bars" -- the Player screen's progress-bar visual style.
    val playerStyle: Flow<String> = dataStore.data.map { it[Keys.PLAYER_STYLE] ?: "waveform" }
    val autoplayRadio: Flow<Boolean> = dataStore.data.map { it[Keys.AUTOPLAY_RADIO] ?: false }
    // One of Sound3dPreset's names (see playback/Sound3dController.kt), or null/
    // "DISABLED" for off.
    val sound3dPreset: Flow<String> = dataStore.data.map { it[Keys.SOUND_3D_PRESET] ?: "DISABLED" }
    val downloadsWifiOnly: Flow<Boolean> = dataStore.data.map { it[Keys.DOWNLOADS_WIFI_ONLY] ?: false }
    val notifyNewUploads: Flow<Boolean> = dataStore.data.map { it[Keys.NOTIFY_NEW_UPLOADS] ?: false }
    val autoBackup: Flow<Boolean> = dataStore.data.map { it[Keys.AUTO_BACKUP] ?: false }
    val lastBackupAt: Flow<String?> = dataStore.data.map { it[Keys.LAST_BACKUP_AT] }
    val lastSeenVersionCode: Flow<Int> = dataStore.data.map { it[Keys.LAST_SEEN_VERSION_CODE] ?: 0 }

    // OkHttp interceptors run synchronously, so they read these caches rather than
    // suspending on the DataStore Flow directly. PlayerController/PlaybackService reads
    // for playback decisions (data-saver, hi-res, EQ) follow the same synchronous-cache
    // pattern since they're invoked from non-suspend-sensitive call sites too.
    private val backendBaseUrlCache = MutableStateFlow("")
    private val apiKeyCache = MutableStateFlow("")
    private val hiResAudioCache = MutableStateFlow(false)
    private val dataSaverModeCache = MutableStateFlow(false)
    private val eqPresetCache = MutableStateFlow(EqPreset.FLAT)
    private val sound3dPresetCache = MutableStateFlow("DISABLED")
    private val downloadsWifiOnlyCache = MutableStateFlow(false)
    private val autoplayRadioCache = MutableStateFlow(false)

    val backendBaseUrlCached: String get() = backendBaseUrlCache.value
    val apiKeyCached: String get() = apiKeyCache.value
    val hiResAudioCached: Boolean get() = hiResAudioCache.value
    val dataSaverModeCached: Boolean get() = dataSaverModeCache.value
    val eqPresetCached: EqPreset get() = eqPresetCache.value
    val sound3dPresetCached: String get() = sound3dPresetCache.value
    val downloadsWifiOnlyCached: Boolean get() = downloadsWifiOnlyCache.value
    val autoplayRadioCached: Boolean get() = autoplayRadioCache.value

    init {
        scope.launch { backendBaseUrl.collect { backendBaseUrlCache.value = it } }
        scope.launch { apiKey.collect { apiKeyCache.value = it } }
        scope.launch { hiResAudio.collect { hiResAudioCache.value = it } }
        scope.launch { dataSaverMode.collect { dataSaverModeCache.value = it } }
        scope.launch { eqPreset.collect { eqPresetCache.value = it } }
        scope.launch { sound3dPreset.collect { sound3dPresetCache.value = it } }
        scope.launch { downloadsWifiOnly.collect { downloadsWifiOnlyCache.value = it } }
        scope.launch { autoplayRadio.collect { autoplayRadioCache.value = it } }
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

    suspend fun setProfileName(name: String) {
        dataStore.edit { it[Keys.PROFILE_NAME] = name }
    }

    suspend fun setProfileColorStyle(style: String) {
        dataStore.edit { it[Keys.PROFILE_COLOR_STYLE] = style }
    }

    suspend fun setShowMixControls(enabled: Boolean) {
        dataStore.edit { it[Keys.SHOW_MIX_CONTROLS] = enabled }
    }

    suspend fun setShowFeatured(enabled: Boolean) {
        dataStore.edit { it[Keys.SHOW_FEATURED] = enabled }
    }

    suspend fun setShowNewUploads(enabled: Boolean) {
        dataStore.edit { it[Keys.SHOW_NEW_UPLOADS] = enabled }
    }

    suspend fun setPlayerStyle(style: String) {
        dataStore.edit { it[Keys.PLAYER_STYLE] = style }
    }

    suspend fun setAutoplayRadio(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTOPLAY_RADIO] = enabled }
    }

    suspend fun setSound3dPreset(preset: String) {
        dataStore.edit { it[Keys.SOUND_3D_PRESET] = preset }
    }

    suspend fun setDownloadsWifiOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.DOWNLOADS_WIFI_ONLY] = enabled }
    }

    suspend fun setNotifyNewUploads(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFY_NEW_UPLOADS] = enabled }
    }

    suspend fun setAutoBackup(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_BACKUP] = enabled }
    }

    suspend fun setLastBackupAt(iso: String) {
        dataStore.edit { it[Keys.LAST_BACKUP_AT] = iso }
    }

    suspend fun setLastSeenVersionCode(versionCode: Int) {
        dataStore.edit { it[Keys.LAST_SEEN_VERSION_CODE] = versionCode }
    }
}
