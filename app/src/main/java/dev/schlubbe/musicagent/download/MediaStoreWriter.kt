package dev.schlubbe.musicagent.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/**
 * Writes a downloaded track into the public Music collection via the MediaStore
 * IS_PENDING pattern (required on scoped storage, API 29+) so the file shows up
 * for other apps (file managers, other players) once complete.
 */
class MediaStoreWriter(private val context: Context) {

    /**
     * [useDownloadsCollection] routes the insert through `MediaStore.Downloads`
     * instead of `MediaStore.Audio.Media`, for a file whose MIME type the Audio
     * collection's own validator won't accept - `ContentResolver.insert()` on
     * `MediaStore.Audio.Media` throws `IllegalArgumentException` for anything
     * outside its internal MIME allowlist (confirmed on device: this is exactly
     * what made every SoundCloud HLS download fail, 100% of the time, before this
     * existed - see DownloadWorker.downloadHls). `MediaStore.Downloads` has no such
     * allowlist, at the cost of the file landing under `Download/` rather than
     * `Music/` and not showing up in other apps' music-only views - an accepted
     * trade for the rare track that offers no plain downloadable transcoding at
     * all, now that [dev.schlubbe.musicagent.data.extract.soundcloud.SoundCloudStreamResolver]
     * prefers one when it exists.
     */
    suspend fun write(
        displayName: String,
        mimeType: String,
        input: InputStream,
        contentLength: Long,
        useDownloadsCollection: Boolean = false,
        onProgress: suspend (Int) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection: Uri
        val values = ContentValues().apply {
            if (useDownloadsCollection) {
                collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PrivateMusicAgent")
                put(MediaStore.Downloads.IS_PENDING, 1)
            } else {
                collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/PrivateMusicAgent")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")

        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("Could not open output stream")
            output.use { copyWithProgress(input, it, contentLength, onProgress) }

            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        } finally {
            input.close()
        }
    }

    private suspend fun copyWithProgress(
        input: InputStream,
        output: java.io.OutputStream,
        contentLength: Long,
        onProgress: suspend (Int) -> Unit,
    ) {
        val buffer = ByteArray(64 * 1024)
        var totalRead = 0L
        var lastReportedPct = -1

        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            totalRead += read

            if (contentLength > 0) {
                val pct = ((totalRead * 100) / contentLength).toInt()
                if (pct != lastReportedPct) {
                    lastReportedPct = pct
                    onProgress(pct)
                }
            }
        }
    }
}
