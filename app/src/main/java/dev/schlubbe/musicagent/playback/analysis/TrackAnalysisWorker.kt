package dev.schlubbe.musicagent.playback.analysis

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import dev.schlubbe.musicagent.data.local.dao.TrackAnalysisDao
import dev.schlubbe.musicagent.data.local.entity.TrackAnalysisEntity

/** Runs [TrackAnalyzer] against one just-completed download and caches the result -
 * enqueued by [dev.schlubbe.musicagent.download.DownloadWorker] right after a
 * download finishes, unique per track so re-downloading an already-analyzed track
 * doesn't repeat the (real, if sub-second-per-second) decode work. A failed/skipped
 * analysis is a quiet no-op, not a retry: [dev.schlubbe.musicagent.playback.CrossfadeController]
 * already treats "no row in track_analysis" as a normal, expected fallback state. */
@HiltWorker
class TrackAnalysisWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadDao: DownloadDao,
    private val trackAnalysisDao: TrackAnalysisDao,
    private val trackAnalyzer: TrackAnalyzer,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val trackId = inputData.getString(KEY_TRACK_ID) ?: return Result.failure()
        val download = downloadDao.getByTrackId(trackId) ?: return Result.success()
        val uriString = download.mediaStoreUri ?: return Result.success()

        val result = trackAnalyzer.analyze(Uri.parse(uriString))
        if (result == null) {
            android.util.Log.i("TrackAnalysisWorker", "no usable mix points for $trackId")
            return Result.success()
        }
        android.util.Log.i("TrackAnalysisWorker", "analyzed $trackId: in=${result.mixInMs}ms out=${result.mixOutMs}ms")
        trackAnalysisDao.upsert(
            TrackAnalysisEntity(
                trackId = trackId,
                mixOutMs = result.mixOutMs,
                mixInMs = result.mixInMs,
                analyzedAt = System.currentTimeMillis(),
            ),
        )
        return Result.success()
    }

    companion object {
        const val KEY_TRACK_ID = "track_id"
    }
}
