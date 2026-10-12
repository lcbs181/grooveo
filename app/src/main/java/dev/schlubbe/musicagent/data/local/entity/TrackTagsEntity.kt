package dev.schlubbe.musicagent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A track's tags ([dev.schlubbe.musicagent.data.tags.TrackTags]); an empty [tags] means "looked, has none". */
@Entity(tableName = "track_tags")
data class TrackTagsEntity(
    @PrimaryKey val trackId: String, // "source:sourceId"
    /** One tag per line. */
    val tags: String,
    val fetchedAt: Long,
)
