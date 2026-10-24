package dev.schlubbe.musicagent.data.recommend

import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import kotlin.random.Random

/** A track some source suggested, with how high it ranked there (0 = top). */
data class Candidate(val track: TrackResultDto, val rank: Int, val origin: Origin)

/** Where a candidate came from; radio of what just played fits best. */
enum class Origin(val relevance: Double) {
    /** The source's own radio/related list for a seed track. */
    RELATED(1.0),
    /** More by an artist of the session. */
    SESSION_ARTIST(0.7),
    /** More by an artist the listener likes in general. */
    AFFINITY(0.5),
    /** The listener's own downloads/likes, not heard for a while. */
    LIBRARY(0.45),
    /** Charts: only when nothing better is there. */
    TRENDING(0.15),
}

/**
 * Turns candidates from several sources into a playlist that fits what was just heard,
 * leaves out what the listener skips, and spreads artists out so it does not sound like
 * the same artist on repeat:
 *
 *   score = 0.45 rel + 0.20 aff + 0.10 sess + fresh + 0.05 pop - 0.40 skip - 0.30 replay + noise
 *
 * rel is the origin's relevance, raised when several seeds suggest the same track; aff
 * the listener's decayed taste for the artist; sess a match with the session's artists;
 * fresh a small bonus for an artist never heard (more while there is little history);
 * pop how high the source ranked it. Then a greedy re-rank (MMR) keeps at most one track
 * per artist in any 3 and two in any 10, and drops re-uploads of the same song.
 */
class Recommender(private val signals: PlaybackSignals, private val random: Random = Random.Default) {

    fun rank(
        candidates: List<Candidate>,
        sessionArtists: Set<String>,
        excludeIds: Set<String>,
        recentArtists: List<String>,
        limit: Int,
    ): List<TrackResultDto> {
        val session = sessionArtists.map(PlaybackSignals::normalizeArtist).toSet()
        val maxAff = signals.artistAffinity.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val recentlyPlayed = signals.playedWithin(REPLAY_WINDOW_MS)

        // merge: one entry per track and per song (re-uploads of a title collapse)
        val merged = LinkedHashMap<String, MutableList<Candidate>>()
        val seenSongs = HashMap<String, String>()
        for (c in candidates) {
            val id = c.track.id
            if (id in excludeIds || id in signals.skippedTracks || !isMusic(c.track)) continue
            val song = songKey(c.track)
            val key = seenSongs.getOrPut(song) { id }
            merged.getOrPut(key) { mutableListOf() } += c
        }

        val scored = merged.values.map { group ->
            val best = group.maxBy { it.origin.relevance / (1 + it.rank / 10.0) }
            val artist = PlaybackSignals.normalizeArtist(best.track.artist.orEmpty())
            val rel = (best.origin.relevance / (1 + best.rank / 10.0) + 0.15 * (group.size - 1)).coerceAtMost(1.0)
            val aff = signals.artistAffinity[artist]?.let { (it / maxAff).coerceIn(-1.0, 1.0) } ?: 0.0
            val sess = if (artist in session) 1.0 else 0.0
            val fresh = if (artist !in signals.artistAffinity) (if (signals.isColdStart) 0.15 else 0.08) else 0.0
            val pop = 1.0 / (1 + best.rank)
            val replay = if (best.track.id in recentlyPlayed) 1.0 else 0.0
            val score = 0.45 * rel + 0.20 * aff + 0.10 * sess + fresh + 0.05 * pop -
                0.40 * signals.skipRatio(best.track.artist) - 0.30 * replay + (random.nextDouble() - 0.5) * 0.1
            Scored(best.track, artist, score)
        }
        return rerank(scored, recentArtists.map(PlaybackSignals::normalizeArtist), limit)
    }

    private class Scored(val track: TrackResultDto, val artist: String, val score: Double)

    /** Greedy MMR with hard artist-spacing rules; relaxes them only when nothing else is left. */
    private fun rerank(items: List<Scored>, recentArtists: List<String>, limit: Int): List<TrackResultDto> {
        val pool = items.sortedByDescending { it.score }.toMutableList()
        val out = mutableListOf<Scored>()
        val history = recentArtists.reversed().toMutableList() // oldest first, like out
        while (out.size < limit && pool.isNotEmpty()) {
            val window = (history + out.map { it.artist })
            fun allowed(s: Scored) = s.artist.isEmpty() ||
                (window.takeLast(2).none { it == s.artist } && window.takeLast(9).count { it == s.artist } < 2)
            val pick = pool.maxByOrNull { s ->
                val sim = if (window.takeLast(5).contains(s.artist)) 1.0 else 0.0
                if (allowed(s)) LAMBDA * s.score - (1 - LAMBDA) * sim else Double.NEGATIVE_INFINITY
            }?.takeIf { allowed(it) } ?: pool.first()
            pool.remove(pick)
            out += pick
        }
        return out.map { it.track }
    }

    companion object {
        const val LAMBDA = 0.7
        const val REPLAY_WINDOW_MS = 3L * 24 * 3600_000
        private val DECORATION = Regex("""[(\[][^)\]]*(official|video|audio|lyric|visuali[sz]er|hd|4k|remaster)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
        private val NOT_MUSIC = Regex("""\b(1|one|10|2|3) ?hours?\b|\bloop(ed)?\b|type beat|\b8d\b|nightcore|sped up|slowed|full album|\bmix 20\d\d\b""", RegexOption.IGNORE_CASE)

        private val TrackResultDto.id get() = "$source:$sourceId"

        /** [tracks] in order, but no artist twice in a row where another track can go
         * between - for shuffled mixes, which otherwise clump one uploader together. */
        fun spreadArtists(tracks: List<TrackResultDto>): List<TrackResultDto> {
            val pool = tracks.toMutableList()
            val out = ArrayList<TrackResultDto>(tracks.size)
            while (pool.isNotEmpty()) {
                val last = out.lastOrNull()?.artist?.let(PlaybackSignals::normalizeArtist)
                val i = pool.indexOfFirst { it.artist == null || PlaybackSignals.normalizeArtist(it.artist) != last }.coerceAtLeast(0)
                out += pool.removeAt(i)
            }
            return out
        }

        /** Same song regardless of upload: artist and title without "(Official Video)" and co. */
        fun songKey(t: TrackResultDto): String {
            val artist = PlaybackSignals.normalizeArtist(t.artist.orEmpty())
            var title = t.title.replace(DECORATION, "").lowercase()
            // "Artist - Title" uploads repeat the artist in the title
            if (title.startsWith("$artist - ")) title = title.removePrefix("$artist - ")
            return artist + "\u0000" + title.replace(Regex("""\s+"""), " ").trim()
        }

        /** Hour-long loops, type beats, "8D"/nightcore/sped-up edits and whole albums are not a next song. */
        fun isMusic(t: TrackResultDto): Boolean {
            val d = t.durationSec
            if (d != null && (d < 45 || d > 15 * 60)) return false
            return !NOT_MUSIC.containsMatchIn(t.title)
        }
    }
}
