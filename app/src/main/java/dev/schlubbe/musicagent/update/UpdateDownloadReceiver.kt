package dev.schlubbe.musicagent.update

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.schlubbe.musicagent.R
import dev.schlubbe.musicagent.data.remote.dto.UpdateInfoDto
import dev.schlubbe.musicagent.data.repository.UpdateDownloadState
import dev.schlubbe.musicagent.data.repository.UpdateRepository
import javax.inject.Inject

/**
 * The system DownloadManager finished the update APK (UpdateRepository.startDownload),
 * possibly while the app was in the background or not running. The update goes
 * straight into a PackageInstaller session (UpdateInstaller): silent where Android
 * allows it, otherwise its confirmation arrives as a notification. While music is
 * playing nothing is installed - an update restarts the app and would cut the song
 * off - and a notification opens the installer whenever the person is ready.
 * A download that "finished" but is no APK (filtered network) is reported too.
 */
@AndroidEntryPoint
class UpdateDownloadReceiver : BroadcastReceiver() {
    @Inject lateinit var updateRepository: UpdateRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (!updateRepository.isUpdateDownload(id)) return
        val info = UpdateInfoDto(updateRepository.pendingVersionCode(), "", "")
        val appName = context.getString(R.string.app_name)
        val pending = goAsync()
        Thread {
            try {
                // size unknown here; downloadedApk() still checks package name and version
                val file = updateRepository.downloadedApk(info)
                when {
                    file == null -> {
                        if (updateRepository.downloadState(info) !is UpdateDownloadState.Running) {
                            UpdateInstaller.notify(context, "Update fehlgeschlagen", updateRepository.invalidDownloadMessage(info), null)
                        }
                    }
                    UpdateInstaller.musicPlaying(context) -> {
                        val install = PendingIntent.getActivity(
                            context, 0, updateRepository.installIntent(file),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                        UpdateInstaller.notify(context, "Update bereit", "Tippen, um $appName zu aktualisieren", install)
                    }
                    else -> runCatching {
                        updateRepository.installApk(file, interactive = UpdateInstaller.appInForeground(), force = false)
                    }.onFailure {
                        UpdateInstaller.notify(context, "Update nicht installiert", it.message ?: "$appName konnte nicht aktualisiert werden", null)
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

}
