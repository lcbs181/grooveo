package dev.schlubbe.musicagent.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.Constraints
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.schlubbe.musicagent.data.local.entity.DownloadState
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
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager,
    private val downloadDao: DownloadDao,
    private val trackDao: TrackDao,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { repairStoredSizes() }
    }

    /** Re-reads each finished download's real size from MediaStore. Downloads that
     * resumed after an interruption stored only the resumed chunk's length, so the
     * Downloads list showed things like "20,8 KB" for a whole song. */
    private suspend fun repairStoredSizes() {
        downloadDao.allCompleted().forEach { entity ->
            val uri = entity.mediaStoreUri ?: return@forEach
            val actual = runCatching {
                context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
                }
            }.getOrNull() ?: return@forEach
            if (actual > 0 && actual != entity.totalBytes) downloadDao.updateTotalBytes(entity.trackId, actual)
        }
    }

    fun startDownload(track: TrackResultDto) {
        val trackId = "${track.source}:${track.sourceId}"

        // DownloadEntity itself carries no title/artist/thumbnail - cache the track's
        // metadata here (same TrackEntity table search/play already populate) so the
        // Downloads tab and playLocalDownload() always have something to show/play
        // with, even for a track downloaded straight from a list without ever being
        // played first. Also what resumeDownload()/retryDownload() look up later,
        // since they only have a trackId, not a full TrackResultDto.
        scope.launch {
            // KEEP only dedupes against *unfinished* work, so without this a finished
            // download would be fetched again from scratch (e.g. "Alle herunterladen"
            // on a list that's already partly offline).
            if (downloadDao.getByTrackId(trackId)?.state == DownloadState.COMPLETED) return@launch
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
                    genre = track.genre,
                ),
            )
            enqueue(track.source, track.sourceId, track.title, track.artist.orEmpty(), trackId)
        }
    }

    /** Removes a download completely: stops any running transfer, deletes the audio
     * file from MediaStore and drops the record, so the space is actually freed. */
    fun deleteDownload(trackId: String) {
        workManager.cancelUniqueWork(trackId)
        scope.launch {
            downloadDao.getByTrackId(trackId)?.mediaStoreUri?.let { uri ->
                runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
            }
            downloadDao.delete(trackId)
        }
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
            // REPLACE, not KEEP: a FAILED entity's underlying WorkManager job is
            // often not actually finished - Result.retry() (returned on most
            // failure paths in DownloadWorker) leaves the unique work scheduled
            // for WorkManager's own backoff retry, so KEEP would silently no-op
            // here, making the "Wiederholen"/"Fortsetzen" button look broken.
            // REPLACE forces a fresh attempt right now regardless of any pending
            // auto-retry.
            enqueue(cached.source, cached.sourceId, cached.title, cached.artist.orEmpty(), trackId, ExistingWorkPolicy.REPLACE)
        }
    }

    private fun enqueue(
        source: String,
        sourceId: String,
        title: String,
        artist: String,
        trackId: String,
        existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    ) {
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

        workManager.enqueueUniqueWork(trackId, existingWorkPolicy, request)
    }
}
