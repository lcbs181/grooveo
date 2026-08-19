package dev.schlubbe.musicagent.data.local.mapper

import dev.schlubbe.musicagent.data.local.dao.PlaylistWithCount
import dev.schlubbe.musicagent.data.local.entity.LikeEntity
import dev.schlubbe.musicagent.data.local.entity.LocalTrackEntity
import dev.schlubbe.musicagent.data.local.entity.PlaylistTrackEntity
import dev.schlubbe.musicagent.data.remote.dto.LikeOutDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistTrackOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import java.time.Instant

fun nowIso(): String = Instant.now().toString()

fun TrackResultDto.toLocalTrackEntity(): LocalTrackEntity =
    LocalTrackEntity(source, sourceId, title, artist, album, durationSec, thumbnailUrl, webpageUrl)

fun LocalTrackEntity.toTrackOutDto(): TrackOutDto = TrackOutDto(
    id = "$source:$sourceId",
    source = source,
    sourceId = sourceId,
    title = title,
    artist = artist,
    album = album,
    durationSec = durationSec,
    thumbnailUrl = thumbnailUrl,
    webpageUrl = webpageUrl,
)

fun LikeEntity.toLikeOutDto(): LikeOutDto = LikeOutDto(track.toTrackOutDto(), createdAt)

fun PlaylistWithCount.toPlaylistOutDto(): PlaylistOutDto =
    PlaylistOutDto(playlist.id, playlist.name, playlist.createdAt, trackCount)

fun PlaylistTrackEntity.toPlaylistTrackOutDto(): PlaylistTrackOutDto =
    PlaylistTrackOutDto(track.toTrackOutDto(), position, addedAt)
