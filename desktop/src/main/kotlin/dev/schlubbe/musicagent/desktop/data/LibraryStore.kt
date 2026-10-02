package dev.schlubbe.musicagent.desktop.data

import com.google.gson.reflect.TypeToken
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.DownloadEntity
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.Instant
import java.util.UUID

val TrackResultDto.key: String get() = "$source:$sourceId"

data class LikedTrack(val track: TrackResultDto, val createdAt: Long)

data class PlaylistTrack(val track: TrackResultDto, val addedAt: Long)

data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val description: String? = null,
    val accentColorKey: String? = null,
    val moodTags: List<String> = emptyList(),
    val coverPath: String? = null,
    val tracks: List<PlaylistTrack> = emptyList(),
)

data class SavedPlaylist(
    val source: String,
    val sourceId: String,
    val title: String,
    val thumbnailUrl: String?,
    val owner: String?,
    val trackCount: Int?,
    val webpageUrl: String,
    val isAlbum: Boolean = false,
    val savedAt: Long = System.currentTimeMillis(),
)

data class FollowedArtist(
    val source: String,
    val sourceId: String,
    val name: String,
    val thumbnailUrl: String?,
    val followedAt: Long = System.currentTimeMillis(),
)

data class HistoryEntry(val track: TrackResultDto, val playedAt: Long)

data class DownloadRecord(
    val track: TrackResultDto,
    val state: DownloadState,
    val progressPct: Int = 0,
    val filePath: String? = null,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val error: String? = null,
)

/** Mix-in/mix-out points from "Übergänge analysieren" (see TrackAnalyzer). */
data class TrackAnalysis(val mixInMs: Long, val mixOutMs: Long, val analyzedAt: Long = System.currentTimeMillis())

/** Weekly listening time is accumulated per ISO day ("2026-10-01") in seconds. */
data class LibraryData(
    val likes: List<LikedTrack> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val savedPlaylists: List<SavedPlaylist> = emptyList(),
    val followed: List<FollowedArtist> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val searches: List<String> = emptyList(),
    val downloads: List<DownloadRecord> = emptyList(),
    val analysis: Map<String, TrackAnalysis> = emptyMap(),
    val dislikedArtists: List<String> = emptyList(),
    val listenSecondsByDay: Map<String, Long> = emptyMap(),
) {
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    fun normalized() = LibraryData(
        likes = likes ?: emptyList(),
        playlists = (playlists ?: emptyList()).map { it.copy(tracks = it.tracks ?: emptyList(), moodTags = it.moodTags ?: emptyList()) },
        savedPlaylists = savedPlaylists ?: emptyList(),
        followed = followed ?: emptyList(),
        history = history ?: emptyList(),
        searches = searches ?: emptyList(),
        downloads = downloads ?: emptyList(),
        analysis = analysis ?: emptyMap(),
        dislikedArtists = dislikedArtists ?: emptyList(),
        listenSecondsByDay = listenSecondsByDay ?: emptyMap(),
    )
}

/**
 * Whole local library in one JSON document (`library.json`). Small enough to keep in
 * memory; every mutation is persisted atomically. Also serves as the desktop
 * implementation of the DAO interfaces the shared sources expect.
 */
class LibraryStore(private val dir: File) : TrackDao, DownloadDao {
    private val file = File(dir, "library.json")
    private val _data = MutableStateFlow(load())
    val data: StateFlow<LibraryData> = _data.asStateFlow()
    val current: LibraryData get() = _data.value

    @Synchronized
    fun mutate(transform: (LibraryData) -> LibraryData) {
        val next = transform(_data.value)
        if (next == _data.value) return
        _data.value = next
        writeJson(file, next)
    }

    private fun load(): LibraryData =
        readJson<LibraryData>(file, LibraryData::class.java)?.normalized() ?: migrateLegacy()

    /** Imports the JSON files written by the first Grooveo desktop build. */
    private fun migrateLegacy(): LibraryData {
        val listType = object : TypeToken<List<TrackResultDto>>() {}.type
        val now = System.currentTimeMillis()
        val liked = readJson<List<TrackResultDto>>(File(dir, "liked.json"), listType).orEmpty()
        val history = readJson<List<TrackResultDto>>(File(dir, "history.json"), listType).orEmpty()
        val playedAt = readJson<Map<String, Double>>(File(dir, "played_at.json"), object : TypeToken<Map<String, Double>>() {}.type).orEmpty()
        val searches = readJson<List<String>>(File(dir, "searches.json"), object : TypeToken<List<String>>() {}.type).orEmpty()
        return LibraryData(
            likes = liked.mapIndexed { i, t -> LikedTrack(t, now - i) },
            history = history.mapIndexed { i, t -> HistoryEntry(t, playedAt[t.key]?.toLong() ?: (now - i * 1000L)) },
            searches = searches,
        )
    }

    // ---------- likes ----------
    fun isLiked(t: TrackResultDto) = current.likes.any { it.track.key == t.key }
    fun toggleLike(t: TrackResultDto) = mutate { d ->
        if (d.likes.any { it.track.key == t.key }) d.copy(likes = d.likes.filterNot { it.track.key == t.key })
        else d.copy(likes = listOf(LikedTrack(t, System.currentTimeMillis())) + d.likes)
    }

    // ---------- history / stats ----------
    fun recordPlay(t: TrackResultDto) = mutate { d ->
        d.copy(history = (listOf(HistoryEntry(t, System.currentTimeMillis())) + d.history.filterNot { it.track.key == t.key }).take(HISTORY_MAX))
    }

    fun addListenSeconds(seconds: Long, day: String = java.time.LocalDate.now().toString()) = mutate { d ->
        d.copy(listenSecondsByDay = (d.listenSecondsByDay + (day to (d.listenSecondsByDay[day] ?: 0) + seconds)).entries
            .sortedByDescending { it.key }.take(60).associate { it.key to it.value })
    }

