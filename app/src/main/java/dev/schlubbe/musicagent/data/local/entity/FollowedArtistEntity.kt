package dev.schlubbe.musicagent.data.local.entity

import androidx.room.Entity

/** An artist the user has explicitly followed on-device -- powers the Artist
 * page's follow/unfollow button and Home's "Neu von Künstlern" shelf. No
 * server-side follow graph exists (this app is backend-less for library data),
 * so this is purely local, same as likes/playlists. */
@Entity(tableName = "followed_artists", primaryKeys = ["source", "sourceId"])
data class FollowedArtistEntity(
    val source: String,
    val sourceId: String,
    val name: String,
    val thumbnailUrl: String?,
    val followedAt: String, // ISO-8601
)
