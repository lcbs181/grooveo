package dev.schlubbe.musicagent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val trackId: String,
    val mediaStoreUri: String?,
    val relativePath: String?,
    val state: DownloadState,
    val progressPct: Int,
    val createdAt: Long,
)
