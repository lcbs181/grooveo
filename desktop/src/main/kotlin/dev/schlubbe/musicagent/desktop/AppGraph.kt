package dev.schlubbe.musicagent.desktop

import dev.schlubbe.musicagent.playback.eq.LoudnessCache
import dev.schlubbe.musicagent.data.extract.StreamResolverRegistry
import dev.schlubbe.musicagent.data.extract.YouTubeFallback
import dev.schlubbe.musicagent.data.extract.di.extractionHttpClient
import dev.schlubbe.musicagent.data.extract.soundcloud.SoundCloudApi
import dev.schlubbe.musicagent.data.extract.soundcloud.SoundCloudClientIdProvider
import dev.schlubbe.musicagent.data.extract.soundcloud.SoundCloudSearchClient
import dev.schlubbe.musicagent.data.extract.soundcloud.SoundCloudStreamResolver
import dev.schlubbe.musicagent.data.extract.youtube.NewPipeDownloader
import dev.schlubbe.musicagent.data.extract.youtube.YouTubeMusicSearchClient
import dev.schlubbe.musicagent.data.extract.youtube.YouTubeStreamResolver
import dev.schlubbe.musicagent.data.repository.FeedRepository
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.LyricsRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import dev.schlubbe.musicagent.desktop.audio.AudioEngine
import dev.schlubbe.musicagent.desktop.audio.FfmpegDeck
import dev.schlubbe.musicagent.desktop.audio.JavaSoundSink
import dev.schlubbe.musicagent.desktop.data.BackupManager
import dev.schlubbe.musicagent.desktop.data.DownloadManager
import dev.schlubbe.musicagent.desktop.data.LibraryStore
import dev.schlubbe.musicagent.desktop.data.UpdateChecker
import dev.schlubbe.musicagent.desktop.data.defaultDataDir
import dev.schlubbe.musicagent.desktop.playback.PlayerController
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.io.File


/** Manual dependency graph (the Android app uses Hilt for the same wiring). */
class AppGraph(val dataDir: File = defaultDataDir()) {
    val http = extractionHttpClient()
    val settings = SettingsRepository(File(dataDir, "settings.json"))
    val store = LibraryStore(dataDir)

    init {
        NewPipe.init(NewPipeDownloader(http), Localization("de", "DE"), ContentCountry("DE"))
    }

    private val scClientId = SoundCloudClientIdProvider(http, settings)
    private val scApi = SoundCloudApi(http, scClientId)
    private val scSearch = SoundCloudSearchClient(scApi)
    private val ytSearch = YouTubeMusicSearchClient()
    val search = SearchRepository(scSearch, ytSearch, settings)
    val resolver = StreamResolverRegistry(SoundCloudStreamResolver(scApi), YouTubeStreamResolver(), YouTubeFallback(ytSearch), store)
    val feed = FeedRepository(store, LikesRepository(store), search, settings)
    val lyrics = LyricsRepository(http)
    val downloads = DownloadManager(store, { t -> resolver.resolveWithFallback(t.source, t.sourceId, t.title, t.artist, t.durationSec, preferProgressive = true) }, http, { downloadDir() })
    val backup = BackupManager(store, settings, File(dataDir, "backups"))
    val updates = UpdateChecker(http, APP_VERSION)
    val player = PlayerController(
        AudioEngine({ FfmpegDeck(it) }, JavaSoundSink()).apply { loudnessCache = LoudnessCache(File(dataDir, "loudness.json")) },
        resolveRemote = { t -> resolver.resolveWithFallback(t.source, t.sourceId, t.title, t.artist, t.durationSec) },
        recommend = { recent, exclude, limit -> feed.predictNext(recent, exclude, limit) },
        store = store,
        settings = settings,
    )

    init {
        // "Automatische Sicherung": weekly backup on start (keeps the newest 5)
        if (settings.current.autoBackup) runCatching {
            val last = settings.current.lastBackupAt?.let { java.time.Instant.parse(it) }
            if (last == null || last.isBefore(java.time.Instant.now().minus(java.time.Duration.ofDays(7)))) {
                backup.export()
                backup.listBackups().drop(5).forEach { it.delete() }
            }
        }
    }

    fun downloadDir(): File = settings.current.downloadDir.takeIf { it.isNotBlank() }?.let(::File) ?: File(dataDir, "downloads")

    fun close() {
        settings.flush()
        player.close()
    }
}
