package dev.schlubbe.musicagent.data.extract

import android.util.Log
import dev.schlubbe.musicagent.data.extract.youtube.YouTubeMusicSearchClient
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val TAG = "YouTubeFallback"
private const val CANDIDATES = 15
private const val MAX_DURATION_DIFF_SEC = 20
private const val MAX_DURATION_DIFF_STRONG_SEC = 45
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
        val fullTitle = normalize(title)
        val cleanArtist = artist?.let(::cleanArtist)?.takeIf { it.isNotBlank() }
        val cacheKey = "$title|$artist|$durationSec"
        synchronized(cache) { if (cache.containsKey(cacheKey)) return cache[cacheKey] }

        // Artist + title first; then the full title alone - SoundCloud "artists" are
        // often uploader accounts ("HardDriverMusic") that YouTube doesn't know.
        val queries = listOfNotNull(
            cleanArtist?.let { "$it ${stripDecorations(title)}" },
            title.replace(Regex("""(?i)\b(feat\.?|ft\.?)\b.*"""), " ").trim(),
        ).distinct()
        var match: TrackResultDto? = null
        // Last resort: plain video search, for releases that only exist as a label
        // channel's video. Same matching rules, so a fan upload of another song
        // still can't win.
        val attempts = queries.map { it to false } + (queries.first() to true)
        for ((query, videos) in attempts) {
            val candidates = runCatching {
                if (videos) youTube.searchVideos(query, CANDIDATES) else youTube.search(query, CANDIDATES)
            }
                .onFailure { Log.w(TAG, "search failed for '$query'", it) }
                .getOrNull() ?: continue
            match = pick(candidates, title, coreTitle, fullTitle, durationSec, cleanArtist)
            if (match == null) {
                Log.d(TAG, "no match among: " + candidates.joinToString { "${it.title} / ${it.artist} / ${it.durationSec}s" } + " (want ${durationSec}s)")
            }
            Log.d(TAG, "replacement for '$query': ${match?.let { "${it.title} (${it.sourceId})" } ?: "none"}")
            if (match != null) break
        }
        synchronized(cache) { cache[cacheKey] = match }
        return match
    }

    private fun pick(
        candidates: List<TrackResultDto>,
        title: String,
        coreTitle: String,
        fullTitle: String,
        durationSec: Int?,
        cleanArtist: String?,
    ): TrackResultDto? {
        val wantArtist = cleanArtist?.let(::normalize)?.replace(" ", "")
        val originalVariant = VARIANT.containsMatchIn(title)
        return candidates
            .mapNotNull { candidate ->
                val candidateTitle = normalize(stripDecorations(candidate.title))
                if (candidateTitle.isBlank()) return@mapNotNull null
                // Candidate must contain the title. The reverse (candidate shorter,
                // contained in ours) only when nearly as long - otherwise "Rise" matched
                // "Rise Again" and a different song played.
                val contains = candidateTitle.contains(coreTitle) ||
                    (coreTitle.contains(candidateTitle) && candidateTitle.length >= coreTitle.length * 0.8)
                if (!contains) return@mapNotNull null
                // The whole original title, subtitle included ("... (Defqon.1 2022
                // Closing Theme)"), appearing in the candidate is strong evidence it is
                // the same recording - allow a longer gap for intro/outro differences
                // between a SoundCloud upload and the official video.
                val strong = fullTitle.length > coreTitle.length && normalize(candidate.title).contains(fullTitle)
                // Same title alone isn't enough ("Rise Again" by someone else): the
                // artist has to match too, unless the full subtitle matched.
                val candidateArtist = normalize(candidate.artist.orEmpty()).replace(" ", "")
                val artistOk = wantArtist.isNullOrEmpty() || candidateArtist.isEmpty() ||
                    candidateArtist.contains(wantArtist) || wantArtist.contains(candidateArtist) ||
                    normalize(candidate.title).replace(" ", "").contains(wantArtist)
                if (!artistOk && !strong) return@mapNotNull null
                val maxDiff = if (strong) MAX_DURATION_DIFF_STRONG_SEC else MAX_DURATION_DIFF_SEC
                val diff = if (durationSec != null && candidate.durationSec != null) {
                    abs(candidate.durationSec - durationSec)
                } else {
                    null
                }
                if (diff != null && diff > maxDiff) return@mapNotNull null
                // Lower is better: a confirmed length match beats an unknown length, and a
                // remix/edit/live version only wins when the original is one as well.
                var score = diff ?: (MAX_DURATION_DIFF_SEC + 1)
                if (!originalVariant && VARIANT.containsMatchIn(candidate.title)) score += 100
                candidate to score
            }
            .minByOrNull { it.second }
            ?.first
    }

    /** "HardDriverMusic" -> "Hard Driver", "SomeArtistOfficial" -> "Some Artist". */
    private fun cleanArtist(artist: String): String =
        artist.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .replace(Regex("""(?i)\b(music|official|records|tv|vevo|topic)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

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
