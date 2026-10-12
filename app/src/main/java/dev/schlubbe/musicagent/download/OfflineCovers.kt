package dev.schlubbe.musicagent.download

import android.content.Context
import coil.map.Mapper
import coil.request.Options
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.schlubbe.musicagent.data.extract.di.ExtractionHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Covers of offline tracks, kept as files in the app's storage (covers/, named by a hash of
 * the cover's URL). [mapper] makes every image load of such a URL use the file, so likes,
 * playlists, the player and the downloads show their covers without a connection too.
 */
@Singleton
class OfflineCovers @Inject constructor(
    @ApplicationContext context: Context,
    @ExtractionHttpClient private val http: OkHttpClient,
) {
    private val dir = File(context.filesDir, "covers")
    /** Names of the files in [dir]; the mapper runs on the main thread and must not touch the disk. */
    private val present: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        dir.list()?.filter { it.endsWith(EXT) }?.let(::addAll)
    }

    fun has(url: String?): Boolean = url != null && nameOf(url) in present

    /** The local file for [url], if it was saved. */
    fun fileFor(url: String?): File? = url?.let(::nameOf)?.takeIf { it in present }?.let { File(dir, it) }

    /** Downloads the cover at [url] once; false when that failed (it is tried again next time). */
    suspend fun save(url: String?): Boolean {
        if (url.isNullOrBlank() || !url.startsWith("http")) return false
        val name = nameOf(url)
        if (name in present) return true
        return withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val tmp = File(dir, "$name.tmp")
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    check(r.isSuccessful) { "HTTP ${r.code}" }
                    val body = r.body ?: error("empty")
                    check((body.contentType()?.type ?: "image") == "image") { "not an image" }
                    tmp.outputStream().use { body.byteStream().copyTo(it) }
                }
                check(tmp.length() > 0 && tmp.renameTo(File(dir, name))) { "write failed" }
                present += name
            }.isSuccess
        }
    }

    /** For Coil: a saved cover's URL loads the file. */
    val mapper = object : Mapper<String, File> {
        override fun map(data: String, options: Options): File? = fileFor(data)
    }

    private fun nameOf(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + EXT

    private companion object {
        const val EXT = ".img"
    }
}
