package dev.schlubbe.musicagent.ui.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.local.entity.PodcastProgressEntity
import dev.schlubbe.musicagent.data.local.entity.PodcastShowEntity
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.playback.PlayerController
import dev.schlubbe.musicagent.podcast.ForYouFeed
import dev.schlubbe.musicagent.podcast.ForYouItem
import dev.schlubbe.musicagent.podcast.PODCAST_SOURCE
import dev.schlubbe.musicagent.podcast.PodcastEpisode
import dev.schlubbe.musicagent.podcast.PodcastShow
import dev.schlubbe.musicagent.podcast.ShowKind
import dev.schlubbe.musicagent.podcast.PodcastRepository
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyPillShape
import dev.schlubbe.musicagent.ui.theme.CanopyShapes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Start page: half-heard episodes, the personal feed, what is new in Hörspiele and
 * Hörbücher, popular podcasts and the subscriptions. Local shelves show at once; the
 * others appear when their source answered (nothing is shown for one that failed). */
@HiltViewModel
class HomePodcastsViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val forYouFeed: ForYouFeed,
    private val playerController: PlayerController,
) : ViewModel() {
    val inProgress: StateFlow<List<PodcastProgressEntity>> = repository.observeInProgress(10)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val subscriptions: StateFlow<List<PodcastShowEntity>?> = repository.observeSubscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val forYou = MutableStateFlow<List<ForYouItem>>(emptyList())
    val newHoerspiele = MutableStateFlow<List<PodcastShow>>(emptyList())
    val newHoerbuecher = MutableStateFlow<List<PodcastShow>>(emptyList())
    val popularPodcasts = MutableStateFlow<List<PodcastShow>>(emptyList())

    init {
        refresh(force = false)
        // a new subscription or a finished episode changes the feed
        viewModelScope.launch {
            repository.observeSubscriptions().map { list -> list.map { it.id } }.distinctUntilChanged().drop(1)
                .collect { runCatching { forYouFeed.load() }.onSuccess { forYou.value = it } }
        }
    }

    /** Cheap when nothing changed: the feed is cached until something was heard or subscribed. */
    fun refreshFeed() {
        viewModelScope.launch { runCatching { forYouFeed.load() }.onSuccess { forYou.value = it } }
    }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch { runCatching { forYouFeed.load(force) }.onSuccess { forYou.value = it } }
        viewModelScope.launch {
            runCatching { repository.ardRecentShows(PodcastRepository.ARD_HOERSPIEL, ShowKind.HOERSPIEL) }
                .onSuccess { newHoerspiele.value = it.take(SHELF) }
        }
        viewModelScope.launch {
            runCatching { repository.ardRecentShows(PodcastRepository.ARD_HOERBUCH, ShowKind.HOERBUCH) }
                .onSuccess { newHoerbuecher.value = it.take(SHELF) }
        }
        viewModelScope.launch { runCatching { repository.topPodcasts() }.onSuccess { popularPodcasts.value = it.take(SHELF) } }
    }

    fun markHeard(progress: PodcastProgressEntity) {
        viewModelScope.launch { repository.markHeard(progress) }
    }

    fun forget(progress: PodcastProgressEntity) {
        viewModelScope.launch { repository.forgetProgress(progress.audioUrl) }
    }

    fun resume(progress: PodcastProgressEntity) {
        val track = TrackResultDto(
            source = PODCAST_SOURCE,
            sourceId = progress.audioUrl,
            title = progress.title,
            artist = progress.showTitle,
            album = progress.showId,
            durationSec = (progress.durationMs / 1000).toInt().takeIf { it > 0 },
            thumbnailUrl = progress.imageUrl,
            webpageUrl = progress.audioUrl,
        )
        viewModelScope.launch { playerController.playTrack(track) }
    }

    /** A feed episode, followed by the next parts of its show (cached, so no new request). */
    fun play(episode: PodcastEpisode) {
        viewModelScope.launch {
            val show = runCatching { repository.loadShow(episode.showId) }.getOrNull()
            val index = show?.episodes?.indexOfFirst { it.audioUrl == episode.audioUrl } ?: -1
            val following = if (show != null && index >= 0) show.following(index).take(10) else emptyList()
            playerController.playQueue((listOf(episode) + following).map { it.toTrack() }, 0)
        }
    }

    private companion object {
        const val SHELF = 12
    }
}

