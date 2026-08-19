package dev.schlubbe.musicagent.data.extract.soundcloud

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.schlubbe.musicagent.data.extract.di.ExtractionHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SoundCloudApi"

/** Raw calls against api-v2.soundcloud.com, the same undocumented JSON API
 * soundcloud.com's own web player uses (and the same one yt-dlp's SoundcloudBaseIE
 * talks to server-side, in the removed backend's soundcloud_service.py). Every call
 * needs `?client_id=...`; on a 401/403 the id is refreshed once and the call retried,
 * since SoundCloud rotates it periodically. */
@Singleton
class SoundCloudApi @Inject constructor(
    @ExtractionHttpClient private val client: OkHttpClient,
    private val clientIdProvider: SoundCloudClientIdProvider,
) {
    /** [pathOrUrl] is either a path relative to [API_BASE] (e.g. "search/tracks") or
     * an absolute URL (used for a track's own transcoding metadata URL, which
     * SoundCloud already returns as a full api-v2 URL). */
    suspend fun get(pathOrUrl: String, query: Map<String, String> = emptyMap()): JsonObject =
        call(pathOrUrl, query, retrying = false)

    private suspend fun call(pathOrUrl: String, query: Map<String, String>, retrying: Boolean): JsonObject =
        withContext(Dispatchers.IO) {
            val base = if (pathOrUrl.startsWith("http")) pathOrUrl else "$API_BASE$pathOrUrl"
            val clientId = clientIdProvider.getClientId(forceRefresh = retrying)
            val urlBuilder = base.toHttpUrl().newBuilder()
            query.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
            urlBuilder.addQueryParameter("client_id", clientId)

            val response = client.newCall(Request.Builder().url(urlBuilder.build()).build()).execute()
            val (code, body) = response.use { it.code to it.body?.string().orEmpty() }

            if ((code == 401 || code == 403) && !retrying) {
                Log.w(TAG, "$code for $pathOrUrl, refreshing client_id and retrying once")
                return@withContext call(pathOrUrl, query, retrying = true)
            }
            if (code !in 200..299) {
                Log.w(TAG, "SoundCloud API error $code for $pathOrUrl, body: ${body.take(300)}")
            }
            check(code in 200..299) { "SoundCloud API error $code for $pathOrUrl" }
            JsonParser.parseString(body).asJsonObject
        }

    companion object {
        private const val API_BASE = "https://api-v2.soundcloud.com/"
    }
}
