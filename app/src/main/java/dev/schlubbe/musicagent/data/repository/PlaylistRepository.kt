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
        )
    }

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

    suspend fun delete(playlistId: String) = playlistDao.delete(playlistId)

    suspend fun addTrack(playlistId: String, track: TrackResultDto): PlaylistDetailOutDto {
        val nextPosition = playlistTrackDao.getMaxPosition(playlistId) + 1
        playlistTrackDao.insert(
            PlaylistTrackEntity(
                playlistId = playlistId,
                trackId = "${track.source}:${track.sourceId}",
                track = track.toLocalTrackEntity(),
                position = nextPosition,
                addedAt = nowIso(),
            ),
        )
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
}
