package dev.schlubbe.musicagent.podcast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hörspiele and Hörbücher from YouTube, only when switched on in the settings.
 *
 * About half of what YouTube returns for "… Hörspiel" is not from the rights holder
 * (re-uploads, fan mixes, upload farms, made-up episodes; measured on 200 results in
 * November 2026). So by default only [OFFICIAL_CHANNELS] are used; the person can
 * allow other uploaders too. Short videos (previews) and fan mixes are always dropped.
 *
 * A YouTube channel or playlist becomes a show ("yt:UC…" / "yt:PL…"); its videos play
 * as podcast episodes whose audio URL is the watch URL, resolved like any YouTube track.
 */
@Singleton
class YouTubeSpoken @Inject constructor() {

    /** Channels and playlists matching [query], as shows of [kind]. */
    suspend fun search(query: String, kind: ShowKind, officialOnly: Boolean): List<PodcastShow> = withContext(Dispatchers.IO) {
        val word = if (kind == ShowKind.HOERBUCH) "hörbuch" else "hörspiel"
        val q = if (query.contains(word, ignoreCase = true)) query else "$query $word"
        coroutineScope {
            val playlists = async { runCatching { searchItems(q, YoutubeSearchQueryHandlerFactory.PLAYLISTS) }.getOrDefault(emptyList()) }
            val channels = async { runCatching { searchItems(q, YoutubeSearchQueryHandlerFactory.CHANNELS) }.getOrDefault(emptyList()) }
            val videos = async { runCatching { searchItems(q, YoutubeSearchQueryHandlerFactory.VIDEOS) }.getOrDefault(emptyList()) }

            val fromPlaylists = playlists.await().filterIsInstance<PlaylistInfoItem>()
                .filter { allowed(channelId(it.uploaderUrl), it.uploaderName, officialOnly) && it.streamCount != 0L }
                .mapNotNull { it.toShow(kind) }
            val fromChannels = channels.await().filterIsInstance<ChannelInfoItem>()
                .filter { allowed(channelId(it.url), it.name, officialOnly) }
                .mapNotNull { it.toShow(kind) }
            // channels whose full-length videos match, e.g. the publisher of one episode
            val fromVideos = videos.await().filterIsInstance<StreamInfoItem>()
                .filter { isFullEpisode(it) && allowed(channelId(it.uploaderUrl), it.uploaderName, officialOnly) }
                .distinctBy { channelId(it.uploaderUrl) }
                .mapNotNull { v ->
                    val id = channelId(v.uploaderUrl) ?: return@mapNotNull null
                    PodcastShow(
                        id = "yt:$id",
                        title = v.uploaderName ?: return@mapNotNull null,
                        author = "YouTube",
                        imageUrl = v.uploaderAvatars.maxByOrNull { it.height }?.url,
                        description = null,
                        kind = kind,
                    )
                }
            (fromPlaylists + fromChannels + fromVideos).distinctBy { it.id }
        }
    }

