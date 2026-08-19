package dev.schlubbe.musicagent.data.repository

import dev.schlubbe.musicagent.data.extract.soundcloud.SoundCloudFollowersPage
import dev.schlubbe.musicagent.data.extract.soundcloud.SoundCloudSearchClient
import dev.schlubbe.musicagent.data.extract.youtube.YouTubeMusicSearchClient
import dev.schlubbe.musicagent.data.remote.dto.AlbumResultDto
import dev.schlubbe.musicagent.data.remote.dto.ArtistDetailDto
import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/** Same public contract as the server-backed app's SearchRepository (so
 * SearchViewModel/ArtistViewModel/etc. need no changes at all) but fans out to the
 * two on-device extraction clients instead of a backend call. For source="all",
 * both sources run concurrently and independently — one source's extractor
 * breaking (e.g. after a YouTube change) no longer takes down "all"-source search
 * entirely, since each is wrapped in its own runCatching. */
@Singleton
class SearchRepository @Inject constructor(
    private val soundCloud: SoundCloudSearchClient,
    private val youTube: YouTubeMusicSearchClient,
) {
    suspend fun search(query: String, source: String = "all", limit: Int = 25): List<TrackResultDto> =
        when (source) {
            "soundcloud" -> soundCloud.search(query, limit)
            "ytmusic" -> youTube.search(query, limit)
            else -> coroutineScope {
                val sc = async { runCatching { soundCloud.search(query, limit) }.getOrDefault(emptyList()) }
                val yt = async { runCatching { youTube.search(query, limit) }.getOrDefault(emptyList()) }
                sc.await() + yt.await()
            }
        }

    suspend fun searchArtists(query: String, source: String = "all", limit: Int = 25): List<ArtistResultDto> =
        when (source) {
            "soundcloud" -> soundCloud.searchArtists(query, limit)
            "ytmusic" -> youTube.searchArtists(query, limit)
            else -> coroutineScope {
                val sc = async { runCatching { soundCloud.searchArtists(query, limit) }.getOrDefault(emptyList()) }
                val yt = async { runCatching { youTube.searchArtists(query, limit) }.getOrDefault(emptyList()) }
                sc.await() + yt.await()
            }
        }

    suspend fun searchPlaylists(query: String, source: String = "all", limit: Int = 25): List<PlaylistResultDto> =
        when (source) {
            "soundcloud" -> soundCloud.searchPlaylists(query, limit)
            "ytmusic" -> youTube.searchPlaylists(query, limit)
            else -> coroutineScope {
                val sc = async { runCatching { soundCloud.searchPlaylists(query, limit) }.getOrDefault(emptyList()) }
                val yt = async { runCatching { youTube.searchPlaylists(query, limit) }.getOrDefault(emptyList()) }
                sc.await() + yt.await()
            }
        }

    suspend fun searchAlbums(query: String, source: String = "all", limit: Int = 25): List<AlbumResultDto> =
        when (source) {
            "soundcloud" -> soundCloud.searchAlbums(query, limit)
            "ytmusic" -> youTube.searchAlbums(query, limit)
            else -> coroutineScope {
                val sc = async { runCatching { soundCloud.searchAlbums(query, limit) }.getOrDefault(emptyList()) }
                val yt = async { runCatching { youTube.searchAlbums(query, limit) }.getOrDefault(emptyList()) }
                sc.await() + yt.await()
            }
        }

    // Used to open a playlist/album search result and play its contents - same
    // sourceId convention as getArtist (an opaque per-source id, not necessarily a
    // structured browseId).
    suspend fun getPlaylistTracks(source: String, sourceId: String): List<TrackResultDto> = when (source) {
        "soundcloud" -> soundCloud.getPlaylistTracks(sourceId)
        "ytmusic" -> youTube.getPlaylistTracks(sourceId)
        else -> error("unknown source: $source")
    }

    /** Global trending charts, independent of any local history - both sources run
     * concurrently and independently, same fan-out/isolation pattern as [search]. */
    suspend fun getTrending(limit: Int = 20): List<TrackResultDto> = coroutineScope {
        val sc = async { runCatching { soundCloud.getTrending(limit) }.getOrDefault(emptyList()) }
        val yt = async { runCatching { youTube.getTrending(limit) }.getOrDefault(emptyList()) }
        sc.await() + yt.await()
    }

    suspend fun getArtist(source: String, sourceId: String): ArtistDetailDto = when (source) {
        "soundcloud" -> soundCloud.getArtist(sourceId)
        "ytmusic" -> youTube.getArtist(sourceId)
        else -> error("unknown source: $source")
    }

    // Follower lists only exist for SoundCloud (YouTube exposes no public subscriber
    // list) - callers only invoke this for source == "soundcloud" artists.
    suspend fun getFollowersPage(sourceId: String, cursorUrl: String?): SoundCloudFollowersPage =
        soundCloud.getFollowersPage(sourceId, cursorUrl)
}
