package dev.schlubbe.musicagent.data.repository

import dev.schlubbe.musicagent.data.local.dao.PlaylistDao
import dev.schlubbe.musicagent.data.local.dao.PlaylistTrackDao
import dev.schlubbe.musicagent.data.local.entity.PlaylistEntity
import dev.schlubbe.musicagent.data.local.entity.PlaylistTrackEntity
import dev.schlubbe.musicagent.data.local.mapper.nowIso
import dev.schlubbe.musicagent.data.local.mapper.toLocalTrackEntity
import dev.schlubbe.musicagent.data.local.mapper.toPlaylistOutDto
import dev.schlubbe.musicagent.data.local.mapper.toPlaylistTrackOutDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistDetailOutDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Same public contract as the server-backed app's PlaylistRepository — backed by
 * Room instead of the (removed) backend, so every caller (LibraryViewModel,
 * PlaylistDetailViewModel, HomeViewModel, ArtistViewModel, AddToPlaylistDialog, ...)
 * needs no changes. */
@Singleton
class PlaylistRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
) {
    suspend fun list(): List<PlaylistOutDto> = playlistDao.getAllWithCounts().map { it.toPlaylistOutDto() }

    suspend fun create(name: String): PlaylistOutDto {
        val id = UUID.randomUUID().toString()
        val createdAt = nowIso()
        playlistDao.insert(PlaylistEntity(id, name, createdAt))
        return PlaylistOutDto(id, name, createdAt, trackCount = 0)
    }

    suspend fun get(playlistId: String): PlaylistDetailOutDto {
        val playlist = playlistDao.getById(playlistId) ?: error("playlist not found: $playlistId")
        val tracks = playlistTrackDao.getForPlaylist(playlistId).map { it.toPlaylistTrackOutDto() }
        return PlaylistDetailOutDto(
            id = playlist.id,
            name = playlist.name,
            createdAt = playlist.createdAt,
            tracks = tracks,
            description = playlist.description,
            accentColorKey = playlist.accentColorKey,
            moodTags = playlist.moodTags?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            coverPath = playlist.coverPath,
        )
    }

    /** Copies [image] (a photo-picker Uri) into app storage, scaled to at most
     * [COVER_MAX_PX] on the long side, and makes it the playlist's cover; null
     * removes the cover. A private copy rather than keeping the picker Uri: picker
     * grants don't survive a restart, and a full-size photo would be decoded for a
     * 120dp tile. The file name carries a timestamp so image caches keyed by path
     * pick up a replaced cover. */
    suspend fun setCover(playlistId: String, image: android.net.Uri?) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val old = playlistDao.getById(playlistId)?.coverPath
        val newPath = image?.let { uri ->
            val dir = java.io.File(context.filesDir, "playlist_covers").apply { mkdirs() }
            val out = java.io.File(dir, "$playlistId-${System.currentTimeMillis()}.jpg")
            val bitmap = decodeScaled(uri) ?: return@withContext false
            out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
            out.absolutePath
        }
        playlistDao.updateCover(playlistId, newPath)
        old?.let { java.io.File(it).delete() }
        true
    }

    private fun decodeScaled(uri: android.net.Uri): android.graphics.Bitmap? = runCatching {
        val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
        android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longSide = maxOf(info.size.width, info.size.height)
            if (longSide > COVER_MAX_PX) {
                val scale = COVER_MAX_PX.toFloat() / longSide
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    /** Persists the playlist edit sheet's full set of fields at once (name,
     * description, accent color, mood tags) -- a single call rather than one
     * setter per field since the sheet always submits all of them together. */
    suspend fun updateDetails(
        playlistId: String,
        name: String,
        description: String?,
        accentColorKey: String?,
        moodTags: List<String>,
    ): PlaylistOutDto {
        playlistDao.updateDetails(
            playlistId,
            name,
            description?.takeIf { it.isNotBlank() },
            accentColorKey,
            moodTags.takeIf { it.isNotEmpty() }?.joinToString(","),
        )
        return playlistDao.getByIdWithCount(playlistId)?.toPlaylistOutDto()
            ?: error("playlist not found: $playlistId")
    }

    suspend fun delete(playlistId: String) {
        playlistDao.getById(playlistId)?.coverPath?.let { java.io.File(it).delete() }
        playlistDao.delete(playlistId)
    }

    suspend fun addTrack(playlistId: String, track: TrackResultDto): PlaylistDetailOutDto {
        // insertAtEnd reads the max position and inserts in one transaction - see its
        // kdoc for why that matters over a separate getMaxPosition()+insert() here.
        playlistTrackDao.insertAtEnd(playlistId) { position ->
            PlaylistTrackEntity(
                playlistId = playlistId,
                trackId = "${track.source}:${track.sourceId}",
                track = track.toLocalTrackEntity(),
                position = position,
                addedAt = nowIso(),
            )
        }
        return get(playlistId)
    }

    suspend fun removeTrack(playlistId: String, track: TrackResultDto): PlaylistDetailOutDto {
        playlistTrackDao.delete(playlistId, "${track.source}:${track.sourceId}")
        return get(playlistId)
    }

    suspend fun reorder(playlistId: String, trackIds: List<String>): PlaylistDetailOutDto {
        playlistTrackDao.reorder(playlistId, trackIds)
        return get(playlistId)
    }

    private companion object {
        const val COVER_MAX_PX = 800
    }
}
