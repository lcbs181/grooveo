package dev.schlubbe.musicagent.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.schlubbe.musicagent.data.extract.ResolvedStream
import dev.schlubbe.musicagent.data.extract.StreamResolverRegistry
import dev.schlubbe.musicagent.data.extract.di.ExtractionHttpClient
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import dev.schlubbe.musicagent.data.local.entity.DownloadEntity
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI

/** Outcome of a single transfer attempt (progressive Range-resume or HLS segment
 * concatenation) - [DownloadWorker.doWork] turns this into the persisted
 * [DownloadEntity] state and the [androidx.work.ListenableWorker.Result]. */
private sealed class TransferOutcome {
    data class Completed(val mimeType: String) : TransferOutcome()
    data class Paused(val bytesSoFar: Long, val pct: Int) : TransferOutcome()
    data class Failed(val bytesSoFar: Long, val retryable: Boolean) : TransferOutcome()
}

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

        val existing = downloadDao.getByTrackId(trackId)
        val tempFile = tempFileFor(trackId)
        // Only trust a persisted byte offset if the temp file on disk actually still
        // has that many bytes - a cleared cache or a mismatched restart shouldn't
        // silently resume from a wrong/missing offset.
        val startOffset = if (existing != null && tempFile.exists() && tempFile.length() == existing.bytesDownloaded) {
            existing.bytesDownloaded
        } else {
            0L
        }
        val createdAt = existing?.createdAt ?: System.currentTimeMillis()

        downloadDao.upsert(
            DownloadEntity(
                trackId, existing?.mediaStoreUri, existing?.relativePath, DownloadState.DOWNLOADING,
                existing?.progressPct ?: 0, createdAt, tempFile.absolutePath, startOffset,
            ),
        )

        val resolved = try {
            streamResolverRegistry.resolve(source, sourceId)
        } catch (e: Exception) {
            downloadDao.upsert(
                DownloadEntity(trackId, null, null, DownloadState.FAILED, 0, createdAt, tempFile.absolutePath, startOffset),
            )
            return Result.retry()
        }

        val outcome = if (resolved.isHls) {
            downloadHls(resolved, tempFile) { pct -> setProgress(workDataOf(PROGRESS_KEY to pct)) }
        } else {
            downloadProgressive(resolved, tempFile, startOffset) { pct -> setProgress(workDataOf(PROGRESS_KEY to pct)) }
        }

        return when (outcome) {
            is TransferOutcome.Completed -> {
                // The full transfer succeeding doesn't guarantee the MediaStore
                // finalize step does too (insert can fail, scoped-storage quirks,
                // a full disk) - unlike downloadProgressive/downloadHls, this has
                // no try/catch of its own, so an exception here used to propagate
                // straight out of doWork() and skip the upsert below entirely,
                // leaving the entity stuck at DOWNLOADING/100% forever (the "pause
                // button never disappears" bug). tempFilePath/bytesDownloaded are
                // kept on failure so a retry can go straight back to finalizing
                // instead of re-downloading the whole track.
                try {
                    val displayName = sanitizeFileName(
                        if (artist.isBlank()) title else "$artist - $title",
                    ) + extensionFor(outcome.mimeType)

                    val uri = MediaStoreWriter(applicationContext).write(
                        displayName = displayName,
                        mimeType = outcome.mimeType,
                        input = tempFile.inputStream(),
                        contentLength = tempFile.length(),
                        onProgress = {},
                    )
                    tempFile.delete()

                    downloadDao.upsert(
                        DownloadEntity(
                            trackId = trackId,
                            mediaStoreUri = uri.toString(),
                            relativePath = "Music/PrivateMusicAgent",
                            state = DownloadState.COMPLETED,
                            progressPct = 100,
                            createdAt = createdAt,
                        ),
                    )
                    Result.success()
                } catch (e: Exception) {
                    downloadDao.upsert(
                        DownloadEntity(
                            trackId, null, null, DownloadState.FAILED, 100, createdAt,
                            tempFile.absolutePath.takeIf { tempFile.exists() }, tempFile.length(),
                        ),
                    )
                    Result.retry()
                }
            }
            is TransferOutcome.Paused -> {
                // Wrapped in NonCancellable: this write must land even though the
                // cancellation that got us here (WorkManager.cancelUniqueWork, see
                // DownloadRepository.pauseDownload) is actively tearing this coroutine
                // down - without this, the persisted PAUSED state could be lost and
                // "Fortsetzen" would have nothing to resume from.
                withContext(NonCancellable) {
                    downloadDao.upsert(
                        DownloadEntity(
                            trackId, existing?.mediaStoreUri, existing?.relativePath, DownloadState.PAUSED,
                            outcome.pct.takeIf { it >= 0 } ?: (existing?.progressPct ?: 0),
                            createdAt, tempFile.absolutePath, outcome.bytesSoFar,
                        ),
                    )
                }
                Result.failure()
            }
            is TransferOutcome.Failed -> {
                downloadDao.upsert(
                    DownloadEntity(
                        trackId, null, null, DownloadState.FAILED, 0, createdAt,
                        tempFile.absolutePath.takeIf { outcome.bytesSoFar > 0 }, outcome.bytesSoFar,
                    ),
                )
                if (outcome.retryable) Result.retry() else Result.failure()
            }
        }
    }

    /** Direct single-file download (YouTube always, SoundCloud when its
     * "progressive" transcoding is what resolved) - byte-range resumable via a
     * `Range` header when [startOffset] > 0. */
    private suspend fun downloadProgressive(
        resolved: ResolvedStream,
        tempFile: File,
        startOffset: Long,
        onProgress: suspend (Int) -> Unit,
    ): TransferOutcome {
        var resumeOffset = startOffset
        val requestBuilder = Request.Builder().url(resolved.url)
        resolved.httpHeaders.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        if (resumeOffset > 0) requestBuilder.addHeader("Range", "bytes=$resumeOffset-")

        return try {
            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                // A Range request for bytes already fully downloaded (e.g. retrying
                // after the transfer succeeded but the MediaStore finalize step
                // failed) lands past the end of the resource - the server's honest
                // answer is 416, not an error. Treat it as already-complete rather
                // than a dead-end failure with no way to recover short of a full
                // redownload; the temp file already has everything finalize needs.
                if (response.code == 416 && resumeOffset > 0) {
                    return TransferOutcome.Completed(mimeType = "audio/mp4")
                }
                if (!response.isSuccessful) {
                    return TransferOutcome.Failed(resumeOffset, retryable = response.code in 500..599)
                }

                // The server may ignore our Range header and send the whole file back
                // with a plain 200 instead of a 206 - resuming into the existing bytes
                // in that case would produce a corrupt, duplicate-prefixed file, so
                // fall back to a full restart.
                val resuming = resumeOffset > 0 && response.code == 206
                if (resumeOffset > 0 && !resuming) resumeOffset = 0

                val body = response.body
                val mimeType = body.contentType()?.toString() ?: "audio/mp4"
                val expectedTotal = body.contentLength().let { if (it > 0) it + resumeOffset else -1L }

                FileOutputStream(tempFile, resuming).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var totalRead = resumeOffset
                        var lastPct = -1
                        while (true) {
                            if (isStopped) return TransferOutcome.Paused(totalRead, lastPct)
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            totalRead += read
                            if (expectedTotal > 0) {
                                val pct = ((totalRead * 100) / expectedTotal).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                        TransferOutcome.Completed(mimeType)
                    }
                }
            }
        } catch (e: IOException) {
            TransferOutcome.Failed(tempFile.length(), retryable = true)
        }
    }

    /** SoundCloud's HLS transcodings resolve to a .m3u8 media playlist of short
     * (a few seconds each) segments, not one downloadable file - fetches the
     * playlist, then each segment in order, concatenating them into one local file
     * (playable via Media3's own MPEG-TS extractor, same as any other local file).
     * Unlike [downloadProgressive], a pause/retry here always restarts from segment
     * 0 - individual segments aren't byte-range-addressable as a single persisted
     * offset, but since each is only a few seconds, redoing them is cheap. */
    private suspend fun downloadHls(
        resolved: ResolvedStream,
        tempFile: File,
        onProgress: suspend (Int) -> Unit,
    ): TransferOutcome {
        return try {
            val playlistRequest = Request.Builder().url(resolved.url)
            resolved.httpHeaders.forEach { (key, value) -> playlistRequest.addHeader(key, value) }
            val playlistText = okHttpClient.newCall(playlistRequest.build()).execute().use { response ->
                if (!response.isSuccessful) return TransferOutcome.Failed(0, retryable = response.code in 500..599)
                response.body.string()
            }

            val segmentUrls = playlistText.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line -> URI(resolved.url).resolve(line).toString() }
                .toList()
            if (segmentUrls.isEmpty()) return TransferOutcome.Failed(0, retryable = false)

            FileOutputStream(tempFile, false).use { output ->
                var lastPct = -1
                for ((index, segmentUrl) in segmentUrls.withIndex()) {
                    if (isStopped) return TransferOutcome.Paused(tempFile.length(), lastPct)

                    val segmentRequest = Request.Builder().url(segmentUrl)
                    resolved.httpHeaders.forEach { (key, value) -> segmentRequest.addHeader(key, value) }
                    okHttpClient.newCall(segmentRequest.build()).execute().use { segmentResponse ->
                        if (!segmentResponse.isSuccessful) {
                            throw IOException("HLS segment $index failed: HTTP ${segmentResponse.code}")
                        }
                        segmentResponse.body.byteStream().use { it.copyTo(output) }
                    }

                    val pct = ((index + 1) * 100) / segmentUrls.size
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(pct)
                    }
                }
            }
            TransferOutcome.Completed(mimeType = "video/mp2t")
        } catch (e: IOException) {
            TransferOutcome.Failed(tempFile.length(), retryable = true)
        }
    }

    private fun tempFileFor(trackId: String): File {
        val dir = File(applicationContext.cacheDir, "downloads").apply { mkdirs() }
        return File(dir, "${sanitizeFileName(trackId)}.part")
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()

    private fun extensionFor(mimeType: String): String = when {
        mimeType.contains("mp4") || mimeType.contains("m4a") -> ".m4a"
        mimeType.contains("mpeg") -> ".mp3"
        mimeType.contains("aac") -> ".aac"
        mimeType.contains("webm") -> ".weba"
        mimeType.contains("ogg") -> ".ogg"
        mimeType.contains("mp2t") -> ".ts"
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
