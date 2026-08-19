package dev.schlubbe.musicagent.data.extract

import android.util.Log
import dev.schlubbe.musicagent.data.extract.soundcloud.SoundCloudDrmOnlyException
import dev.schlubbe.musicagent.data.extract.soundcloud.SoundCloudStreamResolver
import dev.schlubbe.musicagent.data.extract.youtube.YouTubeStreamResolver
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StreamResolverRegistry"

// On-device stream resolution talks directly to SoundCloud's/YouTube's own endpoints
// instead of one stable backend -- firing many resolves at once (a big search-result
// queue, or a whole playlist download) can trip their rate limiting for a subset of
// requests. This cap + retry is centralized here (not in PlayerController alone)
// specifically so DownloadWorker's playlist-download path is covered too, not just
// playback.
private const val MAX_CONCURRENT_RESOLVES = 4

/** Picks the right [StreamResolver] for a track's source — used by
 * [dev.schlubbe.musicagent.playback.PlayerController] and
 * [dev.schlubbe.musicagent.download.DownloadWorker] instead of the (removed)
 * backend's /stream/{source}/{id} proxy. */
@Singleton
class StreamResolverRegistry @Inject constructor(
    private val soundCloud: SoundCloudStreamResolver,
    private val youTube: YouTubeStreamResolver,
) {
    private val semaphore = Semaphore(MAX_CONCURRENT_RESOLVES)

    /** Resolves [source]/[sourceId], bounded by [MAX_CONCURRENT_RESOLVES] and retried
     * once on failure — on-device extraction is more prone to transient failures
     * (rate limiting, a momentary network hiccup, SoundCloud's client_id rotating
     * mid-request) than the old stable backend was, and a single retry clears most of
     * those. Throws (same as before) if both attempts fail; logs the actual cause each
     * time so a repeat report is diagnosable from logcat. */
    suspend fun resolve(source: String, sourceId: String): ResolvedStream {
        var lastError: Throwable? = null
        for (attempt in 1..2) {
            val result = semaphore.withPermit {
                runCatching { resolveOnce(source, sourceId) }
            }
            result.onSuccess { return it }
            val error = result.exceptionOrNull()!!
            lastError = error
            Log.w(TAG, "resolve failed for $source:$sourceId (attempt $attempt/2)", error)
            // A DRM-only track will fail identically every time - retrying just
            // doubles the wait for a result that's already known.
            if (error is SoundCloudDrmOnlyException) break
        }
        throw lastError ?: IllegalStateException("resolve failed for $source:$sourceId")
    }

    private suspend fun resolveOnce(source: String, sourceId: String): ResolvedStream = when {
        soundCloud.supports(source) -> soundCloud.resolve(sourceId)
        youTube.supports(source) -> youTube.resolve(sourceId)
        else -> error("unknown source: $source")
    }
}
