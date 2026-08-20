package dev.schlubbe.musicagent.ui.library

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.local.entity.SavedPlaylistEntity
import dev.schlubbe.musicagent.data.remote.dto.LikeOutDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import dev.schlubbe.musicagent.data.remote.dto.toTrackResultDto
import dev.schlubbe.musicagent.ui.components.NocturneButtonVariant
import dev.schlubbe.musicagent.ui.components.NocturneIconButton
import dev.schlubbe.musicagent.ui.components.SegmentedControl
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.playlist.AddToPlaylistDialog
import dev.schlubbe.musicagent.ui.theme.Nocturne
import dev.schlubbe.musicagent.ui.theme.accentColorFor
import dev.schlubbe.musicagent.ui.util.shareText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onDownloadPlayed: () -> Unit,
    onPlaylistClick: (String) -> Unit,
    onSavedPlaylistClick: (source: String, sourceId: String) -> Unit,
    onArtistSelected: (source: String, sourceId: String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val downloads by viewModel.downloads.collectAsState()
    val likedTrackIds by viewModel.likedTrackIds.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

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

    LaunchedEffect(Unit) {
        when (uiState.selectedTab) {
            LibraryTab.LIKES -> viewModel.refreshLikes()
            LibraryTab.PLAYLISTS -> viewModel.refreshPlaylists()
            LibraryTab.DOWNLOADS -> Unit
        }
    }

    Scaffold(containerColor = Nocturne.bg) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Bibliothek", style = MaterialTheme.typography.headlineMedium)
                if (uiState.selectedTab == LibraryTab.PLAYLISTS) {
                    NocturneIconButton(
                        icon = phosphorIcon("plus"),
                        onClick = { showCreatePlaylistDialog = true },
                        shape = CircleShape,
                        variant = NocturneButtonVariant.Primary,
                    )
                }
            }

            SegmentedControl(
                options = LibraryTab.entries,
                selected = uiState.selectedTab,
                onSelect = viewModel::selectTab,
                label = {
                    when (it) {
                        LibraryTab.DOWNLOADS -> "Downloads"
                        LibraryTab.LIKES -> "Likes"
                        LibraryTab.PLAYLISTS -> "Playlists"
                    }
                },
                fillWidth = true,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            when (uiState.selectedTab) {
                LibraryTab.DOWNLOADS -> DownloadsTab(downloads, likedTrackIds, onDownloadPlayed, viewModel)
                LibraryTab.LIKES -> LikesTab(uiState, onDownloadPlayed, viewModel)
                LibraryTab.PLAYLISTS -> PlaylistsTab(uiState, onPlaylistClick, onSavedPlaylistClick, viewModel)
            }
        }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onCreate = { name ->
                viewModel.createPlaylist(name)
                showCreatePlaylistDialog = false
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

@Composable
private fun DownloadsTab(
    downloads: List<DownloadUiItem>,
    likedTrackIds: Set<String>,
    onDownloadPlayed: () -> Unit,
    viewModel: LibraryViewModel,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(downloads, key = { it.entity.trackId }) { item ->
            DownloadRow(
                item = item,
                isLiked = item.entity.trackId in likedTrackIds,
                onClick = {
                    if (item.entity.state == DownloadState.COMPLETED) {
                        viewModel.playDownload(item)
                        onDownloadPlayed()
                    }
                },
                onLikeClick = { item.track?.let { viewModel.onDownloadLikeToggled(it.toTrackResultDto()) } },
                onAddToPlaylistClick = { item.track?.let { viewModel.onAddToPlaylistClicked(it.toTrackResultDto()) } },
                onAddToQueueClick = { item.track?.let { viewModel.onAddToQueueClicked(it.toTrackResultDto()) } },
                onArtistClick = { item.track?.let { viewModel.onTrackArtistClicked(it.toTrackResultDto()) } },
                onPauseClick = { viewModel.onPauseDownloadClicked(item.entity.trackId) },
                onResumeClick = { viewModel.onResumeDownloadClicked(item.entity.trackId) },
                onRetryClick = { viewModel.onRetryDownloadClicked(item.entity.trackId) },
            )
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadUiItem,
    isLiked: Boolean,
    onClick: () -> Unit,
    onLikeClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onArtistClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onRetryClick: () -> Unit,
) {
    val entity = item.entity
    val track = item.track
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    // Falls back to the persisted percentage (from a prior run, or right before the
    // first live WorkManager progress event arrives) instead of always starting the
    // bar at 0 - matters most for PAUSED, which has no live progress at all.
    val displayPct = item.livePct ?: entity.progressPct

    ListItem(
        colors = ListItemDefaults.colors(containerColor = Nocturne.bg),
        leadingContent = { TrackThumbnail(track?.thumbnailUrl) },
        headlineContent = {
            Text(track?.title ?: entity.trackId, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            when (entity.state) {
                DownloadState.DOWNLOADING, DownloadState.PAUSED -> Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Nocturne.neutral800),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(displayPct / 100f)
                                .height(4.dp)
                                .background(Nocturne.accent),
                        )
                    }
                    if (entity.state == DownloadState.PAUSED) {
                        Text(
                            "Pausiert · $displayPct%",
                            color = Nocturne.neutral500,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                DownloadState.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        phosphorIcon("warning-circle"),
                        contentDescription = null,
                        tint = Nocturne.neutral500,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        "Fehlgeschlagen",
                        color = Nocturne.neutral500,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                DownloadState.COMPLETED -> Text(
                    track?.artist ?: "Heruntergeladen",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Nocturne.neutral500,
                )
                DownloadState.QUEUED -> Text("Wartet...", fontStyle = FontStyle.Italic, color = Nocturne.neutral500)
            }
        },
        trailingContent = when (entity.state) {
            DownloadState.COMPLETED -> if (track == null) null else {
                {
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
                                text = { Text("Zum Künstler") },
                                leadingIcon = { Icon(phosphorIcon("user-circle"), contentDescription = null, tint = Nocturne.accent) },
                                onClick = { menuExpanded = false; onArtistClick() },
                            )
                            DropdownMenuItem(
                                text = { Text("Teilen") },
                                leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Nocturne.accent) },
                                onClick = { menuExpanded = false; context.shareText(track.webpageUrl) },
                            )
                        }
                    }
                }
                }
            }
            DownloadState.DOWNLOADING -> {
                { NocturneIconButton(icon = phosphorIcon("pause"), onClick = onPauseClick) }
            }
            DownloadState.PAUSED -> {
                { NocturneIconButton(icon = phosphorIcon("play"), onClick = onResumeClick) }
            }
            DownloadState.FAILED -> {
                { NocturneIconButton(icon = phosphorIcon("arrow-clockwise"), onClick = onRetryClick) }
            }
            DownloadState.QUEUED -> null
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entity.state == DownloadState.COMPLETED, onClick = onClick),
    )
}

