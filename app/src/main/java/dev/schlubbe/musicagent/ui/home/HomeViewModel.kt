package dev.schlubbe.musicagent.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.LikeOutDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.remote.dto.toTrackResultDto
import dev.schlubbe.musicagent.data.repository.EventReporter
import dev.schlubbe.musicagent.data.repository.FeedItem
import dev.schlubbe.musicagent.data.repository.FeedRepository
import dev.schlubbe.musicagent.data.repository.FollowRepository
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.PlaylistRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.BuildConfig
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import dev.schlubbe.musicagent.playback.PlayerController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val SHELF_LIMIT = 6
private const val RECENTLY_PLAYED_LIMIT = 10
private const val TOP_ARTIST_LIMIT = 8

/** Mood filter chips on the Mix row -- each (besides "Alle") maps to a plain
 * on-device search keyword rather than real mood/genre metadata (SoundCloud/
 * YouTube's extraction surfaces neither), so tapping one is a real search-backed
 * action, not just a decorative filter. */
enum class MoodFilter(val label: String, val searchKeyword: String?) {
    ALL("Alle", null),
    FOCUS("Fokus", "focus music"),
    CHILL("Chill", "chill music"),
    WORKOUT("Workout", "workout music"),
    PARTY("Party", "party music"),
}

data class ResumeTrack(
    val title: String,
    val artist: String?,
    val artworkUrl: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

private fun timeOfDayGreeting(): String = when (LocalTime.now().hour) {
    in 5..10 -> "Guten Morgen"
    in 11..17 -> "Guten Tag"
    else -> "Guten Abend"
}

data class HomeUiState(
    val profileName: String = "",
    val greetingText: String = timeOfDayGreeting(),
    val statLine: String = "",
    val charts: List<TrackResultDto> = emptyList(),
    val isChartsLoading: Boolean = false,
    val feed: List<FeedItem> = emptyList(),
    val isFeedLoading: Boolean = false,
    val playlists: List<PlaylistOutDto> = emptyList(),
    val isPlaylistsLoading: Boolean = false,
    val recentlyPlayed: List<TrackEntity> = emptyList(),
    val likes: List<LikeOutDto> = emptyList(),
    val isLikesLoading: Boolean = false,
    val resumeTrack: ResumeTrack? = null,
    val topArtists: List<String> = emptyList(),
    val showMixControls: Boolean = true,
    val showFeatured: Boolean = true,
    val showNewUploads: Boolean = true,
    val isMixLoading: Boolean = false,
    val showWhatsNewBanner: Boolean = false,
    val latestVersionLabel: String = BuildConfig.VERSION_NAME,
)

/** Converts a locally-cached [TrackEntity] back into a [TrackResultDto] for playback
 * (and, since the share feature, sharing a real link for it too). */
private fun TrackEntity.toTrackResultDto(): TrackResultDto = TrackResultDto(
    source = source,
    sourceId = sourceId,
    title = title,
    artist = artist,
    album = album,
    durationSec = durationSec,
    thumbnailUrl = thumbnailUrl,
    webpageUrl = webpageUrl,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val feedRepository: FeedRepository,
    private val playlistRepository: PlaylistRepository,
    private val likesRepository: LikesRepository,
    private val trackDao: TrackDao,
    private val playerController: PlayerController,
    private val eventReporter: EventReporter,
    private val settingsRepository: SettingsRepository,
    private val followRepository: FollowRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // A live Room query -- no explicit reload needed, it just emits whenever a
        // track is played anywhere in the app.
        viewModelScope.launch {
            trackDao.observeRecentlyPlayed(RECENTLY_PLAYED_LIMIT).collect { tracks ->
                _uiState.value = _uiState.value.copy(recentlyPlayed = tracks, statLine = weeklyListenStatLine(tracks))
                recomputeTopArtists()
            }
        }
        viewModelScope.launch {
            settingsRepository.profileName.collect { name ->
                _uiState.value = _uiState.value.copy(profileName = name)
            }
        }
        viewModelScope.launch {
            settingsRepository.showMixControls.collect { show ->
                _uiState.value = _uiState.value.copy(showMixControls = show)
            }
        }
        viewModelScope.launch {
            settingsRepository.showFeatured.collect { show ->
                _uiState.value = _uiState.value.copy(showFeatured = show)
            }
        }
        viewModelScope.launch {
            settingsRepository.showNewUploads.collect { show ->
                _uiState.value = _uiState.value.copy(showNewUploads = show)
            }
        }
        viewModelScope.launch {
            settingsRepository.lastSeenVersionCode.collect { seen ->
                _uiState.value = _uiState.value.copy(showWhatsNewBanner = seen in 1 until BuildConfig.VERSION_CODE)
            }
        }
        // Resume card: polls the shared player's live position while a track is
        // loaded (PlaybackUiState itself only emits on play/pause/track-change, not
        // every second of playback) -- a lightweight ticker is simpler here than
        // plumbing a new high-frequency Flow through PlayerController for a single
        // progress bar.
        viewModelScope.launch {
            playerController.playbackState.collect { state ->
                _uiState.value = _uiState.value.copy(
                    resumeTrack = state.title?.let { title ->
                        ResumeTrack(
                            title = title,
                            artist = state.artist,
                            artworkUrl = state.artworkUrl,
                            isPlaying = state.isPlaying,
                            positionMs = playerController.currentPositionMs(),
                            durationMs = state.durationMs,
                        )
                    },
                )
            }
        }
        viewModelScope.launch { runCatching { followRepository.refresh() }.onSuccess { recomputeTopArtists() } }
        viewModelScope.launch {
            while (true) {
                delay(1000)
                _uiState.value.resumeTrack?.let { current ->
                    _uiState.value = _uiState.value.copy(
                        resumeTrack = current.copy(positionMs = playerController.currentPositionMs()),
                    )
                }
            }
        }
    }

    /** "Diese Woche: X Std. Y Min gehört" -- estimated from the last-accessed
     * cache's own timestamps rather than a real play-history log (none exists;
     * [dev.schlubbe.musicagent.data.local.entity.TrackEntity] dedups by track,
     * it doesn't record one row per play), so this undercounts replays of the
     * same track within the window. Good enough for a soft home-screen stat. */
    private fun weeklyListenStatLine(tracks: List<TrackEntity>): String {
        val weekAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        val totalSec = tracks.filter { it.lastAccessedAt >= weekAgo }.sumOf { it.durationSec ?: 0 }
        if (totalSec <= 0) return ""
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        return if (hours > 0) "Diese Woche: $hours Std. $minutes Min gehört" else "Diese Woche: $minutes Min gehört"
    }

    /** Powers the "Neu von Künstlern" shelf. Prefers real follows (see
     * FollowRepository) since those are an explicit user action; falls back to a
     * frequency-based signal (likes weighted 3x, plays 1x - the same weighting
     * FeedRepository uses for its own "familiar" half) so the shelf isn't empty
     * before the user has followed anyone. Doesn't track real new-upload state
     * either way - the "new" ring is decorative until that exists. */
    private fun recomputeTopArtists() {
        val followed = followRepository.followedArtists.value.map { it.name }
        if (followed.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(topArtists = followed.take(TOP_ARTIST_LIMIT))
            return
        }
        val playCounts = _uiState.value.recentlyPlayed.mapNotNull { it.artist }
            .groupingBy { it }.eachCount()
        val likeCounts = _uiState.value.likes.mapNotNull { it.track.artist }
            .groupingBy { it }.eachCount()
        val combined = (playCounts.keys + likeCounts.keys).associateWith { artist ->
            (playCounts[artist] ?: 0) + (likeCounts[artist] ?: 0) * 3
        }
        _uiState.value = _uiState.value.copy(
            topArtists = combined.entries.sortedByDescending { it.value }.take(TOP_ARTIST_LIMIT).map { it.key },
        )
    }

    /** Reloads every shelf backed by a suspend call. Called from the screen on first
     * composition and every time it's revisited (e.g. after coming back from the
     * Player), so it reflects likes/playlist edits made elsewhere. */
    fun refresh() {
        loadCharts()
        loadFeed()
        loadPlaylists()
        loadLikes()
    }

    // Global trending charts, not tied to any local history - what a freshly
    // installed app has to show on Home before there's any play/like history to
    // build a feed from at all. YouTube-Music-only: SoundCloud's own "trending"
    // charts skew toward tracks with locked/DRM-only transcodings often enough
    // that mixing them in here made Charts unreliable to tap into.
    private fun loadCharts() {
        _uiState.value = _uiState.value.copy(isChartsLoading = true)
        viewModelScope.launch {
            runCatching { searchRepository.getTrending(source = "ytmusic") }
                .onSuccess { tracks ->
                    _uiState.value = _uiState.value.copy(charts = tracks, isChartsLoading = false)
                }
                .onFailure { e ->
                    android.util.Log.w("HomeViewModel", "loadCharts failed", e)
                    _uiState.value = _uiState.value.copy(isChartsLoading = false)
                }
        }
    }

    private fun loadFeed() {
        _uiState.value = _uiState.value.copy(isFeedLoading = true)
        viewModelScope.launch {
            runCatching { feedRepository.getFeed() }
                .onSuccess { items ->
                    val shelf = items.take(SHELF_LIMIT)
                    _uiState.value = _uiState.value.copy(feed = shelf, isFeedLoading = false)
                    shelf.forEach { eventReporter.feedImpression(it.track) }
                }
                .onFailure { _uiState.value = _uiState.value.copy(isFeedLoading = false) }
        }
    }

    private fun loadPlaylists() {
        _uiState.value = _uiState.value.copy(isPlaylistsLoading = true)
        viewModelScope.launch {
            runCatching { playlistRepository.list() }
                .onSuccess { playlists ->
                    _uiState.value = _uiState.value.copy(
                        playlists = playlists.take(SHELF_LIMIT),
                        isPlaylistsLoading = false,
                    )
                }
                .onFailure { _uiState.value = _uiState.value.copy(isPlaylistsLoading = false) }
        }
    }

    private fun loadLikes() {
        _uiState.value = _uiState.value.copy(isLikesLoading = true)
        viewModelScope.launch {
            runCatching { likesRepository.refresh() }
                .onSuccess { likes ->
                    _uiState.value = _uiState.value.copy(
                        likes = likes.take(SHELF_LIMIT),
                        isLikesLoading = false,
                    )
                    recomputeTopArtists()
                }
                .onFailure { _uiState.value = _uiState.value.copy(isLikesLoading = false) }
        }
    }

    fun onChartTrackClicked(track: TrackResultDto) {
        viewModelScope.launch {
            val queue = _uiState.value.charts
            val index = queue.indexOfFirst { it.source == track.source && it.sourceId == track.sourceId }
            if (index >= 0) playerController.playQueue(queue, index) else playerController.playTrack(track)
        }
    }

    fun onFeedTrackClicked(item: FeedItem) {
        eventReporter.feedClick(item.track)
        viewModelScope.launch {
            val queue = _uiState.value.feed.map { it.track }
            val index = queue.indexOfFirst { it.source == item.track.source && it.sourceId == item.track.sourceId }
            if (index >= 0) playerController.playQueue(queue, index) else playerController.playTrack(item.track)
        }
    }

    fun onRecentlyPlayedClicked(track: TrackEntity) {
        viewModelScope.launch {
            val queue = _uiState.value.recentlyPlayed.map { it.toTrackResultDto() }
            val index = _uiState.value.recentlyPlayed.indexOfFirst { it.id == track.id }
            if (index >= 0) {
                playerController.playQueue(queue, index)
            } else {
                playerController.playTrack(track.toTrackResultDto())
            }
        }
    }

    fun onLikeClicked(like: LikeOutDto) {
        viewModelScope.launch {
            val queue = _uiState.value.likes.map { it.track.toTrackResultDto() }
            val index = _uiState.value.likes.indexOfFirst { it.track.id == like.track.id }
            if (index >= 0) {
                playerController.playQueue(queue, index)
            } else {
                playerController.playTrack(like.track.toTrackResultDto())
            }
        }
    }

    /** Called both when the banner's × is tapped (dismiss only) and when the banner
     * itself is tapped to open the full What's New screen -- either way there's
     * nothing more to surface for this version once it's been seen once. */
    fun onWhatsNewBannerSeen() {
        viewModelScope.launch { settingsRepository.setLastSeenVersionCode(BuildConfig.VERSION_CODE) }
    }

    fun onResumeCardClicked() {
        viewModelScope.launch { playerController.togglePlayPause() }
    }

    /** "Mix starten" with a mood chip selected: a real on-device search using that
     * mood's keyword (see [MoodFilter]), queued and shuffled. "Alle" (or the plain
     * Mix-starten button) instead shuffles the Charts+Für-dich shelves already
     * loaded, needing no extra network round-trip. */
    fun onMoodChipClicked(mood: MoodFilter) {
        _uiState.value = _uiState.value.copy(isMixLoading = true)
        viewModelScope.launch {
            val queue = if (mood.searchKeyword == null) {
                (_uiState.value.charts + _uiState.value.feed.map { it.track }).shuffled()
            } else {
                runCatching { searchRepository.search(mood.searchKeyword, limit = 25) }
                    .getOrDefault(emptyList())
                    .shuffled()
            }
            _uiState.value = _uiState.value.copy(isMixLoading = false)
            if (queue.isNotEmpty()) playerController.playQueue(queue, 0)
        }
    }
}
