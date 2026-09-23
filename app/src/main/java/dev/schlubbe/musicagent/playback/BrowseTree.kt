package dev.schlubbe.musicagent.playback

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession
import dev.schlubbe.musicagent.data.extract.StreamResolverRegistry
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.TrackOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.PlaylistRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

private const val TAG = "BrowseTree"

// Prefixed so a folder id can never collide with a playable "source:sourceId" media id -
// no real extraction source is (or ever will be) named "auto".
private const val ROOT_ID = "auto:root"
private const val FAVORITES_ID = "auto:favorites"
private const val PLAYLISTS_ID = "auto:playlists"
private const val DOWNLOADS_ID = "auto:downloads"
private const val RECENT_ID = "auto:recent"
private const val PLAYLIST_ID_PREFIX = "auto:playlist:"
private const val SEARCH_ID_PREFIX = "auto:search:"

// Same magnitude as LibraryViewModel's own recently-played query - see that class's
// RECENTLY_PLAYED_LIMIT kdoc for why.
private const val RECENT_LIMIT = 30

// A whole-folder queue expansion (see BrowseTree.buildQueue) resolves every sibling's
// stream up front rather than the "start track now, rest in the background" trick
// PlayerController.resolveStreamsWithGaps uses - simpler, but only bounded-safe for
// folders of this rough size. Past it, expansion is skipped and only the tapped track
// plays, rather than making the car wait through dozens of network resolves before
// audio starts.
private const val MAX_QUEUE_EXPANSION = 50

// MediaItem.mediaMetadata.extras keys stamped on every playable item this tree hands
// out - not part of any Media3/Android Auto contract, just this app's own way of
// smuggling enough context through onSetMediaItems/onAddMediaItems (which otherwise
// only get the bare stub MediaItem back) to resolve a real stream and rebuild the
// item's own browse folder as a queue.
private const val EXTRA_PARENT_ID = "dev.schlubbe.musicagent.EXTRA_PARENT_ID"
private const val EXTRA_DURATION_SEC = "dev.schlubbe.musicagent.EXTRA_DURATION_SEC"

/** A track-shaped row from whichever repository/DAO it came from (likes, playlists,
 * downloads joined against [TrackDao], recently-played) - the fields every one of
 * those shapes (TrackOutDto, TrackEntity, TrackResultDto) already has in common,
 * collapsed to one shape so the rest of this file only needs one MediaItem builder. */
private data class BrowsableTrack(
    val source: String,
    val sourceId: String,
    val title: String,
    val artist: String?,
    val thumbnailUrl: String?,
    val durationSec: Int?,
)

private fun TrackOutDto.toBrowsable() = BrowsableTrack(source, sourceId, title, artist, thumbnailUrl, durationSec)
private fun TrackEntity.toBrowsable() = BrowsableTrack(source, sourceId, title, artist, thumbnailUrl, durationSec)
private fun TrackResultDto.toBrowsable() = BrowsableTrack(source, sourceId, title, artist, thumbnailUrl, durationSec)

/**
 * Builds and resolves the Android Auto / Assistant browse tree for [PlaybackService]'s
 * `MediaLibrarySession.Callback`. Kept out of PlaybackService itself (which already owns the
 * player/session lifecycle, the visualizer tap, and the like/download command buttons) so that
 * file stays about running playback, not about listing it.
 *
 * Every playable [MediaItem] this class hands out uses the same "source:sourceId" media id
 * [dev.schlubbe.musicagent.playback.PlayerController.buildMediaItem] does in-app - it is a stub
 * with no URI (browsing must not resolve a stream for every row just to *list* it, only for the
 * one the user actually taps), resolved lazily via [buildQueue]/[resolvePlayable].
 *
 * All data access below is suspend/Flow (Room + repositories already used by
 * [dev.schlubbe.musicagent.ui.library.LibraryViewModel] for the equivalent in-app screens) so
 * nothing here blocks the calling coroutine's thread - callers run these on
 * [PlaybackService]'s own coroutine scope, never the main thread synchronously.
 */
