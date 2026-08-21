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
    // SoundCloud only (see SoundCloudMappers.isDrmOnly) - true when a track's only
    // transcodings are DRM-encrypted, so tapping it would fail to resolve a
    // playable stream. Always false for ytmusic and for any cached/local track
    // shape that doesn't carry this signal (likes, playlists, downloads).
    @SerializedName("is_drm_protected") val isDrmProtected: Boolean = false,
    // SoundCloud only - see SoundCloudMappers.toSoundCloudTrackResultDto. Null for
    // ytmusic (NewPipeExtractor exposes no genre/category field on StreamInfoItem) -
    // a quiet signal for FeedRepository's personalized-mix scoring, not shown in any UI.
    @SerializedName("genre") val genre: String? = null,
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

/** A playlist/album search result's full contents - same fields as
 * [PlaylistResultDto] plus its resolved track list, fetched together in one call
 * (SearchRepository.getPlaylistDetail) so the new browse screen reached from Search
 * doesn't need a second round-trip after the search result is tapped. */
data class RemotePlaylistDetailDto(
    val source: String,
    @SerializedName("source_id") val sourceId: String,
    val title: String,
    @SerializedName("thumbnail_url") val thumbnailUrl: String?,
    @SerializedName("track_count") val trackCount: Int?,
    val owner: String?,
    @SerializedName("webpage_url") val webpageUrl: String,
    // soundcloud: the playlist/album's "description" field, straight from the
    // resolve response. ytmusic: NewPipeExtractor's PlaylistInfo.description
    // content, HTML-stripped. Null when the source has none set - fetch-live
    // only, not persisted (see SavedPlaylistEntity).
    val description: String? = null,
    // soundcloud: parsed from the resolve response's space-separated "tag_list"
    // field (quoted multi-word tags). ytmusic: always empty - NewPipeExtractor's
    // PlaylistInfo exposes no equivalent tag/keyword field for playlists.
    val tags: List<String> = emptyList(),
    val tracks: List<TrackResultDto> = emptyList(),
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