    /** Playlists and channels for the search's "Hörspiele & Hörbücher" tab, so a series opens as a show
     * (episodes in order, subscribe, resume). No uploader filter, like [searchVideos]. */
    suspend fun searchSeries(query: String): List<PodcastShow> = withContext(Dispatchers.IO) {
        coroutineScope {
            val playlists = async { runCatching { searchItems(query, YoutubeSearchQueryHandlerFactory.PLAYLISTS) }.getOrDefault(emptyList()) }
            val channels = async { runCatching { searchItems(query, YoutubeSearchQueryHandlerFactory.CHANNELS) }.getOrDefault(emptyList()) }
            val videos = async { runCatching { searchItems(query, YoutubeSearchQueryHandlerFactory.VIDEOS) }.getOrDefault(emptyList()) }
            val words = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
            val named = { name: String? -> name != null && words.all { it in name.lowercase() } }
            // the channels behind the matching videos, most videos first: what was meant, even
            // when YouTube's channel search (or its content notice) misses it
            val fromVideos = videos.await().filterIsInstance<StreamInfoItem>()
                .groupBy { channelId(it.uploaderUrl) }.filterKeys { it != null }
                .entries.sortedByDescending { it.value.size }
                .filter { it.value.size >= 2 || named(it.value.first().uploaderName) }
                .filter { it.value.first().uploaderName?.let(::isTopicChannel) != true }
                .mapNotNull { (id, list) ->
                    val v = list.first()
                    PodcastShow(
                        id = "yt:$id", title = v.uploaderName ?: return@mapNotNull null, author = "YouTube",
                        imageUrl = v.uploaderAvatars.maxByOrNull { it.height }?.url, description = null, kind = ShowKind.PODCAST,
                    )
                }.take(3)
            val fromChannels = channels.await().filterIsInstance<ChannelInfoItem>()
                .filter { named(it.name) && !isTopicChannel(it.name) }
                .mapNotNull { it.toShow(ShowKind.PODCAST) }.take(3)
            val top = fromVideos.map { it.title }.toSet()
            val fromPlaylists = playlists.await().filterIsInstance<PlaylistInfoItem>()
                .filter { it.streamCount != 0L }
                .sortedByDescending { it.uploaderName in top } // the found channels' own series first
                .mapNotNull { it.toShow(ShowKind.PODCAST) }.take(8)
            (fromVideos + fromChannels + fromPlaylists).distinctBy { it.id }
        }
    }

    /** Albums from YouTube Music (audiobooks and Hörspiele are often released as albums,
     * one track per chapter), for the search's "Hörspiele & Hörbücher" tab. They open as shows, in track order. */
    suspend fun searchAlbums(query: String): List<PodcastShow> = withContext(Dispatchers.IO) {
        searchItems(query, YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS).filterIsInstance<PlaylistInfoItem>()
            .mapNotNull { it.toShow(ShowKind.PODCAST) }
            .take(MAX_ALBUMS)
    }

    /** Any YouTube video as audio (ASMR, talks, stories …), for the search's "Hörspiele & Hörbücher" tab.
     * No uploader filter: the person searched YouTube on purpose. Shorts are left out. */
    suspend fun searchVideos(query: String): List<PodcastEpisode> = withContext(Dispatchers.IO) {
        searchItems(query, YoutubeSearchQueryHandlerFactory.VIDEOS).filterIsInstance<StreamInfoItem>()
            .filter { !it.isShortFormContent && it.duration > 60 }
            .mapNotNull { v ->
                val channel = channelId(v.uploaderUrl) ?: return@mapNotNull null
                PodcastEpisode(
                    showId = "yt:$channel",
                    showTitle = v.uploaderName ?: "YouTube",
                    title = v.name,
                    description = null,
                    audioUrl = v.url,
                    imageUrl = v.thumbnails.maxByOrNull { it.height }?.url,
                    durationSec = v.duration.toInt(),
                    publishedAt = v.uploadDate?.takeUnless { it.isApproximation }?.instant?.toEpochMilli(),
                    link = v.url,
                )
            }
            .distinctBy { it.audioUrl }
    }

