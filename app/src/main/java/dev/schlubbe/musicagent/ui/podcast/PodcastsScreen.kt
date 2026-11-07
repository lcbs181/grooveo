package dev.schlubbe.musicagent.ui.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.local.entity.PodcastProgressEntity
import dev.schlubbe.musicagent.podcast.PodcastShow
import dev.schlubbe.musicagent.podcast.ShowKind
import dev.schlubbe.musicagent.ui.components.CanopyChip
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyPillShape
import dev.schlubbe.musicagent.ui.theme.LocalChromeInset
import dev.schlubbe.musicagent.ui.theme.dawnContainer

/** Podcasts and Hörspiele: search, what is half heard, subscriptions, and something to
 * browse before anything is searched. */
@Composable
fun PodcastsScreen(
    onNavigateBack: () -> Unit,
    onShowSelected: (showId: String) -> Unit,
    onEpisodePlayed: () -> Unit,
    viewModel: PodcastsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val inProgress by viewModel.inProgress.collectAsState()
    var showAddFeed by remember { mutableStateOf(false) }

    LaunchedEffect(state.openShowId) {
        state.openShowId?.let {
            showAddFeed = false
            viewModel.onShowOpened()
            onShowSelected(it)
        }
    }

    Scaffold(containerColor = dawnContainer, contentWindowInsets = WindowInsets(0)) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalChromeInset.current + 16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CanopyIconButton(icon = phosphorIcon("caret-left"), onClick = onNavigateBack, iconSize = 20.dp, contentDescription = "Zurück")
                    Text(
                        "Podcasts & Hörspiele",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    CanopyIconButton(
                        icon = phosphorIcon("rss"),
                        onClick = { showAddFeed = true },
                        iconSize = 20.dp,
                        contentDescription = "RSS-Feed hinzufügen",
                    )
                }
            }
            item { SearchRow(state.query, state.kind, viewModel::setQuery) { viewModel.search() } }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    CanopyChip("Podcasts", active = state.kind == ShowKind.PODCAST, onClick = { viewModel.setKind(ShowKind.PODCAST) })
                    CanopyChip("Hörspiele", active = state.kind == ShowKind.HOERSPIEL, onClick = { viewModel.setKind(ShowKind.HOERSPIEL) })
                }
            }

            if (state.query.trim().length >= 2) {
                when {
                    state.searching && state.results.isEmpty() -> item { Loading() }
                    state.error != null -> item { Hint(state.error!!) }
                    state.results.isEmpty() && !state.searching -> item { Hint("Nichts gefunden") }
                    else -> items(state.results, key = { "r:${it.id}" }) { show -> ShowRow(show) { onShowSelected(show.id) } }
                }
            } else {
                if (inProgress.isNotEmpty()) {
                    item { Section("Weiterhören") }
                    items(inProgress, key = { "p:${it.audioUrl}" }) { progress ->
                        ResumeRow(progress) {
                            viewModel.resume(progress)
                            onEpisodePlayed()
                        }
                    }
                }
                val subscribed = subscriptions.filter { ShowKind.of(it.kind) == state.kind }
                if (subscribed.isNotEmpty()) {
                    item { Section("Abonniert") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(subscribed, key = { "s:${it.id}" }) { show ->
                                Column(modifier = Modifier.width(112.dp).clickable { onShowSelected(show.id) }) {
                                    TrackThumbnail(show.imageUrl, size = 112.dp, seed = show.title)
                                    Text(
                                        show.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (state.kind == ShowKind.PODCAST) {
                    item { Section("Beliebt in Deutschland") }
                    if (state.topPodcasts.isEmpty()) {
                        item { if (state.browseLoading) Loading() else Hint("Gerade nicht erreichbar") }
                    }
                    items(state.topPodcasts, key = { "t:${it.id}" }) { show -> ShowRow(show) { onShowSelected(show.id) } }
                } else {
                    if (state.hoerspiele.isEmpty() && state.kinder.isEmpty()) {
                        item { if (state.browseLoading) Loading() else Hint("Gerade nicht erreichbar") }
                    }
                    if (state.hoerspiele.isNotEmpty()) {
                        item { Section("Hörspiele aus der ARD Audiothek") }
                        items(state.hoerspiele, key = { "h:${it.id}" }) { show -> ShowRow(show) { onShowSelected(show.id) } }
                    }
                    if (state.kinder.isNotEmpty()) {
                        item { Section("Für Kinder") }
                        items(state.kinder, key = { "k:${it.id}" }) { show -> ShowRow(show) { onShowSelected(show.id) } }
                    }
                }
            }
        }
    }

    if (showAddFeed) {
        AddFeedDialog(
            busy = state.addingFeed,
            error = state.feedError,
            onAdd = viewModel::addFeed,
            onDismiss = {
                showAddFeed = false
                viewModel.clearFeedError()
            },
        )
    }
}

@Composable
private fun SearchRow(query: String, kind: ShowKind, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp)
            .clip(CanopyPillShape)
            .background(Canopy.surface)
            .border(1.dp, Canopy.divider, CanopyPillShape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(phosphorIcon("magnifying-glass"), contentDescription = null, tint = Canopy.neutral500, modifier = Modifier.size(18.dp))
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Canopy.text),
                cursorBrush = SolidColor(Canopy.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (query.isEmpty()) {
                Text(
                    if (kind == ShowKind.PODCAST) "Podcasts suchen" else "Hörspiele suchen",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Canopy.neutral500,
                )
            }
        }
        if (query.isNotEmpty()) {
            Icon(
                phosphorIcon("x"),
                contentDescription = "Suche löschen",
                tint = Canopy.neutral400,
                modifier = Modifier.size(18.dp).clickable { onChange("") },
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Canopy.accent)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Canopy.neutral500, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
internal fun ShowRow(show: PodcastShow, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackThumbnail(show.imageUrl, size = 56.dp, seed = show.title)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(show.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val subtitle = listOfNotNull(show.author, show.episodeCount?.takeIf { it > 0 }?.let { "$it Folgen" }).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ResumeRow(progress: PodcastProgressEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackThumbnail(progress.imageUrl, size = 56.dp, seed = progress.title)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(progress.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(progress.showTitle, remainingText(progress.positionMs, progress.durationMs)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Canopy.neutral500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress.durationMs > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) },
                    color = Canopy.accent,
                    trackColor = Canopy.divider,
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CanopyPillShape),
                )
            }
        }
        Icon(phosphorIcon("play", filled = true), contentDescription = "Weiterhören", tint = Canopy.accent, modifier = Modifier.padding(start = 12.dp).size(22.dp))
    }
}

internal fun remainingText(positionMs: Long, durationMs: Long): String? {
    if (durationMs <= 0) return null
    val minutes = ((durationMs - positionMs).coerceAtLeast(0) / 60_000).toInt()
    return if (minutes < 1) "fast fertig" else "noch $minutes Min."
}

@Composable
private fun AddFeedDialog(busy: Boolean, error: String?, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("RSS-Feed hinzufügen") },
        text = {
            Column {
                Text(
                    "Die Feed-Adresse eines Podcasts, z. B. von der Website des Podcasts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Canopy.neutral500,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    placeholder = { Text("https://…/feed.xml") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (url.isNotBlank() && !busy) onAdd(url) }),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                if (busy) LinearProgressIndicator(color = Canopy.accent, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(url) }, enabled = url.isNotBlank() && !busy) { Text("Hinzufügen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
