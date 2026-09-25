package dev.schlubbe.musicagent.data.repository

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import dev.schlubbe.musicagent.data.local.dao.TrackAnalysisDao
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.playback.analysis.TrackAnalysisWorker
import javax.inject.Inject
import javax.inject.Singleton

/** Queues [TrackAnalysisWorker] runs on demand - for a single track, a playlist, the
 * likes or every download - on top of the automatic run after each new download.
 * Analysis decodes the local file, so only completed downloads qualify. */
@Singleton
class TrackAnalysisRepository @Inject constructor(
    private val workManager: WorkManager,
    private val downloadDao: DownloadDao,
    private val trackAnalysisDao: TrackAnalysisDao,
) {
    data class Outcome(val queued: Int, val alreadyAnalyzed: Int, val notDownloaded: Int)

    /** [force] re-analyzes tracks that already have a result. */
    suspend fun analyze(trackIds: Collection<String>, force: Boolean = false): Outcome {
        val ids = trackIds.distinct()
        val downloaded = ids.filter { id ->
            downloadDao.getByTrackId(id)?.let { it.state == DownloadState.COMPLETED && it.mediaStoreUri != null } == true
        }
        val done = if (force) emptySet() else trackAnalysisDao.analyzedIds(downloaded).toSet()
        val todo = downloaded.filterNot { it in done }
        // Decoding hundreds of tracks is real battery work; let WorkManager hold it
        // back while the battery is low instead of draining it further.
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
        todo.forEach { id ->
            workManager.enqueueUniqueWork(
                "analyze:$id",
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TrackAnalysisWorker>()
                    .setInputData(workDataOf(TrackAnalysisWorker.KEY_TRACK_ID to id))
                    .setConstraints(constraints)
                    .build(),
            )
        }
        return Outcome(queued = todo.size, alreadyAnalyzed = done.size, notDownloaded = ids.size - downloaded.size)
    }

    suspend fun analyzeAllDownloads(force: Boolean = false): Outcome =
        analyze(downloadDao.allCompleted().map { it.trackId }, force)
}