class BrowseTree(
    private val trackDao: TrackDao,
    private val downloadDao: DownloadDao,
    private val likesRepository: LikesRepository,
    private val playlistRepository: PlaylistRepository,
    private val searchRepository: SearchRepository,
    private val streamResolverRegistry: StreamResolverRegistry,
) {
    // onSearch populates this, onGetSearchResult paginates out of it - mirrors the
    // subscribe/notify split Media3's own search flow expects (see MediaLibrarySession.Callback's
    // kdoc: onSearch computes and announces a result *count*, a separate onGetSearchResult call
    // then pages through the actual items). Keyed by the exact query string; a car only ever has
    // one search "in flight" as far as this simple implementation cares, so nothing here evicts
    // old entries - they just get overwritten by the next onSearch for the same query, and the
    // small per-query MediaItem list is not worth the complexity of a real cache eviction policy.
    private val searchResults = mutableMapOf<String, List<MediaItem>>()

    suspend fun libraryRoot(): MediaItem = folderItem(ROOT_ID, "Grooveo", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    /** Unpaginated per-parent listing, sliced to [page]/[pageSize] here rather than in every
     * branch below - Auto/Assistant browsers request pages, but none of this app's folders are
     * large enough (bounded by [RECENT_LIMIT]/[MAX_QUEUE_EXPANSION]-scale collections) to make a
     * real streaming/paged query worthwhile over just loading the whole thing and slicing it. */
    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        val all = loadChildren(parentId)
        if (pageSize <= 0) return all
        val start = (page.toLong() * pageSize).coerceIn(0, all.size.toLong()).toInt()
        val end = (start.toLong() + pageSize).coerceIn(start.toLong(), all.size.toLong()).toInt()
        return all.subList(start, end)
    }

    /** Best-effort single-item lookup for [androidx.media3.session.MediaLibrarySession.Callback.onGetItem]
     * - folder ids are rebuilt the same way [loadChildren] builds their parent's listing; a plain
     * "source:sourceId" falls back to whatever this device has cached in [TrackDao] (populated by
     * [dev.schlubbe.musicagent.data.repository.DownloadRepository] on download, not guaranteed to
     * have every track), or a title-less stub as a last resort so the id is never a hard error. */
    suspend fun item(mediaId: String): MediaItem? = when {
        mediaId == ROOT_ID -> libraryRoot()
        mediaId == FAVORITES_ID -> folderItem(FAVORITES_ID, "Favoriten", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        mediaId == PLAYLISTS_ID -> folderItem(PLAYLISTS_ID, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
        mediaId == DOWNLOADS_ID -> folderItem(DOWNLOADS_ID, "Downloads", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        mediaId == RECENT_ID -> folderItem(RECENT_ID, "Zuletzt gehört", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        mediaId.startsWith(PLAYLIST_ID_PREFIX) -> {
            val playlistId = mediaId.removePrefix(PLAYLIST_ID_PREFIX)
            runCatching { playlistRepository.get(playlistId) }.getOrNull()
                ?.let { folderItem(mediaId, it.name, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS) }
        }
        else -> trackDao.getById(mediaId)?.let { trackItem(it.toBrowsable(), parentId = ROOT_ID) }
            ?: mediaId.split(":", limit = 2).takeIf { it.size == 2 }
                ?.let { (source, sourceId) -> trackItem(BrowsableTrack(source, sourceId, mediaId, null, null, null), ROOT_ID) }
    }

    /** Runs [query], caches the hits under it for the paired [searchResults] call, and returns
     * how many were found - [androidx.media3.session.MediaLibrarySession.Callback.onSearch]'s
     * contract is to announce a count via `notifySearchResultChanged`, not to hand back items
     * directly (that's [searchResults]'s job). */
    suspend fun search(query: String): Int {
        val hits = runCatching { searchRepository.search(query) }.getOrDefault(emptyList())
        val parentId = "$SEARCH_ID_PREFIX$query"
        searchResults[query] = hits.map { trackItem(it.toBrowsable(), parentId) }
        return hits.size
    }

    suspend fun searchResults(query: String, page: Int, pageSize: Int): List<MediaItem> {
        val all = searchResults[query] ?: emptyList()
        if (pageSize <= 0) return all
        val start = (page.toLong() * pageSize).coerceIn(0, all.size.toLong()).toInt()
        val end = (start.toLong() + pageSize).coerceIn(start.toLong(), all.size.toLong()).toInt()
        return all.subList(start, end)
    }

    /** Resolves a car-tapped track (or an explicit "Zur Warteschlange" add) into a real,
     * playable [MediaItem] with a URI - dropping any that fail to resolve instead of leaving a
     * URI-less stub for ExoPlayer to choke on. Failures are logged, not surfaced: browsing has no
     * per-row error UI the way the in-app Player screen's "Titel nicht verfügbar" state does, so a
     * skipped track here is the closest equivalent. */
    suspend fun resolvePlayable(mediaItems: List<MediaItem>): List<MediaItem> = coroutineScope {
        mediaItems
            .map { item -> async { runCatching { resolveSingle(item) } } }
            .awaitAll()
            .mapIndexedNotNull { i, result ->
                result.getOrElse { error ->
                    Log.w(TAG, "resolvePlayable: dropping ${mediaItems[i].mediaId}", error)
                    null
                }
            }
    }

    /** Backs `onSetMediaItems`: [mediaItems] is normally exactly the one stub the user tapped in
     * the browse tree, not a queue. [EXTRA_PARENT_ID] (stamped by [trackItem] on every item this
     * tree ever hands out) lets that single tap become "play this and queue the rest of its
     * folder" - matching how tapping a track in-app queues the rest of its list
     * ([dev.schlubbe.musicagent.playback.PlayerController.playQueue]) - rather than stopping dead
     * after one song. Folders over [MAX_QUEUE_EXPANSION] skip expansion (see that constant's
     * kdoc); a request that already carries more than one item (a client building its own queue)
     * is resolved as-is with no expansion. */
    suspend fun buildQueue(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): MediaSession.MediaItemsWithStartPosition {
        val requested = mediaItems.getOrNull(startIndex) ?: mediaItems.firstOrNull()
        val requestedId = requested?.mediaId
        val parentId = requested?.mediaMetadata?.extras?.getString(EXTRA_PARENT_ID)

        val candidateQueue = if (mediaItems.size == 1 && parentId != null) {
            val siblings = loadChildren(parentId).filter { it.mediaMetadata.isPlayable == true }
            if (siblings.size in 1..MAX_QUEUE_EXPANSION) siblings else mediaItems
        } else {
            mediaItems
        }

        val resolvedQueue = resolvePlayable(candidateQueue)
        if (resolvedQueue.isEmpty()) {
            // Whole-folder expansion resolved nothing (e.g. every track in a stale saved
            // playlist 404s) - fall back to just the tapped item so a genuine resolve failure
            // surfaces as a normal playback error instead of the queue silently going empty.
            val single = requireNotNull(requested) { "no media item to resolve" }
            return MediaSession.MediaItemsWithStartPosition(listOf(resolveSingle(single)), 0, startPositionMs)
        }
        val resolvedIndex = resolvedQueue.indexOfFirst { it.mediaId == requestedId }.takeIf { it >= 0 } ?: 0
        return MediaSession.MediaItemsWithStartPosition(resolvedQueue, resolvedIndex, startPositionMs)
    }

    private suspend fun resolveSingle(item: MediaItem): MediaItem {
        val separatorIndex = item.mediaId.indexOf(':')
        require(separatorIndex > 0) { "not a playable media id: ${item.mediaId}" }
        val source = item.mediaId.substring(0, separatorIndex)
        val sourceId = item.mediaId.substring(separatorIndex + 1)
        val title = item.mediaMetadata.title?.toString() ?: item.mediaId
        val artist = item.mediaMetadata.artist?.toString()
        val durationSec = item.mediaMetadata.extras?.getInt(EXTRA_DURATION_SEC, 0)?.takeIf { it > 0 }

        val resolved = streamResolverRegistry.resolveWithFallback(source, sourceId, title, artist, durationSec)
        return item.buildUpon()
            .setUri(resolved.url)
            .setMediaMetadata(
                item.mediaMetadata.buildUpon()
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()
    }

    private suspend fun loadChildren(parentId: String): List<MediaItem> = when (parentId) {
        ROOT_ID -> listOf(
            folderItem(FAVORITES_ID, "Favoriten", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folderItem(PLAYLISTS_ID, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            folderItem(DOWNLOADS_ID, "Downloads", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folderItem(RECENT_ID, "Zuletzt gehört", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        )
        FAVORITES_ID -> likesRepository.refresh().map { trackItem(it.track.toBrowsable(), FAVORITES_ID) }
        PLAYLISTS_ID -> playlistRepository.list().map {
            folderItem("$PLAYLIST_ID_PREFIX${it.id}", it.name, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
        }
        DOWNLOADS_ID -> downloadDao.observeAll().first()
            .filter { it.state == DownloadState.COMPLETED && it.mediaStoreUri != null }
            .mapNotNull { download -> trackDao.getById(download.trackId)?.let { trackItem(it.toBrowsable(), DOWNLOADS_ID) } }
        RECENT_ID -> trackDao.observeRecentlyPlayed(RECENT_LIMIT).first().map { trackItem(it.toBrowsable(), RECENT_ID) }
        else -> if (parentId.startsWith(PLAYLIST_ID_PREFIX)) {
            val playlistId = parentId.removePrefix(PLAYLIST_ID_PREFIX)
            runCatching { playlistRepository.get(playlistId) }.getOrNull()
                ?.tracks?.map { trackItem(it.track.toBrowsable(), parentId) }
                ?: emptyList()
        } else if (parentId.startsWith(SEARCH_ID_PREFIX)) {
            searchResults[parentId.removePrefix(SEARCH_ID_PREFIX)] ?: emptyList()
        } else {
            emptyList()
        }
    }

    private fun folderItem(id: String, title: String, mediaType: Int): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .build(),
            )
            .build()

    private fun trackItem(track: BrowsableTrack, parentId: String): MediaItem {
        val extras = Bundle().apply {
            putString(EXTRA_PARENT_ID, parentId)
            track.durationSec?.let { putInt(EXTRA_DURATION_SEC, it) }
        }
        return MediaItem.Builder()
            .setMediaId("${track.source}:${track.sourceId}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setArtworkUri(track.thumbnailUrl?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }
}
