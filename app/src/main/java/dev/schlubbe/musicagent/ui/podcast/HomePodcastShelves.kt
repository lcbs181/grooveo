package dev.schlubbe.musicagent.ui.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import dev.schlubbe.musicagent.podcast.PODCAST_SOURCE
import dev.schlubbe.musicagent.podcast.PodcastRepository
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyPillShape
import dev.schlubbe.musicagent.ui.theme.CanopyShapes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Local data only (no network on the start page): half-heard episodes and subscriptions. */
@HiltViewModel
class HomePodcastsViewModel @Inject constructor(
    repository: PodcastRepository,
    private val playerController: PlayerController,
) : ViewModel() {
    val inProgress: StateFlow<List<PodcastProgressEntity>> = repository.observeInProgress(10)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val subscriptions: StateFlow<List<PodcastShowEntity>?> = repository.observeSubscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
}

/** Start page: "Weiterhören" and the subscribed shows; with neither yet, one card that
 * leads to Podcasts & Hörspiele so the feature can be found. */
@Composable
fun HomePodcastShelves(
    onOpenPodcasts: () -> Unit,
    onShowSelected: (String) -> Unit,
    onEpisodePlayed: () -> Unit,
    viewModel: HomePodcastsViewModel = hiltViewModel(),
) {
    val inProgress by viewModel.inProgress.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val shows = subscriptions ?: return // not read yet: no flicker of the teaser card

    Column {
    if (inProgress.isNotEmpty()) {
        ShelfTitle("Weiterhören", "Podcasts und Hörspiele, wo du aufgehört hast")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(inProgress, key = { it.audioUrl }) { progress ->
                Column(
                    modifier = Modifier.width(CARD.dp).clickable {
                        viewModel.resume(progress)
                        onEpisodePlayed()
                    },
                ) {
                    Box {
                        TrackThumbnail(progress.imageUrl, size = CARD.dp, seed = progress.title)
                        Icon(
                            phosphorIcon("play-circle", filled = true),
                            contentDescription = "Weiterhören",
                            tint = Canopy.accent,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(34.dp)
                                .clip(CanopyPillShape).background(Canopy.surface),
                        )
                    }
                    if (progress.durationMs > 0) {
                        LinearProgressIndicator(
                            progress = { (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) },
                            color = Canopy.accent,
                            trackColor = Canopy.divider,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp).clip(CanopyPillShape),
                        )
                    }
                    Text(
                        progress.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = Canopy.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        remainingText(progress.positionMs, progress.durationMs) ?: progress.showTitle.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Canopy.neutral400,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    if (shows.isNotEmpty()) {
        ShelfTitle("Deine Podcasts & Hörspiele", "Abonniert", action = "Alle" to onOpenPodcasts)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shows, key = { it.id }) { show ->
                Column(modifier = Modifier.width(CARD.dp).clickable { onShowSelected(show.id) }) {
                    TrackThumbnail(show.imageUrl, size = CARD.dp, seed = show.title)
                    Text(
                        show.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = Canopy.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    } else if (inProgress.isEmpty()) {
        Row(
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = GAP.dp)
                .fillMaxWidth()
                .clip(CanopyShapes.medium)
                .background(Canopy.surface)
                .border(1.dp, Canopy.divider, CanopyShapes.medium)
                .clickable(onClick = onOpenPodcasts)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(phosphorIcon("microphone"), contentDescription = null, tint = Canopy.accent, modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                Text("Podcasts & Hörspiele", style = MaterialTheme.typography.titleMedium, color = Canopy.text)
                Text(
                    "Podcasts abonnieren und Hörspiele aus der ARD Audiothek hören",
                    style = MaterialTheme.typography.bodySmall,
                    color = Canopy.neutral500,
                )
            }
            Icon(phosphorIcon("caret-right"), contentDescription = null, tint = Canopy.neutral400, modifier = Modifier.size(18.dp))
        }
    }
    }
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
private const val GAP = 26
