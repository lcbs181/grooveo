package dev.schlubbe.musicagent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.schlubbe.musicagent.data.local.entity.SavedPlaylistEntity

@Dao
interface SavedPlaylistDao {
    @Query("SELECT * FROM saved_playlists ORDER BY savedAt DESC")
    suspend fun getAll(): List<SavedPlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SavedPlaylistEntity)

    @Query("DELETE FROM saved_playlists WHERE source = :source AND sourceId = :sourceId")
    suspend fun delete(source: String, sourceId: String)
}
