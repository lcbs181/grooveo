package dev.schlubbe.musicagent.data.local.dao

import dev.schlubbe.musicagent.data.local.entity.DownloadEntity
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

/** Desktop counterparts of the Room DAOs the shared sources depend on (implemented by LibraryStore). */
interface TrackDao {
    suspend fun getById(id: String): TrackEntity?
    fun observeRecentlyPlayed(limit: Int = 50): Flow<List<TrackEntity>>
}

interface DownloadDao {
    suspend fun getByTrackId(trackId: String): DownloadEntity?
}
