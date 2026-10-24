package dev.schlubbe.musicagent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One play of a track, written when it ends: how long it was heard and why it ended
 * (see [dev.schlubbe.musicagent.data.recommend.EndReason]). The recommendations learn
 * from these - a track skipped after ten seconds is a "no", one heard to the end a "yes".
 */
@Entity(tableName = "play_events", indices = [Index("startedAt")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "source:sourceId", like [TrackEntity.id]. */
    val trackId: String,
    val artist: String?,
    val startedAt: Long,
    val listenedMs: Long,
    val durationMs: Long,
    /** An [dev.schlubbe.musicagent.data.recommend.EndReason] name. */
    val endReason: String,
)
