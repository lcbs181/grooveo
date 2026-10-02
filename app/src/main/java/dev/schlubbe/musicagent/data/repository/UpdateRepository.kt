package dev.schlubbe.musicagent.data.repository

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
private const val RELEASES_REPO = "grooveo"

/** State of the system download of an update APK. */
sealed interface UpdateDownloadState {
    data class Running(val progressPct: Int, val downloadedBytes: Long, val totalBytes: Long, val waitingForNetwork: Boolean) : UpdateDownloadState
    data class Done(val file: File) : UpdateDownloadState
    data class Failed(val message: String) : UpdateDownloadState
    /** No download is known for this version (never started, or removed). */
    data object None : UpdateDownloadState
}

sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfoDto) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * Checks this project's own PUBLIC GitHub repo's Releases API for a newer
 * .apk and downloads it straight from GitHub - no backend involved at all.
 * A plain unauthenticated GET works here because the repo is public: no
 * GitHub token needs to be embedded in the APK, which would be trivially
 * extractable by anyone who unzips it. (Releases used to live on a separate
 * lcbs181/music-agent-releases repo, back when this source repo (then called
 * "music-agent-standalone") was private - now that the source repo is public
 * too, releases are cut from here directly. See docs/RELEASING.md.)
 *
 * Release tags on that repo must follow "v<versionCode>" (e.g. "v6"), matching
 * android/app/build.gradle.kts's versionCode for that build - see
 * parseVersionCode. After building a new release APK:
 *   gh release create v<versionCode> app-release.apk --repo lcbs181/grooveo \
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
                    sizeBytes = asset.get("size")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
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

    // ---------------- download ----------------
    //
    // The APK (~100 MB) is fetched by the system DownloadManager, not in-process:
    // it keeps going while Grooveo is in the background or killed, shows its own
    // progress notification, and resumes by itself after a network drop. The file
    // is kept per version (grooveo-<versionCode>.apk), so a failed or cancelled
    // installation can simply be retried without downloading again.

    private val prefs get() = context.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val downloadManager get() = context.getSystemService(DownloadManager::class.java)

    fun apkFile(info: UpdateInfoDto): File = File(context.getExternalFilesDir(null), "grooveo-${info.versionCode}.apk")

    /** The already downloaded, complete APK for [info], if there is one. */
    fun downloadedApk(info: UpdateInfoDto): File? {
        val f = apkFile(info)
        if (!f.isFile || f.length() == 0L) return null
        if (info.sizeBytes > 0 && f.length() != info.sizeBytes) return null
        // the package must parse and carry the expected version, or it is truncated/corrupt
        val pkg = runCatching { context.packageManager.getPackageArchiveInfo(f.path, 0) }.getOrNull() ?: return null
        return f.takeIf { pkg.packageName == context.packageName && pkg.longVersionCode == info.versionCode }
    }

    /** Starts the download of [info], or attaches to the one already running for it. */
    fun startDownload(info: UpdateInfoDto) {
        if (downloadState(info) is UpdateDownloadState.Running) return
        apkFile(info).delete()
        val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
            .setTitle("Grooveo ${info.versionName}")
            .setDescription("Update wird heruntergeladen")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, apkFile(info).name)
        val id = downloadManager.enqueue(request)
        prefs.edit().putLong(KEY_ID, id).putLong(KEY_VERSION, info.versionCode).apply()
    }

    /** Current state of the download for [info]. */
    fun downloadState(info: UpdateInfoDto): UpdateDownloadState {
        downloadedApk(info)?.let { return UpdateDownloadState.Done(it) }
        val id = prefs.getLong(KEY_ID, -1)
        if (id < 0 || prefs.getLong(KEY_VERSION, -1) != info.versionCode) return UpdateDownloadState.None
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(id)) ?: return UpdateDownloadState.None
        cursor.use { c ->
            if (!c.moveToFirst()) return UpdateDownloadState.None
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: info.sizeBytes
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> downloadedApk(info)?.let { UpdateDownloadState.Done(it) }
                    ?: UpdateDownloadState.Failed("Heruntergeladene Datei ist unvollständig")
                DownloadManager.STATUS_FAILED -> UpdateDownloadState.Failed(failureMessage(reason))
                else -> UpdateDownloadState.Running(
                    progressPct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0,
                    downloadedBytes = done,
                    totalBytes = total,
                    waitingForNetwork = status == DownloadManager.STATUS_PAUSED,
                )
            }
        }
    }

    /** Stops a running download of [info] and removes its partial file. */
    fun cancelDownload(info: UpdateInfoDto) {
        val id = prefs.getLong(KEY_ID, -1)
        if (id >= 0 && prefs.getLong(KEY_VERSION, -1) == info.versionCode) downloadManager.remove(id)
        prefs.edit().remove(KEY_ID).remove(KEY_VERSION).apply()
    }

    /** True if [downloadId] is the update download (for the completion receiver). */
    fun isUpdateDownload(downloadId: Long) = downloadId >= 0 && prefs.getLong(KEY_ID, -1) == downloadId

    /** Version code of the pending update download, or -1. */
    fun pendingVersionCode(): Long = prefs.getLong(KEY_VERSION, -1)

    /** Deletes APKs of versions that are installed by now (and the old single update.apk). */
    fun cleanupInstalledApks() {
        val current = currentVersionCode()
        context.getExternalFilesDir(null)?.listFiles()?.forEach { f ->
            val code = Regex("""grooveo-(\d+)\.apk""").matchEntire(f.name)?.groupValues?.get(1)?.toLongOrNull()
            if (f.name == "update.apk" || (code != null && code <= current)) f.delete()
        }
    }

    private fun failureMessage(reason: Int) = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Nicht genug Speicherplatz"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME -> "Verbindung abgebrochen"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Speicher nicht verfügbar"
        else -> "Download fehlgeschlagen ($reason)"
    }

    /** Intent that opens the system installer for [file]. */
    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Launches the system installer for a previously downloaded APK file. */
    fun installApk(file: File) {
        context.startActivity(installIntent(file))
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val KEY_ID = "download_id"
        const val KEY_VERSION = "download_version"
    }
}
