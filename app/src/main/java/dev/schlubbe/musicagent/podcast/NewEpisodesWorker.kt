package dev.schlubbe.musicagent.podcast

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
import dev.schlubbe.musicagent.data.local.dao.PodcastDao
import dev.schlubbe.musicagent.follow.NotificationTracks
import kotlinx.coroutines.flow.first

/**
 * Every few hours, checks the subscribed podcasts and Hörspiele for episodes newer than
 * the newest one seen so far and posts one notification per show. Tapping it plays the
 * episode. Can be switched off or limited to Wi-Fi in Einstellungen > Datenschutz.
 */
@HiltWorker
class NewEpisodesWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: PodcastRepository,
    private val dao: PodcastDao,
    private val settings: dev.schlubbe.musicagent.data.repository.SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // every check tells each feed host the IP address and what is subscribed
        if (!settings.podcastCheckNew.first()) return Result.success()
        if (settings.podcastCheckWifiOnly.first() && isMetered()) return Result.success()
        var posted = 0
        for (show in dao.getShows()) {
            val loaded = runCatching { repository.loadShow(show.id, forceRefresh = true) }.getOrNull() ?: continue
            val newest = loaded.episodes.mapNotNull { it.publishedAt }.maxOrNull() ?: continue
            val since = show.latestEpisodeAt
            if (since != null && newest > since && posted < MAX_NOTIFICATIONS) {
                val fresh = loaded.episodes.filter { (it.publishedAt ?: 0L) > since }
                fresh.maxByOrNull { it.publishedAt ?: 0L }?.let { notify(it, fresh.size) }
                posted++
            }
            if (since == null || newest > since) dao.setLatestEpisodeAt(show.id, newest)
        }
        return Result.success()
    }

    private fun isMetered(): Boolean =
        applicationContext.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false

    private fun notify(episode: PodcastEpisode, count: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val channel = NotificationChannel(CHANNEL_ID, "Neue Folgen", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Neue Folgen von abonnierten Podcasts und Hörspielen"
        }
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val track = episode.toTrack()
        val open = PendingIntent.getActivity(
            applicationContext, episode.audioUrl.hashCode(),
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_PLAY_TRACK, NotificationTracks.ticketFor(applicationContext, track)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_small)
            .setContentTitle(if (count > 1) "${episode.showTitle}: $count neue Folgen" else "Neue Folge: ${episode.showTitle}")
            .setContentText(episode.title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(episode.showId.hashCode(), notification)
    }

    companion object {
        const val CHANNEL_ID = "new_episodes"
        const val UNIQUE_WORK_NAME = "new_episodes_periodic"
        private const val MAX_NOTIFICATIONS = 5
    }
}
