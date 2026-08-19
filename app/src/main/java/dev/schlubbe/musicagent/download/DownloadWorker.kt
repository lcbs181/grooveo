package dev.schlubbe.musicagent.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.schlubbe.musicagent.data.extract.StreamResolverRegistry
import dev.schlubbe.musicagent.data.extract.di.ExtractionHttpClient
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import dev.schlubbe.musicagent.data.local.entity.DownloadEntity
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadDao: DownloadDao,
    private val streamResolverRegistry: StreamResolverRegistry,
    @ExtractionHttpClient private val okHttpClient: OkHttpClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val source = inputData.getString(KEY_SOURCE) ?: return Result.failure()
        val sourceId = inputData.getString(KEY_SOURCE_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Unknown"
        val artist = inputData.getString(KEY_ARTIST).orEmpty()
        val trackId = "$source:$sourceId"

        downloadDao.upsert(
            DownloadEntity(trackId, null, null, DownloadState.DOWNLOADING, 0, System.currentTimeMillis()),
        )

        val resolved = try {
            streamResolverRegistry.resolve(source, sourceId)
        } catch (e: Exception) {
            downloadDao.upsert(
                DownloadEntity(trackId, null, null, DownloadState.FAILED, 0, System.currentTimeMillis()),
            )
            return Result.retry()
        }

        // SoundCloud resolves to HLS (a .m3u8 playlist of segments, not a single
        // downloadable file) — a real offline download would need to fetch and remux
        // segments (e.g. via Media3's HlsDownloader), which is a separate, larger
        // piece of work not yet done in this backend-less variant. Fail clearly
        // rather than trying to "download" a playlist file and silently producing a
        // broken local file.
        if (resolved.isHls) {
            downloadDao.upsert(
                DownloadEntity(trackId, null, null, DownloadState.FAILED, 0, System.currentTimeMillis()),
            )
            return Result.failure()
        }

        val requestBuilder = Request.Builder().url(resolved.url)
        resolved.httpHeaders.forEach { (key, value) -> requestBuilder.addHeader(key, value) }

        return try {
            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    downloadDao.upsert(
                        DownloadEntity(trackId, null, null, DownloadState.FAILED, 0, System.currentTimeMillis()),
                    )
                    return if (response.code in 500..599) Result.retry() else Result.failure()
                }

                val body = response.body
                val mimeType = body.contentType()?.toString() ?: "audio/mp4"
                val displayName = sanitizeFileName(
                    if (artist.isBlank()) title else "$artist - $title",
                ) + extensionFor(mimeType)

                val uri = MediaStoreWriter(applicationContext).write(
                    displayName = displayName,
                    mimeType = mimeType,
                    input = body.byteStream(),
                    contentLength = body.contentLength(),
                    onProgress = { pct -> setProgress(workDataOf(PROGRESS_KEY to pct)) },
                )

                downloadDao.upsert(
                    DownloadEntity(
                        trackId = trackId,
                        mediaStoreUri = uri.toString(),
                        relativePath = "Music/PrivateMusicAgent",
                        state = DownloadState.COMPLETED,
                        progressPct = 100,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                Result.success()
            }
        } catch (e: IOException) {
            downloadDao.upsert(
                DownloadEntity(trackId, null, null, DownloadState.FAILED, 0, System.currentTimeMillis()),
            )
            Result.retry()
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()

    private fun extensionFor(mimeType: String): String = when {
        mimeType.contains("mp4") || mimeType.contains("m4a") -> ".m4a"
        mimeType.contains("mpeg") -> ".mp3"
        mimeType.contains("aac") -> ".aac"
        mimeType.contains("webm") -> ".weba"
        mimeType.contains("ogg") -> ".ogg"
        else -> ".m4a"
    }

    companion object {
        const val KEY_SOURCE = "source"
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val PROGRESS_KEY = "progress_pct"
    }
}
