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
    val webpageUrl: String = "",
    // SoundCloud's track JSON has a real "genre" field (see
    // SoundCloudMappers.toSoundCloudTrackResultDto) - ytmusic tracks (NewPipeExtractor's
    // StreamInfoItem exposes no genre/category field) and any row cached before this
    // column existed are left null rather than guessed. Quiet background signal only -
    // feeds FeedRepository's personalized-mix scoring, no screen displays it directly.
    val genre: String? = null,
)
