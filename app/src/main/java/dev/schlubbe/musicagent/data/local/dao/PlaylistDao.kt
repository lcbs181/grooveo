package dev.schlubbe.musicagent.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import dev.schlubbe.musicagent.data.local.entity.PlaylistEntity

data class PlaylistWithCount(
    @Embedded val playlist: PlaylistEntity,
    val trackCount: Int,
)

@Dao
interface PlaylistDao {
    @Query(
        """
        SELECT playlists.*, (
            SELECT COUNT(*) FROM playlist_tracks WHERE playlist_tracks.playlistId = playlists.id
        ) AS trackCount
        FROM playlists ORDER BY createdAt DESC
        """,
    )
    suspend fun getAllWithCounts(): List<PlaylistWithCount>

    @Query(
        """
        SELECT playlists.*, (
            SELECT COUNT(*) FROM playlist_tracks WHERE playlist_tracks.playlistId = playlists.id
        ) AS trackCount
        FROM playlists WHERE id = :id
        """,
    )
    suspend fun getByIdWithCount(id: String): PlaylistWithCount?

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: String): PlaylistEntity?

    @Insert
    suspend fun insert(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: String)
}
