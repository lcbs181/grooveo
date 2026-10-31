package dev.schlubbe.musicagent.data.extract.youtube

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.schlubbe.musicagent.data.remote.dto.AlbumResultDto
import dev.schlubbe.musicagent.data.remote.dto.ArtistDetailDto
import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Fetches an artist's page from YouTube Music itself (the `browse` call its web
 * app makes), which carries what a streaming app's artist page shows and the
 * plain YouTube channel does not: monthly audience, play counts, albums and
 * singles with year, "Zu hören in" playlists and "Fans also like". */
@Singleton
class YouTubeMusicBrowseClient @Inject constructor() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun artist(channelId: String, channelUrl: String): YouTubeMusicArtistPage.Page = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            add("context", JsonObject().apply {
                add("client", JsonObject().apply {
                    addProperty("clientName", "WEB_REMIX")
                    addProperty("clientVersion", CLIENT_VERSION)
                    addProperty("hl", "de")
                    addProperty("gl", "DE")
                })
            })
            addProperty("browseId", channelId)
        }
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("User-Agent", USER_AGENT)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "YouTube Music answered ${response.code}" }
            response.body.string()
        }
        YouTubeMusicArtistPage.parse(text, channelUrl)
    }

    companion object {
        private const val CLIENT_VERSION = "1.20251020.01.00"
        private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}

/** Parses YouTube Music's artist `browse` response. Pure (no network, no
 * Android), and tolerant: a shelf it does not recognise is skipped, an item
 * missing its id is dropped. Shelves are recognised by their content and their
 * German titles (the request asks for hl=de). */
object YouTubeMusicArtistPage {

    data class Page(
        val detail: ArtistDetailDto,
        /** Playlist id behind "Top-Songs" - the full list, of which the page only
         * shows five. */
        val topSongsPlaylistId: String?,
    )

    fun parse(json: String, channelUrl: String): Page {
        val root = JsonParser.parseString(json).asJsonObject
        val header = root.obj("header")?.let { h -> h.entrySet().firstOrNull()?.value?.asObj() }
        val name = header?.obj("title")?.runsText()?.takeIf { it.isNotBlank() } ?: error("no artist name")
        val image = header.obj("thumbnail")?.bestThumbnail()
        val monthly = header.obj("monthlyListenerCount")?.runsText()?.substringBefore(' ')?.takeIf { it.any(Char::isDigit) }
        val subscribers = header.obj("subscriptionButton")?.obj("subscribeButtonRenderer")
            ?.obj("subscriberCountText")?.runsText()?.takeIf { it.any(Char::isDigit) }
        var description = header.obj("description")?.runsText()

        val sections = root.obj("contents")?.obj("singleColumnBrowseResultsRenderer")?.arr("tabs")
            ?.firstOrNull()?.asObj()?.obj("tabRenderer")?.obj("content")?.obj("sectionListRenderer")?.arr("contents")
            ?: JsonArray()

        var topTracks = emptyList<TrackResultDto>()
        var topSongsPlaylist: String? = null
        val albums = ArrayList<AlbumResultDto>()
        val singles = ArrayList<AlbumResultDto>()
        val videos = ArrayList<TrackResultDto>()
        val featuredOn = ArrayList<PlaylistResultDto>()
        val playlists = ArrayList<PlaylistResultDto>()
        val related = ArrayList<ArtistResultDto>()

        for (section in sections) {
            val s = section.asObj() ?: continue
            s.obj("musicShelfRenderer")?.let { shelf ->
                topTracks = shelf.arr("contents").orEmpty().mapNotNull { it.asObj()?.obj("musicResponsiveListItemRenderer")?.let(::song) }
                topSongsPlaylist = shelf.obj("bottomEndpoint")?.obj("browseEndpoint")?.str("browseId")?.removePrefix("VL")
                    ?: shelf.obj("title")?.arr("runs")?.firstOrNull()?.asObj()?.obj("navigationEndpoint")
                        ?.obj("browseEndpoint")?.str("browseId")?.removePrefix("VL")
            }
            s.obj("musicDescriptionShelfRenderer")?.let { shelf ->
                if (description.isNullOrBlank()) description = shelf.obj("description")?.runsText()
            }
            val carousel = s.obj("musicCarouselShelfRenderer") ?: continue
            val title = carousel.obj("header")?.obj("musicCarouselShelfBasicHeaderRenderer")?.obj("title")?.runsText().orEmpty()
            val items = carousel.arr("contents").orEmpty().mapNotNull { it.asObj()?.obj("musicTwoRowItemRenderer") }
            val t = title.lowercase()
            when {
                t.startsWith("alben") || t == "albums" -> items.mapNotNullTo(albums) { album(it, name, "Album") }
                t.startsWith("singles") -> items.mapNotNullTo(singles) { album(it, name, null) }
                t == "videos" -> items.mapNotNullTo(videos) { video(it) }
                t.startsWith("zu hören in") || t.startsWith("featured on") -> items.mapNotNullTo(featuredOn) { playlist(it, name) }
                t.startsWith("playlists") -> items.mapNotNullTo(playlists) { playlist(it, name) }
                t.startsWith("das könnte fans") || t.startsWith("fans might") || t.startsWith("ähnliche") ->
                    items.mapNotNullTo(related) { artist(it) }
            }
        }

        val detail = ArtistDetailDto(
            source = "ytmusic",
            sourceId = channelUrl,
            name = name,
            thumbnailUrl = image?.let(::squareImage),
            bannerUrl = image,
            description = description?.let(::cleanDescription)?.takeIf { it.isNotBlank() },
            subscriberCount = subscribers,
            topTracks = topTracks,
            latestTracks = emptyList(),
            albums = albums,
            playlists = playlists,
            webpageUrl = channelUrl,
            monthlyListeners = monthly,
            singles = singles,
            videos = videos,
            featuredOn = featuredOn,
            related = related,
        )
        return Page(detail, topSongsPlaylist)
    }

