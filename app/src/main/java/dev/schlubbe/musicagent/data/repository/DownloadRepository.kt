package dev.schlubbe.musicagent.data.repository

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.DownloadEntity
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.download.DownloadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    private val workManager: WorkManager,
    private val downloadDao: DownloadDao,
    private val trackDao: TrackDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startDownload(track: TrackResultDto) {
        val trackId = "${track.source}:${track.sourceId}"

        // DownloadEntity itself carries no title/artist/thumbnail - cache the track's
        // metadata here (same TrackEntity table search/play already populate) so the
        // Downloads tab and playLocalDownload() always have something to show/play
        // with, even for a track downloaded straight from a list without ever being
        // played first.
        scope.launch {
            trackDao.upsert(
                TrackEntity(
                    id = trackId,
                    source = track.source,
                    sourceId = track.sourceId,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationSec = track.durationSec,
                    thumbnailUrl = track.thumbnailUrl,
                    lastAccessedAt = System.currentTimeMillis(),
                ),
            )
        }

        // SoundCloud downloads aren't supported yet in this backend-less variant (its
        // stream resolves to HLS, not a single downloadable file — see
        // DownloadWorker's isHls check and the plan's scope cut). Fail immediately with
        // a state the existing Downloads tab already renders ("Fehlgeschlagen") instead
        // of enqueueing work that's guaranteed to fail after a network round-trip.
        if (track.source == "soundcloud") {
            scope.launch {
                downloadDao.upsert(
                    DownloadEntity(trackId, null, null, DownloadState.FAILED, 0, System.currentTimeMillis()),
                )
            }
            return
        }

        val data = workDataOf(
            DownloadWorker.KEY_SOURCE to track.source,
            DownloadWorker.KEY_SOURCE_ID to track.sourceId,
            DownloadWorker.KEY_TITLE to track.title,
            DownloadWorker.KEY_ARTIST to (track.artist ?: ""),
        )
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .build()

        workManager.enqueueUniqueWork(trackId, ExistingWorkPolicy.KEEP, request)
    }

    fun startDownloadAll(tracks: List<TrackResultDto>) {
        tracks.forEach { startDownload(it) }
    }
}
