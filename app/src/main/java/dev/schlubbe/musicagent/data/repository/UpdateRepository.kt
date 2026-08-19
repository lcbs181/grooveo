package dev.schlubbe.musicagent.data.repository

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.schlubbe.musicagent.data.remote.BackendApi
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

sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfoDto) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * Checks the backend's /updates/latest endpoint, downloads the APK it points
 * to, and hands it off to the system package installer. There's no Play
 * Store presence for this app, so this is the whole update mechanism.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backendApi: BackendApi,
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient,
) {
    /** Reads the installed app's own versionCode via PackageManager (no BuildConfig needed). */
    fun currentVersionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.longVersionCode
    }

    // The shared OkHttpClient's read timeout is 10 minutes (tuned for audio
    // streaming, see NetworkModule) - far too long to wait on a plain version
    // check if the backend is unreachable. Bound this one call explicitly instead
    // of touching that shared, streaming-tuned timeout.
    suspend fun checkForUpdate(): UpdateCheckResult = runCatching {
        val latest = withTimeout(15_000) { backendApi.getLatestUpdate() }
        if (latest.versionCode > currentVersionCode()) {
            UpdateCheckResult.Available(latest)
        } else {
            UpdateCheckResult.UpToDate
        }
    }.getOrElse {
        val message = if (it is TimeoutCancellationException) {
            "Zeitüberschreitung – Backend nicht erreichbar"
        } else {
            it.message
        }
        UpdateCheckResult.Error(message ?: "Unbekannter Fehler")
    }

    /** Downloads the APK to the app's external files dir, reporting 0-100 progress.
     *
     * The actual network read (a blocking OkHttp `execute()` plus the whole
     * byte-copy loop for a ~80-90MB file) used to run on whatever thread called
     * this - which for every real caller is `viewModelScope.launch`, i.e. the main
     * thread. Copying tens of megabytes synchronously on the main thread is exactly
     * the kind of thing that trips Android's ANR watchdog partway through, which
     * then surfaces here as some arbitrary interrupted-thread exception (often with
     * no useful message) rather than a clean HTTP failure - almost certainly why
     * this showed a bare "Download fehlgeschlagen" with no code, unlike the
     * `!isSuccessful` branch below which always includes one. */
    suspend fun downloadApk(downloadUrl: String, onProgress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val baseUrl = settingsRepository.backendBaseUrlCached.trimEnd('/')
        val url = if (downloadUrl.startsWith("http")) downloadUrl else "$baseUrl$downloadUrl"
        val request = Request.Builder().url(url).build()

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
                            // MutableStateFlow.value is thread-safe to set from any
                            // thread (this loop runs on Dispatchers.IO), so no need
                            // to hop back to Main for every one of the ~thousands
                            // of chunks a large APK download reads.
                            if (total > 0) onProgress(((totalRead * 100) / total).toInt())
                        }
                    }
                }
            }
        }.onFailure { e -> Log.w(TAG, "downloadApk failed for $url", e) }.getOrThrow()
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
