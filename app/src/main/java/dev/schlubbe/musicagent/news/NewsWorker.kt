package dev.schlubbe.musicagent.news

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.JsonParser
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.schlubbe.musicagent.BuildConfig
import dev.schlubbe.musicagent.MainActivity
import dev.schlubbe.musicagent.R
import dev.schlubbe.musicagent.data.extract.di.ExtractionHttpClient
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate

/**
 * "Neuigkeiten": news posts as notifications without a push server, account or
 * Google service. Every few hours this reads `news/news.json` from the repo's
 * master branch (format: [NewsItem], see news/README.md) and shows each item this
 * app has not shown yet. Items older than [MAX_AGE_DAYS] are taken in silently,
 * so a fresh install is not greeted with old news.
 */
@HiltWorker
class NewsWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    @ExtractionHttpClient private val okHttpClient: OkHttpClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!settingsRepository.notifyNews.first()) return Result.success()
        val items = runCatching { fetch() }.getOrElse { return Result.retry() }
        val seen = settingsRepository.seenNews.first()
        val app = if (BuildConfig.DAWN) "dawn" else "grooveo"
        val version = BuildConfig.VERSION_CODE.toLong()
        val today = LocalDate.now()
        val fresh = items.filter { it.id !in seen }
        val show = fresh.filter { item ->
            (item.apps.isEmpty() || app in item.apps) &&
                version >= item.minVersionCode && (item.maxVersionCode == null || version <= item.maxVersionCode) &&
                item.date?.let { !it.isBefore(today.minusDays(MAX_AGE_DAYS)) && !it.isAfter(today) } != false
        }
        // future-dated items stay unseen until their day comes
        settingsRepository.markNewsSeen(fresh.filter { it.date?.isAfter(today) != true }.map { it.id }.toSet())
        show.take(MAX_NOTIFICATIONS).forEach(::notify)
        return Result.success()
    }

    private suspend fun fetch(): List<NewsItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(NEWS_URL).header("Cache-Control", "no-cache").build()
        okHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            NewsItem.parseAll(response.body.string())
        }
    }

    private fun notify(item: NewsItem) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val channel = NotificationChannel(CHANNEL_ID, "Neuigkeiten", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Neuigkeiten rund um ${context.getString(R.string.app_name)}"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        // A song/playlist/artist link opens in the app itself (MainActivity resolves
        // it like a shared link, a song starts playing); any other link opens in the
        // browser; no link just opens the app.
        val target = when {
            item.url == null -> Intent(context, MainActivity::class.java)
            NewsItem.isMusicLink(item.url) -> Intent(Intent.ACTION_VIEW, Uri.parse(item.url), context, MainActivity::class.java)
            else -> Intent(Intent.ACTION_VIEW, Uri.parse(item.url))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(
            context, item.id.hashCode(), target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_small)
            .setContentTitle(item.title)
            .setContentText(item.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify("news:${item.id}".hashCode(), notification)
    }

    companion object {
        const val CHANNEL_ID = "news"
        const val UNIQUE_WORK_NAME = "news_periodic"
        const val NEWS_URL = "https://raw.githubusercontent.com/lcbs181/grooveo/master/news/news.json"
        private const val MAX_AGE_DAYS = 3L
        private const val MAX_NOTIFICATIONS = 3
    }
}

/** One entry of news/news.json. */
data class NewsItem(
    val id: String,
    val title: String,
    val text: String,
    val url: String? = null,
    val date: LocalDate? = null,
    /** "dawn" / "grooveo"; empty = both. */
    val apps: List<String> = emptyList(),
    val minVersionCode: Long = 0,
    val maxVersionCode: Long? = null,
) {
    companion object {
        private val MUSIC_HOSTS = listOf("youtube.com", "youtu.be", "music.youtube.com", "soundcloud.com", "on.soundcloud.com")

        fun isMusicLink(url: String): Boolean {
            val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return false
            return MUSIC_HOSTS.any { host == it || host.endsWith(".$it") }
        }

        /** Lenient: entries without id/title/text, or with a non-https url, are skipped. */
        fun parseAll(json: String): List<NewsItem> {
            val root = JsonParser.parseString(json)
            val array = if (root.isJsonArray) root.asJsonArray else root.asJsonObject.getAsJsonArray("items") ?: return emptyList()
            return array.mapNotNull { e ->
                runCatching {
                    val o = e.asJsonObject
                    fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
                    NewsItem(
                        id = s("id") ?: return@runCatching null,
                        title = s("title")?.take(80) ?: return@runCatching null,
                        text = s("text")?.take(500) ?: return@runCatching null,
                        url = s("url")?.takeIf { it.startsWith("https://") },
                        date = s("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                        apps = o.getAsJsonArray("apps")?.mapNotNull { a -> a.takeIf { it.isJsonPrimitive }?.asString?.lowercase() }.orEmpty(),
                        minVersionCode = o.get("minVersionCode")?.asLong ?: 0,
                        maxVersionCode = o.get("maxVersionCode")?.asLong,
                    )
                }.getOrNull()
            }
        }
    }
}
