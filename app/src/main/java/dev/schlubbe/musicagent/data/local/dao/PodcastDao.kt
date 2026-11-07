package dev.schlubbe.musicagent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.schlubbe.musicagent.data.local.entity.PodcastProgressEntity
import dev.schlubbe.musicagent.data.local.entity.PodcastShowEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcast_shows ORDER BY subscribedAt DESC")
    fun observeShows(): Flow<List<PodcastShowEntity>>

    @Query("SELECT * FROM podcast_shows")
    suspend fun getShows(): List<PodcastShowEntity>

    @Query("SELECT * FROM podcast_shows WHERE id = :id")
    fun observeShow(id: String): Flow<PodcastShowEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShow(show: PodcastShowEntity)

    @Query("UPDATE podcast_shows SET latestEpisodeAt = :at WHERE id = :id")
    suspend fun setLatestEpisodeAt(id: String, at: Long)

    @Query("DELETE FROM podcast_shows WHERE id = :id")
    suspend fun deleteShow(id: String)

    @Query("SELECT * FROM podcast_progress WHERE audioUrl = :audioUrl")
    suspend fun getProgress(audioUrl: String): PodcastProgressEntity?

    @Query("SELECT * FROM podcast_progress WHERE showId = :showId")
    fun observeProgressForShow(showId: String): Flow<List<PodcastProgressEntity>>

    @Query("SELECT * FROM podcast_progress WHERE completed = 0 ORDER BY updatedAt DESC LIMIT :limit")
    fun observeInProgress(limit: Int): Flow<List<PodcastProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: PodcastProgressEntity)

    @Query("DELETE FROM podcast_progress WHERE audioUrl = :audioUrl")
    suspend fun deleteProgress(audioUrl: String)
}
