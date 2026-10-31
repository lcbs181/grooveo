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
    // SoundCloud only: genre plus the uploader's tag_list (TrackTags.parse); null when the
    // source sent none, so "no tags" and "not known" stay apart (see TagRepository).
    @SerializedName("tags") val tags: List<String>? = null,
    // ytmusic artist pages only: YouTube Music's own play count text for a top
    // song (e.g. "32 Mio. Wiedergaben"), shown like Spotify's play counts.
    @SerializedName("plays") val plays: String? = null,
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
    // Both default to empty - ytmusic official-artist channels and SoundCloud
    // accounts without an ALBUMS/PLAYLISTS equivalent simply have none, same
    // "no signal, no claim" convention as the rest of this DTO.
    val albums: List<AlbumResultDto> = emptyList(),
    val playlists: List<PlaylistResultDto> = emptyList(),
    @SerializedName("webpage_url") val webpageUrl: String,
    // The rest comes from YouTube Music's artist page (ytmusic only, empty/null
    // elsewhere): "436.000" monthly audience, singles & EPs apart from albums,
    // music videos, playlists the artist is featured in ("Zu hören in") and the
    // "Fans also like" artists.
    @SerializedName("monthly_listeners") val monthlyListeners: String? = null,
    val singles: List<AlbumResultDto> = emptyList(),
    val videos: List<TrackResultDto> = emptyList(),
    @SerializedName("featured_on") val featuredOn: List<PlaylistResultDto> = emptyList(),
    val related: List<ArtistResultDto> = emptyList(),
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
    // "Album", "Single" or "EP" when the source says (YouTube Music artist pages).
    val kind: String? = null,
)
