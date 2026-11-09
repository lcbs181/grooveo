package dev.schlubbe.musicagent.podcast

import dev.schlubbe.musicagent.data.local.dao.PodcastDao
import dev.schlubbe.musicagent.data.local.entity.PodcastProgressEntity
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/** One entry of the personal feed: an episode to play, or a show to discover. */
sealed class ForYouItem {
    abstract val reason: String
    abstract val score: Double

    data class Episode(val episode: PodcastEpisode, override val reason: String, override val score: Double) : ForYouItem()
    data class Show(val show: PodcastShow, override val reason: String, override val score: Double) : ForYouItem()
}

/**
 * "Hörtipps für dich": a personal feed of podcasts, Hörspiele and Hörbücher, computed on the device
 * from what is subscribed and what was listened to. Nothing about the listening leaves the
 * phone except the requests the app makes anyway (the feeds themselves, and, if allowed,
 * fyyd's list of similar podcasts for a subscribed one).
 *
 * Every show gets an affinity: subscribing counts 2, each heard episode 1 (half heard ½),
 * fading with a 30-day half-life. Then:
 * - serial shows (audiobooks, multi-part Hörspiele) offer the next part after the last one
 *   heard;
 * - other shows offer their newest unheard episodes, fading with a 7-day half-life;
 * - discovery adds shows the person does not know yet: fyyd's similar podcasts for the
 *   favourite feeds, and new ARD Hörspiele/Hörbücher when those are listened to.
 * At most two episodes per show, and every third entry is a discovery, so one show that
 * publishes daily cannot fill the feed.
 */
