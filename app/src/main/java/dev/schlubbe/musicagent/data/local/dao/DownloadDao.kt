package dev.schlubbe.musicagent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.schlubbe.musicagent.data.local.entity.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE trackId = :trackId")
    suspend fun getByTrackId(trackId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE trackId = :trackId")
    fun observeByTrackId(trackId: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE state = 'COMPLETED' AND mediaStoreUri IS NOT NULL LIMIT 1")
    suspend fun anyCompleted(): DownloadEntity?

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("UPDATE downloads SET totalBytes = :bytes WHERE trackId = :trackId")
    suspend fun updateTotalBytes(trackId: String, bytes: Long)

    @Query("SELECT * FROM downloads WHERE state = 'COMPLETED' AND mediaStoreUri IS NOT NULL")
    suspend fun allCompleted(): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE trackId = :trackId")
    suspend fun delete(trackId: String)
}
