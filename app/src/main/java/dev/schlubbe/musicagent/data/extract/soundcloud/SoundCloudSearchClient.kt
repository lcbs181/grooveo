package dev.schlubbe.musicagent.data.extract.soundcloud

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.schlubbe.musicagent.data.remote.dto.AlbumResultDto
import dev.schlubbe.musicagent.data.remote.dto.ArtistDetailDto
import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistResultDto
import dev.schlubbe.musicagent.data.remote.dto.RemotePlaylistDetailDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SoundCloudSearchClient @Inject constructor(
    private val api: SoundCloudApi,
) {
    suspend fun search(query: String, limit: Int): List<TrackResultDto> {
        val data = api.get("search/tracks", mapOf("q" to query, "limit" to limit.toString()))
        return data.getAsJsonArray("collection")
            .mapNotNull { it.asJsonObject.toSoundCloudTrackResultDto() }
    }

    suspend fun searchArtists(query: String, limit: Int): List<ArtistResultDto> {
        val data = api.get("search/users", mapOf("q" to query, "limit" to limit.toString()))
        return data.getAsJsonArray("collection")
            .mapNotNull { it.asJsonObject.toSoundCloudArtistResultDto() }
    }

    /** SoundCloud's own global trending chart - the same api-v2 endpoint the real
     * web/app clients use, called directly since it's not something yt-dlp (nor
     * this app's other SoundCloud calls) exposes a dedicated wrapper for. Each
     * item is `{"track": {...}, ...}`, not a flat track object like search results.
     *
     * Only `all-music` works here. Verified against the live API: every other
     * genre identifier (and `kind=top` in any form) now returns 404, so there is
     * no genre-scoped chart to call - see SearchRepository.getTrendingByGenre for
     * what Home's genre shelf does instead. */
    suspend fun getTrending(limit: Int): List<TrackResultDto> {
        val data = api.get(
            "charts",
            mapOf("kind" to "trending", "genre" to "soundcloud:genres:all-music", "limit" to limit.toString()),
        )
        return data.getAsJsonArray("collection")
            .mapNotNull { it.asJsonObject.getAsJsonObject("track")?.toSoundCloudTrackResultDto() }
    }

    // SoundCloud has one search endpoint for both playlists and albums - an album is
    // just a playlist with is_album=true - so both fetch the same raw result set and
    // split it client-side. Over-fetches (limit*3) since filtering by is_album can
    // otherwise leave a near-empty result for whichever type is less common in a
    // given result page.
    private suspend fun searchPlaylistsRaw(query: String, limit: Int): List<JsonObject> {
        val data = api.get("search/playlists", mapOf("q" to query, "limit" to (limit * 3).toString()))
        return data.getAsJsonArray("collection").map { it.asJsonObject }
    }

    suspend fun searchPlaylists(query: String, limit: Int): List<PlaylistResultDto> =
        searchPlaylistsRaw(query, limit)
            .filterNot { it.get("is_album")?.takeIf { v -> !v.isJsonNull }?.asBoolean == true }
            .mapNotNull { it.toSoundCloudPlaylistResultDto() }
            .take(limit)

    suspend fun searchAlbums(query: String, limit: Int): List<AlbumResultDto> =
        searchPlaylistsRaw(query, limit)
            .filter { it.get("is_album")?.takeIf { v -> !v.isJsonNull }?.asBoolean == true }
            .mapNotNull { it.toSoundCloudAlbumResultDto() }
            .take(limit)

    /** [permalink] is a playlist/album's permalink path (e.g. "someuser/some-playlist"),
     * same as [getArtist]'s user permalink convention. SoundCloud only embeds full
     * track objects inline for small playlists/albums - larger ones return {id}-only
     * stubs that would need a further batch endpoint call (a different response shape
     * - a bare JSON array - than every other call in this class expects), so those are
     * skipped rather than special-cased; a very large playlist will only play its
     * initially-embedded tracks. */
    suspend fun getPlaylistTracks(permalink: String): List<TrackResultDto> {
        val playlist = api.get("resolve", mapOf("url" to "https://soundcloud.com/$permalink"))
        val trackRefs = playlist.getAsJsonArray("tracks") ?: return emptyList()
        return trackRefs.mapNotNull { ref ->
            ref.asJsonObject.takeIf { it.has("title") }?.toSoundCloudTrackResultDto()
        }
    }

    /** Same "resolve" fetch as [getPlaylistTracks], but also keeps the playlist's
     * own metadata (title/owner/artwork) instead of discarding it - backs the
     * playlist browse screen reached from a Search result, which needs both at
     * once rather than a second round-trip. */
    suspend fun getPlaylistDetail(permalink: String): RemotePlaylistDetailDto {
        val playlist = api.get("resolve", mapOf("url" to "https://soundcloud.com/$permalink"))
        val meta = playlist.toSoundCloudPlaylistResultDto()
            ?: error("SoundCloud playlist resolve returned an unexpected shape for $permalink")
        val trackRefs = playlist.getAsJsonArray("tracks") ?: JsonArray()
        val tracks = trackRefs.mapNotNull { ref ->
            ref.asJsonObject.takeIf { it.has("title") }?.toSoundCloudTrackResultDto()
        }
        return RemotePlaylistDetailDto(
            source = meta.source,
            sourceId = meta.sourceId,
            title = meta.title,
            thumbnailUrl = meta.thumbnailUrl,
            trackCount = meta.trackCount,
            owner = meta.owner,
            webpageUrl = meta.webpageUrl,
            description = playlist.stringOrNull("description")?.takeIf { it.isNotBlank() },
            tags = parseSoundCloudTagList(playlist.stringOrNull("tag_list")),
            tracks = tracks,
        )
    }

    // SoundCloud's "tag_list" is a single space-separated string where multi-word
    // tags are wrapped in double quotes (e.g. `"hip hop" edm chill`) - not JSON, so
    // it needs its own tiny tokenizer rather than a plain split(" ").
    private fun parseSoundCloudTagList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return Regex("\"([^\"]+)\"|(\\S+)").findAll(raw)
            .map { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }
            .filter { it.isNotBlank() }
            .toList()
    }

    suspend fun getArtist(permalink: String): ArtistDetailDto {
        val user = api.get("resolve", mapOf("url" to "https://soundcloud.com/$permalink"))
        val userId = user.get("id").asLong

        // A single 50-track batch covers both "top" and "latest" - sorting it two
        // different ways client-side instead of issuing a second API call keeps this
        // page to one request, same rate-limit-conscious approach as the follower list.
        val tracksData = api.get(
            "users/$userId/tracks",
            mapOf("limit" to "50", "linked_partitioning" to "1"),
        )
        val rawTracks = tracksData.getAsJsonArray("collection").map { it.asJsonObject }
        val latestTracks = rawTracks.mapNotNull { it.toSoundCloudTrackResultDto() }
        val topTracks = rawTracks
            .sortedByDescending { it.longOrNull("playback_count") ?: 0L }
            .mapNotNull { it.toSoundCloudTrackResultDto() }

        val artist = user.toSoundCloudArtistResultDto()
            ?: error("SoundCloud user resolve returned an unexpected shape for $permalink")

        return ArtistDetailDto(
            source = artist.source,
            sourceId = artist.sourceId,
            name = artist.name,
            thumbnailUrl = artist.thumbnailUrl,
            bannerUrl = user.bannerUrlOrNull(),
            description = user.stringOrNull("description"),
            subscriberCount = artist.subscriberCount,
            topTracks = topTracks,
            latestTracks = latestTracks,
            webpageUrl = artist.webpageUrl,
        )
    }

    // Follower list is loaded on demand only (first tap, then further pages on
    // scroll) - never eagerly from getArtist() - to avoid tripping SoundCloud's rate
    // limiting the same way an unbounded stream-resolve queue did earlier.
    // [cursorUrl] is null for the first page (resolves permalink -> userId itself);
    // for later pages, pass the previous page's [SoundCloudFollowersPage.nextCursorUrl]
    // straight through - it's already a complete api-v2.soundcloud.com URL.
    suspend fun getFollowersPage(permalink: String, cursorUrl: String?): SoundCloudFollowersPage {
        val data = if (cursorUrl != null) {
            api.get(cursorUrl)
        } else {
            val user = api.get("resolve", mapOf("url" to "https://soundcloud.com/$permalink"))
            val userId = user.get("id").asLong
            api.get("users/$userId/followers", mapOf("limit" to "20", "linked_partitioning" to "1"))
        }
        val items = data.getAsJsonArray("collection")
            .mapNotNull { it.asJsonObject.toSoundCloudArtistResultDto() }
        return SoundCloudFollowersPage(items = items, nextCursorUrl = data.stringOrNull("next_href"))
    }
}

data class SoundCloudFollowersPage(
    val items: List<ArtistResultDto>,
    val nextCursorUrl: String?,
)
