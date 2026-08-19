package dev.schlubbe.musicagent.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "likes")
data class LikeEntity(
    @PrimaryKey val trackId: String, // "source:sourceId"
    @Embedded val track: LocalTrackEntity,
    val createdAt: String, // ISO-8601, matches LikeOutDto.createdAt's String type
)
