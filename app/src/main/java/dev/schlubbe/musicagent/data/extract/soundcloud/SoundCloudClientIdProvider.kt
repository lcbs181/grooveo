package dev.schlubbe.musicagent.data.extract.soundcloud

import dev.schlubbe.musicagent.data.extract.di.ExtractionHttpClient
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
) {
    @Volatile private var cached: String? = null
    private val mutex = Mutex()

    suspend fun getClientId(forceRefresh: Boolean = false): String {
        if (!forceRefresh) cached?.let { return it }
        return mutex.withLock {
            if (!forceRefresh) cached?.let { return it }
            fetchClientId().also { cached = it }
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

            CLIENT_ID_REGEX.find(script)?.let { return@withContext it.groupValues[1] }
        }
        error("Unable to extract SoundCloud client_id")
    }

    companion object {
        private val SCRIPT_SRC_REGEX = Regex("<script[^>]+src=\"([^\"]+)\"")
        private val CLIENT_ID_REGEX = Regex("client_id\\s*:\\s*\"([0-9a-zA-Z]{32})\"")
    }
}
