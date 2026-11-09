package dev.schlubbe.musicagent.ui.search

import dev.schlubbe.musicagent.ui.util.friendlyError
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.AlbumResultDto
import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.repository.DownloadRepository
import dev.schlubbe.musicagent.data.repository.EventReporter
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.PlaylistRepository
import dev.schlubbe.musicagent.data.repository.SearchHistoryRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.playback.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val source: String = "all",
    // "all" (sections like SoundCloud's "Alle"), or one kind: tracks, artists, ...
    val resultType: String = "all",
    val results: List<TrackResultDto> = emptyList(),
    val artistResults: List<ArtistResultDto> = emptyList(),
    val playlistResults: List<PlaylistResultDto> = emptyList(),
    val albumResults: List<AlbumResultDto> = emptyList(),
    val podcastResults: List<dev.schlubbe.musicagent.podcast.PodcastShow> = emptyList(),
    val hoerspielResults: List<dev.schlubbe.musicagent.podcast.PodcastShow> = emptyList(),
    val hoerbuchResults: List<dev.schlubbe.musicagent.podcast.PodcastShow> = emptyList(),
    /** YouTube videos as audio (podcast-like episodes); the tab exists only when switched on. */
    val youtubeResults: List<TrackResultDto> = emptyList(),
    /** Playlists and channels on the "Hörspiele & Hörbücher" tab: series that open as shows. */
    val youtubeShows: List<dev.schlubbe.musicagent.podcast.PodcastShow> = emptyList(),
    val youtubeAlbums: List<dev.schlubbe.musicagent.podcast.PodcastShow> = emptyList(),
    val youtubeEnabled: Boolean = false,
    /** Sources of the "Hörspiele & Hörbücher" tab still searching. */
    val spokenPending: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    val likedTrackIds: Set<String> = emptySet(),
    val playlists: List<PlaylistOutDto> = emptyList(),
    val trackPendingPlaylistAdd: TrackResultDto? = null,
    // Set once a playlist/album search result is tapped - the screen navigates to
    // the remote playlist browse screen on seeing this, then calls
    // onRemotePlaylistNavigated() to clear it back to null.
    val remotePlaylistNavTarget: Pair<String, String>? = null,
    // Set once an artist (from the artist tab, or resolved by name from a track's
    // "Zum Künstler" action) is ready to open - the screen navigates on seeing this,
    // then calls onArtistNavigated() to clear it back to null.
    val artistNavTarget: Pair<String, String>? = null,
    val artistLookupError: String? = null,
    /** A pasted link started playback: the screen opens the player once. */
    val openPlayer: Boolean = false,
    val searchHistory: List<String> = emptyList(), // Recent query strings
    // Live search-as-you-type suggestions, shown under the field until a search runs.
    val suggestions: List<String> = emptyList(),
    // Backs the per-result state pill the redesign puts on every row. Keyed by
    // "source:sourceId", matching DownloadRepository's own trackId format.
    val downloadStates: Map<String, DownloadState> = emptyMap(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val trackDao: TrackDao,
    private val downloadDao: DownloadDao,
    private val playerController: PlayerController,
    private val downloadRepository: DownloadRepository,
    private val likesRepository: LikesRepository,
    private val playlistRepository: PlaylistRepository,
    private val eventReporter: EventReporter,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val podcastRepository: dev.schlubbe.musicagent.podcast.PodcastRepository,
    private val sharedLinkResolver: dev.schlubbe.musicagent.data.extract.SharedLinkResolver,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            podcastRepository.youtubeEnabled.collect { on -> _uiState.value = _uiState.value.copy(youtubeEnabled = on) }
        }
        viewModelScope.launch {
            likesRepository.likedTrackIds.collect { ids ->
                _uiState.value = _uiState.value.copy(likedTrackIds = ids)
            }
        }
        viewModelScope.launch {
            searchHistoryRepository.history.collect { historyEntities ->
                _uiState.value = _uiState.value.copy(searchHistory = historyEntities.map { it.query })
            }
        }
        viewModelScope.launch {
            downloadDao.observeAll().collect { downloads ->
                _uiState.value = _uiState.value.copy(
                    downloadStates = downloads.associate { it.trackId to it.state },
                )
            }
        }
        viewModelScope.launch { runCatching { likesRepository.refresh() } }
        viewModelScope.launch { runCatching { searchHistoryRepository.refresh() } }
    }

    private val _browseCovers = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Cover art for Dawn Music's "Alle durchsuchen" tiles, by tile query:
     * the first matching playlist's cover (or the first track's). */
    val browseCovers: StateFlow<Map<String, String>> = _browseCovers.asStateFlow()
    private var browseCoversJob: Job? = null

    /** Looks up a cover per browse tile, once per screen instance; tiles show
     * their plain colour until (or if never) a cover arrives. */
    fun loadBrowseCovers(queries: List<String>) {
        if (browseCoversJob != null) return
        // Cached across launches: the tiles show their covers straight away (the
        // images themselves sit in Coil's disk cache) and the lookups - 18 searches -
        // only run again once a week, or for tiles that have no cover yet.
        val prefs = context.getSharedPreferences("browse_covers", android.content.Context.MODE_PRIVATE)
        val cached = queries.mapNotNull { q -> prefs.getString(q, null)?.takeIf { it.startsWith("http") }?.let { q to it } }.toMap()
        _browseCovers.value = cached
        val fresh = System.currentTimeMillis() - prefs.getLong(KEY_COVERS_AT, 0) < COVERS_MAX_AGE_MS
        val todo = if (fresh) queries.filter { it !in cached } else queries
        if (todo.isEmpty()) return
        val permits = Semaphore(4)
        browseCoversJob = viewModelScope.launch {
            if (!fresh) prefs.edit().putLong(KEY_COVERS_AT, System.currentTimeMillis()).apply()
            todo.forEach { q ->
                launch {
                    val url = permits.withPermit { lookupCover(q, avoid = emptySet()) }
                    if (url != null) {
                        _browseCovers.value = _browseCovers.value + (q to url)
                        prefs.edit().putString(q, url).apply()
                    }
                }
            }
        }
    }

    private val coverRetried = HashSet<String>()

    /** A tile's cover did not load (an expired or dead thumbnail URL from the cache):
     * looks the tile up again once per screen, skipping that URL. */
    fun onBrowseCoverFailed(query: String) {
        val dead = _browseCovers.value[query] ?: return
        if (!coverRetried.add(query)) return
        val prefs = context.getSharedPreferences("browse_covers", android.content.Context.MODE_PRIVATE)
        prefs.edit().remove(query).apply()
        _browseCovers.value = _browseCovers.value - query
        viewModelScope.launch {
            val url = lookupCover(query, avoid = setOf(dead)) ?: return@launch
            _browseCovers.value = _browseCovers.value + (query to url)
            prefs.edit().putString(query, url).apply()
        }
    }

    /** The first usable cover for a tile: a matching playlist's, else a track's. */
    private suspend fun lookupCover(query: String, avoid: Set<String>): String? = runCatching {
        fun String?.usable() = this?.takeIf { it.startsWith("http") && it !in avoid }
        searchRepository.searchPlaylists(query, source = "ytmusic", limit = 3).firstNotNullOfOrNull { it.thumbnailUrl.usable() }
            ?: searchRepository.search(query, source = "ytmusic", limit = 3).firstNotNullOfOrNull { it.thumbnailUrl.usable() }
    }.getOrNull()

    private var suggestionJob: Job? = null
    /** The search under way; a new one cancels it, so late results never cover newer ones. */
    private var searchJob: Job? = null

    fun onQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        suggestionJob?.cancel()
        if (query.isBlank() || LINK.matches(query.trim())) {
            _uiState.value = _uiState.value.copy(suggestions = emptyList())
            return
        }
        suggestionJob = viewModelScope.launch {
            // Debounced: one request per pause in typing, not per keystroke.
            delay(250)
            val results = searchRepository.suggestions(query)
            if (_uiState.value.query == query) _uiState.value = _uiState.value.copy(suggestions = results)
        }
    }

    fun onSuggestionTapped(suggestion: String) {
        _uiState.value = _uiState.value.copy(query = suggestion, suggestions = emptyList())
        runSearch()
    }

    fun onSourceChanged(source: String) {
        _uiState.value = _uiState.value.copy(source = source)
        if (_uiState.value.query.isNotBlank()) runSearch()
    }

    fun onResultTypeChanged(resultType: String) {
        _uiState.value = _uiState.value.copy(resultType = resultType)
        if (_uiState.value.query.isNotBlank()) runSearch()
    }

    fun runSearch(): Job? {
        val query = _uiState.value.query
        if (query.isBlank()) return null

        val source = _uiState.value.source
        // Without this the in-flight debounce from the last keystroke lands after the
        // search and re-covers the results with a suggestion list.
        suggestionJob?.cancel()
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true, error = null, suggestions = emptyList())
        if (LINK.matches(query.trim())) {
            searchJob = viewModelScope.launch { openLink(query.trim()) }
            return searchJob
        }
        searchJob = viewModelScope.launch {
            // Record search query into history
            runCatching { searchHistoryRepository.addQuery(query) }

            when (_uiState.value.resultType) {
                "artists" -> runCatching { searchRepository.searchArtists(query, source) }
                    .onSuccess { artists ->
                        _uiState.value = _uiState.value.copy(artistResults = artists, isLoading = false)
                        eventReporter.search(query)
                    }
                    .onFailure { e ->
                        _uiState.value = _uiState.value.copy(isLoading = false, error = friendlyError(e))
                    }
                "playlists" -> runCatching { searchRepository.searchPlaylists(query, source) }
                    .onSuccess { playlists ->
                        _uiState.value = _uiState.value.copy(playlistResults = playlists, isLoading = false)
                        eventReporter.search(query)
                    }
                    .onFailure { e ->
                        _uiState.value = _uiState.value.copy(isLoading = false, error = friendlyError(e))
                    }
                "podcasts" -> runCatching { podcastRepository.searchPodcasts(query) }
                    .onSuccess { shows -> _uiState.value = _uiState.value.copy(podcastResults = shows, isLoading = false) }
                    .onFailure { e -> _uiState.value = _uiState.value.copy(isLoading = false, error = friendlyError(e)) }
                "spoken" -> searchSpoken(query)
                "all" -> searchAll(query, source)
                "albums" -> runCatching { searchRepository.searchAlbums(query, source) }
                    .onSuccess { albums ->
                        _uiState.value = _uiState.value.copy(albumResults = albums, isLoading = false)
                        eventReporter.search(query)
                    }
                    .onFailure { e ->
                        _uiState.value = _uiState.value.copy(isLoading = false, error = friendlyError(e))
                    }
                else -> runCatching { searchRepository.search(query, source) }
                    .onSuccess { results ->
                        _uiState.value = _uiState.value.copy(results = results, isLoading = false)
                        eventReporter.search(query)
                    }
                    .onFailure { e ->
                        _uiState.value = _uiState.value.copy(isLoading = false, error = friendlyError(e))
                    }
            }
        }
        return searchJob
    }

    /** A pasted YouTube or SoundCloud link opens what it points at, as if shared into
     * the app: a video or track plays, a playlist or channel opens its page. */
    private suspend fun openLink(url: String) {
        val target = sharedLinkResolver.resolveUrl(url)
        _uiState.value = _uiState.value.copy(isLoading = false)
        when (target) {
            is dev.schlubbe.musicagent.data.extract.SharedLinkTarget.Track -> {
                cacheTrack(target.track)
                playerController.playTrack(target.track)
                _uiState.value = _uiState.value.copy(openPlayer = true)
            }
            is dev.schlubbe.musicagent.data.extract.SharedLinkTarget.Playlist ->
                _uiState.value = _uiState.value.copy(remotePlaylistNavTarget = target.source to target.sourceId)
            is dev.schlubbe.musicagent.data.extract.SharedLinkTarget.Artist ->
                _uiState.value = _uiState.value.copy(artistNavTarget = target.source to target.sourceId)
            null -> _uiState.value = _uiState.value.copy(error = "Mit diesem Link kann die App nichts anfangen")
        }
    }

    /** "Hörspiele & Hörbücher": ARD, LibriVox and (if switched on) YouTube at once,
     * each section shown as soon as its source answers. */
    private suspend fun searchSpoken(query: String) = kotlinx.coroutines.coroutineScope {
        val youtube = _uiState.value.youtubeEnabled
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            hoerspielResults = emptyList(), hoerbuchResults = emptyList(),
            youtubeShows = emptyList(), youtubeAlbums = emptyList(), youtubeResults = emptyList(),
            spokenPending = if (youtube) 5 else 2,
        )
        fun <T> part(load: suspend () -> List<T>, apply: (SearchUiState, List<T>) -> SearchUiState) = launch {
            val found = runCatching { load() }.getOrDefault(emptyList())
            _uiState.value = apply(_uiState.value, found).let { it.copy(spokenPending = (it.spokenPending - 1).coerceAtLeast(0)) }
        }
        part({ podcastRepository.searchHoerspiele(query) }) { st, l -> st.copy(hoerspielResults = l) }
        part({ podcastRepository.searchHoerbuecher(query) }) { st, l -> st.copy(hoerbuchResults = l) }
        if (youtube) {
            part({ podcastRepository.searchYouTubeSeries(query) }) { st, l -> st.copy(youtubeShows = l) }
            part({ podcastRepository.searchYouTubeAlbums(query) }) { st, l -> st.copy(youtubeAlbums = l) }
            part({ podcastRepository.searchYouTubeAudio(query).map { it.toTrack() } }) { st, l -> st.copy(youtubeResults = l) }
        }
    }

    /** Switches YouTube on from the hint on the "Hörspiele & Hörbücher" tab and searches again. */
    fun enableYouTube() {
        viewModelScope.launch {
            podcastRepository.setYouTubeEnabled(true)
            _uiState.value = _uiState.value.copy(youtubeEnabled = true)
            runSearch()
        }
    }

    fun onPlayerOpened() {
        _uiState.value = _uiState.value.copy(openPlayer = false)
    }

    /** "Alle": every kind at once, each one allowed to fail on its own. Only when the
     * tracks fail as well is it an error. */
    private suspend fun searchAll(query: String, source: String) = kotlinx.coroutines.coroutineScope {
        _uiState.value = _uiState.value.copy(
            podcastResults = emptyList(), hoerspielResults = emptyList(), hoerbuchResults = emptyList(),
            playlistResults = emptyList(), albumResults = emptyList(),
        )
        val tracks = async { runCatching { searchRepository.search(query, source) } }
        val artists = async { runCatching { searchRepository.searchArtists(query, source) }.getOrDefault(emptyList()) }
        // playlists and albums take about twice as long as tracks; their sections fill in later
        launch {
            val found = runCatching { searchRepository.searchPlaylists(query, source) }.getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(playlistResults = found)
        }
        launch {
            val found = runCatching { searchRepository.searchAlbums(query, source) }.getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(albumResults = found)
        }
        // spoken-word sections fill in as they arrive; the music results never wait for them
        launch {
            val shows = runCatching { podcastRepository.searchPodcasts(query) }.getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(podcastResults = shows)
        }
        launch {
            val shows = runCatching { podcastRepository.searchHoerspiele(query) }.getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(hoerspielResults = shows)
        }
        launch {
            val shows = runCatching { podcastRepository.searchHoerbuecher(query) }.getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(hoerbuchResults = shows)
        }
        val trackResult = tracks.await()
        val artistList = artists.await()
        val error = trackResult.exceptionOrNull()
        _uiState.value = _uiState.value.copy(
            results = trackResult.getOrDefault(emptyList()),
            artistResults = artistList,
            isLoading = false,
            error = error?.takeIf { artistList.isEmpty() }?.let(::friendlyError),
        )
        if (error == null) eventReporter.search(query)
    }

    // A playlist/album search result now opens the browse screen instead of
    // immediately playing all its tracks - lets the user see what's in it (and
    // save/like it) before committing to playback.
    fun onPlaylistResultClicked(source: String, sourceId: String) {
        _uiState.value = _uiState.value.copy(remotePlaylistNavTarget = source to sourceId)
    }

    fun onAlbumResultClicked(source: String, sourceId: String) = onPlaylistResultClicked(source, sourceId)

    fun onRemotePlaylistNavigated() {
        _uiState.value = _uiState.value.copy(remotePlaylistNavTarget = null)
    }

    fun onArtistResultClicked(artist: ArtistResultDto) {
        _uiState.value = _uiState.value.copy(artistNavTarget = artist.source to artist.sourceId)
    }

    // The "Zum Künstler" action on a track row only has an artist name, not an id -
    // resolve it to a real artist page via SearchRepository.findArtistByName on the
    // same source. Best-effort: a name search can match a different account with the
    // same display name (common on SoundCloud), not necessarily the actual uploader.
    fun onTrackArtistClicked(track: TrackResultDto) {
        val name = track.artist
        if (name.isNullOrBlank()) return
        viewModelScope.launch {
            runCatching { searchRepository.artistOf(track) }
                .onSuccess { artist ->
                    _uiState.value = if (artist != null) {
                        _uiState.value.copy(artistNavTarget = artist.source to artist.sourceId)
                    } else {
                        _uiState.value.copy(artistLookupError = "Künstler \"$name\" nicht gefunden")
                    }
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(artistLookupError = "Künstler konnte nicht geladen werden")
                }
        }
    }

    fun onArtistNavigated() {
        _uiState.value = _uiState.value.copy(artistNavTarget = null)
    }

    fun onArtistLookupErrorShown() {
        _uiState.value = _uiState.value.copy(artistLookupError = null)
    }

    private suspend fun cacheTrack(track: TrackResultDto) {
        trackDao.upsert(
            TrackEntity(
                id = "${track.source}:${track.sourceId}",
                source = track.source,
                sourceId = track.sourceId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationSec = track.durationSec,
                thumbnailUrl = track.thumbnailUrl,
                lastAccessedAt = System.currentTimeMillis(),
                webpageUrl = track.webpageUrl,
                genre = track.genre,
            ),
        )
    }

    // Clicking a row plays the rest of the currently displayed list as the
    // "up next" queue, not just the tapped track in isolation.
    fun onTrackClicked(track: TrackResultDto) {
        viewModelScope.launch {
            cacheTrack(track)
            // the YouTube videos queue up the same way as the music results
            val results = _uiState.value.results.takeIf { list -> list.any { it.sourceId == track.sourceId } }
                ?: _uiState.value.youtubeResults
            val index = results.indexOfFirst { it.source == track.source && it.sourceId == track.sourceId }
            if (index >= 0) playerController.playQueue(results, index) else playerController.playTrack(track)
        }
    }

    fun onDownloadClicked(track: TrackResultDto) {
        downloadRepository.startDownload(track)
    }

    // Used by both the swipe-right gesture and the overflow menu's "Zur Warteschlange
    // hinzufügen" action on every track row — appends without touching current playback.
    fun onAddToQueueClicked(track: TrackResultDto) {
        viewModelScope.launch { runCatching { playerController.addToQueue(track) } }
    }

    fun onLikeToggled(track: TrackResultDto) {
        viewModelScope.launch { runCatching { likesRepository.toggle(track) } }
    }

    fun onAddToPlaylistClicked(track: TrackResultDto) {
        _uiState.value = _uiState.value.copy(trackPendingPlaylistAdd = track)
        viewModelScope.launch {
            runCatching { playlistRepository.list() }.onSuccess { playlists ->
                _uiState.value = _uiState.value.copy(playlists = playlists)
            }
        }
    }

    fun dismissAddToPlaylist() {
        _uiState.value = _uiState.value.copy(trackPendingPlaylistAdd = null)
    }

    fun onPlaylistPicked(playlistId: String) {
        val track = _uiState.value.trackPendingPlaylistAdd ?: return
        viewModelScope.launch {
            runCatching { playlistRepository.addTrack(playlistId, track) }
            _uiState.value = _uiState.value.copy(trackPendingPlaylistAdd = null)
        }
    }

    fun onCreatePlaylistAndAdd(name: String) {
        val track = _uiState.value.trackPendingPlaylistAdd ?: return
        viewModelScope.launch {
            runCatching {
                val playlist = playlistRepository.create(name)
                playlistRepository.addTrack(playlist.id, track)
            }
            _uiState.value = _uiState.value.copy(trackPendingPlaylistAdd = null)
        }
    }

    fun onHistoryQueryTapped(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        // Automatically run the search for the tapped history query
        viewModelScope.launch {
            runSearch()
        }
    }

    /** A tile of the empty search's "Hörspiele & Hörbücher" row: searches that tab. */
    fun onSpokenTileTapped(query: String) {
        _uiState.value = _uiState.value.copy(query = query, resultType = "spoken")
        runSearch()
    }

    fun onHistoryQueryDeleted(query: String) {
        viewModelScope.launch {
            runCatching { searchHistoryRepository.deleteQuery(query) }
        }
    }

    private companion object {
        const val KEY_COVERS_AT = "_fetched_at"
        const val COVERS_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/** A pasted web link instead of search words. */
private val LINK = Regex("""(?i)^https?://\S+$""")
