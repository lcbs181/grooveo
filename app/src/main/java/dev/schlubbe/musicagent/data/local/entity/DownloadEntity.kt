package dev.schlubbe.musicagent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
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
    // Partial-download bookkeeping so a PAUSED (or a FAILED-mid-transfer) progressive
    // download can resume via an HTTP Range request instead of restarting from byte 0
    // - see DownloadWorker. Null/0 for QUEUED/COMPLETED, and for HLS downloads (which
    // always restart from the first segment - segments aren't byte-range-resumable
    // as a single persisted offset).
    val tempFilePath: String? = null,
    val bytesDownloaded: Long = 0,
    // Total file size in bytes from HTTP Content-Length header (progressive downloads)
    // or calculated from HLS segments. Used for displaying download file size to the user.
    // Null for QUEUED, populated when download starts.
    val totalBytes: Long? = null,
)
