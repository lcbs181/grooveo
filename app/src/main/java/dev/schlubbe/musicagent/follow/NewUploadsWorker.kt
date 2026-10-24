package dev.schlubbe.musicagent.follow

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.repository.FollowRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * "Neue Uploads von gefolgten Künstlern": every few hours, looks at the latest tracks of
 * followed artists and posts a notification for each one not seen before, like the PC
 * app's NewUploadsWatcher. What was seen is kept in [SettingsRepository], and a newly
 * followed artist's existing tracks are taken in silently the first time.
 */
@HiltWorker
class NewUploadsWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val followRepository: FollowRepository,
    private val searchRepository: SearchRepository,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!settingsRepository.notifyNewUploads.first()) return Result.success()
        val seen = settingsRepository.seenUploads.first()
        val checked = settingsRepository.uploadArtists.first()
        val fresh = mutableListOf<TrackResultDto>()
        val newSeen = mutableSetOf<String>()
        val newArtists = mutableSetOf<String>()
        for (artist in runCatching { followRepository.refresh() }.getOrDefault(emptyList())) {
            val key = "${artist.source}:${artist.sourceId}"
            val latest = runCatching { searchRepository.getArtist(artist.source, artist.sourceId, refresh = true).latestTracks.take(3) }
                .getOrNull() ?: continue
            for (t in latest) {
                val id = "${t.source}:${t.sourceId}"
                if (id !in seen) { newSeen += id; if (key in checked) fresh += t }
            }
            if (key !in checked) newArtists += key
        }
        settingsRepository.markUploadsSeen(newSeen, newArtists)
        fresh.take(MAX_NOTIFICATIONS).forEach(::notify)
        return Result.success()
    }

    private fun notify(track: TrackResultDto) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val channel = NotificationChannel(CHANNEL_ID, "Neue Uploads", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Neue Titel von Künstlern, denen du folgst"
        }
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_small)
            .setContentTitle("Neu von ${track.artist ?: "einem Künstler, dem du folgst"}")
            .setContentText(track.title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify("${track.source}:${track.sourceId}".hashCode(), notification)
    }

    companion object {
        const val CHANNEL_ID = "new_uploads"
        const val UNIQUE_WORK_NAME = "new_uploads_periodic"
        private const val MAX_NOTIFICATIONS = 5
    }
}
