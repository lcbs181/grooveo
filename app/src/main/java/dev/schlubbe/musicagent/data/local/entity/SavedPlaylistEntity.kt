package dev.schlubbe.musicagent.data.local.entity

import androidx.room.Entity

/** A public (SoundCloud/YouTube) playlist or album the user has explicitly saved
 * from Search - same local-only "no server-side graph" pattern as
 * [FollowedArtistEntity], keyed by (source, sourceId) since remote items are
 * already uniquely identified that way. Distinct from [PlaylistEntity]: that one
 * is a locally *created* playlist the user builds track-by-track; this one is a
 * bookmark onto someone else's existing remote playlist/album, with no track list
 * of its own stored (re-fetched live via SearchRepository.getPlaylistDetail when
 * opened). */
@Entity(tableName = "saved_playlists", primaryKeys = ["source", "sourceId"])
data class SavedPlaylistEntity(
    val source: String,
    val sourceId: String,
    val title: String,
    val thumbnailUrl: String?,
    val owner: String?,
    val trackCount: Int?,
    val webpageUrl: String,
    val savedAt: String, // ISO-8601
)
