package dev.schlubbe.musicagent.data.spotify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.schlubbe.musicagent.MainActivity
import dev.schlubbe.musicagent.R
import dev.schlubbe.musicagent.data.local.mapper.nowIso
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.repository.FollowRepository
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.PlaylistRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/** Imports a Spotify library (liked songs, playlists, followed artists) into
 * Grooveo. Two phases:
 *
 * 1. Read the export file at [KEY_FILE] (Exportify, TuneMyMusic or Spotify's own
 *    data download, see [SpotifyExport]). The file is deleted afterwards.
 * 2. Find each track on YouTube Music (SoundCloud as fallback) via
 *    [SearchRepository] and [TrackMatcher], then write likes, playlists and follows
 *    through the normal repositories. Matches are cached by Spotify id, so a song in
 *    several playlists is searched once; searches run four at a time.
 *
 * Runs as a foreground (dataSync) worker: a big library takes longer than the
 * ten minutes WorkManager allows a plain worker, and it keeps going when the
 * screen is left. */
@HiltWorker
class SpotifyImportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val searchRepository: SearchRepository,
    private val likesRepository: LikesRepository,
    private val playlistRepository: PlaylistRepository,
    private val followRepository: FollowRepository,
    private val store: SpotifyImportStore,
) : CoroutineWorker(appContext, params) {

    private val searchPermits = Semaphore(SEARCH_CONCURRENCY)
    private val matchCache = ConcurrentHashMap<String, CompletableDeferred<TrackResultDto?>>()
    private val notFound = ConcurrentHashMap<String, String>()

    private val progress = Progress()
    @Volatile private var lastProgressAt = 0L

    override suspend fun doWork(): Result = try {
        runImport()
    } finally {
        // When the worker could not go foreground, the progress notification is an
        // ordinary ongoing one that WorkManager will not remove for us.
        runCatching { NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID) }
    }

    private suspend fun runImport(): Result {
        val file = inputData.getString(KEY_FILE)?.let(::File)?.takeIf { it.isFile } ?: return failure(ERROR_NO_FILE)
        createChannel()
        // May be refused when the app is already in the background (Android 12+);
        // the import still runs, just without the guarantee.
        runCatching { setForeground(foregroundInfo()) }

        progress.phase = PHASE_FETCH
        report(force = true)
        val library = try {
            file.inputStream().use { SpotifyExport.read(file.name, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return failure(ERROR_FILE)
        } finally {
            file.delete()
        }
        if (library.isEmpty) return failure(ERROR_EMPTY)
        progress.likesTotal = library.likes.size
        progress.playlistsTotal = library.playlists.size
        progress.artistsTotal = library.artists.size
        progress.fetched = library.likes.size + library.playlists.sumOf { it.second.size }

        val summary = try {
            importLibrary(library)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return failure(ERROR_NETWORK)
        }
        store.saveSummary(summary)
        notifyDone(summary)
        return Result.success(workDataOf(KEY_NOT_FOUND to summary.notFound.size))
    }

    // — Phase 2: match and write —

    private suspend fun importLibrary(library: SpotifyLibrary): SpotifyImportSummary {
        // Likes
        progress.phase = PHASE_LIKES
        report(force = true)
        likesRepository.refresh()
        val alreadyLiked = likesRepository.likedTrackIds.value
        var likesAdded = 0
        var likesAlreadyThere = 0
        val likeMatches = matchAll(library.likes) { progress.likesDone.incrementAndGet() }
        // Oldest first, so ties in the like date still land in Spotify's order.
        for ((track, match) in library.likes.zip(likeMatches).asReversed()) {
            match ?: continue
            if ("${match.source}:${match.sourceId}" in alreadyLiked) { likesAlreadyThere++; continue }
            likesRepository.like(match, track.addedAt)
            likesAdded++
        }

        // Playlists
        progress.phase = PHASE_PLAYLISTS
        report(force = true)
        val playlistMap = store.playlistMap().toMutableMap()
        var playlistsImported = 0
        var playlistTracksAdded = 0
        for ((playlist, tracks) in library.playlists) {
            progress.current = playlist.name
            progress.currentDone.set(0)
            progress.currentTotal = tracks.size
            report(force = true)
            val matches = matchAll(tracks) { progress.currentDone.incrementAndGet() }.filterNotNull()
                .distinctBy { "${it.source}:${it.sourceId}" }
            if (matches.isNotEmpty()) {
                val added = writePlaylist(playlist, matches, playlistMap)
                playlistTracksAdded += added
                playlistsImported++
                store.savePlaylistMap(playlistMap)
            }
            progress.playlistsDone++
            report(force = true)
        }
        progress.current = null

        // Artists
        progress.phase = PHASE_ARTISTS
        report(force = true)
        followRepository.refresh()
        val alreadyFollowed = followRepository.followedIds.value
        val artistsNotFound = ArrayList<String>()
        var artistsFollowed = 0
        val artistMatches = coroutineScope {
            library.artists.map { artist ->
                async {
                    val hit = searchPermits.withPermit { findArtist(artist.name) }
                    progress.artistsDone.incrementAndGet()
                    report()
                    hit
                }
            }.awaitAll()
        }
        for ((artist, hit) in library.artists.zip(artistMatches)) {
            if (hit == null) { artistsNotFound += artist.name; continue }
            if ("${hit.source}:${hit.sourceId}" !in alreadyFollowed) {
                runCatching { followRepository.follow(hit.source, hit.sourceId, hit.name, hit.thumbnailUrl) }
            }
            artistsFollowed++
        }
        report(force = true)

        return SpotifyImportSummary(
            likesTotal = library.likes.size,
            likesAdded = likesAdded,
            likesAlreadyThere = likesAlreadyThere,
            playlistsTotal = progress.playlistsTotal,
            playlistsImported = playlistsImported,
            playlistTracksAdded = playlistTracksAdded,
            artistsTotal = library.artists.size,
            artistsFollowed = artistsFollowed,
            notFound = notFound.values.sorted(),
            artistsNotFound = artistsNotFound,
            finishedAt = nowIso(),
        )
    }

    /** Creates the Grooveo playlist (or reuses the one an earlier import made) and
     * appends the matches it does not contain yet. Returns how many were added. */
    private suspend fun writePlaylist(
        playlist: SpotifyPlaylist,
        matches: List<TrackResultDto>,
        playlistMap: MutableMap<String, String>,
    ): Int {
        val existing = playlistRepository.list()
        val reuseId = playlistMap[playlist.id]?.takeIf { id -> existing.any { it.id == id } }
        val targetId = reuseId ?: playlistRepository.create(
            SpotifyImportRules.playlistName(playlist.name, existing.map { it.name }),
        ).id
        playlistMap[playlist.id] = targetId
        val present = if (reuseId != null) {
            playlistRepository.get(targetId).tracks.map { "${it.track.source}:${it.track.sourceId}" }.toMutableSet()
        } else {
            mutableSetOf()
        }
        var added = 0
        for (track in matches) {
            if (!present.add("${track.source}:${track.sourceId}")) continue
            runCatching { playlistRepository.addTrack(targetId, track) }.onSuccess { added++ }
        }
        return added
    }

    /** Matches [tracks] concurrently (bounded by [searchPermits]), keeping order. */
    private suspend fun matchAll(tracks: List<SpotifyTrack>, onEach: () -> Unit): List<TrackResultDto?> = coroutineScope {
        tracks.map { track ->
            async {
                val match = match(track)
                onEach()
                report()
                match
            }
        }.awaitAll()
    }

    private suspend fun match(track: SpotifyTrack): TrackResultDto? {
        val fresh = CompletableDeferred<TrackResultDto?>()
        val existing = matchCache.putIfAbsent(track.id, fresh)
        if (existing != null) return existing.await()
        val result = try {
            searchPermits.withPermit { searchMatch(track) }
        } catch (e: CancellationException) {
            fresh.cancel(e)
            throw e
        } catch (_: Exception) {
            null
        }
        if (result == null) {
            notFound[track.id] = SpotifyImportRules.label(track)
            progress.notFound = notFound.size
        }
        fresh.complete(result)
        return result
    }

    private suspend fun searchMatch(track: SpotifyTrack): TrackResultDto? {
        val query = TrackMatcher.query(track)
        val yt = runCatching { searchRepository.search(query, "ytmusic", 10) }.getOrDefault(emptyList())
        TrackMatcher.best(track, yt)?.let { return it }
        val sc = runCatching { searchRepository.search(query, "soundcloud", 10) }.getOrDefault(emptyList())
            .filterNot { it.isDrmProtected }
        return TrackMatcher.best(track, sc)
    }

    private suspend fun findArtist(name: String) =
        runCatching { TrackMatcher.bestArtist(name, searchRepository.searchArtists(name, "ytmusic", 5)) }.getOrNull()
            ?: runCatching { TrackMatcher.bestArtist(name, searchRepository.searchArtists(name, "soundcloud", 5)) }.getOrNull()

    // — progress & notifications —

    /** Counters shown on the import screen and in the notification; written from
     * several coroutines at once. */
    private class Progress {
        @Volatile var phase: String = PHASE_FETCH
        @Volatile var fetched = 0
        @Volatile var likesTotal = 0
        val likesDone = AtomicInteger()
        @Volatile var playlistsTotal = 0
        @Volatile var playlistsDone = 0
        @Volatile var artistsTotal = 0
        val artistsDone = AtomicInteger()
        @Volatile var current: String? = null
        val currentDone = AtomicInteger()
        @Volatile var currentTotal = 0
        @Volatile var notFound = 0
    }

    private suspend fun report(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastProgressAt < PROGRESS_INTERVAL_MS) return
        lastProgressAt = now
        val p = progress
        setProgress(
            workDataOf(
                KEY_PHASE to p.phase,
                KEY_FETCHED to p.fetched,
                KEY_LIKES_DONE to p.likesDone.get(),
                KEY_LIKES_TOTAL to p.likesTotal,
                KEY_PLAYLISTS_DONE to p.playlistsDone,
                KEY_PLAYLISTS_TOTAL to p.playlistsTotal,
                KEY_ARTISTS_DONE to p.artistsDone.get(),
                KEY_ARTISTS_TOTAL to p.artistsTotal,
                KEY_CURRENT to p.current,
                KEY_CURRENT_DONE to p.currentDone.get(),
                KEY_CURRENT_TOTAL to p.currentTotal,
                KEY_NOT_FOUND to p.notFound,
            ),
        )
        if (canNotify()) {
            runCatching { NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, progressNotification()) }
        }
    }

    private fun progressText(): Pair<String, Pair<Int, Int>?> {
        val p = progress
        return when (p.phase) {
            PHASE_LIKES -> "Likes ${p.likesDone.get()}/${p.likesTotal}" to (p.likesDone.get() to p.likesTotal)
            PHASE_PLAYLISTS -> "Playlists ${p.playlistsDone}/${p.playlistsTotal}" + (p.current?.let { " · $it" } ?: "") to
                (p.playlistsDone to p.playlistsTotal)
            PHASE_ARTISTS -> "Künstler ${p.artistsDone.get()}/${p.artistsTotal}" to (p.artistsDone.get() to p.artistsTotal)
            else -> "Lese deine Spotify-Datei …" to null
        }
    }

    private fun progressNotification() = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notif_small)
        .setContentTitle("Spotify-Import läuft")
        .setContentText(progressText().first)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(openAppIntent())
        .apply {
            val bar = progressText().second
            if (bar == null || bar.second == 0) setProgress(0, 0, true) else setProgress(bar.second, bar.first, false)
        }
        .build()

    private fun foregroundInfo() =
        ForegroundInfo(NOTIFICATION_ID, progressNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notifyDone(s: SpotifyImportSummary) {
        if (!canNotify()) return
        val text = buildString {
            append("${s.likesAdded} Likes, ${s.playlistsImported} Playlists, ${s.artistsFollowed} Künstler")
            if (s.notFound.isNotEmpty()) append(" · ${s.notFound.size} nicht gefunden")
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_small)
            .setContentTitle("Spotify-Import fertig")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(DONE_NOTIFICATION_ID, notification) }
    }

    private fun failure(error: String): Result = Result.failure(workDataOf(KEY_ERROR to error))

    private fun canNotify() =
        ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        applicationContext, 0,
        Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Spotify-Import", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Fortschritt beim Übernehmen deiner Spotify-Bibliothek"
        }
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "spotify-import"
        const val CHANNEL_ID = "spotify_import"
        private const val NOTIFICATION_ID = 4711
        private const val DONE_NOTIFICATION_ID = 4712
        private const val SEARCH_CONCURRENCY = 4
        private const val PROGRESS_INTERVAL_MS = 400L

        const val PHASE_FETCH = "fetch"
        const val PHASE_LIKES = "likes"
        const val PHASE_PLAYLISTS = "playlists"
        const val PHASE_ARTISTS = "artists"

        const val KEY_PHASE = "phase"
        const val KEY_FETCHED = "fetched"
        const val KEY_LIKES_DONE = "likes_done"
        const val KEY_LIKES_TOTAL = "likes_total"
        const val KEY_PLAYLISTS_DONE = "playlists_done"
        const val KEY_PLAYLISTS_TOTAL = "playlists_total"
        const val KEY_ARTISTS_DONE = "artists_done"
        const val KEY_ARTISTS_TOTAL = "artists_total"
        const val KEY_CURRENT = "current"
        const val KEY_CURRENT_DONE = "current_done"
        const val KEY_CURRENT_TOTAL = "current_total"
        const val KEY_NOT_FOUND = "not_found"
        const val KEY_ERROR = "error"
        /** Absolute path of the export file to import (in the app's cache). */
        const val KEY_FILE = "file"

        /** The export file was gone - the cache was cleared before the import ran. */
        const val ERROR_NO_FILE = "no_file"
        /** Not an export the importer understands. */
        const val ERROR_FILE = "file"
        /** Readable, but without a single song or artist. */
        const val ERROR_EMPTY = "empty"
        const val ERROR_NETWORK = "network"
    }
}
