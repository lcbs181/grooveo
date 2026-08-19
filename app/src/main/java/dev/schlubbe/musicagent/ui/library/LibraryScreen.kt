package dev.schlubbe.musicagent.ui.library

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.remote.dto.LikeOutDto
import dev.schlubbe.musicagent.data.remote.dto.toTrackResultDto
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.playlist.AddToPlaylistDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onDownloadPlayed: () -> Unit,
    onPlaylistClick: (String) -> Unit,
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

    Scaffold(
        topBar = { TopAppBar(title = { Text("Bibliothek") }) },
        floatingActionButton = {
            if (uiState.selectedTab == LibraryTab.PLAYLISTS) {
                FloatingActionButton(onClick = { showCreatePlaylistDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Neue Playlist")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
                Tab(
                    selected = uiState.selectedTab == LibraryTab.DOWNLOADS,
                    onClick = { viewModel.selectTab(LibraryTab.DOWNLOADS) },
                    text = { Text("Downloads") },
                )
                Tab(
                    selected = uiState.selectedTab == LibraryTab.LIKES,
                    onClick = { viewModel.selectTab(LibraryTab.LIKES) },
                    text = { Text("Likes") },
                )
                Tab(
                    selected = uiState.selectedTab == LibraryTab.PLAYLISTS,
                    onClick = { viewModel.selectTab(LibraryTab.PLAYLISTS) },
                    text = { Text("Playlists") },
                )
            }

            when (uiState.selectedTab) {
                LibraryTab.DOWNLOADS -> DownloadsTab(downloads, likedTrackIds, onDownloadPlayed, viewModel)
                LibraryTab.LIKES -> LikesTab(uiState, onDownloadPlayed, viewModel)
                LibraryTab.PLAYLISTS -> PlaylistsTab(uiState, onPlaylistClick)
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
) {
    val entity = item.entity
    val track = item.track
    val haptic = LocalHapticFeedback.current
    var menuExpanded by remember { mutableStateOf(false) }

    ListItem(
        leadingContent = { TrackThumbnail(track?.thumbnailUrl) },
        headlineContent = {
            Text(track?.title ?: entity.trackId, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            when (entity.state) {
                DownloadState.DOWNLOADING -> LinearProgressIndicator(
                    progress = { (item.livePct ?: 0) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                DownloadState.FAILED -> Text("Fehlgeschlagen", color = MaterialTheme.colorScheme.error)
                DownloadState.COMPLETED -> Text(
                    track?.artist ?: "Heruntergeladen",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DownloadState.QUEUED -> Text("Wartet...", fontStyle = FontStyle.Italic)
            }
        },
        trailingContent = if (entity.state == DownloadState.COMPLETED && track != null) {
            {
                Row {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                            onLikeClick()
                        },
                    ) {
                        Icon(
                            if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Gefällt mir",
                            tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Weitere Optionen")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Zu Playlist hinzufügen") },
                                leadingIcon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = null) },
                                onClick = { menuExpanded = false; onAddToPlaylistClick() },
                            )
                            DropdownMenuItem(
                                text = { Text("Zur Warteschlange hinzufügen") },
                                leadingIcon = { Icon(Icons.Filled.QueueMusic, contentDescription = null) },
                                onClick = { menuExpanded = false; onAddToQueueClick() },
                            )
                            DropdownMenuItem(
                                text = { Text("Zum Künstler") },
                                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                                onClick = { menuExpanded = false; onArtistClick() },
                            )
                        }
                    }
                }
            }
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entity.state == DownloadState.COMPLETED, onClick = onClick),
    )
}

@Composable
private fun LikesTab(uiState: LibraryUiState, onTrackPlayed: () -> Unit, viewModel: LibraryViewModel) {
    if (uiState.isLoadingLikes) {
        CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        return
    }
    if (uiState.likes.isEmpty()) {
        Text(
            "Noch keine Likes",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
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
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(start = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.QueueMusic,
                    contentDescription = "Zur Warteschlange hinzufügen",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) {
        var menuExpanded by remember { mutableStateOf(false) }
        ListItem(
            leadingContent = { TrackThumbnail(like.track.thumbnailUrl) },
            headlineContent = { Text(like.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(like.track.artist ?: like.track.source, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = {
                Row {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.ToggleOff)
                            onUnlikeClick()
                        },
                    ) {
                        Icon(Icons.Filled.FavoriteBorder, contentDescription = "Entfernen")
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Weitere Optionen")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Zu Playlist hinzufügen") },
                                leadingIcon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onAddToPlaylistClick()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Zur Warteschlange hinzufügen") },
                                leadingIcon = { Icon(Icons.Filled.QueueMusic, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onAddToQueueClick()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Herunterladen") },
                                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onDownloadClick()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Nicht mehr gefällt mir") },
                                leadingIcon = { Icon(Icons.Filled.FavoriteBorder, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onUnlikeClick()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Zum Künstler") },
                                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    like.track.artist?.let(onArtistClick)
                                },
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

@Composable
private fun PlaylistsTab(uiState: LibraryUiState, onPlaylistClick: (String) -> Unit) {
    if (uiState.isLoadingPlaylists) {
        CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        return
    }
    if (uiState.playlists.isEmpty()) {
        Text(
            "Noch keine Playlists",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(uiState.playlists, key = { it.id }) { playlist ->
            ListItem(
                headlineContent = { Text(playlist.name) },
                supportingContent = { Text("${playlist.trackCount} Titel") },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlaylistClick(playlist.id) },
            )
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
                label = { Text("Name") },
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