    fun weeklyListenSeconds(today: java.time.LocalDate = java.time.LocalDate.now()): Long =
        (0L..6L).sumOf { current.listenSecondsByDay[today.minusDays(it).toString()] ?: 0L }

    fun clearHistory() = mutate { it.copy(history = emptyList()) }

    // ---------- searches ----------
    fun addSearch(q: String) {
        val query = q.trim().takeIf { it.isNotEmpty() } ?: return
        mutate { d -> d.copy(searches = (listOf(query) + d.searches.filterNot { it.equals(query, true) }).take(SEARCHES_MAX)) }
    }
    fun removeSearch(q: String) = mutate { d -> d.copy(searches = d.searches - q) }
    fun clearSearches() = mutate { it.copy(searches = emptyList()) }

    // ---------- playlists ----------
    fun createPlaylist(name: String, tracks: List<TrackResultDto> = emptyList()): Playlist {
        val now = System.currentTimeMillis()
        val p = Playlist(name = name.trim().ifEmpty { "Neue Playlist" }, tracks = tracks.distinctBy { it.key }.map { PlaylistTrack(it, now) })
        mutate { it.copy(playlists = listOf(p) + it.playlists) }
        return p
    }
    fun updatePlaylist(id: String, transform: (Playlist) -> Playlist) =
        mutate { d -> d.copy(playlists = d.playlists.map { if (it.id == id) transform(it) else it }) }
    fun deletePlaylist(id: String) = mutate { d -> d.copy(playlists = d.playlists.filterNot { it.id == id }) }
    fun addToPlaylist(id: String, tracks: List<TrackResultDto>) = updatePlaylist(id) { p ->
        val existing = p.tracks.map { it.track.key }.toSet()
        p.copy(tracks = p.tracks + tracks.filter { it.key !in existing }.distinctBy { it.key }.map { PlaylistTrack(it, System.currentTimeMillis()) })
    }
    fun removeFromPlaylist(id: String, index: Int) = updatePlaylist(id) { p ->
        p.copy(tracks = p.tracks.filterIndexed { i, _ -> i != index })
    }
    fun movePlaylistTrack(id: String, from: Int, to: Int) = updatePlaylist(id) { p ->
        if (from !in p.tracks.indices || to !in p.tracks.indices) p
        else p.copy(tracks = p.tracks.toMutableList().apply { add(to, removeAt(from)) })
    }

    // ---------- saved remote playlists / follows ----------
    fun isSaved(source: String, sourceId: String) = current.savedPlaylists.any { it.source == source && it.sourceId == sourceId }
    fun toggleSaved(p: SavedPlaylist) = mutate { d ->
        if (d.savedPlaylists.any { it.source == p.source && it.sourceId == p.sourceId })
            d.copy(savedPlaylists = d.savedPlaylists.filterNot { it.source == p.source && it.sourceId == p.sourceId })
        else d.copy(savedPlaylists = listOf(p) + d.savedPlaylists)
    }
    fun isFollowing(source: String, sourceId: String) = current.followed.any { it.source == source && it.sourceId == sourceId }
    fun toggleFollow(a: FollowedArtist) = mutate { d ->
        if (d.followed.any { it.source == a.source && it.sourceId == a.sourceId })
            d.copy(followed = d.followed.filterNot { it.source == a.source && it.sourceId == a.sourceId })
        else d.copy(followed = listOf(a) + d.followed)
    }
    fun dislikeArtist(artist: String) = mutate { d -> d.copy(dislikedArtists = (d.dislikedArtists + artist.lowercase()).distinct()) }

    // ---------- downloads ----------
    fun download(key: String) = current.downloads.firstOrNull { it.track.key == key }
    fun upsertDownload(r: DownloadRecord) = mutate { d ->
        d.copy(downloads = if (d.downloads.any { it.track.key == r.track.key }) d.downloads.map { if (it.track.key == r.track.key) r else it } else listOf(r) + d.downloads)
    }
    fun removeDownload(key: String) = mutate { d -> d.copy(downloads = d.downloads.filterNot { it.track.key == key }) }

    // ---------- analysis ----------
    fun putAnalysis(key: String, a: TrackAnalysis) = mutate { it.copy(analysis = it.analysis + (key to a)) }

    // ---------- DAO implementations for shared sources ----------
    override suspend fun getById(id: String): TrackEntity? = current.history.firstOrNull { it.track.key == id }?.toEntity()

    override fun observeRecentlyPlayed(limit: Int): Flow<List<TrackEntity>> =
        data.map { d -> d.history.take(limit).map { it.toEntity() } }

    override suspend fun getByTrackId(trackId: String): DownloadEntity? = download(trackId)?.let { r ->
        DownloadEntity(
            trackId = trackId,
            mediaStoreUri = r.filePath?.takeIf { r.state == DownloadState.COMPLETED && File(it).isFile },
            relativePath = r.filePath,
            state = r.state,
            progressPct = r.progressPct,
            createdAt = r.createdAt,
            bytesDownloaded = r.bytesDownloaded,
            totalBytes = r.totalBytes,
        )
    }

    private fun HistoryEntry.toEntity() = TrackEntity(
        id = track.key, source = track.source, sourceId = track.sourceId, title = track.title,
        artist = track.artist, album = track.album, durationSec = track.durationSec,
        thumbnailUrl = track.thumbnailUrl, lastAccessedAt = playedAt, webpageUrl = track.webpageUrl, genre = track.genre,
    )

    companion object {
        const val HISTORY_MAX = 200
        const val SEARCHES_MAX = 20
        fun isoNow(): String = Instant.now().toString()
    }
}
