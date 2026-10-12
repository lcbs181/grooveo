package dev.schlubbe.musicagent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.schlubbe.musicagent.data.local.entity.TrackTagsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackTagsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: TrackTagsEntity)

    @Query("SELECT * FROM track_tags")
    fun observeAll(): Flow<List<TrackTagsEntity>>

    @Query("SELECT trackId FROM track_tags")
    suspend fun knownIds(): List<String>
}
