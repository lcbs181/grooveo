package dev.schlubbe.musicagent.data.extract.youtube

import android.net.Uri
import android.util.Log
import dev.schlubbe.musicagent.data.remote.dto.AlbumResultDto
import dev.schlubbe.musicagent.data.remote.dto.ArtistDetailDto
import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import kotlinx.coroutines.Dispatchers
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

    suspend fun searchArtists(query: String, limit: Int): List<ArtistResultDto> = withContext(Dispatchers.IO) {
        val handler = ServiceList.YouTube.searchQHFactory
            .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS), "")
        val info = SearchInfo.getInfo(ServiceList.YouTube, handler)
        info.relatedItems.filterIsInstance<ChannelInfoItem>()
            .mapNotNull { it.toArtistResultDto() }
            .take(limit)
    }

    /** NewPipeExtractor has no equivalent of ytmusicapi's dedicated YouTube-Music
     * charts browse id (`FEmusic_charts`) - the closest first-class extractor is
     * YouTube's general "Trending" kiosk, which mixes in non-music content (news,
     * gaming, vlogs). A typical-song-length window (1-15 min) filters out the most
     * obvious non-music items; imperfect, but real trending data rather than a
     * fabricated substitute. */
    suspend fun getTrending(limit: Int): List<TrackResultDto> = withContext(Dispatchers.IO) {
        val info = KioskInfo.getInfo(ServiceList.YouTube, "Trending")
        info.relatedItems.filterIsInstance<StreamInfoItem>()
            .filter { it.duration in 60..900 }
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
        // show *something* for non-music-official channels.
        val tracksTab = info.tabs.firstOrNull { ChannelTabs.TRACKS in it.contentFilters }
        val videosTab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }

        var tracksTabItems = tracksTab?.let { fetchTabTracks(it) }
        var videosTabItems = videosTab?.let { fetchTabTracks(it) }

        // Auto-generated "<Artist> - Topic" channels (YouTube's stand-in for artists
        // with no manually managed channel - common for uploads distributed via a
        // label/aggregator, e.g. observed live on a Travis Scott page) sometimes
        // expose neither a TRACKS- nor VIDEOS-tagged tab through NewPipeExtractor's
        // channel model, even though the channel page itself clearly has uploads.
        // Rather than silently showing an empty artist page, fall back to whatever
        // tab IS listed - logged first, since this is exactly the kind of
        // channel-structure edge case worth having concrete data on if it recurs.
        if (tracksTabItems == null && videosTabItems == null && info.tabs.isNotEmpty()) {
            Log.w(
                TAG,
                "no TRACKS/VIDEOS tab for $channelUrl - falling back to first available " +
                    "(all tabs' contentFilters: ${info.tabs.map { it.contentFilters }})",
            )
            videosTabItems = fetchTabTracks(info.tabs.first())
        }

        // "Top" prefers the dedicated music tab (already curated); "latest" prefers
        // the chronological videos tab. Either falls back to whichever tab actually
        // came back non-null so both shelves still show something on channels that
        // only expose one of the two tabs.
        val topTracks = (tracksTabItems ?: videosTabItems ?: emptyList()).take(20)
        val latestTracks = (videosTabItems ?: tracksTabItems ?: emptyList()).take(20)

        ArtistDetailDto(
            source = "ytmusic",
            sourceId = channelUrl,
            name = info.name,
            thumbnailUrl = info.avatars.maxByOrNull { it.height }?.url,
            bannerUrl = runCatching { info.banners.maxByOrNull { it.height }?.url }.getOrNull(),
            description = info.description,
            subscriberCount = info.subscriberCount.takeIf { it >= 0 }?.let(::formatCount),
            topTracks = topTracks,
            latestTracks = latestTracks,
            webpageUrl = channelUrl,
        )
    }

    private fun fetchTabTracks(tab: ListLinkHandler): List<TrackResultDto>? = runCatching {
        ChannelTabInfo.getInfo(ServiceList.YouTube, tab)
            .relatedItems.filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toTrackResultDto() }
    }.onFailure { e ->
        Log.w(TAG, "failed to fetch channel tab ${tab.url}", e)
    }.getOrNull()

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
        name = name,
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
