package dev.schlubbe.musicagent.update

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.schlubbe.musicagent.R
import dev.schlubbe.musicagent.data.remote.dto.UpdateInfoDto
import dev.schlubbe.musicagent.data.repository.UpdateRepository
import javax.inject.Inject

/**
 * The system DownloadManager finished the update APK (UpdateRepository.startDownload),
 * possibly while Grooveo was in the background or not running. Android does not let
 * a background app open the installer itself, so this posts a notification whose tap
 * opens it. Tapping it again after a failed installation works too: the verified APK
 * stays until that version is installed.
 */
@AndroidEntryPoint
class UpdateDownloadReceiver : BroadcastReceiver() {
    @Inject lateinit var updateRepository: UpdateRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (!updateRepository.isUpdateDownload(id)) return
        val code = updateRepository.pendingVersionCode()
        // size unknown here; downloadedApk() still checks package name and version
        val file = updateRepository.downloadedApk(UpdateInfoDto(code, "", "")) ?: return

        val channel = NotificationChannel(UpdateCheckWorker.CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val install = PendingIntent.getActivity(
            context, 0, updateRepository.installIntent(file),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, UpdateCheckWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_small)
            .setContentTitle("Update bereit")
            .setContentText("Tippen, um Grooveo zu aktualisieren")
            .setContentIntent(install)
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(UpdateCheckWorker.NOTIFICATION_ID, notification)
    }
}
