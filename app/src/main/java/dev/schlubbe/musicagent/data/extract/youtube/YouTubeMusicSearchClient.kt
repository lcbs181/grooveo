package dev.schlubbe.musicagent.data.extract.youtube

import android.net.Uri
import android.util.Log
import dev.schlubbe.musicagent.data.remote.dto.AlbumResultDto
import dev.schlubbe.musicagent.data.remote.dto.ArtistDetailDto
import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistResultDto
import dev.schlubbe.musicagent.data.remote.dto.RemotePlaylistDetailDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "YouTubeMusicSearchClient"

/** On-device replacement for the (now-removed) backend's ytmusic_service.py
 * (ytmusicapi). NewPipeExtractor's YouTube search supports dedicated YouTube-Music
 * content filters (MUSIC_SONGS/MUSIC_ARTISTS, confirmed present in the actual
 * v0.26.5 artifact — see YoutubeSearchQueryHandlerFactory), so this is a fairly
 * close match rather than falling back to generic video search. */
@Singleton
class YouTubeMusicSearchClient @Inject constructor() {

    suspend fun search(query: String, limit: Int): List<TrackResultDto> = withContext(Dispatchers.IO) {
        val handler = ServiceList.YouTube.searchQHFactory
            .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        val info = SearchInfo.getInfo(ServiceList.YouTube, handler)
        info.relatedItems.filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toTrackResultDto() }
            .take(limit)
    }

    /** Plain YouTube video search (not YT Music songs) - some official releases only
     * exist as label-channel videos. Used by YouTubeFallback as a last resort. */
    suspend fun searchVideos(query: String, limit: Int): List<TrackResultDto> = withContext(Dispatchers.IO) {
        val handler = ServiceList.YouTube.searchQHFactory
            .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        val info = SearchInfo.getInfo(ServiceList.YouTube, handler)
        info.relatedItems.filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toTrackResultDto() }
            .take(limit)
    }

    /** YouTube's own search-as-you-type suggestions - the same list its search box
     * shows, so a half-typed or misspelled artist still leads somewhere. */
    suspend fun suggestions(query: String, limit: Int): List<String> = withContext(Dispatchers.IO) {
        ServiceList.YouTube.suggestionExtractor
            ?.suggestionList(query)
            .orEmpty()
            .take(limit)
    }

    suspend fun searchArtists(query: String, limit: Int): List<ArtistResultDto> = withContext(Dispatchers.IO) {
        val handler = ServiceList.YouTube.searchQHFactory
            .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS), "")
        val info = SearchInfo.getInfo(ServiceList.YouTube, handler)
        info.relatedItems.filterIsInstance<ChannelInfoItem>()
            .mapNotNull { it.toArtistResultDto() }
            .take(limit)
    }

    /** NewPipeExtractor v0.26.5 registers a dedicated "trending_music" kiosk for
     * YouTube (`YoutubeTrendingMusicExtractor`, confirmed present via bytecode
     * inspection of the artifact) - the closest real equivalent of ytmusicapi's
     * `FEmusic_charts`, and already music-only unlike the generic "Trending" kiosk.
     * Kiosks are looked up by id via [org.schabi.newpipe.extractor.kiosk.KioskList]
     * (`KioskInfo.getInfo(service, url)` is a different overload entirely - it
     * resolves the second argument as a URL via a linkHandler match, not a kiosk
     * id, and throws ExtractionException("Could not find a kiosk that fits to the
     * url: ...") for a bare id like "Trending"). */
    suspend fun getTrending(limit: Int): List<TrackResultDto> = withContext(Dispatchers.IO) {
        val extractor = ServiceList.YouTube.kioskList.getExtractorById("trending_music", null)
        extractor.fetchPage()
        val info = KioskInfo.getInfo(extractor)
        info.relatedItems.filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toTrackResultDto() }
            .take(limit)
    }

    suspend fun searchPlaylists(query: String, limit: Int): List<PlaylistResultDto> = withContext(Dispatchers.IO) {
        val handler = ServiceList.YouTube.searchQHFactory
            .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS), "")
        val info = SearchInfo.getInfo(ServiceList.YouTube, handler)
        info.relatedItems.filterIsInstance<PlaylistInfoItem>()
            .map { it.toPlaylistResultDto() }
            .take(limit)
    }

    suspend fun searchAlbums(query: String, limit: Int): List<AlbumResultDto> = withContext(Dispatchers.IO) {
        val handler = ServiceList.YouTube.searchQHFactory
            .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS), "")
        val info = SearchInfo.getInfo(ServiceList.YouTube, handler)
        info.relatedItems.filterIsInstance<PlaylistInfoItem>()
            .map { it.toAlbumResultDto() }
            .take(limit)
    }

    /** [playlistUrl] is a YT playlist/album's own url (used as PlaylistResultDto/
     * AlbumResultDto.sourceId, same "opaque id from the app's point of view"
     * convention as the artist channelUrl above). Unlike SoundCloud, NewPipeExtractor
     * gives a full track list directly, no stub/batch-refetch complication. */
    suspend fun getPlaylistTracks(playlistUrl: String): List<TrackResultDto> = withContext(Dispatchers.IO) {
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, playlistUrl)
        info.relatedItems.filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toTrackResultDto() }
    }

    /** Same fetch as [getPlaylistTracks], but also keeps the playlist's own name/
     * uploader/thumbnail instead of discarding them - backs the playlist browse
     * screen reached from a Search result. */
    suspend fun getPlaylistDetail(playlistUrl: String): RemotePlaylistDetailDto = withContext(Dispatchers.IO) {
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, playlistUrl)
        val tracks = info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrackResultDto() }
        RemotePlaylistDetailDto(
            source = "ytmusic",
            sourceId = playlistUrl,
            // Album playlists (OLAK5uy_...) come back named "Album – <title>" with
            // no uploader - use the tracks' own artist instead of a blank owner.
            title = info.name.removePrefix("Album – ").removePrefix("Album - "),
            thumbnailUrl = info.thumbnails.maxByOrNull { it.height }?.url,
            trackCount = tracks.size,
            owner = info.uploaderName?.takeIf { it.isNotBlank() }
                ?: tracks.mapNotNull { it.artist }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
            webpageUrl = playlistUrl,
            // PlaylistInfo.description is a Description wrapper (unlike ChannelInfo's
            // plain String used for artist bios above) - its content can be HTML for
            // some playlists, so tags are stripped before display.
            description = info.description?.content
                ?.let { Regex("<[^>]*>").replace(it, "") }
                ?.trim()
                ?.takeIf { it.isNotBlank() },
            // NewPipeExtractor's PlaylistInfo has no tag/keyword field for playlists
            // (unlike SoundCloud's tag_list) - always empty for ytmusic.
            tags = emptyList(),
            tracks = tracks,
        )
    }

    private fun PlaylistInfoItem.toPlaylistResultDto(): PlaylistResultDto = PlaylistResultDto(
        source = "ytmusic",
        sourceId = url,
        title = name,
        thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url,
        trackCount = streamCount.takeIf { it >= 0 }?.toInt(),
        owner = uploaderName,
        webpageUrl = url,
    )

    private fun PlaylistInfoItem.toAlbumResultDto(): AlbumResultDto = AlbumResultDto(
        source = "ytmusic",
        sourceId = url,
        title = name,
        artist = uploaderName,
        thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url,
        year = null, // NewPipeExtractor's PlaylistInfoItem carries no release-year field
        webpageUrl = url,
    )

    /** [channelUrl] is the full YouTube channel URL, used directly as the artist's
     * sourceId (see ArtistResultDto.sourceId — an opaque id from the app's point of
     * view, doesn't need to look like ytmusicapi's browseId).
     *
     * Every tab fetch is wrapped individually: previously a single failing tab
     * (e.g. a channel with an unusual/missing TRACKS or VIDEOS tab) threw out of the
     * whole method, so the artist page failed completely instead of at least showing
     * the profile. Now the page degrades gracefully — profile info loads as long as
     * [ChannelInfo.getInfo] itself succeeds, and song shelves are simply empty if
     * their tab can't be fetched. */
    suspend fun getArtist(channelUrl: String): ArtistDetailDto = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(ServiceList.YouTube, channelUrl)

        // ChannelTabs.TRACKS is what an official-artist YouTube channel exposes for
        // its music catalog (closest match to ytmusicapi's "top songs"); ordinary
        // channels only have a VIDEOS tab, used as a fallback so artist pages still
        // show *something* for non-music-official channels. ALBUMS/PLAYLISTS are
        // fetched the same way - an auto-generated "- Topic" channel (see below)
        // typically only has these two, not TRACKS/VIDEOS.
        val tracksTab = info.tabs.firstOrNull { ChannelTabs.TRACKS in it.contentFilters }
        val videosTab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
        val albumsTab = info.tabs.firstOrNull { ChannelTabs.ALBUMS in it.contentFilters }
        val playlistsTab = info.tabs.firstOrNull { ChannelTabs.PLAYLISTS in it.contentFilters }

        // All four tabs are fetched concurrently rather than one after another - this
        // block is already inside withContext(Dispatchers.IO), which hands out a
        // CoroutineScope, so `async` works directly here. Each fetch is still wrapped
        // individually (fetchTabTracks/fetchTabPlaylistItems), so one tab failing
        // can't fail or block the others.
        val tracksDeferred = async { tracksTab?.let { fetchTabTracks(it) } }
        val videosDeferred = async { videosTab?.let { fetchTabTracks(it) } }
        val albumsDeferred = async { albumsTab?.let { fetchTabPlaylistItems(it) } }
        val playlistsDeferred = async { playlistsTab?.let { fetchTabPlaylistItems(it) } }

        var tracksTabItems = tracksDeferred.await()
        var videosTabItems = videosDeferred.await()
        val albumItems = albumsDeferred.await().orEmpty()
        val playlistItems = playlistsDeferred.await().orEmpty()

        // Auto-generated "<Artist> - Topic" channels (YouTube's stand-in for artists
        // with no manually managed channel - common for uploads distributed via a
        // label/aggregator, e.g. observed live on a Travis Scott page, and on every
        // "- Topic" channel in general) sometimes expose neither a TRACKS- nor
        // VIDEOS-tagged tab through NewPipeExtractor's channel model, even though the
        // channel page itself clearly has uploads. Checked by emptiness, not just
        // nullness: a "- Topic" channel's VIDEOS tab reliably resolves without
        // throwing (so tracksTabItems/videosTabItems come back as a real, non-null
        // emptyList(), not null) but returns zero items due to how NewPipeExtractor
        // parses that channel type's non-standard tab layout - the previous
        // null-only check skipped the fallback entirely for this exact case, leaving
        // the whole artist page blank below the header (no crash, no log, nothing
        // rendered - the bug a real "- Topic" channel screenshot surfaced). Rather
        // than silently showing an empty artist page, fall back to whatever tab IS
        // listed - logged first, since this is exactly the kind of channel-structure
        // edge case worth having concrete data on if it recurs.
        if (tracksTabItems.isNullOrEmpty() && videosTabItems.isNullOrEmpty() && info.tabs.isNotEmpty()) {
            Log.w(
                TAG,
                "no usable TRACKS/VIDEOS tab for $channelUrl - falling back to first available " +
                    "(all tabs' contentFilters: ${info.tabs.map { it.contentFilters }})",
            )
            videosTabItems = info.tabs
                .asSequence()
                .filter { it != tracksTab && it != videosTab && it != albumsTab && it != playlistsTab }
                .map { fetchTabTracks(it) }
                .firstOrNull { !it.isNullOrEmpty() }
                ?: fetchTabTracks(info.tabs.first())
        }

        // "Top" prefers the dedicated music tab (already curated); "latest" prefers
        // the chronological videos tab. Either falls back to whichever tab actually
        // came back with real items so both shelves still show something on channels
        // that only expose one of the two tabs - checked by emptiness (isNullOrEmpty),
        // not just nullness, for the same reason as the fallback trigger above: an
        // empty-but-non-null list must still fall through to the other candidate.
        var topTracks = (tracksTabItems.takeIf { !it.isNullOrEmpty() } ?: videosTabItems ?: emptyList()).take(20)
        val latestTracks = (videosTabItems.takeIf { !it.isNullOrEmpty() } ?: tracksTabItems ?: emptyList()).take(20)

        // A "- Topic" channel typically has no usable TRACKS/VIDEOS tab at all (see
        // above) but does have an ALBUMS tab - its actual catalog just lives one level
        // down, per-album. Without this, such a channel's Play/Shuffle controls stay
        // disabled (hasTracks false) even though it clearly has music. Only the first
        // album is fetched, not all of them, to keep this one extra request instead of
        // N - "Top-Titel" doesn't need to be exhaustive, just non-empty.
        if (topTracks.isEmpty() && latestTracks.isEmpty() && albumItems.isNotEmpty()) {
            topTracks = runCatching { getPlaylistDetail(albumItems.first().url).tracks }
                .getOrDefault(emptyList())
                .take(20)
        }

        // Many "- Topic" channels expose no tabs at all through NewPipeExtractor, so
        // everything above comes back empty. Fall back to YouTube Music search,
        // keeping only songs/albums credited to this artist.
        val artistName = stripTopicSuffix(info.name)
        var albums = albumItems.map { it.toAlbumResultDto() }.take(20)
        if (topTracks.isEmpty() && latestTracks.isEmpty()) {
            topTracks = runCatching { search(artistName, 30) }.getOrDefault(emptyList())
                .filter { it.artist?.let { a -> creditsArtist(a, artistName) } == true }
                .take(20)
        }
        if (albums.isEmpty()) {
            albums = runCatching { searchAlbums(artistName, 20) }.getOrDefault(emptyList())
                .filter { it.artist?.let { a -> creditsArtist(a, artistName) } == true }
        }

        ArtistDetailDto(
            source = "ytmusic",
            sourceId = channelUrl,
            // Auto-generated channels are always named "<Artist> - Topic" - stripped
            // here so the artist page (and anything that later name-matches against
            // it, e.g. SearchRepository.findArtistByName) shows the real artist name.
            name = stripTopicSuffix(info.name),
            thumbnailUrl = info.avatars.maxByOrNull { it.height }?.url,
            bannerUrl = runCatching { info.banners.maxByOrNull { it.height }?.url }.getOrNull(),
            description = info.description,
            subscriberCount = info.subscriberCount.takeIf { it >= 0 }?.let(::formatCount),
            topTracks = topTracks,
            latestTracks = latestTracks,
            albums = albums,
            playlists = playlistItems.map { it.toPlaylistResultDto() }.take(20),
            webpageUrl = channelUrl,
        )
    }

    private fun creditsArtist(credit: String, artist: String): Boolean =
        stripTopicSuffix(credit).split(",", "&", " x ", " feat. ", " ft. ")
            .any { it.trim().equals(artist, ignoreCase = true) }

    private fun fetchTabTracks(tab: ListLinkHandler): List<TrackResultDto>? = runCatching {
        ChannelTabInfo.getInfo(ServiceList.YouTube, tab)
            .relatedItems.filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toTrackResultDto() }
    }.onFailure { e ->
        Log.w(TAG, "failed to fetch channel tab ${tab.url}", e)
    }.getOrNull()

    // Backs the ALBUMS/PLAYLISTS tabs (see getArtist) - both surface PlaylistInfoItems,
    // unlike TRACKS/VIDEOS' StreamInfoItems, so this is fetchTabTracks' sibling rather
    // than a shared helper. Same individually-wrapped, log-and-return-null failure
    // handling.
    private fun fetchTabPlaylistItems(tab: ListLinkHandler): List<PlaylistInfoItem>? = runCatching {
        ChannelTabInfo.getInfo(ServiceList.YouTube, tab)
            .relatedItems.filterIsInstance<PlaylistInfoItem>()
    }.onFailure { e ->
        Log.w(TAG, "failed to fetch channel tab ${tab.url}", e)
    }.getOrNull()

    // Auto-generated "<Artist> - Topic" channels use this suffix as YouTube's own
    // wrapper around an artist with no manually managed channel, not a distinct
    // artist name - stripped wherever a channel's display name reaches the app.
    private fun stripTopicSuffix(name: String): String =
        Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE).replace(name, "").trim()

    private fun StreamInfoItem.toTrackResultDto(): TrackResultDto? {
        val videoId = Uri.parse(url).getQueryParameter("v") ?: return null
        return TrackResultDto(
            source = "ytmusic",
            sourceId = videoId,
            title = name,
            artist = uploaderName,
            album = null,
            durationSec = duration.takeIf { it > 0 }?.toInt(),
            thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url,
            webpageUrl = "https://music.youtube.com/watch?v=$videoId",
        )
    }

    private fun ChannelInfoItem.toArtistResultDto(): ArtistResultDto? = ArtistResultDto(
        source = "ytmusic",
        sourceId = url,
        name = stripTopicSuffix(name),
        thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url,
        subscriberCount = subscriberCount.takeIf { it >= 0 }?.let(::formatCount),
        webpageUrl = url,
    )

    private fun formatCount(n: Long): String = when {
        n >= 1_000_000 -> trimTrailingZero(n / 1_000_000.0) + "M"
        n >= 1_000 -> trimTrailingZero(n / 1_000.0) + "K"
        else -> n.toString()
    }

    private fun trimTrailingZero(value: Double): String {
        val formatted = "%.1f".format(value)
        return if (formatted.endsWith(".0")) formatted.dropLast(2) else formatted
    }
}
