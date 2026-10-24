package dev.schlubbe.musicagent.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Syncs with the paired PC by itself: at app start and every few hours, on Wi-Fi only
 * (the PC is found in the home network). A PC that is off or away is no error: the
 * next run tries again.
 */
@HiltWorker
class AutoSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncClient: SyncClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (syncClient.isPaired) syncClient.sync()
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "auto_sync_periodic"
        private const val ON_START = "auto_sync_start"

        fun schedule(workManager: WorkManager) {
            val wifi = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AutoSyncWorker>(6, TimeUnit.HOURS).setConstraints(wifi).build(),
            )
            workManager.enqueueUniqueWork(
                ON_START,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AutoSyncWorker>().setConstraints(wifi).setInitialDelay(20, TimeUnit.SECONDS).build(),
            )
        }
    }
}
