package dev.schlubbe.musicagent.data.spotify

import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import java.text.Normalizer
import kotlin.math.abs

/** Picks the search hit that is really the same recording as a Spotify track.
 * Pure (no Android, no network) so the scoring is unit-tested.
 *
 * Score = 0.5 title + 0.3 artist + 0.2 duration, minus a penalty when the hit is a
 * variant (live, remix, sped up, ...) the Spotify title does not ask for. A hit is
 * only accepted with a title similarity of at least [MIN_TITLE] and a total of at
 * least [MIN_SCORE]; otherwise the track counts as "nicht gefunden" rather than
 * importing a wrong song. */
object TrackMatcher {

    const val MIN_TITLE = 0.6
    const val MIN_SCORE = 0.6

    /** The search query for a Spotify track: its title without "- Remastered 2011"
     * or "(feat. X)" noise, plus the first artist. */
    fun query(track: SpotifyTrack): String =
        listOfNotNull(coreTitleRaw(track.title).ifBlank { track.title }, track.artists.firstOrNull())
            .joinToString(" ")

    fun best(track: SpotifyTrack, candidates: List<TrackResultDto>): TrackResultDto? {
        var best: TrackResultDto? = null
        var bestScore = -1.0
        for (c in candidates) {
            val s = score(track, c) ?: continue
            if (s > bestScore) { best = c; bestScore = s }
        }
        return best
    }

    /** Null when [candidate] is not acceptable at all, else its score. */
    fun score(track: SpotifyTrack, candidate: TrackResultDto): Double? {
        val srcFull = normalize(track.title)
        val srcCore = normalize(coreTitleRaw(track.title))
        val candFull = normalize(candidate.title)
        val candCore = normalize(coreTitleRaw(candidate.title))
        val candArtist = normalizeArtist(candidate.artist.orEmpty())
        val artists = track.artists.map(::normalizeArtist).filter { it.isNotEmpty() }

        // YouTube uploads often read "Artist - Title": drop a leading artist so the
        // title is compared with the title.
        val candCoreNoArtist = artists.firstOrNull { candCore.startsWith("$it ") }
            ?.let { candCore.removePrefix(it).trim() } ?: candCore

        val title = maxOf(
            similarity(srcCore, candCore),
            similarity(srcCore, candCoreNoArtist),
            similarity(srcFull, candFull),
        )
        if (title < MIN_TITLE) return null

        val artist = when {
            artists.isEmpty() -> 0.5
            artists.any { it.isNotEmpty() && (candArtist.contains(it) || (candArtist.isNotEmpty() && it.contains(candArtist))) } -> 1.0
            artists.any { candFull.contains(it) } -> 0.8
            else -> artists.maxOf { similarity(it, candArtist) }
        }

        val duration = durationScore(track.durationMs?.let { (it / 1000).toInt() }, candidate.durationSec) ?: return null

        val penalty = VARIANT_WORDS.count { w -> containsWord(candFull, w) && !containsWord(srcFull, w) } * 0.25
        val total = 0.5 * title + 0.3 * artist + 0.2 * duration - penalty
        return total.takeIf { it >= MIN_SCORE }
    }

    /** 1 within ±10 s, falling to 0.3 at ±30 s, 0 beyond; null (reject) when the
     * lengths are clearly different recordings; 0.5 when a length is unknown. */
    internal fun durationScore(srcSec: Int?, candSec: Int?): Double? {
        if (srcSec == null || candSec == null || srcSec <= 0 || candSec <= 0) return 0.5
        val diff = abs(srcSec - candSec)
        return when {
            diff <= 10 -> 1.0
            diff <= 30 -> 1.0 - 0.7 * (diff - 10) / 20.0
            diff <= maxOf(90, srcSec / 3) -> 0.0
            else -> null
        }
    }

    /** Best-matching artist search hit for a followed Spotify artist, or null. */
    fun bestArtist(name: String, candidates: List<ArtistResultDto>): ArtistResultDto? {
        val target = normalizeArtist(name)
        if (target.isEmpty()) return null
        return candidates
            .map { it to similarity(target, normalizeArtist(it.name)) }
            .filter { it.second >= 0.8 }
            .maxByOrNull { it.second }
            ?.first
    }

    // — normalisation —

    /** Title before " - Remastered ...", "(feat. ...)", "[Live]" etc. */
    internal fun coreTitleRaw(title: String): String {
        var t = title
        t = t.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), " ")
        t = t.replace(Regex("\\s+-\\s+.*$"), " ")
        t = t.replace(Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE), " ")
        return t.trim()
    }

    internal fun normalize(s: String): String {
        val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return noAccents.lowercase()
            .replace("&", " and ")
            .replace("ß", "ss")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    internal fun normalizeArtist(s: String): String =
        normalize(s.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), ""))
            .removeSuffix(" vevo")
            .removeSuffix(" official")
            .trim()

    /** Dice coefficient on word sets, with a character-bigram fallback for scripts
     * without spaces (a Japanese title is one "word"). */
    internal fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val ta = a.split(' ').toSet()
        val tb = b.split(' ').toSet()
        val words = 2.0 * ta.intersect(tb).size / (ta.size + tb.size)
        val chars = bigramDice(a.replace(" ", ""), b.replace(" ", "")) * 0.95
        return maxOf(words, chars)
    }

    private fun bigramDice(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        val ba = a.windowed(2).groupingBy { it }.eachCount()
        val bb = b.windowed(2).groupingBy { it }.eachCount()
        val common = ba.entries.sumOf { (k, v) -> minOf(v, bb[k] ?: 0) }
        return 2.0 * common / (a.length - 1 + b.length - 1)
    }

    private fun containsWord(text: String, word: String): Boolean =
        Regex("(^| )${Regex.escape(word)}( |$)").containsMatchIn(text)

    private val VARIANT_WORDS = listOf(
        "live", "remix", "acoustic", "instrumental", "karaoke", "cover", "sped up",
        "slowed", "nightcore", "reverb", "8d", "extended", "piano version",
    )
}
