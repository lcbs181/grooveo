package dev.schlubbe.musicagent.data.remote.dto

import com.google.gson.annotations.SerializedName

data class TrackResultDto(
    val source: String,
    @SerializedName("source_id") val sourceId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    @SerializedName("duration_sec") val durationSec: Int?,
    @SerializedName("thumbnail_url") val thumbnailUrl: String?,
    @SerializedName("webpage_url") val webpageUrl: String,
)

data class ArtistResultDto(
    val source: String,
    @SerializedName("source_id") val sourceId: String,
    val name: String,
    @SerializedName("thumbnail_url") val thumbnailUrl: String?,
    @SerializedName("subscriber_count") val subscriberCount: String?,
    @SerializedName("webpage_url") val webpageUrl: String,
)

data class ArtistDetailDto(
    val source: String,
    @SerializedName("source_id") val sourceId: String,
    val name: String,
    @SerializedName("thumbnail_url") val thumbnailUrl: String?,
    @SerializedName("banner_url") val bannerUrl: String? = null,
    val description: String? = null,
    @SerializedName("subscriber_count") val subscriberCount: String?,
    @SerializedName("top_tracks") val topTracks: List<TrackResultDto> = emptyList(),
    @SerializedName("latest_tracks") val latestTracks: List<TrackResultDto> = emptyList(),
    @SerializedName("webpage_url") val webpageUrl: String,
)

data class PlaylistResultDto(
    val source: String,
    // ytmusic: the playlist's own YouTube url. soundcloud: permalink path (e.g.
    // "someuser/some-playlist") — both opaque ids from the app's point of view,
    // passed straight to SearchRepository.getPlaylistTracks to open one.
    @SerializedName("source_id") val sourceId: String,
    val title: String,
    @SerializedName("thumbnail_url") val thumbnailUrl: String?,
    @SerializedName("track_count") val trackCount: Int?,
    val owner: String?,
    // Full shareable webpage URL — unlike sourceId (opaque id), always the
    // complete https:// permalink, for both sources.
    @SerializedName("webpage_url") val webpageUrl: String,
)

data class AlbumResultDto(
    val source: String,
    @SerializedName("source_id") val sourceId: String,
    val title: String,
    val artist: String?,
    @SerializedName("thumbnail_url") val thumbnailUrl: String?,
    val year: String?,
    @SerializedName("webpage_url") val webpageUrl: String,
)