    /** The full-length videos of a channel (its uploads, newest first) or a playlist. */
    suspend fun load(id: String, kind: ShowKind): ShowWithEpisodes = withContext(Dispatchers.IO) {
        val listId = if (id.startsWith("UC")) "UU" + id.removePrefix("UC") else id
        val extractor = ServiceList.YouTube.getPlaylistExtractor("https://www.youtube.com/playlist?list=$listId")
        extractor.forceLocalization(GERMAN)
        extractor.forceContentCountry(GERMANY)
        extractor.fetchPage()
        val items = ArrayList<StreamInfoItem>()
        var page = extractor.initialPage
        items += page.items
        var next: Page? = page.nextPage
        while (next != null && items.size < MAX_EPISODES) {
            page = extractor.getPage(next)
            items += page.items
            next = page.nextPage
        }
        val showId = "yt:$id"
        val title = runCatching { extractor.name }.getOrNull()?.removePrefix("Uploads from ")?.replace(ALBUM_PREFIX, "")?.trim().orEmpty()
            .ifEmpty { runCatching { extractor.uploaderName }.getOrNull().orEmpty() }.ifEmpty { "YouTube" }
        val show = PodcastShow(
            id = showId,
            title = if (id.startsWith("UC")) runCatching { extractor.uploaderName }.getOrNull() ?: title else title,
            author = runCatching { extractor.uploaderName }.getOrNull(),
            imageUrl = runCatching { extractor.thumbnails.maxByOrNull { it.height }?.url }.getOrNull()
                ?: items.firstOrNull()?.thumbnails?.maxByOrNull { it.height }?.url,
            description = null,
            kind = kind,
        )
        // Hörspiele/Hörbücher: whole episodes only; an album: every track (chapters can be
        // short); anything else (ASMR, talks): every real video
        val keep: (StreamInfoItem) -> Boolean = when {
            isAlbum(id) -> { _ -> true }
            kind == ShowKind.PODCAST -> { v -> !v.isShortFormContent && v.duration > 60 }
            else -> ::isFullEpisode
        }
        val episodes = items.filter(keep).map { v ->
            PodcastEpisode(
                showId = showId,
                showTitle = show.title,
                title = v.name,
                description = null,
                audioUrl = v.url,
                imageUrl = v.thumbnails.maxByOrNull { it.height }?.url,
                durationSec = v.duration.takeIf { it > 0 }?.toInt(),
                publishedAt = v.uploadDate?.takeUnless { it.isApproximation }?.instant?.toEpochMilli(),
                link = v.url,
            )
        }.distinctBy { it.audioUrl }
        // uploads list the newest first, publisher playlists usually episode 1 first
        val ordered = EpisodeOrder.chronological(episodes, sourceNewestFirst = id.startsWith("UC"))
        // playlists only come from Hörspiel/Hörbuch searches: a publisher's series, heard from part 1
        val curated = !id.startsWith("UC")
        ShowWithEpisodes.of(show.copy(episodeCount = ordered.size), ordered, EpisodeOrder.isSerial(kind, curated, ordered))
    }

    /**
     * Some harmless queries ("David Holy") get YouTube's "results may be inappropriate"
     * notice instead of results, which NewPipe reports as nothing found; a browser would
     * click through. The same query with one more word ("audio", "deutsch") gets plain
     * results, so those are tried before giving up.
     */
    private fun searchItems(query: String, filter: String): List<InfoItem> {
        for (q in listOf(query, "$query audio", "$query deutsch")) {
            try {
                return searchOnce(q, filter)
            } catch (_: org.schabi.newpipe.extractor.search.SearchExtractor.NothingFoundException) {
                // the notice, or really nothing: try the next wording
            }
        }
        return emptyList()
    }

    private fun searchOnce(query: String, filter: String): List<InfoItem> {
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
        extractor.forceLocalization(GERMAN) // else YouTube translates the titles
        extractor.forceContentCountry(GERMANY)
        extractor.fetchPage()
        return extractor.initialPage.items
    }

    private fun PlaylistInfoItem.toShow(kind: ShowKind): PodcastShow? {
        val list = Regex("[?&]list=([\\w-]+)").find(url)?.groupValues?.get(1) ?: return null
        return PodcastShow(
            id = "yt:$list",
            title = name,
            author = uploaderName,
            imageUrl = thumbnails.maxByOrNull { it.height }?.url,
            description = null,
            kind = kind,
            episodeCount = streamCount.takeIf { it > 0 }?.toInt(),
        )
    }

    private fun ChannelInfoItem.toShow(kind: ShowKind): PodcastShow? {
        val id = channelId(url) ?: return null
        return PodcastShow(
            id = "yt:$id",
            title = name,
            author = "YouTube",
            imageUrl = thumbnails.maxByOrNull { it.height }?.url,
            description = description,
            kind = kind,
        )
    }

