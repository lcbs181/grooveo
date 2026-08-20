package dev.schlubbe.musicagent.ui.playlist

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.remote.dto.PlaylistDetailOutDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistTrackOutDto
import dev.schlubbe.musicagent.data.remote.dto.toTrackResultDto
import dev.schlubbe.musicagent.ui.components.NocturneIconButton
import dev.schlubbe.musicagent.ui.components.NocturneTag
import dev.schlubbe.musicagent.ui.components.NocturneTagStyle
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Nocturne
import dev.schlubbe.musicagent.ui.theme.accentColorFor
import dev.schlubbe.musicagent.ui.util.shareText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onTrackSelected: () -> Unit,
    onNavigateBack: () -> Unit,
    onDeleted: () -> Unit,
    onArtistSelected: (source: String, sourceId: String) -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val playlist = uiState.playlist
    var showEditSheet by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(uiState.artistNavTarget) {
        uiState.artistNavTarget?.let { (source, sourceId) ->
            onArtistSelected(source, sourceId)
            viewModel.onArtistNavigated()
        }
    }
    LaunchedEffect(uiState.artistLookupError) {
        uiState.artistLookupError?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.onArtistLookupErrorShown()
        }
    }
    LaunchedEffect(uiState.downloadPlaylistMessage) {
        uiState.downloadPlaylistMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.onDownloadPlaylistMessageShown()
        }
    }

    Scaffold(containerColor = Nocturne.bg) { padding ->
        when {
            uiState.isLoading -> CircularProgressIndicator(
                modifier = Modifier.padding(padding).padding(16.dp),
                color = Nocturne.accent,
            )
            playlist == null -> Text(
                "Playlist nicht gefunden",
                modifier = Modifier.padding(padding).padding(16.dp),
            )
            else -> LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        NocturneIconButton(icon = phosphorIcon("caret-left"), onClick = onNavigateBack, iconSize = 20.dp)
                        Box {
                            NocturneIconButton(icon = phosphorIcon("dots-three"), onClick = { showTopMenu = true })
                            DropdownMenu(expanded = showTopMenu, onDismissRequest = { showTopMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Herunterladen") },
                                    leadingIcon = { Icon(phosphorIcon("download-simple"), contentDescription = null, tint = Nocturne.accent) },
                                    onClick = {
                                        showTopMenu = false
                                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                        viewModel.onDownloadPlaylistClicked()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Löschen") },
                                    leadingIcon = { Icon(phosphorIcon("trash"), contentDescription = null, tint = Nocturne.accent) },
                                    onClick = {
                                        showTopMenu = false
                                        viewModel.delete(onDeleted)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Teilen") },
                                    leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Nocturne.accent) },
                                    onClick = {
                                        showTopMenu = false
                                        // Local playlists have no remote URL - shares a
                                        // plain-text summary (name + track list) instead.
                                        val trackLines = playlist.tracks.joinToString("\n") { "- ${it.track.title}" }
                                        context.shareText("${playlist.name} (${playlist.tracks.size} Titel)\n$trackLines")
                                    },
                                )
                            }
                        }
                    }
                }
                item { PlaylistHeader(playlist, onEditClick = { showEditSheet = true }) }
                if (playlist.tracks.isEmpty()) {
                    item {
                        Text(
                            "Noch keine Titel",
                            color = Nocturne.neutral500,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                } else {
                    items(playlist.tracks, key = { it.track.id }) { item ->
                        PlaylistTrackRow(
                            item = item,
                            isLiked = item.track.id in uiState.likedTrackIds,
                            onClick = {
                                viewModel.playTrack(item)
                                onTrackSelected()
                            },
                            onMoveUp = { viewModel.moveTrack(item, -1) },
                            onMoveDown = { viewModel.moveTrack(item, 1) },
                            onRemove = { viewModel.removeTrack(item) },
                            onLikeClick = { viewModel.onLikeToggled(item.track.toTrackResultDto()) },
                            onAddToPlaylistClick = { viewModel.onAddToPlaylistClicked(item.track.toTrackResultDto()) },
                            onAddToQueueClick = { viewModel.onAddToQueueClicked(item.track.toTrackResultDto()) },
                            onDownloadClick = { viewModel.onDownloadClicked(item.track.toTrackResultDto()) },
                            onArtistClick = { viewModel.onTrackArtistClicked(item.track.toTrackResultDto()) },
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(20.dp)) }
            }
        }
    }

    if (showEditSheet && playlist != null) {
        PlaylistEditSheet(
            playlistId = playlist.id,
            initialName = playlist.name,
            initialDescription = playlist.description,
            initialAccentColorKey = playlist.accentColorKey,
            initialMoodTags = playlist.moodTags,
            onDismiss = { showEditSheet = false },
            onSave = { name, description, accentColorKey, moodTags ->
                viewModel.updateDetails(name, description, accentColorKey, moodTags)
                showEditSheet = false
            },
        )
    }

    uiState.trackPendingPlaylistAdd?.let {
        AddToPlaylistDialog(
            playlists = uiState.playlists,
            onDismiss = viewModel::dismissAddToPlaylist,
            onPlaylistPicked = viewModel::onPlaylistPicked,
            onCreatePlaylist = viewModel::onCreatePlaylistAndAdd,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistTrackRow(
    item: PlaylistTrackOutDto,
    isLiked: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onLikeClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onArtistClick: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onAddToQueueClick()
                Toast.makeText(context, "Zur Warteschlange hinzugefügt", Toast.LENGTH_SHORT).show()
            }
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Nocturne.accent800)
                    .padding(start = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(phosphorIcon("list-plus"), contentDescription = "Zur Warteschlange hinzufügen", tint = Nocturne.accent100)
            }
        },
    ) {
        var menuExpanded by remember { mutableStateOf(false) }
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Nocturne.bg),
            leadingContent = { TrackThumbnail(item.track.thumbnailUrl) },
            headlineContent = {
                Text(item.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(item.track.artist ?: item.track.source, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Nocturne.neutral500)
            },
            trailingContent = {
                Row {
                    NocturneIconButton(
                        icon = phosphorIcon("heart", filled = isLiked),
                        onClick = {
                            haptic.performHapticFeedback(if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                            onLikeClick()
                        },
                    )
                    Box {
                        NocturneIconButton(icon = phosphorIcon("dots-three"), onClick = { menuExpanded = true })
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Zu Playlist hinzufügen") },
                                leadingIcon = { Icon(phosphorIcon("plus-circle"), contentDescription = null, tint = Nocturne.accent) },
                                onClick = { menuExpanded = false; onAddToPlaylistClick() },
                            )
                            DropdownMenuItem(
                                text = { Text("Zur Warteschlange hinzufügen") },
                                leadingIcon = { Icon(phosphorIcon("list-plus"), contentDescription = null, tint = Nocturne.accent) },
                                onClick = { menuExpanded = false; onAddToQueueClick() },
                            )
                            DropdownMenuItem(
                                text = { Text("Herunterladen") },
                                leadingIcon = { Icon(phosphorIcon("download-simple"), contentDescription = null, tint = Nocturne.accent) },
                                onClick = { menuExpanded = false; onDownloadClick() },
                            )
                            DropdownMenuItem(
                                text = { Text("Zum Künstler") },
                                leadingIcon = { Icon(phosphorIcon("user-circle"), contentDescription = null, tint = Nocturne.accent) },
                                onClick = { menuExpanded = false; item.track.artist?.let(onArtistClick) },
                            )
                            DropdownMenuItem(
                                text = { Text("Teilen") },
                                leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Nocturne.accent) },
                                onClick = { menuExpanded = false; context.shareText(item.track.webpageUrl) },
                            )
                            DropdownMenuItem(
                                text = { Text("Nach oben") },
                                leadingIcon = { Icon(phosphorIcon("caret-right"), contentDescription = null, tint = Nocturne.accent) },
                                onClick = { menuExpanded = false; onMoveUp() },
                            )
                            DropdownMenuItem(
                                text = { Text("Nach unten") },
                                leadingIcon = { Icon(phosphorIcon("caret-down"), contentDescription = null, tint = Nocturne.accent) },
                                onClick = { menuExpanded = false; onMoveDown() },
                            )
                            DropdownMenuItem(
                                text = { Text("Entfernen") },
                                leadingIcon = { Icon(phosphorIcon("trash"), contentDescription = null, tint = Nocturne.accent) },
                                onClick = { menuExpanded = false; onRemove() },
                            )
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        )
    }
}

/** Small 80dp cover slot next to name/description/tags, with the edit-pencil at
 * the row's end -- matches the design's header layout exactly (not a big
 * full-width hero cover, which is what this looked like before). */
@Composable
private fun PlaylistHeader(playlist: PlaylistDetailOutDto, onEditClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(accentColorFor(playlist.accentColorKey, playlist.id)),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp, top = 2.dp)) {
            Text(playlist.name, style = MaterialTheme.typography.headlineSmall)
            if (!playlist.description.isNullOrBlank()) {
                Text(
                    playlist.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Nocturne.neutral500,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            if (playlist.moodTags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    playlist.moodTags.forEach { key ->
                        val label = MoodTag.entries.firstOrNull { it.key == key }?.label ?: key
                        NocturneTag(label, style = NocturneTagStyle.Outline)
                    }
                }
            }
        }
        NocturneIconButton(icon = phosphorIcon("pencil-simple"), onClick = onEditClick, iconSize = 17.dp)
    }
}
