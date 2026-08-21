package dev.schlubbe.musicagent.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.schlubbe.musicagent.MainActivity
import dev.schlubbe.musicagent.R
import dev.schlubbe.musicagent.data.repository.UpdateCheckResult
import dev.schlubbe.musicagent.data.repository.UpdateRepository

/**
 * Background counterpart to Settings' manual "Nach Updates suchen" flow
 * (UpdateViewModel.checkForUpdate) - runs on a 24h WorkManager periodic schedule
 * (see MusicAgentApp.schedulePeriodicUpdateCheck) and, if GitHub's Releases API
 * reports a newer .apk than what's installed, posts a notification instead of
 * silently doing nothing (the only other automatic check - NavGraph's
 * once-per-launch checkForUpdate(silent = true) - only ever surfaces anything
 * while the app is actually open).
 *
 * Tapping the notification just opens MainActivity: NavGraph's own
 * LaunchedEffect(Unit) { updateViewModel.checkForUpdate(silent = true) } re-runs on
 * every fresh launch and - unlike its up-to-date/error cases - always surfaces
 * UpdateUiState.Available as the same UpdateDialog Settings' manual check shows, so
 * this reuses that existing entry point rather than needing its own deep link into
 * a specific screen/route.
 *
 * No SettingsRepository toggle gates this: the only existing notification
 * preference (notifyNewUploads) is specifically about new-upload notifications, not
 * updates, so per the feature request this always runs.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val updateRepository: UpdateRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return when (val result = updateRepository.checkForUpdate()) {
            is UpdateCheckResult.Available -> {
                postUpdateNotification(result.info.versionName)
                Result.success()
            }
            // UpToDate/Error: nothing to notify about - and unlike the manual
            // Settings flow, there's no UI here to show an error in anyway. The
            // next 24h run (or the next app launch's silent check) will just try
            // again.
            else -> Result.success()
        }
    }

    private fun postUpdateNotification(versionName: String) {
        ensureNotificationChannel()

        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_small)
            .setContentTitle("Update verfügbar")
            .setContentText("Version $versionName kann installiert werden")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // POST_NOTIFICATIONS is only a runtime permission from API 33 (TIRAMISU) on
        // - MainActivity already requests it at first launch, but a background
        // worker can run before that request is granted/answered, so check rather
        // than assume.
        if (ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Benachrichtigung, wenn eine neue Version von Music Agent verfügbar ist"
        }
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "updates"
        const val NOTIFICATION_ID = 1001

        /** Unique work name for MusicAgentApp's enqueueUniquePeriodicWork - keeps a
         * second periodic schedule from silently stacking up if onCreate() ever runs
         * more than once (process restarts, etc.). */
        const val UNIQUE_WORK_NAME = "update_check_periodic"
    }
}
