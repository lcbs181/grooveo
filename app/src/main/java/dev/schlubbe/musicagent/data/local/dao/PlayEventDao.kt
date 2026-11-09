package dev.schlubbe.musicagent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.schlubbe.musicagent.data.local.entity.PlayEventEntity

@Dao
interface PlayEventDao {
    @Insert
    suspend fun insert(event: PlayEventEntity)

    @Query("SELECT * FROM play_events WHERE startedAt >= :since ORDER BY startedAt DESC")
    suspend fun since(since: Long): List<PlayEventEntity>

    @Query("DELETE FROM play_events")
    suspend fun deleteAll()

    @Query("DELETE FROM play_events WHERE startedAt < :before")
    suspend fun prune(before: Long)
}
