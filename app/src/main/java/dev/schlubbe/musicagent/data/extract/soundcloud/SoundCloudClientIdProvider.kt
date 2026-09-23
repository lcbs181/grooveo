package dev.schlubbe.musicagent.data.extract.soundcloud

import dev.schlubbe.musicagent.data.extract.di.ExtractionHttpClient
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** SoundCloud's api-v2 requires a `client_id` query param on every request. It's not
 * a real API key - it's a value embedded in one of soundcloud.com's own JS assets,
 * rotated periodically. This mirrors yt-dlp's SoundcloudBaseIE._update_client_id():
 * fetch the homepage, find linked <script> assets, regex each for the id, cache it. */
@Singleton
class SoundCloudClientIdProvider @Inject constructor(
    @ExtractionHttpClient private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) {
    @Volatile private var cached: String? = null
    private val mutex = Mutex()

    suspend fun getClientId(forceRefresh: Boolean = false): String {
        if (!forceRefresh) cached?.let { return it }
        return mutex.withLock {
            if (!forceRefresh) cached?.let { return it }
            // Survives a process restart, so a temporarily unreachable/altered
            // soundcloud.com doesn't leave the whole source unusable - the last id
            // that worked keeps working until it's actually rejected (a 401 then
            // arrives here as forceRefresh).
            if (!forceRefresh) {
                settingsRepository.soundCloudClientId.first().takeIf { it.isNotBlank() }?.let {
                    cached = it
                    return it
                }
            }
            val fetched = runCatching { fetchClientId() }.getOrElse { error ->
                settingsRepository.soundCloudClientId.first().takeIf { it.isNotBlank() }
                    ?: throw error
            }
            cached = fetched
            settingsRepository.setSoundCloudClientId(fetched)
            fetched
        }
    }

    private suspend fun fetchClientId(): String = withContext(Dispatchers.IO) {
        val homepage = client.newCall(Request.Builder().url("https://soundcloud.com/").build())
            .execute().use { it.body?.string().orEmpty() }

        val scriptUrls = SCRIPT_SRC_REGEX.findAll(homepage).map { it.groupValues[1] }.toList().asReversed()
        for (scriptUrl in scriptUrls) {
            val script = runCatching {
                client.newCall(Request.Builder().url(scriptUrl).build()).execute()
                    .use { it.body?.string() }
            }.getOrNull() ?: continue

            CLIENT_ID_PATTERNS.firstNotNullOfOrNull { pattern ->
                pattern.find(script)?.groupValues?.get(1)
            }?.let { return@withContext it }
        }
        error("SoundCloud ist gerade nicht erreichbar")
    }

    companion object {
        private val SCRIPT_SRC_REGEX = Regex("<script[^>]+src=\"([^\"]+)\"")

        // SoundCloud's bundles have shipped all of these spellings over time; trying
        // each beats breaking whenever they reformat their JS.
        private val CLIENT_ID_PATTERNS = listOf(
            Regex("client_id\\s*:\\s*\"([0-9a-zA-Z]{32})\""),
            Regex("client_id\\s*=\\s*\"([0-9a-zA-Z]{32})\""),
            Regex("\"clientId\"\\s*:\\s*\"([0-9a-zA-Z]{32})\""),
            Regex("client_id=([0-9a-zA-Z]{32})"),
        )
    }
}
