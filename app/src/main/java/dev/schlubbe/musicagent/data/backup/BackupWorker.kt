package dev.schlubbe.musicagent.data.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant

/**
 * "Automatische Sicherung": checked once a day, writes a backup when the last one is a week
 * old (inside the app and, if chosen, in the backup folder that survives uninstalling).
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backupManager: BackupManager,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!settings.autoBackup.first()) return Result.success()
        val last = settings.lastBackupAt.first()?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (last != null && last.isAfter(Instant.now().minus(INTERVAL))) return Result.success()
        return backupManager.createBackup().fold(
            onSuccess = { settings.setLastBackupAt(Instant.now().toString()); Result.success() },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "auto_backup"
        val INTERVAL: Duration = Duration.ofDays(7)
    }
}
