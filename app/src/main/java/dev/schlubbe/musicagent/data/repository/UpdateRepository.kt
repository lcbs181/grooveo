package dev.schlubbe.musicagent.data.repository

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.schlubbe.musicagent.data.extract.di.ExtractionHttpClient
import dev.schlubbe.musicagent.data.remote.dto.UpdateInfoDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UpdateRepository"
private const val RELEASES_OWNER = "lcbs181"
private const val RELEASES_REPO = "music-agent-releases"

sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfoDto) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * Checks a dedicated PUBLIC GitHub repo's Releases API for a newer .apk and
 * downloads it straight from GitHub - no backend involved at all. Kept in its
 * own public repo, separate from the app's private source repo
 * (lcbs181/music-agent-standalone), specifically so this never needs a GitHub
 * token: a private repo's Releases API requires auth for both the metadata
 * call and the asset download, which would mean embedding a token in the APK
 * itself - trivially extractable by anyone who unzips it. A plain
 * unauthenticated GET works here because the repo is public.
 *
 * Release tags on that repo must follow "v<versionCode>" (e.g. "v6"), matching
 * android/app/build.gradle.kts's versionCode for that build - see
 * parseVersionCode. After building a new release APK:
 *   gh release create v<versionCode> app-debug.apk --repo lcbs181/music-agent-releases \
 *     --title "<versionName>" --notes "..."
 *
 * Uses [ExtractionHttpClient] (the same plain client SoundCloud/YouTube calls
 * use), not the app's main OkHttpClient - that one carries
 * DynamicBaseUrlInterceptor, which rewrites every request's scheme/host/port to
 * the user's configured backend address and would silently redirect these
 * GitHub calls there instead.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ExtractionHttpClient private val okHttpClient: OkHttpClient,
) {
    /** Reads the installed app's own versionCode via PackageManager (no BuildConfig needed). */
    fun currentVersionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.longVersionCode
    }

    suspend fun checkForUpdate(): UpdateCheckResult = runCatching {
        val release = withTimeout(15_000) { fetchLatestRelease() }
        val versionCode = parseVersionCode(release.get("tag_name").asString)
        if (versionCode > currentVersionCode()) {
            val asset = apkAsset(release) ?: error("Neuestes Release hat keine .apk-Datei")
            UpdateCheckResult.Available(
                UpdateInfoDto(
                    versionCode = versionCode,
                    versionName = release.get("name")?.takeIf { !it.isJsonNull }?.asString
                        ?: release.get("tag_name").asString,
                    downloadUrl = asset.get("browser_download_url").asString,
                ),
            )
        } else {
            UpdateCheckResult.UpToDate
        }
    }.getOrElse {
        val message = when {
            it is TimeoutCancellationException -> "Zeitüberschreitung – GitHub nicht erreichbar"
            else -> it.message
        }
        UpdateCheckResult.Error(message ?: "Unbekannter Fehler")
    }

    private suspend fun fetchLatestRelease(): JsonObject = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$RELEASES_OWNER/$RELEASES_REPO/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()
        val response = okHttpClient.newCall(request).execute()
        val (code, body) = response.use { it.code to it.body?.string().orEmpty() }
        check(code in 200..299) { "GitHub releases API error $code" }
        JsonParser.parseString(body).asJsonObject
    }

    private fun apkAsset(release: JsonObject): JsonObject? =
        release.getAsJsonArray("assets")
            ?.map { it.asJsonObject }
            ?.firstOrNull { it.get("name").asString.endsWith(".apk") }

    // Tags are expected as "v<versionCode>" (e.g. "v6"). Falls back to 0 (never
    // looks "newer" than any installed app) if a tag doesn't follow that
    // convention, rather than crashing the check on a malformed/manual tag.
    private fun parseVersionCode(tagName: String): Long =
        tagName.filter { it.isDigit() }.toLongOrNull() ?: 0L

    /** Downloads the APK straight from its GitHub browser_download_url, reporting
     * 0-100 progress. Runs on Dispatchers.IO since this blocks on OkHttp's
     * synchronous execute() + a byte-copy loop for a ~80-90MB file - doing that on
     * whatever thread calls this (viewModelScope.launch, i.e. main) risks tripping
     * Android's ANR watchdog partway through. */
    suspend fun downloadApk(downloadUrl: String, onProgress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(downloadUrl).build()
        val outputFile = File(context.getExternalFilesDir(null), "update.apk")
        runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Download fehlgeschlagen (${response.code})")
                val body = response.body
                val total = body.contentLength()
                var bytesRead: Int
                var totalRead = 0L
                val buffer = ByteArray(8 * 1024)
                outputFile.outputStream().use { out ->
                    body.byteStream().use { input ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (total > 0) onProgress(((totalRead * 100) / total).toInt())
                        }
                    }
                }
            }
        }.onFailure { e -> Log.w(TAG, "downloadApk failed for $downloadUrl", e) }.getOrThrow()
        outputFile
    }

    /** Launches the system installer for a previously downloaded APK file. */
    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
