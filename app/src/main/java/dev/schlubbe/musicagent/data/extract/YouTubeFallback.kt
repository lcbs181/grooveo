package dev.schlubbe.musicagent.data.extract

import android.util.Log
import dev.schlubbe.musicagent.data.extract.youtube.YouTubeMusicSearchClient
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val TAG = "YouTubeFallback"
private const val CANDIDATES = 8
private const val MAX_DURATION_DIFF_SEC = 20
private val VARIANT = Regex("""(?i)\b(remix|edit|live|version|sped|slowed|reverb|cover|instrumental|acoustic|karaoke|mix)\b""")

/** Finds the YouTube Music equivalent of a track that SoundCloud won't play in
 * full (DRM-only or a 30s Go+ preview) - mainstream label releases are mostly one
 * or the other there, while YouTube Music carries the same recording. A candidate
 * must share the core title and, when both lengths are known, run within
 * [MAX_DURATION_DIFF_SEC] of the original, so a remix or live version isn't
 * silently swapped in. */
@Singleton
class YouTubeFallback @Inject constructor(
    private val youTube: YouTubeMusicSearchClient,
) {
    private val cache = object : LinkedHashMap<String, TrackResultDto?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackResultDto?>) = size > 200
    }

    suspend fun findReplacement(title: String, artist: String?, durationSec: Int?): TrackResultDto? {
        val coreTitle = normalize(stripDecorations(title))
        if (coreTitle.isBlank()) return null
        val query = listOfNotNull(artist?.takeIf { it.isNotBlank() }, stripDecorations(title)).joinToString(" ")
        val cacheKey = "$query|$durationSec"
        synchronized(cache) { if (cache.containsKey(cacheKey)) return cache[cacheKey] }

        val candidates = runCatching { youTube.search(query, CANDIDATES) }
            .onFailure { Log.w(TAG, "search failed for '$query'", it) }
            .getOrNull()
            ?: return null
        val originalVariant = VARIANT.containsMatchIn(title)
        val match = candidates
            .mapNotNull { candidate ->
                val candidateTitle = normalize(stripDecorations(candidate.title))
                if (candidateTitle.isBlank()) return@mapNotNull null
                if (!candidateTitle.contains(coreTitle) && !coreTitle.contains(candidateTitle)) return@mapNotNull null
                val diff = if (durationSec != null && candidate.durationSec != null) {
                    abs(candidate.durationSec - durationSec)
                } else {
                    null
                }
                if (diff != null && diff > MAX_DURATION_DIFF_SEC) return@mapNotNull null
                // Lower is better: a confirmed length match beats an unknown length, and a
                // remix/edit/live version only wins when the original is one as well.
                var score = diff ?: (MAX_DURATION_DIFF_SEC + 1)
                if (!originalVariant && VARIANT.containsMatchIn(candidate.title)) score += 100
                candidate to score
            }
            .minByOrNull { it.second }
            ?.first
        synchronized(cache) { cache[cacheKey] = match }
        Log.d(TAG, "replacement for '$query': ${match?.let { "${it.title} (${it.sourceId})" } ?: "none"}")
        return match
    }

    private fun stripDecorations(title: String): String =
        title.replace(Regex("""[(\[].*?[)\]]"""), " ")
            .replace(Regex("""(?i)\b(feat\.?|ft\.?|official (music )?video|lyrics?)\b.*"""), " ")
            .trim()

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
}
