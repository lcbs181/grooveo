package dev.schlubbe.musicagent.data.extract.youtube

import dev.schlubbe.musicagent.data.extract.di.ExtractionHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Request as OkHttpRequest
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse

/** NewPipeExtractor needs a [Downloader] implementation supplied by the embedding
 * app (it has no HTTP client of its own) — this is plain OkHttp glue, modeled on
 * NewPipe's own `DownloaderImpl` (see NewPipe/app/.../DownloaderImpl.java in the
 * reference checkout) but trimmed to the minimum needed here (no cookie jar/
 * restricted-mode handling). Registered once via `NewPipe.init(...)` in
 * MusicAgentApp.onCreate(). */
@Singleton
class NewPipeDownloader @Inject constructor(
    @ExtractionHttpClient private val client: OkHttpClient,
) : Downloader() {

    override fun execute(request: ExtractorRequest): ExtractorResponse {
        val mediaType = "application/octet-stream".toMediaTypeOrNull()
        val builder = OkHttpRequest.Builder().url(request.url())
        request.headers().forEach { (name, values) ->
            values.forEach { value -> builder.addHeader(name, value) }
        }
        if (request.headers()["User-Agent"].isNullOrEmpty()) {
            builder.header("User-Agent", USER_AGENT)
        }

        val body = request.dataToSend()?.toRequestBody(mediaType)
        when (val method = request.httpMethod()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            else -> builder.method(method, body ?: ByteArray(0).toRequestBody(mediaType))
        }

        client.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            return ExtractorResponse(
                response.code,
                response.message,
                response.headers.toMultimap(),
                responseBody,
                response.request.url.toString(),
            )
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
