package dev.schlubbe.musicagent.desktop.data

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class UpdateInfo(val version: String, val notes: String, val url: String)

/** Polls the same public GitHub Releases feed as the Android app's UpdateRepository. */
class UpdateChecker(private val http: OkHttpClient, private val currentVersion: String) {
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/repos/lcbs181/grooveo/releases/latest")
            .header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val o = JsonParser.parseString(resp.body.string()).asJsonObject
            val tag = o.get("tag_name")?.asString ?: return@withContext null
            if (!isNewer(tag, currentVersion)) return@withContext null
            UpdateInfo(tag.removePrefix("v"), o.get("body")?.takeIf { !it.isJsonNull }?.asString.orEmpty(), o.get("html_url")?.asString.orEmpty())
        }
    }

    companion object {
        /** Semantic comparison of "v1.2.3"-style tags; non-numeric parts count as 0. */
        fun isNewer(tag: String, current: String): Boolean {
            fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            val a = parts(tag); val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
