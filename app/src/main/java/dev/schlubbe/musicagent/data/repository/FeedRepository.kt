package dev.schlubbe.musicagent.data.repository

import dev.schlubbe.musicagent.data.local.dao.PlayEventDao
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.recommend.Candidate
import dev.schlubbe.musicagent.data.recommend.EndReason
import dev.schlubbe.musicagent.data.recommend.Origin
import dev.schlubbe.musicagent.data.recommend.PlaybackSignals
import dev.schlubbe.musicagent.data.recommend.Recommender
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.LikeOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.remote.dto.toTrackResultDto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.pow
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

data class FeedItem(val track: TrackResultDto, val reason: String)

data class PersonalShelves(val onRepeat: List<TrackResultDto>, val rediscover: List<TrackResultDto>)

/** [isTasteBased] is true only while [FeedRepository.getFeed] had no real play/
 * like history to work from and fell back to onboarding's genre/artist picks
 * instead of local affinity - see that function. Lets HomeViewModel show an
 * honest "Mehr für dich" subline instead of always claiming it's calculated
 * from device history. */
data class FeedResult(val items: List<FeedItem>, val isTasteBased: Boolean)

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

/** How many unskipped recent tracks seed the sources' radio. */
private const val SEED_COUNT = 2

/** How long [FeedRepository.predictNext] waits for any one source. */
private const val SOURCE_TIMEOUT_MS = 6_000L

/** A liked track comes back as a radio pick only after this long unheard. */
private const val LIBRARY_REST_MS = 14L * 24 * 3600_000

