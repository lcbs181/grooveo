package dev.schlubbe.musicagent.podcast

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.schlubbe.musicagent.data.extract.di.ExtractionHttpClient
import dev.schlubbe.musicagent.data.local.dao.PodcastDao
import dev.schlubbe.musicagent.data.local.entity.PodcastProgressEntity
import dev.schlubbe.musicagent.data.local.entity.PodcastShowEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Podcasts and Hörspiele. Three sources, none needing an account or key:
 * - Apple's public podcast directory for searching podcasts (it returns the RSS feed),
 * - the RSS feeds themselves for the episodes (also any feed URL the person adds),
 * - the ARD Audiothek for Hörspiele, many of which exist only there.
 */
@Singleton
class PodcastRepository @Inject constructor(
    @ExtractionHttpClient private val http: OkHttpClient,
    private val dao: PodcastDao,
    private val settings: dev.schlubbe.musicagent.data.repository.SettingsRepository,
    private val youTube: YouTubeSpoken,
) {
    /** What a YouTube channel/playlist was found as (Hörspiel, Hörbuch …); YouTube itself does not say. */
    private val kindHints = java.util.concurrent.ConcurrentHashMap<String, ShowKind>()
    private val youTubeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Hörspiele/Hörbücher from YouTube, if switched on (Einstellungen). Never fails the search. */
    private suspend fun youTubeShows(query: String, kind: ShowKind): List<PodcastShow> {
        if (!settings.youtubeAudio.first()) return emptyList()
        // never let YouTube hold up the ARD/LibriVox results for long; the extractor blocks
        // and cannot be cancelled, so it runs detached and is only waited for a while
        val officialOnly = settings.youtubeOfficialOnly.first()
        val search = youTubeScope.async { runCatching { youTube.search(query, kind, officialOnly) }.getOrDefault(emptyList()) }
        val shows = kotlinx.coroutines.withTimeoutOrNull(YOUTUBE_TIMEOUT_MS) { search.await() } ?: emptyList()
        shows.forEach { kindHints[it.id] = kind }
        return shows
    }

    val youtubeEnabled: Flow<Boolean> get() = settings.youtubeAudio

    suspend fun setYouTubeEnabled(on: Boolean) = settings.setYoutubeAudio(on)

    /** The search's YouTube tab: any video as audio. */
    suspend fun searchYouTubeSeries(query: String): List<PodcastShow> =
        if (settings.youtubeAudio.first()) youTube.searchSeries(query) else emptyList()

    suspend fun searchYouTubeAlbums(query: String): List<PodcastShow> =
        if (settings.youtubeAudio.first()) youTube.searchAlbums(query) else emptyList()

    suspend fun searchYouTubeAudio(query: String): List<PodcastEpisode> =
        if (settings.youtubeAudio.first()) youTube.searchVideos(query) else emptyList()

    private val showCache = object : LinkedHashMap<String, Pair<Long, ShowWithEpisodes>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, ShowWithEpisodes>>?) = size > 20
    }

    // ---- search and browse ----

    /** fyyd (open, German, no key) first; Apple's directory only when fyyd has nothing that
     * matches and the person allows it (Einstellungen > Datenschutz). */
    suspend fun searchPodcasts(query: String): List<PodcastShow> = withContext(Dispatchers.IO) {
        val open = runCatching { searchFyyd(query) }
        val shows = open.getOrDefault(emptyList())
        if (shows.any { matches(it.title, query) } || !settings.podcastAppleFallback.first()) {
            return@withContext shows.ifEmpty { open.getOrThrow() }
        }
        val apple = runCatching { searchApple(query) }.getOrDefault(emptyList())
        (apple + shows).distinctBy { it.id }.ifEmpty { open.getOrThrow() }
    }

    private fun searchFyyd(query: String): List<PodcastShow> {
        val url = "https://api.fyyd.de/0.2/search/podcast".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)
            .addQueryParameter("count", "30")
            .build()
        return fyydShows(getJson(Request.Builder().url(url).build()).getAsJsonArray("data"))
    }

    private fun fyydShows(data: JsonArray?): List<PodcastShow> {
        return data?.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val feed = o.str("xmlURL")?.takeIf(::isWebUrl) ?: return@mapNotNull null
            PodcastShow(
                id = ShowId.Rss(feed).toString(),
                title = o.str("title")?.trim() ?: return@mapNotNull null,
                author = o.str("author"),
                imageUrl = (o.str("imgURL") ?: o.str("layoutImageURL"))?.takeIf(::isWebUrl)?.let(::secureUrl),
                description = null,
                kind = ShowKind.PODCAST,
                episodeCount = o.get("episode_count")?.takeIf { it.isJsonPrimitive }?.asInt,
            )
        }.orEmpty().distinctBy { it.id }
    }

    private fun searchApple(query: String): List<PodcastShow> {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("media", "podcast")
            .addQueryParameter("country", "DE")
            .addQueryParameter("limit", "30")
            .addQueryParameter("term", query)
            .build()
        val json = getJson(Request.Builder().url(url).build())
        return json.getAsJsonArray("results")?.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val feed = o.str("feedUrl")?.takeIf(::isWebUrl) ?: return@mapNotNull null
            PodcastShow(
                id = ShowId.Rss(feed).toString(),
                title = o.str("collectionName") ?: return@mapNotNull null,
                author = o.str("artistName"),
                imageUrl = o.str("artworkUrl600") ?: o.str("artworkUrl100"),
                description = o.str("primaryGenreName"),
                kind = ShowKind.PODCAST,
                episodeCount = o.get("trackCount")?.takeIf { it.isJsonPrimitive }?.asInt,
            )
        }.orEmpty().distinctBy { it.id } // the directory lists some feeds twice
    }

    /** Something to browse before anything is searched: Apple's German charts when Apple
     * may be asked, else podcasts from the ARD Audiothek. */
    suspend fun topPodcasts(): List<PodcastShow> = withContext(Dispatchers.IO) {
        if (!settings.podcastAppleFallback.first()) {
            return@withContext listOf(ARD_WISSEN, ARD_COMEDY, ARD_DOKU)
                .flatMap { runCatching { ardCategory(it, ShowKind.PODCAST) }.getOrDefault(emptyList()).take(10) }
                .distinctBy { it.id }
        }
        val json = getJson(Request.Builder().url("https://itunes.apple.com/de/rss/toppodcasts/limit=25/json").build())
        val ids = json.getAsJsonObject("feed")?.getAsJsonArray("entry")?.mapNotNull { e ->
            (e as? JsonObject)?.getAsJsonObject("id")?.getAsJsonObject("attributes")?.str("im:id")
        }.orEmpty()
        if (ids.isEmpty()) return@withContext emptyList()
        val lookup = getJson(Request.Builder().url("https://itunes.apple.com/lookup?country=DE&id=${ids.joinToString(",")}").build())
        val byId = lookup.getAsJsonArray("results")?.mapNotNull { it as? JsonObject }
            ?.associateBy { it.get("collectionId")?.asString }.orEmpty()
        ids.mapNotNull { id ->
            val o = byId[id] ?: return@mapNotNull null
            val feed = o.str("feedUrl")?.takeIf(::isWebUrl) ?: return@mapNotNull null
            PodcastShow(
                id = ShowId.Rss(feed).toString(),
                title = o.str("collectionName") ?: return@mapNotNull null,
                author = o.str("artistName"),
                imageUrl = o.str("artworkUrl600") ?: o.str("artworkUrl100"),
                description = o.str("primaryGenreName"),
                kind = ShowKind.PODCAST,
            )
        }.distinctBy { it.id }
    }

    suspend fun searchHoerspiele(query: String): List<PodcastShow> = withContext(Dispatchers.IO) {
        val data = ardQuery(
            "query(\$q: String!) { search(query: \$q) { programSets { nodes { $ARD_SET_FIELDS } } } }",
            JsonObject().apply { addProperty("q", query) },
        )
        ardSets(data.getAsJsonObject("search")?.getAsJsonObject("programSets")?.getAsJsonArray("nodes")) +
            youTubeShows(query, ShowKind.HOERSPIEL)
    }

    /** ARD Audiothek editorial category, e.g. [ARD_HOERSPIEL] or [ARD_KINDER]. */
    suspend fun ardCategory(categoryId: String, kind: ShowKind = ShowKind.HOERSPIEL): List<PodcastShow> = withContext(Dispatchers.IO) {
        val data = ardQuery(
            "query(\$id: ID!) { editorialCategory(id: \$id) { programSets { nodes { $ARD_SET_FIELDS } } } }",
            JsonObject().apply { addProperty("id", categoryId) },
        )
        ardSets(data.getAsJsonObject("editorialCategory")?.getAsJsonObject("programSets")?.getAsJsonArray("nodes"), kind)
            .sortedByDescending { it.episodeCount ?: 0 }
    }

    // ---- Hörbücher ----

    /** LibriVox (public-domain readings, many German ones) plus the ARD Audiothek's
     * Hörbuch category, both matched against the query. */
    /** ARD, LibriVox (by title and by author) and YouTube at the same time; LibriVox's API
     * is slow at times, so it gets [LIBRIVOX_TIMEOUT_MS] and is left out after that. */
    suspend fun searchHoerbuecher(query: String): List<PodcastShow> = kotlinx.coroutines.coroutineScope {
        val ard = async(Dispatchers.IO) { runCatching { ardCategory(ARD_HOERBUCH, ShowKind.HOERBUCH).filter { matches(it.title, query) } } }
        val byTitle = detached(LIBRIVOX_TIMEOUT_MS) { libriVox("title", query) }
        val byAuthor = detached(LIBRIVOX_TIMEOUT_MS) { libriVox("author", query) }
        val youtube = async { youTubeShows(query, ShowKind.HOERBUCH) }
        val ardResult = ard.await()
        val all = (ardResult.getOrDefault(emptyList()) + byTitle.await().orEmpty() + byAuthor.await().orEmpty()).distinctBy { it.id }
        if (all.isEmpty()) ardResult.getOrThrow()
        all + youtube.await()
    }

    /** Runs blocking [block] apart and waits at most [timeoutMs] for it: null on timeout or error. */
    private fun <T> kotlinx.coroutines.CoroutineScope.detached(timeoutMs: Long, block: () -> T) = async {
        val work = youTubeScope.async { runCatching(block).getOrNull() }
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { work.await() }
    }

    /** German LibriVox classics by some well-known authors, for browsing. */
    suspend fun libriVoxClassics(): List<PodcastShow> = withContext(Dispatchers.IO) {
        listOf("Kafka", "Fontane", "Hauff", "Storm", "Grimm").flatMap { author ->
            runCatching { libriVox("author", author) }.getOrDefault(emptyList()).take(4)
        }.distinctBy { it.id }
    }

    private fun libriVox(field: String, query: String): List<PodcastShow> {
        val url = "https://librivox.org/api/feed/audiobooks/".toHttpUrl().newBuilder()
            .addQueryParameter(field, query)
            .addQueryParameter("format", "json")
            .addQueryParameter("extended", "1") // the only way to get the cover
            .addQueryParameter("limit", "20")
            .build()
        val json = runCatching { getJson(Request.Builder().url(url).build()) }
            .getOrElse { if (it.message == "HTTP 404") return emptyList() else throw it } // "could not be found"
        val books = json.getAsJsonArray("books")?.mapNotNull { it as? JsonObject }.orEmpty()
        return books.mapNotNull { o ->
            val feed = o.str("url_rss")?.takeIf(::isWebUrl)?.let(::secureUrl) ?: return@mapNotNull null
            val authors = o.getAsJsonArray("authors")?.mapNotNull { a ->
                (a as? JsonObject)?.let { listOfNotNull(it.str("first_name"), it.str("last_name")).joinToString(" ") }
            }?.joinToString(", ")
            val german = o.str("language") == "German"
            german to PodcastShow(
                id = ShowId.Rss(feed).toString(),
                title = o.str("title")?.trim() ?: return@mapNotNull null,
                author = authors?.takeIf { it.isNotBlank() },
                imageUrl = (o.str("coverart_thumbnail") ?: o.str("coverart_jpg"))?.takeIf(::isWebUrl)?.let(::secureUrl)
                    ?: o.str("url_iarchive")?.substringAfter("/details/", "")?.takeIf { it.isNotBlank() && '/' !in it }
                        ?.let { "https://archive.org/services/img/$it" }, // the Internet Archive item's cover
                description = null,
                kind = ShowKind.HOERBUCH,
                episodeCount = o.str("num_sections")?.toIntOrNull(),
            )
        }.sortedByDescending { it.first }.map { it.second } // German readings first
    }

    private fun isLibriVox(url: String) = url.contains("://librivox.org/", ignoreCase = true)

    // ---- what is new ----

    /** Program sets of an ARD category with the most recently published episodes first,
     * for the start page ("Neue Hörspiele", "Neue Hörbücher"). */
    suspend fun ardRecentShows(categoryId: String, kind: ShowKind): List<PodcastShow> = withContext(Dispatchers.IO) {
        val data = ardQuery(
            "query(\$id: ID!) { editorialCategory(id: \$id) { recentItems(first: 40) { nodes { publishDate programSet { $ARD_SET_FIELDS } } } } }",
            JsonObject().apply { addProperty("id", categoryId) },
        )
        val now = System.currentTimeMillis()
        data.getAsJsonObject("editorialCategory")?.getAsJsonObject("recentItems")?.getAsJsonArray("nodes")
            ?.mapNotNull { it as? JsonObject }
            ?.filter { o -> o.str("publishDate")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }?.let { it <= now } ?: true }
            ?.mapNotNull { o -> o.getAsJsonObject("programSet")?.let(::ardSet)?.copy(kind = kind) }
            .orEmpty()
            .distinctBy { it.id }
    }

    /** fyyd's "similar podcasts" for a feed. fyyd ids are looked up by searching the
     * show's title and matching the feed address. */
    suspend fun similarPodcasts(feedUrl: String, title: String): List<PodcastShow> = withContext(Dispatchers.IO) {
        val fyydId = fyydIds.getOrPut(feedUrl) {
            val url = "https://api.fyyd.de/0.2/search/podcast".toHttpUrl().newBuilder()
                .addQueryParameter("term", title).addQueryParameter("count", "10").build()
            getJson(Request.Builder().url(url).build()).getAsJsonArray("data")
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { o -> o.str("xmlURL")?.let { sameFeed(it, feedUrl) } == true }
                ?.str("id").orEmpty() // "" = not on fyyd, don't search again
        }.ifEmpty { return@withContext emptyList() }
        val url = "https://api.fyyd.de/0.2/podcast/recommend".toHttpUrl().newBuilder()
            .addQueryParameter("podcast_id", fyydId).addQueryParameter("count", "10").build()
        fyydShows(getJson(Request.Builder().url(url).build()).getAsJsonArray("data"))
    }

    private val fyydIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun sameFeed(a: String, b: String): Boolean =
        a.substringAfter("://").trimEnd('/').equals(b.substringAfter("://").trimEnd('/'), ignoreCase = true)

    // ---- shows and episodes ----

    suspend fun loadShow(id: String, forceRefresh: Boolean = false): ShowWithEpisodes = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            synchronized(showCache) { showCache[id] }
                ?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }
                ?.let { return@withContext it.second }
        }
        val kind = dao.getShows().firstOrNull { it.id == id }?.let { ShowKind.of(it.kind) }
        val parsed = ShowId.parse(id) ?: run {
            android.util.Log.w("PodcastRepository", "unknown show id: $id")
            throw IOException("Unbekannte Sendung")
        }
        val loaded = when (val showId = parsed) {
            is ShowId.Rss -> loadRss(showId, kind ?: if (isLibriVox(showId.feedUrl)) ShowKind.HOERBUCH else ShowKind.PODCAST)
            is ShowId.Ard -> loadArd(showId, kind)
            is ShowId.YouTube -> youTube.load(showId.listId, kind ?: kindHints[id] ?: ShowKind.PODCAST)
        }
        synchronized(showCache) { showCache[id] = System.currentTimeMillis() to loaded }
        loaded
    }

    private fun loadRss(id: ShowId.Rss, kind: ShowKind): ShowWithEpisodes {
        val request = Request.Builder().url(secureUrl(id.feedUrl)).header("Accept", "application/rss+xml, application/xml, text/xml, */*").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Feed nicht erreichbar (HTTP ${response.code})")
            val body = response.body ?: throw IOException("Feed ist leer")
            if (body.contentLength() > MAX_FEED_BYTES) throw IOException("Feed ist zu groß")
            val input = LimitedInputStream(body.byteStream(), MAX_FEED_BYTES)
            return runCatching { RssParser.parse(input, id.toString(), kind) }
                .getOrElse { throw IOException("Das ist kein Podcast-Feed", it) }
        }
    }

    private fun loadArd(id: ShowId.Ard, kind: ShowKind?): ShowWithEpisodes {
        val data = ardQuery(
            "query(\$id: ID!) { programSet(id: \$id) { $ARD_SET_FIELDS items(first: 200, orderBy: PUBLISH_DATE_DESC) { " +
                "nodes { title summary duration publishDate episodeNumber isPublished image { url } audios { url } sharingUrl } } } }",
            JsonObject().apply { addProperty("id", id.programSetId) },
        )
        val set = data.getAsJsonObject("programSet") ?: throw IOException("Sendung nicht gefunden")
        val show = ardSet(set)?.let { if (kind != null) it.copy(kind = kind) else it } ?: throw IOException("Sendung nicht gefunden")
        val episodes = set.getAsJsonObject("items")?.getAsJsonArray("nodes")?.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            // taken down: still listed, but its audio answers 404
            if (o.get("isPublished")?.takeIf { it.isJsonPrimitive }?.asBoolean == false) return@mapNotNull null
            val audio = o.getAsJsonArray("audios")?.firstOrNull()?.let { (it as? JsonObject)?.str("url") }
                ?.takeIf(::isWebUrl)?.let { TrackingPrefixes.strip(secureUrl(it)) } ?: return@mapNotNull null // announced, not out yet
            o.get("episodeNumber")?.takeIf { it.isJsonPrimitive }?.asInt to PodcastEpisode(
                showId = show.id,
                showTitle = show.title,
                title = o.str("title") ?: "Folge",
                description = o.str("summary"),
                audioUrl = audio,
                imageUrl = ardImage(o) ?: show.imageUrl,
                durationSec = o.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt,
                publishedAt = o.str("publishDate")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
                link = o.str("sharingUrl"),
            )
        }.orEmpty()
            // announced episodes already list their audio, but it 404s until the date
            .filter { (it.second.publishedAt ?: 0L) <= System.currentTimeMillis() }
            .distinctBy { it.second.audioUrl }
        val ordered = EpisodeOrder.chronological(episodes.map { it.second }, sourceNewestFirst = true, numbers = episodes.map { it.first })
        val numberByUrl = episodes.associate { it.second.audioUrl to it.first }
        val numbers = ordered.map { numberByUrl[it.audioUrl] }
        return ShowWithEpisodes.of(show.copy(episodeCount = ordered.size), ordered, EpisodeOrder.isSerial(show.kind, false, ordered, numbers))
    }

    /** One ARD Audiothek episode by its item id, or null when it is gone (depublished)
     * or not out yet. */
    suspend fun ardEpisode(itemId: String): PodcastEpisode? = withContext(Dispatchers.IO) {
        val data = ardQuery(
            "query(\$id: ID!) { item(id: \$id) { title summary duration publishDate isPublished image { url } audios { url } sharingUrl programSet { $ARD_SET_FIELDS } } }",
            JsonObject().apply { addProperty("id", itemId) },
        )
        val o = data.getAsJsonObject("item") ?: return@withContext null
        if (o.get("isPublished")?.takeIf { it.isJsonPrimitive }?.asBoolean == false) return@withContext null
        val set = o.getAsJsonObject("programSet")?.let(::ardSet) ?: return@withContext null
        val audio = o.getAsJsonArray("audios")?.firstOrNull()?.let { (it as? JsonObject)?.str("url") }
            ?.takeIf(::isWebUrl)?.let { TrackingPrefixes.strip(secureUrl(it)) } ?: return@withContext null
        val published = o.str("publishDate")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
        if ((published ?: 0L) > System.currentTimeMillis()) return@withContext null
        PodcastEpisode(
            showId = set.id,
            showTitle = set.title,
            title = o.str("title") ?: "Folge",
            description = o.str("summary"),
            audioUrl = audio,
            imageUrl = ardImage(o) ?: set.imageUrl,
            durationSec = o.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt,
            publishedAt = published,
            link = o.str("sharingUrl"),
        )
    }

    // ---- subscriptions and progress ----

    fun observeSubscriptions(): Flow<List<PodcastShowEntity>> = dao.observeShows()

    fun observeSubscription(id: String): Flow<PodcastShowEntity?> = dao.observeShow(id)

    suspend fun subscribe(show: PodcastShow, latestEpisodeAt: Long?) = dao.insertShow(
        PodcastShowEntity(
            id = show.id,
            title = show.title,
            author = show.author,
            imageUrl = show.imageUrl,
            kind = show.kind.key,
            subscribedAt = System.currentTimeMillis(),
            latestEpisodeAt = latestEpisodeAt,
        ),
    )

    suspend fun unsubscribe(id: String) = dao.deleteShow(id)

    fun observeInProgress(limit: Int = 20): Flow<List<PodcastProgressEntity>> = dao.observeInProgress(limit)

    /** Hides an episode from "Weiterhören" by counting it as heard; the next part moves up. */
    suspend fun markHeard(progress: PodcastProgressEntity) =
        dao.upsertProgress(progress.copy(completed = true, updatedAt = System.currentTimeMillis()))

    /** Removes an episode from "Weiterhören" and forgets where it stopped. */
    suspend fun forgetProgress(audioUrl: String) = dao.deleteProgress(audioUrl)

    fun observeProgressForShow(showId: String): Flow<List<PodcastProgressEntity>> = dao.observeProgressForShow(showId)

    suspend fun setPlayed(episode: PodcastEpisode, played: Boolean) {
        if (!played) {
            dao.deleteProgress(episode.audioUrl)
            return
        }
        dao.upsertProgress(
            PodcastProgressEntity(
                audioUrl = episode.audioUrl,
                showId = episode.showId,
                showTitle = episode.showTitle,
                title = episode.title,
                imageUrl = episode.imageUrl,
                positionMs = 0,
                durationMs = (episode.durationSec ?: 0) * 1000L,
                completed = true,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    // ---- helpers ----

    private fun ardQuery(query: String, variables: JsonObject): JsonObject {
        val body = JsonObject().apply {
            addProperty("query", query)
            add("variables", variables)
        }.toString().toRequestBody("application/json".toMediaType())
        val json = getJson(Request.Builder().url(ARD_GRAPHQL).post(body).build())
        return json.getAsJsonObject("data") ?: throw IOException("ARD Audiothek antwortet nicht")
    }

    private fun ardSets(nodes: JsonArray?, kind: ShowKind = ShowKind.HOERSPIEL): List<PodcastShow> =
        nodes?.mapNotNull { (it as? JsonObject)?.let { o -> ardSet(o)?.copy(kind = kind) } }?.filter { (it.episodeCount ?: 0) > 0 }.orEmpty().distinctBy { it.id }

    private fun ardSet(o: JsonObject): PodcastShow? {
        val id = o.str("id")?.takeIf { it.all(Char::isLetterOrDigit) } ?: return null
        return PodcastShow(
            id = ShowId.Ard(id).toString(),
            title = o.str("title")?.trim() ?: return null,
            author = o.getAsJsonObject("publicationService")?.str("title"),
            imageUrl = ardImage(o),
            description = o.str("synopsis"),
            kind = ShowKind.HOERSPIEL,
            episodeCount = o.get("numberOfElements")?.takeIf { it.isJsonPrimitive }?.asInt,
        )
    }

    private fun ardImage(o: JsonObject): String? =
        o.getAsJsonObject("image")?.str("url")?.replace("{width}", "600")?.takeIf(::isWebUrl)

    private fun getJson(request: Request): JsonObject =
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val text = response.body?.string() ?: throw IOException("Leere Antwort")
            JsonParser.parseString(text).asJsonObject
        }

    /** Every word of the query appears in the title. */
    private fun matches(title: String, query: String): Boolean {
        val t = title.lowercase()
        return query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it != "und" }.all { it in t }
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    /** Stops reading a feed once it is unreasonably big (a broken or hostile server). */
    private class LimitedInputStream(private val inner: java.io.InputStream, private val maxBytes: Long) : java.io.InputStream() {
        private var read = 0L
        private fun count(n: Int): Int {
            if (n > 0) {
                read += n
                if (read > maxBytes) throw IOException("Feed ist zu groß")
            }
            return n
        }

        override fun read(): Int = inner.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = count(inner.read(b, off, len))
        override fun close() = inner.close()
    }

    companion object {
        const val ARD_HOERSPIEL = "42914712"
        const val ARD_KINDER = "42914714"
        const val ARD_HOERBUCH = "42914713"
        const val YOUTUBE_TIMEOUT_MS = 12_000L
        const val LIBRIVOX_TIMEOUT_MS = 8_000L
        const val ARD_WISSEN = "42914742"
        const val ARD_COMEDY = "42914694"
        const val ARD_DOKU = "42914710"
        private const val ARD_GRAPHQL = "https://api.ardaudiothek.de/graphql"
        private const val ARD_SET_FIELDS = "id title synopsis numberOfElements image { url } publicationService { title }"
        private const val MAX_FEED_BYTES = 20L * 1024 * 1024
        private const val CACHE_MS = 10 * 60 * 1000L
    }
}
