package dev.schlubbe.musicagent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.schlubbe.musicagent.data.local.entity.FollowedArtistEntity

@Dao
interface FollowedArtistDao {
    @Query("SELECT * FROM followed_artists ORDER BY followedAt DESC")
    suspend fun getAll(): List<FollowedArtistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FollowedArtistEntity)

    @Query("DELETE FROM followed_artists WHERE source = :source AND sourceId = :sourceId")
    suspend fun delete(source: String, sourceId: String)
}
