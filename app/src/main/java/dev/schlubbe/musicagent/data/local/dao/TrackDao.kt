package dev.schlubbe.musicagent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(track: TrackEntity)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks")
    suspend fun getAll(): List<TrackEntity>

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: String)

    /** Metadata for every track that has a downloads row, in one query - the Downloads
     * list joins against this instead of a getById() per row. A JOIN rather than
     * `WHERE id IN (:ids)`: minSdk 30's SQLite caps bound variables at 999. */
    @Query("SELECT tracks.* FROM tracks INNER JOIN downloads ON downloads.trackId = tracks.id")
    fun observeWithDownload(): Flow<List<TrackEntity>>

    /** 0 means "never played" (cached for a download, like or playlist only). */
    @Query("SELECT * FROM tracks WHERE lastAccessedAt > 0 ORDER BY lastAccessedAt DESC LIMIT :limit")
    fun observeRecentlyPlayed(limit: Int = 50): Flow<List<TrackEntity>>

    /** Clears the listening history; the rows stay, likes and playlists still need them. */
    @Query("UPDATE tracks SET lastAccessedAt = 0")
    suspend fun forgetPlayed()

    /** Takes one track out of the listening history. */
    @Query("UPDATE tracks SET lastAccessedAt = 0 WHERE id = :id")
    suspend fun forgetPlayed(id: String)
}