@Singleton
class ForYouFeed @Inject constructor(
    private val repository: PodcastRepository,
    private val dao: PodcastDao,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()
    private var cached: Triple<Long, Int, List<ForYouItem>>? = null

    /** Cached for a while, but rebuilt at once when a subscription or a heard episode changed. */
    suspend fun load(force: Boolean = false): List<ForYouItem> = mutex.withLock {
        val now = System.currentTimeMillis()
        val signature = signature(now)
        cached?.takeIf { !force && now - it.first < CACHE_MS && it.second == signature }?.let { return it.third }
        val items = build(now)
        cached = Triple(now, signature, items)
        items
    }

    private suspend fun signature(now: Long): Int =
        (dao.getShows().map { it.id }.sorted() to dao.progressSince(now - HISTORY_MS).filter { it.completed }.map { it.audioUrl }.sorted()).hashCode()

    private suspend fun build(now: Long): List<ForYouItem> = coroutineScope {
        val subscriptions = dao.getShows()
        val history = dao.progressSince(now - HISTORY_MS)
        val byUrl = history.associateBy { it.audioUrl }

        val affinity = HashMap<String, Double>()
        subscriptions.forEach { affinity.merge(it.id, SUBSCRIBED, Double::plus) }
        history.forEach { p ->
            val weight = (if (p.completed) 1.0 else 0.5) * decay(now - p.updatedAt, AFFINITY_HALF_LIFE)
            p.showId?.let { affinity.merge(it, weight, Double::plus) }
        }
        if (affinity.isEmpty()) return@coroutineScope coldStart()

        val seeds = affinity.entries.sortedByDescending { it.value }.take(MAX_SEEDS)
        val loaded = seeds.map { (id, score) ->
            async { runCatching { repository.loadShow(id) }.getOrNull()?.let { it to score } }
        }.awaitAll().filterNotNull()

        val episodes = loaded.flatMap { (show, score) -> candidates(show, score, byUrl, now) }
            .sortedByDescending { it.score }
        val known = affinity.keys
        val discovery = discover(loaded.map { it.first }, known)
        interleave(episodes, discovery)
    }

    private fun candidates(
        data: ShowWithEpisodes,
        affinity: Double,
        heard: Map<String, PodcastProgressEntity>,
        now: Long,
    ): List<ForYouItem.Episode> {
        val strength = ln(1 + affinity) // a tenth heard episode adds less than the second
        val listened = { e: PodcastEpisode -> heard[e.audioUrl] != null } // heard or in "Weiterhören"
        if (data.serial) {
            val lastHeard = data.episodes.indexOfLast { heard[it.audioUrl]?.completed == true }
            val next = data.episodes.drop(lastHeard + 1).firstOrNull { !listened(it) } ?: return emptyList()
            val reason = if (lastHeard >= 0) "Weiter mit ${data.show.title}" else "Von vorn: ${data.show.title}"
            return listOf(ForYouItem.Episode(next, reason, strength * SERIAL_BOOST))
        }
        return data.episodes.asSequence()
            .filter { !listened(it) }
            .take(MAX_PER_SHOW)
            .mapNotNull { e ->
                val age = now - (e.publishedAt ?: return@mapNotNull null)
                if (age > FRESH_MS) return@mapNotNull null
                ForYouItem.Episode(e, "Neu bei ${data.show.title}", strength * decay(age, FRESH_HALF_LIFE))
            }
            .toList()
    }

    private suspend fun discover(seeds: List<ShowWithEpisodes>, known: Set<String>): List<ForYouItem.Show> = coroutineScope {
        val similar = if (settings.podcastSimilar.first()) {
            seeds.filter { it.show.kind == ShowKind.PODCAST && it.show.id.startsWith("rss:") }.take(SIMILAR_SEEDS).map { seed ->
                async {
                    runCatching { repository.similarPodcasts(seed.show.id.removePrefix("rss:"), seed.show.title) }
                        .getOrDefault(emptyList()).take(4)
                        .map { ForYouItem.Show(it, "Ähnlich wie ${seed.show.title}", 0.0) }
                }
            }
        } else {
            emptyList()
        }
        val kinds = seeds.map { it.show.kind }.toSet()
        val ard = listOfNotNull(
            (PodcastRepository.ARD_HOERSPIEL to ShowKind.HOERSPIEL).takeIf { ShowKind.HOERSPIEL in kinds },
            (PodcastRepository.ARD_HOERBUCH to ShowKind.HOERBUCH).takeIf { ShowKind.HOERBUCH in kinds },
        ).map { (category, kind) ->
            async {
                runCatching { repository.ardRecentShows(category, kind) }.getOrDefault(emptyList()).take(4)
                    .map { ForYouItem.Show(it, if (kind == ShowKind.HOERBUCH) "Neues Hörbuch" else "Neues Hörspiel", 0.0) }
            }
        }
        // round-robin over the sources, so each one gets a turn
        val lists = (similar + ard).awaitAll().map { list -> list.filter { it.show.id !in known } }
        roundRobin(lists).distinctBy { it.show.id }
    }

    /** Nothing heard or subscribed yet: what is new and popular, a bit of everything. */
    private suspend fun coldStart(): List<ForYouItem> = coroutineScope {
        val hoerspiele = async { runCatching { repository.ardRecentShows(PodcastRepository.ARD_HOERSPIEL, ShowKind.HOERSPIEL) }.getOrDefault(emptyList()) }
        val hoerbuecher = async { runCatching { repository.ardRecentShows(PodcastRepository.ARD_HOERBUCH, ShowKind.HOERBUCH) }.getOrDefault(emptyList()) }
        val podcasts = async { runCatching { repository.topPodcasts() }.getOrDefault(emptyList()) }
        roundRobin(
            listOf(
                podcasts.await().take(5).map { ForYouItem.Show(it, "Beliebter Podcast", 0.0) },
                hoerspiele.await().take(5).map { ForYouItem.Show(it, "Neues Hörspiel", 0.0) },
                hoerbuecher.await().take(5).map { ForYouItem.Show(it, "Neues Hörbuch", 0.0) },
            ),
        ).distinctBy { it.show.id }
    }

    /** Two episodes, one discovery, two episodes, …; then whatever is left. */
    private fun interleave(episodes: List<ForYouItem.Episode>, discovery: List<ForYouItem.Show>): List<ForYouItem> {
        val perShow = HashMap<String, Int>()
        val capped = episodes.filter { perShow.merge(it.episode.showId, 1, Int::plus)!! <= MAX_PER_SHOW }
        val out = ArrayList<ForYouItem>()
        val e = capped.iterator()
        val d = discovery.iterator()
        while ((e.hasNext() || d.hasNext()) && out.size < MAX_ITEMS) {
            repeat(2) { if (e.hasNext() && out.size < MAX_ITEMS) out += e.next() }
            if (d.hasNext() && out.size < MAX_ITEMS) out += d.next()
            if (!e.hasNext()) while (d.hasNext() && out.size < MAX_ITEMS) out += d.next()
        }
        return out
    }

    private fun <T> roundRobin(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) lists.forEach { list -> list.getOrNull(i)?.let(out::add) }
        return out
    }

    private fun decay(ageMs: Long, halfLifeMs: Long): Double = exp(-ln(2.0) * min(ageMs, 10 * halfLifeMs).coerceAtLeast(0) / halfLifeMs)

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        const val SUBSCRIBED = 2.0
        const val SERIAL_BOOST = 1.5
        const val HISTORY_MS = 180 * DAY
        const val AFFINITY_HALF_LIFE = 30 * DAY
        const val FRESH_MS = 30 * DAY
        const val FRESH_HALF_LIFE = 7 * DAY
        const val MAX_SEEDS = 8
        const val SIMILAR_SEEDS = 2
        const val MAX_PER_SHOW = 2
        const val MAX_ITEMS = 15
        const val CACHE_MS = 15 * 60 * 1000L
    }
}
