package dev.schlubbe.musicagent.desktop.data

import dev.schlubbe.musicagent.data.extract.soundcloud.SoundCloudApi
import dev.schlubbe.musicagent.data.extract.soundcloud.stringOrNull
import dev.schlubbe.musicagent.data.extract.soundcloud.toSoundCloudArtistResultDto
import dev.schlubbe.musicagent.data.extract.soundcloud.toSoundCloudPlaylistResultDto
import dev.schlubbe.musicagent.data.extract.soundcloud.toSoundCloudTrackResultDto
import dev.schlubbe.musicagent.data.extract.youtube.YouTubeMusicSearchClient
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI

/** What a pasted SoundCloud or YouTube (Music) link points at. */
sealed interface LinkTarget {
    data class Track(val track: TrackResultDto) : LinkTarget
    data class Playlist(val source: String, val sourceId: String, val title: String?) : LinkTarget
    data class Artist(val source: String, val sourceId: String, val name: String?) : LinkTarget
}

/** Opens links pasted into the search field, like the phone app's SharedLinkResolver. */
class LinkResolver(private val http: OkHttpClient, private val soundCloud: SoundCloudApi, private val youTube: YouTubeMusicSearchClient) {

    /** A link worth resolving: http(s) on a SoundCloud or YouTube host. */
    fun isLink(text: String): Boolean = hostOf(text.trim())?.let { it in YOUTUBE_HOSTS || it in SOUNDCLOUD_HOSTS } == true

    suspend fun resolve(text: String): LinkTarget? = withContext(Dispatchers.IO) {
        val url = text.trim()
        val uri = runCatching { URI(url) }.getOrNull() ?: return@withContext null
        when (uri.host?.lowercase()) {
            in YOUTUBE_HOSTS -> youTube(uri)
            in SOUNDCLOUD_HOSTS -> soundCloud(url, uri.host.lowercase())
            else -> null
        }
    }

    private suspend fun youTube(uri: URI): LinkTarget? {
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val videoId = query["v"]
            ?: segments.firstOrNull()?.takeIf { uri.host.equals("youtu.be", true) }
            ?: segments.indexOf("shorts").takeIf { it >= 0 }?.let { segments.getOrNull(it + 1) }
        if (videoId != null) return LinkTarget.Track(youTube.getTrack(videoId))
        query["list"]?.let { return LinkTarget.Playlist("ytmusic", "https://www.youtube.com/playlist?list=$it", null) }
        val path = uri.path.orEmpty()
        if (path.startsWith("/channel/") || path.startsWith("/@")) return LinkTarget.Artist("ytmusic", "https://www.youtube.com$path", null)
        return null
    }

    private suspend fun soundCloud(url: String, host: String): LinkTarget? {
        // on.soundcloud.com short links only say where they lead once requested
        val full = if (host == "on.soundcloud.com") {
            runCatching { http.newCall(Request.Builder().url(url).build()).execute().use { it.request.url.toString() } }.getOrNull() ?: return null
        } else url
        val r = soundCloud.get("resolve", mapOf("url" to full))
        return when (r.stringOrNull("kind")) {
            "track" -> r.toSoundCloudTrackResultDto()?.let { LinkTarget.Track(it) }
            "playlist" -> r.toSoundCloudPlaylistResultDto()?.let { LinkTarget.Playlist(it.source, it.sourceId, it.title) }
            "user" -> r.toSoundCloudArtistResultDto()?.let { LinkTarget.Artist(it.source, it.sourceId, it.name) }
            else -> null
        }
    }

    private fun hostOf(text: String): String? =
        text.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }

    private companion object {
        val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")
        val SOUNDCLOUD_HOSTS = setOf("soundcloud.com", "www.soundcloud.com", "m.soundcloud.com", "on.soundcloud.com")
    }
}