/** Recently heard-out tracks whose song radio fills "Mehr für dich". */
private const val DISCOVERY_SEEDS = 2

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
    private val playEventDao: PlayEventDao,
    private val likesRepository: LikesRepository,
    private val searchRepository: SearchRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun getFeed(
        limit: Int = 20,
        preferredGenres: Set<String> = emptySet(),
        preferredArtists: List<String> = emptyList(),
    ): FeedResult {
        val recentlyPlayed = trackDao.observeRecentlyPlayed(RECENT_WINDOW).first()
        val likes = runCatching { likesRepository.refresh() }.getOrDefault(emptyList())
        val hasHistory = recentlyPlayed.isNotEmpty() || likes.isNotEmpty()

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

        // A brand new install has no history to build "familiar" from at all -
        // topArtists comes back empty, and without this the whole shelf used to
        // fall straight through to tier 2 below (ytmusic global trending), which
        // is what "Mehr für dich" was showing under a "calculated from your
        // history and likes" subtitle it hadn't earned yet. Onboarding's taste
        // picks are the honest substitute signal for exactly this case - only
        // actually claimed once they've contributed a real item, not just
        // attempted (a search that comes back empty shouldn't mislabel a shelf
        // that's 100% trending fallback underneath).
        var isTasteBased = false

        if (hasHistory) {
            for ((source, artist) in topArtists) {
                if (familiar.size >= familiarTarget) break
                val tracks = runCatching { searchRepository.search(artist, source = source, limit = 8) }
                    .getOrDefault(emptyList())
                    .filterForDiscovery(settingsRepository.contentSafetyFilterCached)
                    .filterNot { "${it.source}:${it.sourceId}" in seenIds }
                for (track in tracks) {
                    if (familiar.size >= familiarTarget) break
                    familiar += FeedItem(track, "Weil du $artist magst")
                    seenIds += "${track.source}:${track.sourceId}"
                }
            }
        } else if (preferredGenres.isNotEmpty() || preferredArtists.isNotEmpty()) {
            for (artist in preferredArtists) {
                if (familiar.size >= familiarTarget) break
                // one source: a name search across both can match a different account
                val own = runCatching { searchRepository.search(artist, source = "ytmusic", limit = 8) }
                    .getOrDefault(emptyList())
                    .filterForDiscovery(settingsRepository.contentSafetyFilterCached)
                // a few of the artist's own, then the song radio of their top track
                val similar = own.firstOrNull()?.let { top ->
                    withTimeoutOrNull(SOURCE_TIMEOUT_MS) { runCatching { searchRepository.related(top, 8) }.getOrNull() }
                }.orEmpty()
                val picks = own.take(3).map { FeedItem(it, "Weil du $artist magst") } +
                    similar.map { FeedItem(it, "Ähnlich wie $artist") }
                for (item in picks) {
                    if (familiar.size >= familiarTarget) break
                    if ("${item.track.source}:${item.track.sourceId}" in seenIds) continue
                    familiar += item
                    seenIds += "${item.track.source}:${item.track.sourceId}"
                }
            }
            for (genre in preferredGenres) {
                if (familiar.size >= familiarTarget) break
                // Already filterForDiscovery-filtered internally.
                val tracks = runCatching { searchRepository.getTrendingByGenre(genre, limit = 8) }
                    .getOrDefault(emptyList())
                    .filterNot { "${it.source}:${it.sourceId}" in seenIds }
                for (track in tracks) {
                    if (familiar.size >= familiarTarget) break
                    familiar += FeedItem(track, "Weil du auf $genre stehst")
                    seenIds += "${track.source}:${track.sourceId}"
                }
            }
            isTasteBased = familiar.isNotEmpty()
        }

        // new to the listener but fitting: song radio of what they recently heard out
        val discovery = mutableListOf<FeedItem>()
        if (hasHistory) {
            val now = System.currentTimeMillis()
            val signals = PlaybackSignals(runCatching { playEventDao.since(now - PlaybackSignals.KEEP_MS) }.getOrDefault(emptyList()), now)
            val heardOut = signals.recentTracks.filter { it.endReason == EndReason.COMPLETE.name }.map { it.trackId }
            val seeds = heardOut.mapNotNull { id -> recentlyPlayed.firstOrNull { it.id == id } }.take(DISCOVERY_SEEDS)
            for (seed in seeds) {
                val related = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                    runCatching { searchRepository.related(seed.toTrackResultDto(), 10) }.getOrNull()
                }.orEmpty()
                for (track in related) {
                    if (discovery.size >= limit - familiar.size) break
                    val id = "${track.source}:${track.sourceId}"
                    if (id in seenIds || id in signals.skippedTracks || !Recommender.isMusic(track)) continue
                    discovery += FeedItem(track, "Passt zu ${seed.title}")
                    seenIds += id
                }
            }
        }

        val novelTarget = limit - familiar.size - discovery.size
        val novel = discovery + if (novelTarget > 0) {
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

        return FeedResult(diversify(interleave(familiar, novel)), isTasteBased)
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
     * per-item reason to get wrong). With no history/likes to build from at all,
     * falls back to onboarding's genre/artist picks (same substitute signal as
     * [getFeed]'s own no-history branch) rather than an empty pool, so a brand
     * new user still gets a "Fokus-Mix" card instead of Home just not showing
     * one; only truly empty (no history *and* no picks) hides the card. */
    suspend fun getPersonalizedMix(
        limit: Int = 40,
        preferredGenres: Set<String> = emptySet(),
        preferredArtists: List<String> = emptyList(),
    ): List<TrackResultDto> {
        val recentlyPlayed = trackDao.observeRecentlyPlayed(RECENT_WINDOW).first()
        val likes = runCatching { likesRepository.refresh() }.getOrDefault(emptyList())
        if (recentlyPlayed.isEmpty() && likes.isEmpty()) {
            return getTastePool(limit, preferredGenres, preferredArtists)
        }

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
                .filterForDiscovery(settingsRepository.contentSafetyFilterCached)
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

    /** The next tracks after [recentQueueTracks] (what was just playing, most recent
     * first), for radio once a queue runs out. Candidates come from several sources at
     * once, each allowed [SOURCE_TIMEOUT_MS]:
     *  - the sources' own radio for the last tracks that were not skipped (SoundCloud
     *    related tracks, YouTube Music song radio): songs that fit, by other artists too
     *  - more by the session's artists, and by artists the listener likes overall
     *  - the listener's likes not heard for a while
     *  - charts, only to fill up
     * [Recommender] then scores them against the local listening history (skips count
     * against a track and its artist) and spreads artists out. [excludeIds] should cover
     * the whole queue, so a long radio run does not loop. Empty only when every source
     * failed, e.g. offline. */
    suspend fun predictNext(
        recentQueueTracks: List<TrackResultDto>,
        excludeIds: Set<String>,
        limit: Int,
        dislikedArtists: Set<String> = emptySet(),
    ): List<TrackResultDto> = coroutineScope {
        val now = System.currentTimeMillis()
        val signals = PlaybackSignals(
            runCatching { playEventDao.since(now - PlaybackSignals.KEEP_MS) }.getOrDefault(emptyList()),
            now,
        )
        val session = recentQueueTracks.take(SESSION_TAIL_SIZE)
        // seeds: the latest tracks the listener did not skip
        val seeds = session.filter { "${it.source}:${it.sourceId}" !in signals.skippedTracks }.take(SEED_COUNT)
            .ifEmpty { session.take(1) }
        val safe = settingsRepository.contentSafetyFilterCached

        suspend fun <T> timed(block: suspend () -> List<T>): List<T> =
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) { runCatching { block() }.getOrNull() }.orEmpty()

        val related = seeds.map { seed ->
            async { timed { searchRepository.related(seed, 20) }.mapIndexed { i, t -> Candidate(t, i, Origin.RELATED) } }
        }
        val sessionArtists = session.mapNotNull { t -> t.artist?.let { t.source to it } }.distinct().take(2)
        val bySessionArtist = sessionArtists.map { (source, artist) ->
            async {
                timed { searchRepository.search(artist, source = source, limit = 10) }
                    .filterForDiscovery(safe)
                    .filter { PlaybackSignals.normalizeArtist(it.artist.orEmpty()) == PlaybackSignals.normalizeArtist(artist) }
                    .mapIndexed { i, t -> Candidate(t, i, Origin.SESSION_ARTIST) }
            }
        }
        val likes = runCatching { likesRepository.refresh() }.getOrDefault(emptyList())
        val recentlyPlayed = trackDao.observeRecentlyPlayed(RECENT_WINDOW).first()
        val affinity = buildArtistAffinity(recentlyPlayed, likes)
            .filterKeys { (_, artist) -> signals.skipRatio(artist) < 0.5 }
        val byAffinity = weightedSampleArtists(affinity.entries.sortedByDescending { it.value }.take(CANDIDATE_ARTIST_POOL), TOP_ARTIST_COUNT)
            .map { (source, artist) ->
                async {
                    timed { searchRepository.search(artist, source = source, limit = 8) }
                        .filterForDiscovery(safe)
                        .mapIndexed { i, t -> Candidate(t, i, Origin.AFFINITY) }
                }
            }
        val heardLately = signals.playedWithin(LIBRARY_REST_MS)
        val library = likes.map { it.track.toTrackResultDto() }
            .filter { "${it.source}:${it.sourceId}" !in heardLately }
            .shuffled().take(10)
            .mapIndexed { i, t -> Candidate(t, i + 5, Origin.LIBRARY) }

        val disliked = dislikedArtists.map(PlaybackSignals::normalizeArtist).toSet()
        fun List<Candidate>.wanted() = filter { PlaybackSignals.normalizeArtist(it.track.artist.orEmpty()) !in disliked }
        val candidates = (related.awaitAll().flatten() + bySessionArtist.awaitAll().flatten() + byAffinity.awaitAll().flatten() + library).wanted()
        val recommender = Recommender(signals)
        val sessionNames = session.mapNotNull { it.artist }.toSet()
        var result = recommender.rank(candidates, sessionNames, excludeIds, session.mapNotNull { it.artist }, limit)
        if (result.size < limit) {
            val trending = timed { searchRepository.getTrending(limit * 2, source = "ytmusic") }
                .mapIndexed { i, t -> Candidate(t, i, Origin.TRENDING) }.wanted()
            result = recommender.rank(candidates + trending, sessionNames, excludeIds, session.mapNotNull { it.artist }, limit)
        }
        result
    }

    /** "Dauerschleife" (heard out three or more times this month) and "Wiederentdecken"
     * (heard out often, but not for two months) from the local listening history. */
    suspend fun personalShelves(limit: Int = 15): PersonalShelves {
        val now = System.currentTimeMillis()
        val signals = PlaybackSignals(runCatching { playEventDao.since(now - PlaybackSignals.KEEP_MS) }.getOrDefault(emptyList()), now)
        suspend fun load(ids: List<String>) = ids.take(limit).mapNotNull { id -> trackDao.getById(id)?.toTrackResultDto() }
        return PersonalShelves(onRepeat = load(signals.onRepeat), rediscover = load(signals.rediscover))
    }

    /** [getPersonalizedMix]'s no-history fallback: a shuffled pool from onboarding's
     * genre/artist picks instead of the real affinity there's nothing yet to build.
     * Empty picks (declined/skipped onboarding, or nothing set) fall through to an
     * empty pool, same as before this existed. */
    private suspend fun getTastePool(
        limit: Int,
        preferredGenres: Set<String>,
        preferredArtists: List<String>,
    ): List<TrackResultDto> {
        if (preferredGenres.isEmpty() && preferredArtists.isEmpty()) return emptyList()
        val pool = mutableListOf<TrackResultDto>()
        for (artist in preferredArtists) {
            pool += runCatching { searchRepository.search(artist, limit = MIX_TRACKS_PER_ARTIST) }.getOrDefault(emptyList())
        }
        for (genre in preferredGenres) {
            pool += runCatching { searchRepository.getTrendingByGenre(genre, limit = MIX_TRACKS_PER_ARTIST) }.getOrDefault(emptyList())
        }
        return pool.filterForDiscovery(settingsRepository.contentSafetyFilterCached)
            .distinctBy { "${it.source}:${it.sourceId}" }
            .shuffled()
            .take(limit)
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
}
