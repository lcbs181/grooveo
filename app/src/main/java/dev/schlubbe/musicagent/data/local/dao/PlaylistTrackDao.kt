package dev.schlubbe.musicagent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.schlubbe.musicagent.data.local.entity.PlaylistTrackEntity

@Dao
interface PlaylistTrackDao {
    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getForPlaylist(playlistId: String): List<PlaylistTrackEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getMaxPosition(playlistId: String): Int

    @Insert
    suspend fun insert(entry: PlaylistTrackEntity)

    /** Reads [getMaxPosition] and inserts in one transaction - unlike calling those
     * two separately, two concurrent calls for the same [playlistId] (e.g. an "add
     * all results" action firing one insert per track) can't both read the same max
     * position before either commits and insert with the same, now-duplicated
     * [PlaylistTrackEntity.position], corrupting the playlist's intended order. */
    @Transaction
    suspend fun insertAtEnd(playlistId: String, entryFactory: (Int) -> PlaylistTrackEntity) {
        insert(entryFactory(getMaxPosition(playlistId) + 1))
    }

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun delete(playlistId: String, trackId: String)

    @Update
    suspend fun updateAll(entries: List<PlaylistTrackEntity>)

    @Transaction
    suspend fun reorder(playlistId: String, trackIds: List<String>) {
        val current = getForPlaylist(playlistId).associateBy { it.trackId }
        val reindexed = trackIds.mapIndexedNotNull { index, trackId -> current[trackId]?.copy(position = index) }
        updateAll(reindexed)
    }
}
