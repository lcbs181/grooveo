package dev.schlubbe.musicagent.data.remote.dto

import com.google.gson.annotations.SerializedName

data class TrackOutDto(
    val id: String,
    val source: String,
    @SerializedName("source_id") val sourceId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    @SerializedName("duration_sec") val durationSec: Int?,
    @SerializedName("thumbnail_url") val thumbnailUrl: String?,
    @SerializedName("webpage_url") val webpageUrl: String,
)

data class LikeOutDto(
    val track: TrackOutDto,
    @SerializedName("created_at") val createdAt: String,
)

data class PlaylistOutDto(
    val id: String,
    val name: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("track_count") val trackCount: Int,
    val description: String? = null,
    val accentColorKey: String? = null,
    val moodTags: List<String> = emptyList(),
)

data class PlaylistTrackOutDto(
    val track: TrackOutDto,
    val position: Int,
    @SerializedName("added_at") val addedAt: String,
)

data class PlaylistDetailOutDto(
    val id: String,
    val name: String,
    @SerializedName("created_at") val createdAt: String,
    val tracks: List<PlaylistTrackOutDto>,
    val description: String? = null,
    val accentColorKey: String? = null,
    val moodTags: List<String> = emptyList(),
)

fun TrackOutDto.toTrackResultDto(): TrackResultDto = TrackResultDto(
    source = source,
    sourceId = sourceId,
    title = title,
    artist = artist,
    album = album,
    durationSec = durationSec,
    thumbnailUrl = thumbnailUrl,
    webpageUrl = webpageUrl,
)
