package dev.schlubbe.musicagent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.schlubbe.musicagent.data.local.entity.LikeEntity

@Dao
interface LikeDao {
    @Query("SELECT * FROM likes ORDER BY createdAt DESC")
    suspend fun getAll(): List<LikeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(like: LikeEntity)

    @Query("DELETE FROM likes WHERE trackId = :trackId")
    suspend fun deleteByTrackId(trackId: String)
}
