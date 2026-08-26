package dev.schlubbe.musicagent.data.repository

import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.LikeOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.remote.dto.toTrackResultDto
import kotlinx.coroutines.flow.first
import kotlin.math.pow
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

data class FeedItem(val track: TrackResultDto, val reason: String)

/** Local counterpart to [dev.schlubbe.musicagent.data.remote.dto.toTrackResultDto]
 * (TrackOutDto's version) - needed here (rather than reusing HomeViewModel's private
 * copy) since [FeedRepository.getPersonalizedMix] mixes cached [TrackEntity] rows
 * straight back into its shuffle pool. */
private fun TrackEntity.toTrackResultDto(): TrackResultDto = TrackResultDto(
    source = source,
    sourceId = sourceId,
    title = title,
    artist = artist,
    album = album,
    durationSec = durationSec,
    thumbnailUrl = thumbnailUrl,
    webpageUrl = webpageUrl,
    genre = genre,
)

private const val RECENT_WINDOW = 50
private const val TOP_ARTIST_COUNT = 2
private const val LIKE_WEIGHT = 3.0
private const val PLAY_WEIGHT = 1.0
private const val MIX_TOP_ARTIST_COUNT = 3
private const val MIX_TRACKS_PER_ARTIST = 10

/** Half-life of a play's contribution to artist affinity, in milliseconds - see
 * [FeedRepository.decayedWeight]. Without this, a listening binge from six months
 * ago counted exactly as much as one from two minutes ago, so the feed stayed
 * anchored to old habits and never actually tracked what a session is doing right
 * now. A week is short enough that yesterday's session still visibly outweighs one
 * from a month back, long enough that a single day's listening doesn't erase
 * everything before it. */
private const val RECENCY_HALF_LIFE_MS = 7L * 24 * 60 * 60 * 1000

/** How many candidate artists (not just the fixed top few) go into the weighted
 * sample - see [FeedRepository.weightedSampleArtists]. Wider than the number
 * actually drawn per call, so repeat visits don't pull an identical artist set
 * every time. */
private const val CANDIDATE_ARTIST_POOL = 8

/** How many of the most-recently-played tracks count as "the session" for
 * [FeedRepository.predictNext]'s primary continuation tier. */
private const val SESSION_TAIL_SIZE = 5

/** On-device analogue of the (removed) backend's MAYA feed: no server, no
 * cross-user data, so "familiar" comes from purely local signal (recently played +
 * liked tracks, weighted by recency-decayed (source, artist) frequency - see
 * [FeedRepository.decayedWeight]) instead of a per-user Postgres popularity query,
 * and "novel" comes from [SearchRepository.getTrending] (global charts) instead of
 * cross-user popularity - there's no other users to draw novelty from on a
 * backend-less, single-device app. */
