package dev.schlubbe.musicagent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.schlubbe.musicagent.data.local.entity.TrackAnalysisEntity

@Dao
interface TrackAnalysisDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(analysis: TrackAnalysisEntity)

    @Query("SELECT * FROM track_analysis WHERE trackId = :trackId")
    suspend fun getByTrackId(trackId: String): TrackAnalysisEntity?

    @Query("SELECT trackId FROM track_analysis WHERE trackId IN (:trackIds)")
    suspend fun analyzedIds(trackIds: List<String>): List<String>

    @Query("DELETE FROM track_analysis WHERE trackId = :trackId")
    suspend fun delete(trackId: String)
}