    private fun song(r: JsonObject): TrackResultDto? {
        val columns = r.arr("flexColumns").orEmpty().map {
            it.asObj()?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")
        }
        val titleRun = columns.getOrNull(0)?.arr("runs")?.firstOrNull()?.asObj() ?: return null
        val videoId = titleRun.obj("navigationEndpoint")?.obj("watchEndpoint")?.str("videoId")
            ?: r.obj("playlistItemData")?.str("videoId")
            ?: return null
        val artist = columns.getOrNull(1)?.runsText()?.takeIf { it.isNotBlank() }
        val plays = columns.drop(2).mapNotNull { it?.runsText() }.firstOrNull { "wiedergabe" in it.lowercase() || "plays" in it.lowercase() }
        val album = columns.drop(2).mapNotNull { it?.runsText() }.lastOrNull { it != plays && it.isNotBlank() }
        return TrackResultDto(
            source = "ytmusic",
            sourceId = videoId,
            title = titleRun.str("text") ?: return null,
            artist = artist,
            album = album,
            durationSec = null,
            thumbnailUrl = r.obj("thumbnail")?.bestThumbnail()?.let(::squareImage),
            webpageUrl = "https://music.youtube.com/watch?v=$videoId",
            plays = plays,
        )
    }

    private fun album(r: JsonObject, artist: String, defaultKind: String?): AlbumResultDto? {
        val playlistId = overlayPlaylistId(r) ?: return null
        val parts = r.obj("subtitle")?.arr("runs").orEmpty().mapNotNull { it.asObj()?.str("text")?.replace('\u00A0', ' ')?.trim() }
            .filter { it.isNotEmpty() && it != "•" }
        val year = parts.firstOrNull { it.length == 4 && it.all(Char::isDigit) }
        val kind = parts.firstOrNull { it in KINDS } ?: defaultKind
        val url = "https://www.youtube.com/playlist?list=$playlistId"
        return AlbumResultDto(
            source = "ytmusic",
            sourceId = url,
            title = r.obj("title")?.runsText() ?: return null,
            artist = artist,
            thumbnailUrl = r.obj("thumbnailRenderer")?.bestThumbnail()?.let(::squareImage),
            year = year,
            webpageUrl = url,
            kind = kind,
        )
    }

