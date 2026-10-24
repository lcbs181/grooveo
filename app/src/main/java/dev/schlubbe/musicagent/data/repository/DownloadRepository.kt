package dev.schlubbe.musicagent.data.repository

import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import dev.schlubbe.musicagent.data.extract.youtube.YouTubeMusicSearchClient
import dev.schlubbe.musicagent.data.extract.soundcloud.toSoundCloudTrackResultDto
import dev.schlubbe.musicagent.data.extract.soundcloud.SoundCloudApi
import dev.schlubbe.musicagent.download.OfflineCovers
import dev.schlubbe.musicagent.data.tags.TagRepository
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
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
import dev.schlubbe.musicagent.data.local.dao.LikeDao
import dev.schlubbe.musicagent.data.local.dao.PlaylistTrackDao
import dev.schlubbe.musicagent.data.local.entity.DownloadEntity
import dev.schlubbe.musicagent.download.DeviceDownloads
import dev.schlubbe.musicagent.playback.analysis.TrackAnalysisWorker
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.download.DownloadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager,
    private val downloadDao: DownloadDao,
    private val trackDao: TrackDao,
    private val settingsRepository: SettingsRepository,
    private val likeDao: LikeDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val tagRepository: TagRepository,
    private val offlineCovers: OfflineCovers,
    private val soundCloud: SoundCloudApi,
    private val youTube: YouTubeMusicSearchClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanLock = kotlinx.coroutines.sync.Mutex()

    init {
        scope.launch { repairStoredSizes(); scanDevice() }
    }

    /**
     * Adds the downloaded files on the phone that the Downloads list does not know (after a
     * reinstall, a backup import or "Daten löschen"): everything in Grooveo's download folders.
     * A file is matched to a liked, playlist or played track by its name ("Artist - Title");
     * otherwise it shows up as a local track that plays from the file. A file that was added
     * as a local track before its track was known (the scan on start runs before a backup is
     * imported) is moved to that track once it is, with its cover and offline mark.
     * Without the audio permission Android only shows this install's own files.
     * Returns how many files were added or matched.
     */
    suspend fun scanDevice(): Int = scanLock.withLock {
        val completed = downloadDao.allCompleted()
        val owner = HashMap<Long, DownloadEntity>()
        completed.forEach { e -> e.mediaStoreUri?.let { runCatching { owner[ContentUris.parseId(Uri.parse(it))] = e } } }
        // new files, and files only known as a local track (they may match a track now)
        val files = deviceFiles().filter { f -> owner[f.id]?.trackId?.startsWith("$LOCAL_SOURCE:") ?: true }
        if (files.isEmpty()) return@withLock 0
        val tracks = trackDao.getAll()
        val likes = likeDao.getAll()
        val playlistTracks = playlistTrackDao.getAll()
        val index = DeviceDownloads.index(
            likes.map { DeviceDownloads.Known(it.trackId, it.track.artist, it.track.title) } +
                playlistTracks.map { DeviceDownloads.Known(it.trackId, it.track.artist, it.track.title) } +
                tracks.filter { it.source != LOCAL_SOURCE }.map { DeviceDownloads.Known(it.id, it.artist, it.title) },
        )
        val trackById = tracks.associateBy { it.id }
        val snapshots = (likes.map { it.trackId to it.track } + playlistTracks.map { it.trackId to it.track }).toMap()
        val covers = ArrayList<String>()
        var added = 0
        for (f in files) {
            val match = index[DeviceDownloads.key(f.name)]
            val previous = owner[f.id]
            // a local track stays local until a real track matches it
            if (previous != null && match == null) continue
            val trackId = match?.id ?: "$LOCAL_SOURCE:${f.id}"
            val existing = downloadDao.getByTrackId(trackId)
            // a track already offline from another file keeps that one
            if (existing?.state == DownloadState.COMPLETED && existing.mediaStoreUri != null) continue
            val snap = snapshots[trackId]
            val known = trackById[trackId]
            if (known == null) {
                val (artist, title) = DeviceDownloads.parse(f.name)
                trackDao.upsert(
                    TrackEntity(
                        id = trackId,
                        source = snap?.source ?: LOCAL_SOURCE,
                        sourceId = snap?.sourceId ?: f.id.toString(),
                        title = snap?.title ?: f.title?.takeIf { it.isNotBlank() && match == null && artist == null } ?: title,
                        artist = snap?.artist ?: artist ?: f.artist,
                        album = null,
                        durationSec = f.durationMs?.let { (it / 1000).toInt() },
                        thumbnailUrl = snap?.thumbnailUrl,
                        lastAccessedAt = 0,
                        webpageUrl = snap?.webpageUrl ?: "",
                    ),
                )
            } else if (known.thumbnailUrl == null && snap?.thumbnailUrl != null) {
                trackDao.upsert(known.copy(thumbnailUrl = snap.thumbnailUrl))
            }
            downloadDao.upsert(
                DownloadEntity(
                    trackId = trackId,
                    mediaStoreUri = f.uri.toString(),
                    relativePath = f.folder.trimEnd('/'),
                    state = DownloadState.COMPLETED,
                    progressPct = 100,
                    createdAt = f.addedMs,
                    totalBytes = f.size,
                ),
            )
            if (previous != null) {
                downloadDao.delete(previous.trackId)
                trackDao.delete(previous.trackId)
            }
            (snap?.thumbnailUrl ?: known?.thumbnailUrl)?.let(covers::add)
            if (match != null) {
                workManager.enqueueUniqueWork(
                    "analyze:$trackId",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<TrackAnalysisWorker>()
                        .setInputData(workDataOf(TrackAnalysisWorker.KEY_TRACK_ID to trackId))
                        .build(),
                )
            }
            added++
        }
        if (covers.isNotEmpty()) scope.launch { covers.forEach { offlineCovers.save(it) } }
        added
    }

    /** What "Neu einlesen" did: files newly found, offline tracks whose info was refreshed, and failures. */
    data class RefreshResult(val found: Int, val updated: Int, val failed: Int)

    /**
     * "Neu einlesen": looks for files on the phone again ([scanDevice]), then fetches the
     * current title, artist, cover and tags of every offline track from its source and
     * keeps the cover on the phone. A track that cannot be reached keeps what it had.
     */
    suspend fun refreshOffline(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): RefreshResult {
        val found = scanDevice()
        val ids = downloadDao.allCompleted().map { it.trackId }
        val tracks = ids.mapNotNull { trackDao.getById(it) }.filter { it.source == "soundcloud" || it.source == "ytmusic" }
        val permits = kotlinx.coroutines.sync.Semaphore(3)
        val done = java.util.concurrent.atomic.AtomicInteger()
        val results = kotlinx.coroutines.coroutineScope {
            tracks.map { t ->
                async {
                    permits.withPermit {
                        val fresh = runCatching {
                            when (t.source) {
                                "soundcloud" -> soundCloud.get("resolve", mapOf("url" to "https://soundcloud.com/${t.sourceId}")).toSoundCloudTrackResultDto()
                                else -> youTube.getTrack(t.sourceId)
                            }
                        }.getOrNull()
                        if (fresh != null) {
                            trackDao.upsert(
                                t.copy(
                                    title = fresh.title,
                                    artist = fresh.artist ?: t.artist,
                                    durationSec = fresh.durationSec ?: t.durationSec,
                                    thumbnailUrl = fresh.thumbnailUrl ?: t.thumbnailUrl,
                                    webpageUrl = fresh.webpageUrl.ifBlank { t.webpageUrl },
                                    genre = fresh.genre ?: t.genre,
                                ),
                            )
                            likeDao.updateTrackInfo(t.id, fresh.title, fresh.artist ?: t.artist, fresh.thumbnailUrl ?: t.thumbnailUrl)
                            tagRepository.remember(fresh)
                        }
                        offlineCovers.save(fresh?.thumbnailUrl ?: t.thumbnailUrl)
                        onProgress(done.incrementAndGet(), tracks.size)
                        fresh != null
                    }
                }
            }.awaitAll()
        }
        return RefreshResult(found, results.count { it }, results.count { !it })
    }

    private class DeviceFile(
        val id: Long, val uri: Uri, val name: String, val folder: String, val size: Long?, val addedMs: Long,
        val title: String?, val artist: String?, val durationMs: Long?,
    )

    /** Files in Grooveo's download folders that this app may read. */
    private fun deviceFiles(): List<DeviceFile> {
        val out = ArrayList<DeviceFile>()
        val seen = HashSet<Long>()
        val audio = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        for (collection in listOf(audio, downloads)) {
            val isAudio = collection == audio
            val cols = buildList {
                add(MediaStore.MediaColumns._ID); add(MediaStore.MediaColumns.DISPLAY_NAME); add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.SIZE); add(MediaStore.MediaColumns.DATE_ADDED)
                if (isAudio) { add(MediaStore.Audio.Media.TITLE); add(MediaStore.Audio.Media.ARTIST); add(MediaStore.Audio.Media.DURATION) }
            }.toTypedArray()
            val where = DeviceDownloads.FOLDERS.joinToString(" OR ") { "${MediaStore.MediaColumns.RELATIVE_PATH} = ?" }
            runCatching {
                context.contentResolver.query(collection, cols, where, DeviceDownloads.FOLDERS.toTypedArray(), null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        if (!seen.add(id)) continue
                        val name = c.getString(1) ?: continue
                        out += DeviceFile(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            name = name,
                            folder = c.getString(2) ?: "",
                            size = if (c.isNull(3)) null else c.getLong(3),
                            addedMs = if (c.isNull(4)) System.currentTimeMillis() else c.getLong(4) * 1000,
                            title = if (isAudio) c.getString(5) else null,
                            artist = if (isAudio) c.getString(6)?.takeIf { it != MediaStore.UNKNOWN_STRING } else null,
                            durationMs = if (isAudio && !c.isNull(7)) c.getLong(7) else null,
                        )
                    }
                }
            }
        }
        return out
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
            val known = downloadDao.getByTrackId(trackId)
            if (known?.state == DownloadState.COMPLETED) return@launch
            // listed right away as waiting, even while the worker waits for Wi-Fi
            if (known == null) downloadDao.upsert(DownloadEntity(trackId, null, null, DownloadState.QUEUED, 0, System.currentTimeMillis()))
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
            tagRepository.remember(track)
            offlineCovers.save(track.thumbnailUrl)
        }
    }

    /** Removes a download completely: stops any running transfer, deletes the audio
     * file from MediaStore and drops the record, so the space is actually freed. */
    fun deleteDownload(trackId: String) {
        workManager.cancelUniqueWork(trackId)
        scope.launch {
            val entity = downloadDao.getByTrackId(trackId)
            downloadDao.delete(trackId)
            entity?.mediaStoreUri?.let { uri ->
                runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
            }
            entity?.tempFilePath?.let { runCatching { java.io.File(it).delete() } }
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
        // a worker that never ran writes nothing: without this the row stayed "Wartet"
        scope.launch {
            downloadDao.getByTrackId(trackId)?.takeIf { it.state == DownloadState.QUEUED }
                ?.let { downloadDao.upsert(it.copy(state = DownloadState.PAUSED)) }
        }
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

    companion object {
        /** Source of a file on the phone that matched no known track; it plays only from the file. */
        const val LOCAL_SOURCE = "local"
    }
}
