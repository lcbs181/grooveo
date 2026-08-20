package dev.schlubbe.musicagent.data.repository

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import dev.schlubbe.musicagent.data.local.dao.TrackDao
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
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startDownload(track: TrackResultDto) {
        val trackId = "${track.source}:${track.sourceId}"

        // DownloadEntity itself carries no title/artist/thumbnail - cache the track's
        // metadata here (same TrackEntity table search/play already populate) so the
        // Downloads tab and playLocalDownload() always have something to show/play
        // with, even for a track downloaded straight from a list without ever being
        // played first. Also what resumeDownload()/retryDownload() look up later,
        // since they only have a trackId, not a full TrackResultDto.
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
                    webpageUrl = track.webpageUrl,
                ),
            )
        }

        enqueue(track.source, track.sourceId, track.title, track.artist.orEmpty(), trackId)
    }

    fun startDownloadAll(tracks: List<TrackResultDto>) {
        tracks.forEach { startDownload(it) }
    }

    /** Stops an in-progress download without discarding what's already been
     * transferred - [dev.schlubbe.musicagent.download.DownloadWorker] cooperatively
     * detects the cancellation and persists a PAUSED [dev.schlubbe.musicagent.data.local.entity.DownloadEntity]
     * with the byte offset (or, for an HLS/SoundCloud download, the segment
     * progress) it had reached. */
    fun pauseDownload(trackId: String) {
        workManager.cancelUniqueWork(trackId)
    }

    /** Continues a PAUSED download - re-enqueues the same worker, which resumes via
     * an HTTP Range request for a progressive (YouTube, or SoundCloud when its
     * "progressive" transcoding resolved) download, or restarts from the first
     * segment for an HLS one. */
    fun resumeDownload(trackId: String) {
        reEnqueueFromCache(trackId)
    }

    /** Re-attempts a FAILED download. Mechanically identical to [resumeDownload] -
     * DownloadWorker itself decides whether to resume from the persisted byte
     * offset (progressive) or start over (HLS), the same way it would for a pause. */
    fun retryDownload(trackId: String) {
        reEnqueueFromCache(trackId)
    }

    private fun reEnqueueFromCache(trackId: String) {
        scope.launch {
            val cached = trackDao.getById(trackId) ?: return@launch
            enqueue(cached.source, cached.sourceId, cached.title, cached.artist.orEmpty(), trackId)
        }
    }

    private fun enqueue(source: String, sourceId: String, title: String, artist: String, trackId: String) {
        val data = workDataOf(
            DownloadWorker.KEY_SOURCE to source,
            DownloadWorker.KEY_SOURCE_ID to sourceId,
            DownloadWorker.KEY_TITLE to title,
            DownloadWorker.KEY_ARTIST to artist,
        )
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settingsRepository.downloadsWifiOnlyCached) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .build()

        // KEEP is safe here even for a resume/retry re-enqueue: it only no-ops when
        // existing work under this unique name is still pending/running, and a
        // PAUSED (cancelled) or FAILED download's work is always already finished.
        workManager.enqueueUniqueWork(trackId, ExistingWorkPolicy.KEEP, request)
    }
}