@Singleton
class FeedRepository @Inject constructor(
    private val trackDao: TrackDao,
    private val likesRepository: LikesRepository,
    private val searchRepository: SearchRepository,
) {
    suspend fun getFeed(limit: Int = 20): List<FeedItem> {
        val recentlyPlayed = trackDao.observeRecentlyPlayed(RECENT_WINDOW).first()
        val likes = runCatching { likesRepository.refresh() }.getOrDefault(emptyList())

        val excludeIds = (recentlyPlayed.map { it.id } + likes.map { it.track.id }).toSet()

        val affinity = buildArtistAffinity(recentlyPlayed, likes)
        // YouTube-Music-only, same reasoning as the "novel" half below - an
        // affinity artist discovered via SoundCloud plays/likes is excluded here
        // rather than searched on YouTube instead, since a name search on the other
        // source can easily resolve to a different, unrelated account/channel.
        val candidateArtists = affinity.entries
            .filter { it.key.first == "ytmusic" }
            .sortedByDescending { it.value }
            .take(CANDIDATE_ARTIST_POOL)
        // Weighted sampling rather than always the strict top-N: two visits in the
        // same session used to draw the exact same 2 artists every time, which is
        // both repetitive and, once their catalogs are exhausted by the exclude-set
        // below, exactly what forced an early, jarring fallback to generic trending.
        // A wider, weighted-random draw keeps the feed anchored to real affinity
        // while giving real variety a chance.
        val topArtists = weightedSampleArtists(candidateArtists, TOP_ARTIST_COUNT)

        val familiarTarget = (limit * 0.7).toInt().coerceAtLeast(1)
        val seenIds = excludeIds.toMutableSet()
        val familiar = mutableListOf<FeedItem>()

        for ((source, artist) in topArtists) {
            if (familiar.size >= familiarTarget) break
            val tracks = runCatching { searchRepository.search(artist, source = source, limit = 8) }
                .getOrDefault(emptyList())
                .filterForDiscovery()
                .filterNot { "${it.source}:${it.sourceId}" in seenIds }
            for (track in tracks) {
                if (familiar.size >= familiarTarget) break
                familiar += FeedItem(track, "Weil du $artist magst")
                seenIds += "${track.source}:${track.sourceId}"
            }
        }

        val novelTarget = limit - familiar.size
        val novel = if (novelTarget > 0) {
            // YouTube-Music-only, same reasoning as HomeViewModel's Charts shelf
            // (which shares this exact call) - see loadCharts()'s comment.
            // getTrending already runs filterForDiscovery internally.
            runCatching { searchRepository.getTrending(novelTarget * 2, source = "ytmusic") }.getOrDefault(emptyList())
                .filterNot { "${it.source}:${it.sourceId}" in seenIds }
                .take(novelTarget)
                .map { FeedItem(it, "Gerade beliebt") }
        } else {
            emptyList()
        }

        return diversify(interleave(familiar, novel))
    }

    /** Powers Home's "Deine Mixes" personalized "Fokus-Mix" card (formerly "Im
     * Fokus"'s "Dein Mix" card, moved so "Im Fokus" can go back to pure
     * editorial/curated content - see HomeViewModel.loadMixCards) - same
     * on-device, no-server-signal constraint as [getFeed] (recently played +
     * likes, weighted the
     * same way), but tuned for a shuffle-play *pool* rather than a ranked, reasoned
     * list: more top artists, more tracks per artist, and the user's own
     * history/likes mixed back in rather than excluded, since "a mix of what I
     * already like" is exactly the point (unlike the feed's "something new" half).
     * Both sources contribute (getFeed's ytmusic-only restriction is about keeping a
     * *reason* like "Weil du X magst" trustworthy - a shuffle pool has no such
     * per-item reason to get wrong). Returns an empty pool when there's no history/
     * likes to build from at all, so the caller can hide the card instead of showing
     * an unlabeled shuffle of nothing. */
    suspend fun getPersonalizedMix(limit: Int = 40): List<TrackResultDto> {
        val recentlyPlayed = trackDao.observeRecentlyPlayed(RECENT_WINDOW).first()
        val likes = runCatching { likesRepository.refresh() }.getOrDefault(emptyList())
        if (recentlyPlayed.isEmpty() && likes.isEmpty()) return emptyList()

        val affinity = buildArtistAffinity(recentlyPlayed, likes)
        val topArtists = affinity.entries
            .sortedByDescending { it.value }
            .take(MIX_TOP_ARTIST_COUNT)
            .map { it.key }

        val knownIds = (recentlyPlayed.map { it.id } + likes.map { it.track.id }).toSet()
        val discovered = mutableListOf<TrackResultDto>()
        for ((source, artist) in topArtists) {
            val tracks = runCatching { searchRepository.search(artist, source = source, limit = MIX_TRACKS_PER_ARTIST) }
                .getOrDefault(emptyList())
                .filterForDiscovery()
                .filterNot { "${it.source}:${it.sourceId}" in knownIds }
            discovered += tracks
        }

        val pool = (
            discovered +
                likes.map { it.track.toTrackResultDto() } +
                recentlyPlayed.map { it.toTrackResultDto() }
            ).distinctBy { "${it.source}:${it.sourceId}" }

        // Quiet genre boost (see TrackEntity.genre - SoundCloud-only, null until a
        // track carrying it has actually been played/liked) - tracks matching the
        // user's single most-played genre float to the front of the pool rather than
        // filtering anything out, since genre coverage is partial by design.
        val topGenre = buildGenreAffinity(recentlyPlayed).maxByOrNull { it.value }?.key
        val ranked = if (topGenre != null) pool.sortedByDescending { it.genre == topGenre } else pool

        return ranked.take(limit)
    }

    /**
     * The actual "what plays next" engine: extends a queue that has run out,
     * instead of letting playback simply stop.
     *
     * Scored overwhelmingly by *session* context - [recentQueueTracks], the tracks
     * that were actually just playing, most recent first - rather than lifetime
     * affinity. That is the direct fix for "the next songs should be the next
     * songs after wherever I started": once an explicit queue (a playlist, an
     * artist's tracks, a search result list) runs out, the natural continuation is
     * more of *that* - the artists of what was just heard - not a jump back to
     * generic all-time favourites or unrelated global trending. Long-term affinity
     * ([buildArtistAffinity]) is only the second tier, for when the session itself
     * doesn't supply enough artists to fill the request; global trending is the
     * last resort, so this never simply stops.
     *
     * [excludeIds] should cover the whole queue that led here (not just
     * [recentQueueTracks]) so a long autoplay run doesn't loop the same handful of
     * tracks. */
    suspend fun predictNext(
        recentQueueTracks: List<TrackResultDto>,
        excludeIds: Set<String>,
        limit: Int,
    ): List<TrackResultDto> {
        val seen = excludeIds.toMutableSet()
        val result = mutableListOf<TrackResultDto>()

        // Tier 1: the session itself. Weighted so the most recently played tracks'
        // artists dominate - the tail's first entry counts several times more than
        // its last - because "next" should feel like a direct continuation of what
        // was *just* on, not an even blend of the whole queue.
        val sessionArtists = LinkedHashMap<Pair<String, String>, Double>()
        recentQueueTracks.take(SESSION_TAIL_SIZE).forEachIndexed { i, track ->
            val artist = track.artist ?: return@forEachIndexed
            val key = track.source to artist
            val recencyBoost = (SESSION_TAIL_SIZE - i).toDouble()
            sessionArtists[key] = (sessionArtists[key] ?: 0.0) + recencyBoost
        }
        for ((source, artist) in sessionArtists.entries.sortedByDescending { it.value }.map { it.key }) {
            if (result.size >= limit) break
            val tracks = runCatching { searchRepository.search(artist, source = source, limit = 10) }
                .getOrDefault(emptyList())
                .filterForDiscovery()
                .filterNot { "${it.source}:${it.sourceId}" in seen }
            for (track in tracks) {
                if (result.size >= limit) break
                result += track
                seen += "${track.source}:${track.sourceId}"
            }
        }

        // Tier 2: long-term affinity, only to top up what the session couldn't fill.
        if (result.size < limit) {
            val recentlyPlayed = trackDao.observeRecentlyPlayed(RECENT_WINDOW).first()
            val likes = runCatching { likesRepository.refresh() }.getOrDefault(emptyList())
            val affinity = buildArtistAffinity(recentlyPlayed, likes)
            val candidateArtists = affinity.entries.sortedByDescending { it.value }.take(CANDIDATE_ARTIST_POOL)
            for ((source, artist) in weightedSampleArtists(candidateArtists, TOP_ARTIST_COUNT + 1)) {
                if (result.size >= limit) break
                val tracks = runCatching { searchRepository.search(artist, source = source, limit = 8) }
                    .getOrDefault(emptyList())
                    .filterForDiscovery()
                    .filterNot { "${it.source}:${it.sourceId}" in seen }
                for (track in tracks) {
                    if (result.size >= limit) break
                    result += track
                    seen += "${track.source}:${track.sourceId}"
                }
            }
        }

        // Tier 3: never just stop. getTrending already runs filterForDiscovery.
        if (result.size < limit) {
            val trending = runCatching { searchRepository.getTrending((limit - result.size) * 2, source = "ytmusic") }
                .getOrDefault(emptyList())
                .filterNot { "${it.source}:${it.sourceId}" in seen }
                .take(limit - result.size)
            result += trending
        }

        return diversifyTracks(result)
    }

    private fun buildArtistAffinity(
        recentlyPlayed: List<TrackEntity>,
        likes: List<LikeOutDto>,
    ): Map<Pair<String, String>, Double> {
        val nowMs = System.currentTimeMillis()
        val affinity = mutableMapOf<Pair<String, String>, Double>()
        recentlyPlayed.forEach { track ->
            track.artist?.let { artist ->
                val key = track.source to artist
                val weight = PLAY_WEIGHT * decayedWeight(track.lastAccessedAt, nowMs)
                affinity[key] = (affinity[key] ?: 0.0) + weight
            }
        }
        // Likes carry no timestamp (LikeOutDto has none - see its own model), so
        // they are undecayed on purpose: a like is a durable, deliberate preference
        // rather than a passive play, and shouldn't quietly age out the way idle
        // listening does.
        likes.forEach { like ->
            like.track.artist?.let { artist ->
                val key = like.track.source to artist
                affinity[key] = (affinity[key] ?: 0.0) + LIKE_WEIGHT
            }
        }
        return affinity
    }

    /** Exponential recency decay: 1.0 for a play right now, 0.5 at
     * [RECENCY_HALF_LIFE_MS] ago, 0.25 at twice that, and so on. This is the whole
     * fix for the feed only ever reflecting lifetime totals - without it a binge
     * from months back outweighs an entire session happening right now. */
    private fun decayedWeight(playedAtMs: Long, nowMs: Long): Double {
        val ageMs = (nowMs - playedAtMs).coerceAtLeast(0L)
        return 0.5.pow(ageMs.toDouble() / RECENCY_HALF_LIFE_MS)
    }

    /** Draws [count] artists from [candidates] without replacement, biased by their
     * affinity weight rather than always taking the strict top-N. Each pick removes
     * its artist and renormalizes over what's left, so a clearly dominant artist
     * still shows up most of the time - this is variety on top of relevance, not
     * instead of it. */
    private fun weightedSampleArtists(
        candidates: List<Map.Entry<Pair<String, String>, Double>>,
        count: Int,
    ): List<Pair<String, String>> {
        if (candidates.isEmpty()) return emptyList()
        val pool = candidates.map { it.key to it.value.coerceAtLeast(0.01) }.toMutableList()
        val picked = mutableListOf<Pair<String, String>>()
        repeat(count.coerceAtMost(pool.size)) {
            val total = pool.sumOf { it.second }
            var roll = Random.nextDouble() * total
            var index = pool.lastIndex
            for (i in pool.indices) {
                roll -= pool[i].second
                if (roll <= 0.0) {
                    index = i
                    break
                }
            }
            picked += pool[index].first
            pool.removeAt(index)
        }
        return picked
    }

    // LikeOutDto's embedded TrackOutDto carries no genre field (it's a denormalized
    // snapshot with its own separate schema, see LocalTrackEntity) - only the tracks
    // cache table does, so likes don't contribute here yet.
    private fun buildGenreAffinity(recentlyPlayed: List<TrackEntity>): Map<String, Int> {
        val genreCounts = mutableMapOf<String, Int>()
        recentlyPlayed.forEach { track ->
            track.genre?.let { genre -> genreCounts[genre] = (genreCounts[genre] ?: 0) + PLAY_WEIGHT.toInt() }
        }
        return genreCounts
    }

    private fun interleave(primary: List<FeedItem>, secondary: List<FeedItem>): List<FeedItem> {
        if (secondary.isEmpty()) return primary
        if (primary.isEmpty()) return secondary

        val step = (primary.size / secondary.size).coerceAtLeast(1)
        val result = mutableListOf<FeedItem>()
        val secondaryIter = secondary.iterator()
        primary.forEachIndexed { i, item ->
            result += item
            if ((i + 1) % step == 0 && secondaryIter.hasNext()) result += secondaryIter.next()
        }
        while (secondaryIter.hasNext()) result += secondaryIter.next()
        return result
    }

    /** No two same-artist tracks back to back, even if that means demoting a
     * higher-ranked pick by a couple of slots - same idea as the backend's feed. */
    private fun diversify(items: List<FeedItem>): List<FeedItem> {
        val result = items.toMutableList()
        for (i in 1 until result.size) {
            if (result[i].track.artist != null && result[i].track.artist == result[i - 1].track.artist) {
                for (j in i + 1 until result.size) {
                    if (result[j].track.artist != result[i - 1].track.artist) {
                        val tmp = result[i]
                        result[i] = result[j]
                        result[j] = tmp
                        break
                    }
                }
            }
        }
        return result
    }

    /** Same rule as [diversify], for a plain track list rather than [FeedItem]. */
    private fun diversifyTracks(items: List<TrackResultDto>): List<TrackResultDto> {
        val result = items.toMutableList()
        for (i in 1 until result.size) {
            if (result[i].artist != null && result[i].artist == result[i - 1].artist) {
                for (j in i + 1 until result.size) {
                    if (result[j].artist != result[i - 1].artist) {
                        val tmp = result[i]
                        result[i] = result[j]
                        result[j] = tmp
                        break
                    }
                }
            }
        }
        return result
    }
}
