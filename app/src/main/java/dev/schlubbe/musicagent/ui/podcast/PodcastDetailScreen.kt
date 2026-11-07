package dev.schlubbe.musicagent.ui.podcast

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.local.entity.PodcastProgressEntity
import dev.schlubbe.musicagent.podcast.PodcastEpisode
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.PullRefresh
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyPillShape
import dev.schlubbe.musicagent.ui.theme.LocalChromeInset
import dev.schlubbe.musicagent.ui.theme.dawnContainer
import dev.schlubbe.musicagent.ui.util.shareText
import java.text.DateFormat
import java.util.Date

@Composable
fun PodcastDetailScreen(
    onNavigateBack: () -> Unit,
    onEpisodePlayed: () -> Unit,
    viewModel: PodcastDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val subscribed by viewModel.isSubscribed.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.onMessageShown()
        }
    }

    Scaffold(containerColor = dawnContainer, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 10.dp)) {
                CanopyIconButton(icon = phosphorIcon("caret-left"), onClick = onNavigateBack, iconSize = 20.dp, contentDescription = "Zurück")
            }
            val data = state.data
            when {
                state.loading -> Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Canopy.accent)
                }
                data == null -> Column(modifier = Modifier.padding(16.dp)) {
                    Text(state.error ?: "Nicht gefunden", color = MaterialTheme.colorScheme.error)
                    CanopyButton(
                        text = "Erneut versuchen",
                        onClick = { viewModel.load() },
                        variant = CanopyButtonVariant.Secondary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                else -> PullRefresh(onRefresh = { viewModel.load() }, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalChromeInset.current + 16.dp)) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TrackThumbnail(data.show.imageUrl, size = 96.dp, seed = data.show.title)
                                Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                                    Text(data.show.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    val subtitle = listOfNotNull(data.show.author, "${data.episodes.size} Folgen").joinToString(" · ")
                                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Canopy.neutral500, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                            data.show.description?.let { Description(it) }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val next = nextToPlay(data.episodes, progress)
                                CanopyButton(
                                    text = if (next != null && progress[next.audioUrl]?.let { !it.completed } == true) "Weiterhören" else "Abspielen",
                                    onClick = {
                                        next?.let {
                                            viewModel.play(it)
                                            onEpisodePlayed()
                                        }
                                    },
                                    leadingIcon = phosphorIcon("play"),
                                    enabled = next != null,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                CanopyButton(
                                    text = if (subscribed) "Abonniert" else "Abonnieren",
                                    onClick = viewModel::toggleSubscribed,
                                    leadingIcon = phosphorIcon(if (subscribed) "check" else "plus"),
                                    variant = CanopyButtonVariant.Secondary,
                                )
                            }
                        }
                        if (data.episodes.isEmpty()) {
                            item { Text("Keine Folgen verfügbar", color = Canopy.neutral500, modifier = Modifier.padding(20.dp)) }
                        }
                        items(data.episodes, key = { it.audioUrl }) { episode ->
                            EpisodeRow(
                                episode = episode,
                                progress = progress[episode.audioUrl],
                                onClick = {
                                    viewModel.play(episode)
                                    onEpisodePlayed()
                                },
                                onAddToQueue = { viewModel.addToQueue(episode) },
                                onDownload = { viewModel.download(episode) },
                                onSetPlayed = { viewModel.setPlayed(episode, it) },
                                onShare = { context.shareText(episode.link ?: episode.audioUrl) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The episode the big button plays: one that is half heard, else the newest unheard one. */
private fun nextToPlay(episodes: List<PodcastEpisode>, progress: Map<String, PodcastProgressEntity>): PodcastEpisode? =
    episodes.filter { progress[it.audioUrl]?.let { p -> !p.completed } == true }
        .maxByOrNull { progress[it.audioUrl]!!.updatedAt }
        ?: episodes.firstOrNull { progress[it.audioUrl]?.completed != true }
        ?: episodes.firstOrNull()

@Composable
private fun Description(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Canopy.neutral500,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).clickable { expanded = !expanded },
    )
}

@Composable
private fun EpisodeRow(
    episode: PodcastEpisode,
    progress: PodcastProgressEntity?,
    onClick: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownload: () -> Unit,
    onSetPlayed: (Boolean) -> Unit,
    onShare: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val done = progress?.completed == true
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                episode.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (done) FontWeight.Normal else FontWeight.Medium,
                color = if (done) Canopy.neutral500 else Canopy.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                episode.publishedAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) },
                when {
                    done -> "Gehört"
                    progress != null -> remainingText(progress.positionMs, progress.durationMs)
                    else -> episode.durationSec?.let { "${(it + 59) / 60} Min." }
                },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500, modifier = Modifier.padding(top = 2.dp))
            }
            if (progress != null && !done && progress.durationMs > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) },
                    color = Canopy.accent,
                    trackColor = Canopy.divider,
                    modifier = Modifier.fillMaxWidth(0.6f).height(3.dp).clip(CanopyPillShape),
                )
            }
        }
        Box {
            CanopyIconButton(icon = phosphorIcon("dots-three"), onClick = { menu = true }, contentDescription = "Mehr")
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Zur Warteschlange hinzufügen") },
                    leadingIcon = { Icon(phosphorIcon("list-plus"), contentDescription = null, tint = Canopy.accent) },
                    onClick = { menu = false; onAddToQueue() },
                )
                DropdownMenuItem(
                    text = { Text("Herunterladen") },
                    leadingIcon = { Icon(phosphorIcon("download-simple"), contentDescription = null, tint = Canopy.accent) },
                    onClick = { menu = false; onDownload() },
                )
                DropdownMenuItem(
                    text = { Text(if (done) "Als ungehört markieren" else "Als gehört markieren") },
                    leadingIcon = { Icon(phosphorIcon("check-circle"), contentDescription = null, tint = Canopy.accent) },
                    onClick = { menu = false; onSetPlayed(!done) },
                )
                DropdownMenuItem(
                    text = { Text("Teilen") },
                    leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Canopy.accent) },
                    onClick = { menu = false; onShare() },
                )
            }
        }
    }
}
