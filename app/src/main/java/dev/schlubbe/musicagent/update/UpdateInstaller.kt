package dev.schlubbe.musicagent.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.schlubbe.musicagent.R
import java.io.File

/**
 * Installs a downloaded update through [PackageInstaller] instead of handing the
 * APK to the system installer screen. On Android 12+ that lets an update go in
 * without any prompt once this app is the one that installed itself (the first
 * update after a manual install still asks once; Android decides). When Android
 * does want a confirmation, or when the app is in the background, a notification
 * carries it, so the update never silently waits for the app to be opened.
 */
object UpdateInstaller {

    private const val ACTION_STATUS = "dev.schlubbe.musicagent.UPDATE_INSTALL_STATUS"
    const val NOTIFICATION_ID = 4711

    /** Starts the install; [interactive] = the person just tapped "Installieren"
     * in the app, so a confirmation screen may open straight away. */
    fun install(context: Context, file: File, interactive: Boolean) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("update.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            // explicit component: the PendingIntent has to be mutable (the installer
            // fills in the result), so it must not be able to reach anything else
            val status = Intent(ACTION_STATUS).setClass(context, UpdateInstallReceiver::class.java)
                .putExtra(EXTRA_INTERACTIVE, interactive)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, sessionId, status, flags)
            session.commit(pending.intentSender)
        }
    }

    /** Silent installs restart the app: never do that under the person's music. */
    fun musicPlaying(context: Context): Boolean =
        context.getSystemService(AudioManager::class.java)?.isMusicActive == true

    /** True while one of the app's screens is visible. */
    fun appInForeground(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    internal const val EXTRA_INTERACTIVE = "interactive"

    internal fun notify(context: Context, title: String, text: String, intent: PendingIntent?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val channel = NotificationChannel(UpdateCheckWorker.CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(context, UpdateCheckWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_small)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).cancel(UpdateCheckWorker.NOTIFICATION_ID) // the "Update verfügbar" one
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}

/** Result of an [UpdateInstaller] session. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appName = context.getString(R.string.app_name)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    ) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // In the app (the person tapped "Installieren"): show it right away.
                // Otherwise Android would block the screen from the background - a
                // notification carries it instead.
                // (startActivity does not throw when Android blocks it, so check first)
                if (intent.getBooleanExtra(UpdateInstaller.EXTRA_INTERACTIVE, false) &&
                    UpdateInstaller.appInForeground() &&
                    runCatching { context.startActivity(confirm) }.isSuccess
                ) return
                val tap = PendingIntent.getActivity(
                    context, 0, confirm,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                UpdateInstaller.notify(context, "Update bereit", "Tippen, um $appName zu aktualisieren", tap)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the new version is running now
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // the person said no
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                UpdateInstaller.notify(
                    context,
                    "Update nicht installiert",
                    message?.let { "$appName: $it" } ?: "$appName konnte nicht aktualisiert werden",
                    null,
                )
            }
        }
    }
}
