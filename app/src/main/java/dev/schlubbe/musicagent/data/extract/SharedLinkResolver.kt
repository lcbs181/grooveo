package dev.schlubbe.musicagent.data.extract

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.util.Patterns
import dev.schlubbe.musicagent.data.extract.di.ExtractionHttpClient
import dev.schlubbe.musicagent.data.extract.soundcloud.SoundCloudApi
import dev.schlubbe.musicagent.data.extract.soundcloud.stringOrNull
import dev.schlubbe.musicagent.data.extract.soundcloud.toSoundCloudArtistResultDto
import dev.schlubbe.musicagent.data.extract.soundcloud.toSoundCloudPlaylistResultDto
import dev.schlubbe.musicagent.data.extract.soundcloud.toSoundCloudTrackResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SharedLinkResolver"

private val YOUTUBE_HOSTS = setOf(
    "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be",
)
private val SOUNDCLOUD_HOSTS = setOf("soundcloud.com", "m.soundcloud.com", "on.soundcloud.com")

/** What a resolved shared link (see [SharedLinkResolver]) should do once tapped. */
sealed class SharedLinkTarget {
    data class Track(val track: TrackResultDto) : SharedLinkTarget()
    data class Playlist(val source: String, val sourceId: String) : SharedLinkTarget()
    data class Artist(val source: String, val sourceId: String) : SharedLinkTarget()
}

/** Turns a SoundCloud/YouTube link shared into the app (ACTION_VIEW on a matching
 * host, or a shared ACTION_SEND text containing one — see the manifest's intent
 * filters on MainActivity) into something the app can actually open. Mirrors the
 * sourceId conventions the search clients already use ([TrackResultDto.sourceId] etc.)
 * so the result plugs straight into [dev.schlubbe.musicagent.data.repository.SearchRepository]
 * and [dev.schlubbe.musicagent.playback.PlayerController] without any special-casing
 * downstream. */
@Singleton
class SharedLinkResolver @Inject constructor(
    @ExtractionHttpClient private val client: OkHttpClient,
    private val soundCloudApi: SoundCloudApi,
) {
    /** First URL found in the intent's data (ACTION_VIEW) or EXTRA_TEXT (a shared
     * ACTION_SEND, which can be an arbitrary sentence with a link buried in it). */
    fun extractUrl(intent: Intent): String? {
        intent.data?.toString()?.let { return it }
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        val matcher = Patterns.WEB_URL.matcher(sharedText)
        return if (matcher.find()) matcher.group() else null
    }

    suspend fun resolve(intent: Intent): SharedLinkTarget? {
        val url = extractUrl(intent) ?: return null
        return resolveUrl(url)
    }

    suspend fun resolveUrl(urlString: String): SharedLinkTarget? = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(urlString) }.getOrNull() ?: return@withContext null
        val host = uri.host?.lowercase() ?: return@withContext null
        runCatching {
            when {
                host in YOUTUBE_HOSTS -> resolveYouTube(uri)
                host in SOUNDCLOUD_HOSTS -> resolveSoundCloud(uri, host)
                else -> null
            }
        }.onFailure {
            Log.w(TAG, "failed to resolve shared link $urlString", it)
        }.getOrNull()
    }

    private suspend fun resolveYouTube(uri: Uri): SharedLinkTarget? {
        extractYouTubeVideoId(uri)?.let { videoId -> return resolveYouTubeTrack(videoId) }

        val listId = uri.getQueryParameter("list")
        if (listId != null) {
            return SharedLinkTarget.Playlist(
                source = "ytmusic",
                sourceId = "https://www.youtube.com/playlist?list=$listId",
            )
        }

        val path = uri.path.orEmpty()
        if (path.startsWith("/channel/") || path.startsWith("/@")) {
            return SharedLinkTarget.Artist(source = "ytmusic", sourceId = "https://www.youtube.com$path")
        }
        return null
    }

    // watch?v=ID (any YouTube host, including music.youtube.com), youtu.be/ID and
    // /shorts/ID all identify the same kind of thing - a single playable video -
    // just spelled differently depending on where the link came from.
    private fun extractYouTubeVideoId(uri: Uri): String? {
        uri.getQueryParameter("v")?.let { return it }
        val segments = uri.pathSegments
        if (uri.host?.lowercase() == "youtu.be" && segments.isNotEmpty()) return segments[0]
        val shortsIndex = segments.indexOf("shorts")
        if (shortsIndex >= 0 && segments.size > shortsIndex + 1) return segments[shortsIndex + 1]
        return null
    }

    /** Fetches real title/artist/thumbnail/duration via NewPipe (same call
     * [dev.schlubbe.musicagent.data.extract.youtube.YouTubeStreamResolver] makes to
     * resolve playback) rather than shipping a bare id with placeholder metadata -
     * the Player screen would otherwise show nothing useful until playback starts. */
    private suspend fun resolveYouTubeTrack(videoId: String): SharedLinkTarget.Track = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        SharedLinkTarget.Track(
            TrackResultDto(
                source = "ytmusic",
                sourceId = videoId,
                title = info.name,
                artist = info.uploaderName,
                album = null,
                durationSec = info.duration.takeIf { it > 0 }?.toInt(),
                thumbnailUrl = info.thumbnails.maxByOrNull { it.height }?.url,
                webpageUrl = "https://music.youtube.com/watch?v=$videoId",
            ),
        )
    }

    /** [host] "on.soundcloud.com" short links carry no path info at all - they only
     * resolve once actually requested, so the redirect has to be followed before
     * SoundCloud's own `resolve` endpoint (which needs a real soundcloud.com url)
     * can be called. */
    private suspend fun resolveSoundCloud(uri: Uri, host: String): SharedLinkTarget? {
        val resolvedUrl = if (host == "on.soundcloud.com") followRedirect(uri.toString()) else uri.toString()
        if (resolvedUrl == null) return null

        val resource = soundCloudApi.get("resolve", mapOf("url" to resolvedUrl))
        return when (resource.stringOrNull("kind")) {
            "track" -> resource.toSoundCloudTrackResultDto()?.let { SharedLinkTarget.Track(it) }
            "playlist" -> resource.toSoundCloudPlaylistResultDto()
                ?.let { SharedLinkTarget.Playlist(source = it.source, sourceId = it.sourceId) }
            "user" -> resource.toSoundCloudArtistResultDto()
                ?.let { SharedLinkTarget.Artist(source = it.source, sourceId = it.sourceId) }
            else -> null
        }
    }

    private fun followRedirect(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { it.request.url.toString() }
    }.getOrNull()
}