@Composable
fun HomePodcastShelves(
    onOpenPodcasts: () -> Unit,
    onShowSelected: (String) -> Unit,
    onEpisodePlayed: () -> Unit,
    viewModel: HomePodcastsViewModel = hiltViewModel(),
) {
    val inProgress by viewModel.inProgress.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val forYou by viewModel.forYou.collectAsState()
    val hoerspiele by viewModel.newHoerspiele.collectAsState()
    val hoerbuecher by viewModel.newHoerbuecher.collectAsState()
    val podcasts by viewModel.popularPodcasts.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshFeed() }

    Column {
        if (inProgress.isNotEmpty()) {
            ShelfTitle("Weiterhören", "Podcasts, Hörspiele und Hörbücher, wo du aufgehört hast")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(inProgress, key = { it.audioUrl }) { progress ->
                    var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    Column(
                        // a long press offers to hide it
                        modifier = Modifier.width(CARD.dp).combinedClickable(
                            onLongClick = { menu = true },
                        ) {
                            viewModel.resume(progress)
                            onEpisodePlayed()
                        },
                    ) {
                        ResumeMenu(menu, onDismiss = { menu = false }, onMarkHeard = { viewModel.markHeard(progress) }, onForget = { viewModel.forget(progress) })
                        Box {
                            TrackThumbnail(progress.imageUrl, size = CARD.dp, seed = progress.title)
                            PlayBadge(Modifier.align(Alignment.BottomEnd))
                        }
                        if (progress.durationMs > 0) {
                            LinearProgressIndicator(
                                progress = { (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) },
                                color = Canopy.accent,
                                trackColor = Canopy.divider,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp).clip(CanopyPillShape),
                            )
                        }
                        CardText(progress.title, remainingText(progress.positionMs, progress.durationMs) ?: progress.showTitle.orEmpty())
                    }
                }
            }
        }

        if (forYou.isNotEmpty()) {
            ShelfTitle("Hörtipps für dich", "Podcasts, Hörspiele und Hörbücher nach deinem Geschmack", action = "Alle" to onOpenPodcasts)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(forYou, key = { item -> when (item) { is ForYouItem.Episode -> "e:" + item.episode.audioUrl; is ForYouItem.Show -> "s:" + item.show.id } }) { item ->
                    when (item) {
                        is ForYouItem.Episode -> Column(
                            modifier = Modifier.width(WIDE.dp).clickable {
                                viewModel.play(item.episode)
                                onEpisodePlayed()
                            },
                        ) {
                            Box {
                                TrackThumbnail(item.episode.imageUrl, size = WIDE.dp, seed = item.episode.title)
                                PlayBadge(Modifier.align(Alignment.BottomEnd))
                            }
                            CardText(item.episode.title, item.reason)
                        }
                        is ForYouItem.Show -> Column(modifier = Modifier.width(WIDE.dp).clickable { onShowSelected(item.show.id) }) {
                            TrackThumbnail(item.show.imageUrl, size = WIDE.dp, seed = item.show.title)
                            CardText(item.show.title, item.reason)
                        }
                    }
                }
            }
        }

        ShowShelf("Neue Hörspiele", "Gerade erschienen in der ARD Audiothek", hoerspiele, onShowSelected)
        ShowShelf("Neue Hörbücher", "Lesungen und Hörbücher der ARD", hoerbuecher, onShowSelected)
        ShowShelf("Angesagte Podcasts", "Was gerade viele hören", podcasts, onShowSelected)

        val shows = subscriptions.orEmpty()
        if (shows.isNotEmpty()) {
            ShelfTitle("Deine Abos", "Podcasts, Hörspiele und Hörbücher", action = "Alle" to onOpenPodcasts)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shows, key = { it.id }) { show ->
                    Column(modifier = Modifier.width(CARD.dp).clickable { onShowSelected(show.id) }) {
                        TrackThumbnail(show.imageUrl, size = CARD.dp, seed = show.title)
                        CardText(show.title, null)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowShelf(title: String, subtitle: String, shows: List<PodcastShow>, onShowSelected: (String) -> Unit) {
    if (shows.isEmpty()) return
    ShelfTitle(title, subtitle)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(shows, key = { it.id }) { show ->
            Column(modifier = Modifier.width(CARD.dp).clickable { onShowSelected(show.id) }) {
                TrackThumbnail(show.imageUrl, size = CARD.dp, seed = show.title)
                CardText(show.title, show.author)
            }
        }
    }
}

@Composable
private fun CardText(title: String, subtitle: String?) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = Canopy.text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (!subtitle.isNullOrBlank()) {
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Canopy.neutral400, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlayBadge(modifier: Modifier) {
    Icon(
        phosphorIcon("play-circle", filled = true),
        contentDescription = null,
        tint = Canopy.accent,
        modifier = modifier.padding(8.dp).size(34.dp).clip(CanopyPillShape).background(Canopy.surface),
    )
}

@Composable
private fun ShelfTitle(title: String, subtitle: String, action: Pair<String, () -> Unit>? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = GAP.dp), verticalAlignment = Alignment.Bottom) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Canopy.text)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Canopy.neutral500,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        action?.let { (label, onClick) ->
            Text(label, style = MaterialTheme.typography.labelLarge, color = Canopy.accent, modifier = Modifier.clickable(onClick = onClick).padding(4.dp))
        }
    }
    Box(modifier = Modifier.height(12.dp))
}

// same measures as the start page's own shelves
private const val CARD = 146
private const val WIDE = 168
private const val GAP = 26