@Composable
private fun LikesTab(uiState: LibraryUiState, onTrackPlayed: () -> Unit, viewModel: LibraryViewModel) {
    if (uiState.isLoadingLikes) {
        CircularProgressIndicator(modifier = Modifier.padding(16.dp), color = Nocturne.accent)
        return
    }
    if (uiState.likes.isEmpty()) {
        Text("Noch keine Likes", color = Nocturne.neutral500, modifier = Modifier.padding(20.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(uiState.likes, key = { it.track.id }) { like ->
            LikeRow(
                like = like,
                onClick = {
                    viewModel.playLikedTrack(like)
                    onTrackPlayed()
                },
                onUnlikeClick = { viewModel.unlike(like) },
                onAddToPlaylistClick = { viewModel.onAddToPlaylistClicked(like.track.toTrackResultDto()) },
                onAddToQueueClick = { viewModel.onAddToQueueClicked(like.track.toTrackResultDto()) },
                onDownloadClick = { viewModel.onDownloadClicked(like.track.toTrackResultDto()) },
                onArtistClick = { viewModel.onTrackArtistClicked(like.track.toTrackResultDto()) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LikeRow(
    like: LikeOutDto,
    onClick: () -> Unit,
    onUnlikeClick: () -> Unit,
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
            leadingContent = { TrackThumbnail(like.track.thumbnailUrl) },
            headlineContent = { Text(like.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(like.track.artist ?: like.track.source, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Nocturne.neutral500)
            },
            trailingContent = {
                Row {
                    NocturneIconButton(
                        icon = phosphorIcon("heart", filled = true),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.ToggleOff)
                            onUnlikeClick()
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
                                text = { Text("Nicht mehr gefällt mir") },
                                leadingIcon = { Icon(phosphorIcon("heart", filled = true), contentDescription = null, tint = Nocturne.accent) },
                                onClick = { menuExpanded = false; onUnlikeClick() },
                            )
                            DropdownMenuItem(
                                text = { Text("Zum Künstler") },
                                leadingIcon = { Icon(phosphorIcon("user-circle"), contentDescription = null, tint = Nocturne.accent) },
                                onClick = {
                                    menuExpanded = false
                                    like.track.artist?.let(onArtistClick)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Teilen") },
                                leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Nocturne.accent) },
                                onClick = { menuExpanded = false; context.shareText(like.track.webpageUrl) },
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

/** Either a locally-created playlist (Room, editable, no remote origin) or a
 * public SoundCloud/YouTube playlist the user liked from Search (see
 * SavedPlaylistRepository) - merged into one list per the chosen "Playlists" tab
 * UX (badge to tell them apart, rather than a separate tab). */
private sealed class LibraryPlaylistItem {
    abstract val key: String
    data class Local(val playlist: PlaylistOutDto) : LibraryPlaylistItem() {
        override val key = "local:${playlist.id}"
    }
    data class Saved(val playlist: SavedPlaylistEntity) : LibraryPlaylistItem() {
        override val key = "saved:${playlist.source}:${playlist.sourceId}"
    }
}

@Composable
private fun PlaylistsTab(
    uiState: LibraryUiState,
    onPlaylistClick: (String) -> Unit,
    onSavedPlaylistClick: (source: String, sourceId: String) -> Unit,
    viewModel: LibraryViewModel,
) {
    val context = LocalContext.current
    if (uiState.isLoadingPlaylists) {
        CircularProgressIndicator(modifier = Modifier.padding(16.dp), color = Nocturne.accent)
        return
    }
    val items: List<LibraryPlaylistItem> =
        uiState.playlists.map { LibraryPlaylistItem.Local(it) } +
            uiState.savedPlaylists.map { LibraryPlaylistItem.Saved(it) }
    if (items.isEmpty()) {
        Text("Noch keine Playlists", color = Nocturne.neutral500, modifier = Modifier.padding(20.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is LibraryPlaylistItem.Local -> {
                    val playlist = item.playlist
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Nocturne.bg),
                        modifier = Modifier.fillMaxWidth().clickable { onPlaylistClick(playlist.id) },
                        leadingContent = {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(accentColorFor(playlist.accentColorKey, playlist.id)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(phosphorIcon("stack"), contentDescription = null, tint = Nocturne.text, modifier = Modifier.size(18.dp))
                            }
                        },
                        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text("${playlist.trackCount} Titel", color = Nocturne.neutral500)
                        },
                        trailingContent = {
                            Row {
                                // Local playlists have no remote URL - shares a plain-text
                                // summary instead of a link, unlike every other share action.
                                NocturneIconButton(
                                    icon = phosphorIcon("share-network"),
                                    onClick = { context.shareText("${playlist.name} (${playlist.trackCount} Titel) — geteilt aus Music Agent") },
                                )
                                NocturneIconButton(icon = phosphorIcon("pencil-simple"), onClick = { onPlaylistClick(playlist.id) })
                            }
                        },
                    )
                }
                is LibraryPlaylistItem.Saved -> {
                    val playlist = item.playlist
                    var menuExpanded by remember { mutableStateOf(false) }
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Nocturne.bg),
                        modifier = Modifier.fillMaxWidth().clickable {
                            onSavedPlaylistClick(playlist.source, playlist.sourceId)
                        },
                        leadingContent = { TrackThumbnail(playlist.thumbnailUrl, size = 46.dp) },
                        headlineContent = { Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (playlist.source == "soundcloud") "SoundCloud" else "YT Music",
                                    color = Nocturne.accent,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                val subtitle = listOfNotNull(playlist.owner, playlist.trackCount?.let { "$it Titel" })
                                    .joinToString(" · ")
                                if (subtitle.isNotBlank()) {
                                    Text(
                                        " · $subtitle",
                                        color = Nocturne.neutral500,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Row {
                                NocturneIconButton(
                                    icon = phosphorIcon("share-network"),
                                    onClick = { context.shareText(playlist.webpageUrl) },
                                )
                                Box {
                                    NocturneIconButton(icon = phosphorIcon("dots-three"), onClick = { menuExpanded = true })
                                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Nicht mehr gespeichert") },
                                            leadingIcon = {
                                                Icon(phosphorIcon("heart", filled = true), contentDescription = null, tint = Nocturne.accent)
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                viewModel.onUnsavePlaylist(playlist.source, playlist.sourceId)
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neue Playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Playlist-Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) {
                Text("Erstellen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}
