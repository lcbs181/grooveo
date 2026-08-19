package dev.schlubbe.musicagent.data.local.entity

/** Denormalized track snapshot embedded directly into [LikeEntity]/[PlaylistTrackEntity]
 * rows, mirroring how the (now-removed) backend embedded a full TrackOutDto inside
 * LikeOutDto/PlaylistTrackOutDto rather than joining. Not a @Entity itself — this
 * avoids any foreign-key dependency on the existing `tracks` cache table (TrackEntity),
 * so this migration can't touch that table's schema or data at all. */
data class LocalTrackEntity(
    val source: String,
    val sourceId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationSec: Int?,
    val thumbnailUrl: String?,
    val webpageUrl: String,
)
