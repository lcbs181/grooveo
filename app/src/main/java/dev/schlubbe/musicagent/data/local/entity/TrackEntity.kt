package dev.schlubbe.musicagent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val source: String,
    val sourceId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationSec: Int?,
    val thumbnailUrl: String?,
    val lastAccessedAt: Long,
)