    companion object {
        private val GERMAN = Localization("de", "DE")
        private val GERMANY = ContentCountry("DE")
        private const val MAX_EPISODES = 200
        private const val MAX_ALBUMS = 8
        /** YouTube Music names its albums "Album – Title" (with a no-break space). */
        private val ALBUM_PREFIX = Regex("""^Album[\s\u00A0]*[–-][\s\u00A0]*""")

        /** Auto-generated "Artist - Topic" channels: their music shows up as albums instead. */
        private fun isTopicChannel(name: String) = name.endsWith(" - Topic") || name.endsWith(" – Thema")
        private const val MIN_EPISODE_SECONDS = 20 * 60

        private val NOT_AN_EPISODE = Regex("(?i)hörprobe|fanabmischung|fan[- ]?mix|fan[- ]?made|trailer|reaction|teaser")

        /**
         * Rights holders checked by hand (November 2026): publishers, labels and
         * broadcasters with their own channel. Ids, not names: impostors copy names
         * (a "TKKG" channel with 22 subscribers, a "John Sinclair TV" about football).
         */
        val OFFICIAL_CHANNELS: Map<String, String> = mapOf(
            "UCbThkgerVBzYrVuXliV34jQ" to "EUROPA Hörspiele",
            "UCkBOK5_V5AcQlye0TV2g0-A" to "Die drei ???",
            "UC6uedfYJICE9qZkrfPZKV6g" to "TKKG",
            "UCMLzrskqdxJ2Txi13JYw8Lw" to "Benjamin Blümchen TV",
            "UCXa_Oq65-bXDim7ZbBraXcw" to "Bibi Blocksberg TV",
            "UC7PQc4num1U-hj9NbQsIanQ" to "Bibi & Tina TV",
            "UCLnHNJjslCBwS8bXqR4i4Rw" to "John Sinclair",
            "UCjMzlcG7THh8QEsP5pWFr2w" to "Maritim Verlag",
            "UCEhwxym2xgmm0FPzuQ2rrVg" to "Gruselkabinett",
            "UCeyX-L-7VfTmuJ2ZifKFnWA" to "Titania Medien",
            "UC706WYJusWohe_AE_-f1FVg" to "WinterZeit AUDIOBOOKS",
            "UC7DrIO6aeHzWmfLrFT-Emtw" to "HoerTalk",
            "UCvpJd7PAs9_x-az5Rt7rEYA" to "Krimi Hörspiel (Deutschlandfunk)",
            "UCcl74-3SMsj5t9ri4ZD7vbg" to "Der Hörverlag",
            "UCqV9HjfhZ89nxMXgpFnr8JA" to "Hörbuch Hamburg",
            "UCUzjknFumAsLlsym-Y26a0Q" to "argon verlag",
            "UCyuB3HdfJ3Wh7kbtKTc4ecA" to "Lübbe Audio",
            "UCwJtKO1weLilrwuGmVa-oPw" to "Der Audio Verlag",
            "UCARg_CD3A0jAbB8awS0lEVw" to "Medienhaus Oetinger",
            // independent producer of his own Hörspiele (Holy Klassiker, Holy Horror)
            "UC_df2qgwDbfuubYMaJ03UVQ" to "David Holy",
        )

        fun channelId(url: String?): String? = url?.let { Regex("/channel/(UC[\\w-]{22})").find(it)?.groupValues?.get(1) }

        /** A rights holder's channel. YouTube's auto-generated "– Thema" channels are not
         * counted: they are mostly musicians (ASMR, sleep music), and audiobooks there
         * come split into short tracks anyway. */
        @Suppress("UNUSED_PARAMETER")
        fun isOfficial(channelId: String?, name: String?): Boolean = channelId in OFFICIAL_CHANNELS

        private fun allowed(channelId: String?, name: String?, officialOnly: Boolean) =
            channelId != null && (!officialOnly || isOfficial(channelId, name))

        fun isFullEpisode(v: StreamInfoItem): Boolean =
            v.duration >= MIN_EPISODE_SECONDS && !v.isShortFormContent && !NOT_AN_EPISODE.containsMatchIn(v.name)

        /** "https://www.youtube.com/watch?v=ID" and the like; the video id, else null. */
        fun videoId(url: String): String? = youTubeVideoId(url)

        /** YouTube Music album playlists ("OLAK5uy_…"). */
        fun isAlbum(listId: String): Boolean = listId.removePrefix("yt:").startsWith("OLAK5uy_")
    }
}
