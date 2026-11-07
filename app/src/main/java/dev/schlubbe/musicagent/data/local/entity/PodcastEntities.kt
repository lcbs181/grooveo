package dev.schlubbe.musicagent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A subscribed podcast or Hörspiel series. [id] is a [dev.schlubbe.musicagent.podcast.ShowId]
 * string: "rss:<feed url>" or "ard:<ARD Audiothek program set id>". */
@Entity(tableName = "podcast_shows")
data class PodcastShowEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val imageUrl: String?,
    val kind: String,
    val subscribedAt: Long,
    // Newest episode seen so far, so the new-episode check only reports newer ones.
    val latestEpisodeAt: Long?,
)

/** Where listening to an episode stopped; doubles as the "Weiterhören" list. Keyed by
 * the episode's audio URL, which is also its track id ("podcast:<url>"). */
@Entity(tableName = "podcast_progress", indices = [Index("updatedAt")])
data class PodcastProgressEntity(
    @PrimaryKey val audioUrl: String,
    val showId: String?,
    val showTitle: String?,
    val title: String,
    val imageUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAt: Long,
)
