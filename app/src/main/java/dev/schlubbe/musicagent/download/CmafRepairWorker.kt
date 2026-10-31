package dev.schlubbe.musicagent.download

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import java.io.File

/** Fixes SoundCloud HLS downloads saved before [CmafRemux] existed: they showed a
 * wrong duration and could not be seeked. Already fixed and progressive files are
 * left alone, so running this again is harmless. */
@HiltWorker
class CmafRepairWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadDao: DownloadDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val resolver = applicationContext.contentResolver
        val tmp = File(applicationContext.cacheDir, "cmaf-repair.m4a")
        for (download in downloadDao.allCompleted()) {
            if (!download.trackId.startsWith("soundcloud:")) continue
            val uri = Uri.parse(download.mediaStoreUri ?: continue)
            runCatching {
                resolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return@runCatching
                if (CmafRemux.rewriteInPlace(tmp)) {
                    resolver.openOutputStream(uri, "wt")?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    downloadDao.updateTotalBytes(download.trackId, tmp.length())
                }
            }
            tmp.delete()
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "cmaf-repair"
    }
}
