package dev.schlubbe.musicagent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String, // client-generated UUID string
    val name: String,
    val createdAt: String, // ISO-8601
    val description: String? = null,
    // One of "accent"/"accent2"/"neutral", or null for the deterministic per-id
    // hash color (see ui/theme/Color.kt's accentColorFor) -- matches the edit
    // sheet's 4 swatches (Auto/Akzent/Akzent 2/Neutral).
    val accentColorKey: String? = null,
    // Comma-separated subset of "chill"/"focus"/"workout"/"party" -- a plain
    // string column rather than a join table since this is a small fixed set,
    // not a growing list.
    val moodTags: String? = null,
)
