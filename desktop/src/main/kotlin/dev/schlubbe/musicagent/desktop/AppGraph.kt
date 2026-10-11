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
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.desktop.audio.AudioEngine
import dev.schlubbe.musicagent.desktop.audio.FfmpegDeck
import dev.schlubbe.musicagent.desktop.audio.JavaSoundSink
import dev.schlubbe.musicagent.desktop.audio.TrackAnalyzer
import dev.schlubbe.musicagent.desktop.data.BackupManager
import dev.schlubbe.musicagent.desktop.data.DownloadManager
import dev.schlubbe.musicagent.desktop.data.LibraryStore
import dev.schlubbe.musicagent.desktop.data.TrackAnalysis
import dev.schlubbe.musicagent.desktop.data.key
import dev.schlubbe.musicagent.desktop.data.UpdateChecker
import dev.schlubbe.musicagent.desktop.data.defaultDataDir
import dev.schlubbe.musicagent.desktop.playback.PlayerController
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    val sync = dev.schlubbe.musicagent.desktop.data.SyncServer(backup, File(dataDir, "sync.json"))
        .also { if (it.enabled) runCatching { it.start() } }
    val updates = UpdateChecker(http, APP_VERSION, File(dataDir, "updates"))
    val player = PlayerController(
        AudioEngine({ FfmpegDeck(it) }, JavaSoundSink()).apply { loudnessCache = LoudnessCache(File(dataDir, "loudness.json")) },
        resolveRemote = { t -> resolver.resolveWithFallback(t.source, t.sourceId, t.title, t.artist, t.durationSec) },
        recommend = { recent, exclude, limit -> feed.predictNext(recent, exclude, limit) },
        store = store,
        settings = settings,
        analyze = { t -> analyze(t) },
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

    /** "KI-Steuerung (MCP)", started with the app when the user left it on. */
    val mcp = dev.schlubbe.musicagent.desktop.mcp.McpService(this, File(dataDir, "mcp.json"))
        .also { if (it.enabled) runCatching { it.start() } }

    /** Full decodes running at once; more would only slow each other and the UI down. */
    private val analysisSlots = Semaphore(2)

    /**
     * "Übergänge analysieren" for one track: decodes its download or stream, stores
     * mix points and beat grid in the library and returns them (null if not loadable).
     * Cancelling the caller stops the decode.
     */
    suspend fun analyze(t: TrackResultDto): TrackAnalysis? {
        val src = try {
            player.resolveStream(t)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            java.util.logging.Logger.getLogger("AppGraph").warning("analysis: cannot load ${t.key}: $e")
            return null
        }
        val r = analysisSlots.withPermit { TrackAnalyzer.analyzeCancellable(src.url, src.headers) } ?: return null
        if (r.mix == null && r.grid == null) return null
        return TrackAnalysis(r.mix?.mixInMs, r.mix?.mixOutMs, grid = r.grid, structure = r.structure, key = r.key, version = TrackAnalysis.CURRENT_VERSION)
            .also { store.putAnalysis(t.key, it) }
    }

    /** The in-app assistant ("KI-Panel"); created on first use. */
    private val aiLazy = lazy { dev.schlubbe.musicagent.desktop.ai.AiAssistant(this, File(dataDir, "ai-anthropic.key")) }
    val ai by aiLazy

    init {
        // the phone's "KI" tab: the assistant above and the tools, over the sync connection
        sync.remote = dev.schlubbe.musicagent.desktop.ai.PhoneBridge(this)
    }

    fun downloadDir(): File = settings.current.downloadDir.takeIf { it.isNotBlank() }?.let(::File) ?: File(dataDir, "downloads")

    fun close() {
        if (aiLazy.isInitialized()) ai.close()
        mcp.shutdown()
        sync.shutdown()
        settings.flush()
        player.close()
    }
}
