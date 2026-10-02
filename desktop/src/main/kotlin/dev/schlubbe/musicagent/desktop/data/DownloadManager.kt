package dev.schlubbe.musicagent.desktop.data

import dev.schlubbe.musicagent.data.extract.ResolvedStream
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Offline downloads: resolves a progressive stream (falls back to re-encoding HLS
 * with FFmpeg), downloads with HTTP Range resume, and tracks state in [LibraryStore]
 * (Warteschlange / Lädt / Angehalten / Fehlgeschlagen / Fertig).
 */
class DownloadManager(
    private val store: LibraryStore,
    /** Resolves a downloadable (preferably progressive) stream for a track. */
    private val resolve: suspend (TrackResultDto) -> ResolvedStream,
    private val http: OkHttpClient,
    private val dirProvider: () -> File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val gate = Semaphore(2)

    init {
        // downloads interrupted by quitting resume as queued
        store.current.downloads.filter { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED }
            .forEach { enqueue(it.track) }
    }

    fun enqueue(track: TrackResultDto) {
        val existing = store.download(track.key)
        if (existing?.state == DownloadState.COMPLETED && existing.filePath?.let { File(it).isFile } == true) return
        if (jobs[track.key]?.isActive == true) return
        store.upsertDownload((existing ?: DownloadRecord(track, DownloadState.QUEUED)).copy(state = DownloadState.QUEUED, error = null))
        jobs[track.key] = scope.launch { gate.withPermit { run(track) } }
    }

    fun enqueueAll(tracks: List<TrackResultDto>) = tracks.forEach(::enqueue)

    fun pause(key: String) {
        jobs.remove(key)?.cancel()
        store.download(key)?.takeIf { it.state != DownloadState.COMPLETED }?.let { store.upsertDownload(it.copy(state = DownloadState.PAUSED)) }
    }

    fun pauseAll() = store.current.downloads.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING }.forEach { pause(it.track.key) }

    fun resume(key: String) { store.download(key)?.let { enqueue(it.track) } }
    fun resumeAll() = store.current.downloads.filter { it.state == DownloadState.PAUSED || it.state == DownloadState.FAILED }.forEach { enqueue(it.track) }

    fun remove(key: String) {
        jobs.remove(key)?.cancel()
        store.download(key)?.filePath?.let { File(it).delete(); File("$it.part").delete() }
        store.removeDownload(key)
    }

    fun totalBytes(): Long = store.current.downloads.sumOf { r -> r.filePath?.let { File(it).takeIf(File::isFile)?.length() } ?: 0L }

    private suspend fun run(track: TrackResultDto) {
        val key = track.key
        try {
            update(key) { it.copy(state = DownloadState.DOWNLOADING) }
            val stream = resolve(track)
            val dir = dirProvider().apply { mkdirs() }
            val base = "${track.source}_${track.sourceId.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
            val file = if (stream.isHls) {
                File(dir, "$base.m4a").also { transcodeHls(stream.url, stream.httpHeaders, it, key) }
            } else {
                downloadHttp(stream.url, stream.httpHeaders, dir, base, key)
            }
            update(key) { it.copy(state = DownloadState.COMPLETED, progressPct = 100, filePath = file.path, totalBytes = file.length(), bytesDownloaded = file.length()) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            update(key) { it.copy(state = DownloadState.FAILED, error = e.message ?: "Download fehlgeschlagen") }
        } finally {
            jobs.remove(key)
        }
    }

    private suspend fun downloadHttp(url: String, headers: Map<String, String>, dir: File, base: String, key: String): File {
        val part = File(dir, "$base.part")
        val have = if (part.isFile) part.length() else 0L
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (have > 0) header("Range", "bytes=$have-")
        }.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val append = resp.code == 206
            val body = resp.body
            val total = (body.contentLength().takeIf { it > 0 }?.let { if (append) it + have else it })
            val ext = extensionFor(resp.header("Content-Type"), url)
            RandomAccessFile(part, "rw").use { out ->
                if (append) out.seek(have) else out.setLength(0)
                var done = if (append) have else 0L
                val buf = ByteArray(64 * 1024)
                var lastPct = -1
                body.byteStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = total?.let { (done * 100 / it).toInt() } ?: 0
                        if (pct != lastPct) { lastPct = pct; update(key) { it.copy(progressPct = pct, bytesDownloaded = done, totalBytes = total) } }
                    }
                }
            }
            val target = File(dir, "$base.$ext")
            if (!part.renameTo(target)) error("Datei konnte nicht gespeichert werden")
            return target
        }
    }

    private suspend fun transcodeHls(url: String, headers: Map<String, String>, target: File, key: String) {
        val g = FFmpegFrameGrabber(url).apply {
            if (headers.isNotEmpty()) setOption("headers", headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" })
            start()
        }
        val totalUs = g.lengthInTime.coerceAtLeast(1)
        val r = FFmpegFrameRecorder(target, 2).apply {
            format = "mp4"; audioCodec = avcodec.AV_CODEC_ID_AAC; audioBitrate = 192_000; sampleRate = g.sampleRate.takeIf { it > 0 } ?: 44100
            start()
        }
        try {
            while (true) {
                coroutineContext.ensureActive()
                val f = g.grabSamples() ?: break
                r.record(f)
                val pct = (g.timestamp * 100 / totalUs).toInt().coerceIn(0, 99)
                if (pct != store.download(key)?.progressPct) update(key) { it.copy(progressPct = pct) }
            }
        } finally {
            runCatching { r.close() }; runCatching { g.close() }
        }
    }

    private fun update(key: String, f: (DownloadRecord) -> DownloadRecord) {
        store.download(key)?.let { store.upsertDownload(f(it)) }
    }

    companion object {
        fun extensionFor(contentType: String?, url: String): String {
            val ct = contentType?.substringBefore(';')?.trim()?.lowercase()
            return when {
                ct == "audio/mpeg" || ct == "audio/mp3" -> "mp3"
                ct == "audio/mp4" || ct == "audio/x-m4a" || ct == "audio/aac" -> "m4a"
                ct == "audio/webm" || ct == "video/webm" -> "webm"
                ct == "audio/ogg" || ct == "audio/opus" -> "opus"
                url.contains("mime=audio%2Fwebm") -> "webm"
                url.contains("mime=audio%2Fmp4") -> "m4a"
                url.substringBefore('?').endsWith(".mp3") -> "mp3"
                else -> "m4a"
            }
        }
    }
}
