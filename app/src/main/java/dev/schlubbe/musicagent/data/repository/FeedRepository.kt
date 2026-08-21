package dev.schlubbe.musicagent.data.repository

import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.LikeOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.remote.dto.toTrackResultDto
import kotlinx.coroutines.flow.first
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
private const val LIKE_WEIGHT = 3
private const val PLAY_WEIGHT = 1
private const val MIX_TOP_ARTIST_COUNT = 3
private const val MIX_TRACKS_PER_ARTIST = 10

/** On-device analogue of the (removed) backend's MAYA feed: no server, no
 * cross-user data, so "familiar" comes from purely local signal (recently played +
 * liked tracks, weighted by (source, artist) frequency) instead of a per-user
 * Postgres popularity query, and "novel" comes from [SearchRepository.getTrending]
 * (global charts) instead of cross-user popularity - there's no other users to draw
 * novelty from on a backend-less, single-device app. */
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
        val topArtists = affinity.entries
            .filter { it.key.first == "ytmusic" }
            .sortedByDescending { it.value }
            .take(TOP_ARTIST_COUNT)
            .map { it.key }

        val familiarTarget = (limit * 0.7).toInt().coerceAtLeast(1)
        val seenIds = excludeIds.toMutableSet()
        val familiar = mutableListOf<FeedItem>()

        for ((source, artist) in topArtists) {
            if (familiar.size >= familiarTarget) break
            val tracks = runCatching { searchRepository.search(artist, source = source, limit = 8) }
                .getOrDefault(emptyList())
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

    private fun buildArtistAffinity(
        recentlyPlayed: List<TrackEntity>,
        likes: List<LikeOutDto>,
    ): Map<Pair<String, String>, Int> {
        val affinity = mutableMapOf<Pair<String, String>, Int>()
        recentlyPlayed.forEach { track ->
            track.artist?.let { artist ->
                val key = track.source to artist
                affinity[key] = (affinity[key] ?: 0) + PLAY_WEIGHT
            }
        }
        likes.forEach { like ->
            like.track.artist?.let { artist ->
                val key = like.track.source to artist
                affinity[key] = (affinity[key] ?: 0) + LIKE_WEIGHT
            }
        }
        return affinity
    }

    // LikeOutDto's embedded TrackOutDto carries no genre field (it's a denormalized
    // snapshot with its own separate schema, see LocalTrackEntity) - only the tracks
    // cache table does, so likes don't contribute here yet.
    private fun buildGenreAffinity(recentlyPlayed: List<TrackEntity>): Map<String, Int> {
        val genreCounts = mutableMapOf<String, Int>()
        recentlyPlayed.forEach { track ->
            track.genre?.let { genre -> genreCounts[genre] = (genreCounts[genre] ?: 0) + PLAY_WEIGHT }
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