    private fun video(r: JsonObject): TrackResultDto? {
        val videoId = r.obj("navigationEndpoint")?.obj("watchEndpoint")?.str("videoId") ?: return null
        val sub = r.obj("subtitle")?.arr("runs").orEmpty().mapNotNull { it.asObj()?.str("text")?.replace('\u00A0', ' ')?.trim() }
            .filter { it.isNotEmpty() && it != "•" }
        return TrackResultDto(
            source = "ytmusic",
            sourceId = videoId,
            title = r.obj("title")?.runsText() ?: return null,
            artist = sub.firstOrNull(),
            album = null,
            durationSec = null,
            thumbnailUrl = r.obj("thumbnailRenderer")?.bestThumbnail(),
            webpageUrl = "https://music.youtube.com/watch?v=$videoId",
            plays = sub.getOrNull(1),
        )
    }

    private fun playlist(r: JsonObject, artist: String): PlaylistResultDto? {
        val id = overlayPlaylistId(r)
            ?: r.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId")?.removePrefix("VL")
            ?: return null
        val sub = r.obj("subtitle")?.arr("runs").orEmpty().mapNotNull { it.asObj()?.str("text")?.replace('\u00A0', ' ')?.trim() }
            .filter { it.isNotEmpty() && it != "•" && it != "Playlist" }
        val url = "https://www.youtube.com/playlist?list=$id"
        return PlaylistResultDto(
            source = "ytmusic",
            sourceId = url,
            title = r.obj("title")?.runsText()?.trim() ?: return null,
            thumbnailUrl = r.obj("thumbnailRenderer")?.bestThumbnail(),
            trackCount = null,
            // the channel is often written "KRAFTKLUB" - spell it like the page
            owner = sub.firstOrNull()?.let { if (it.equals(artist, ignoreCase = true)) artist else it },
            webpageUrl = url,
        )
    }

    private fun artist(r: JsonObject): ArtistResultDto? {
        val id = r.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId")?.takeIf { it.startsWith("UC") } ?: return null
        val url = "https://www.youtube.com/channel/$id"
        return ArtistResultDto(
            source = "ytmusic",
            sourceId = url,
            name = r.obj("title")?.runsText() ?: return null,
            thumbnailUrl = r.obj("thumbnailRenderer")?.bestThumbnail()?.let(::squareImage),
            subscriberCount = r.obj("subtitle")?.runsText(),
            webpageUrl = url,
        )
    }

    private val KINDS = setOf("Album", "Single", "EP")

    private fun overlayPlaylistId(r: JsonObject): String? =
        r.obj("thumbnailOverlay")?.obj("musicItemThumbnailOverlayRenderer")?.obj("content")
            ?.obj("musicPlayButtonRenderer")?.obj("playNavigationEndpoint")?.let { e ->
                e.obj("watchPlaylistEndpoint")?.str("playlistId") ?: e.obj("watchEndpoint")?.str("playlistId")
            }

    /** Wikipedia blurbs end in "Quelle: Wikipedia (url), lizenziert unter ..." -
     * cut at the source line. */
    internal fun cleanDescription(text: String): String =
        text.substringBefore("\n\nQuelle:").substringBefore("\n\nFrom Wikipedia").trim()

    /** Google image URLs carry their size as "=w540-h225-..."; ask for a square,
     * centre-cropped 544 px version instead. Other URLs are returned unchanged. */
    internal fun squareImage(url: String): String {
        if ("googleusercontent.com" !in url) return url
        val base = url.substringBefore('=')
        return "$base=w544-h544-p-l90-rj"
    }

    private fun JsonObject.bestThumbnail(): String? {
        val list = obj("musicThumbnailRenderer")?.obj("thumbnail")?.arr("thumbnails")
            ?: obj("thumbnail")?.arr("thumbnails")
            ?: arr("thumbnails")
        return list.orEmpty().mapNotNull { it.asObj() }.maxByOrNull { it.get("width")?.asInt ?: 0 }?.str("url")
    }

    private fun JsonObject.runsText(): String =
        arr("runs").orEmpty().joinToString("") { it.asObj()?.str("text").orEmpty() }.replace('\u00A0', ' ').replace('\u202F', ' ')

    private fun JsonElement.asObj(): JsonObject? = if (isJsonObject) asJsonObject else null
    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject?.arr(key: String): JsonArray? = this?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
}
