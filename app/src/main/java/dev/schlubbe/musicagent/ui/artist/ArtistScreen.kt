package dev.schlubbe.musicagent.ui.artist

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.playlist.AddToPlaylistDialog

private const val SHELF_PREVIEW_COUNT = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    source: String,
    sourceId: String,
    onTrackSelected: () -> Unit,
    onFollowersSelected: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAllTop by remember { mutableStateOf(false) }
    var showAllLatest by remember { mutableStateOf(false) }

    LaunchedEffect(source, sourceId) {
        viewModel.load(source, sourceId)
    }
    LaunchedEffect(uiState.navigateToFollowers) {
        if (uiState.navigateToFollowers) {
            onFollowersSelected()
            viewModel.onFollowersNavigated()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.artist?.name ?: "Künstler") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                uiState.error != null -> Text(
                    "Fehler: ${uiState.error}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                uiState.artist != null -> {
                    val artist = uiState.artist!!
                    val topPreview = artist.topTracks.take(if (showAllTop) artist.topTracks.size else SHELF_PREVIEW_COUNT)
                    val latestPreview = artist.latestTracks.take(
                        if (showAllLatest) artist.latestTracks.size else SHELF_PREVIEW_COUNT,
                    )

                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (artist.bannerUrl != null) {
                            item {
                                AsyncImage(
                                    model = artist.bannerUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f),
                                )
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TrackThumbnail(artist.thumbnailUrl, size = 96.dp)
                                Column(modifier = Modifier.padding(start = 16.dp)) {
                                    Text(artist.name, style = MaterialTheme.typography.titleLarge)
                                }
                            }

                            artist.description?.takeIf { it.isNotBlank() }?.let { description ->
                                ArtistBio(description)
                            }

                            artist.subscriberCount?.let { count ->
                                val followersClickable = artist.source == "soundcloud"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = followersClickable) {
                                            viewModel.onFollowersClicked()
                                        }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "$count Follower",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (followersClickable) {
                                        Icon(
                                            Icons.Filled.ChevronRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }

                            if (artist.topTracks.isNotEmpty()) {
                                ShelfHeader(
                                    title = "Top Songs",
                                    expanded = showAllTop,
                                    total = artist.topTracks.size,
                                    onToggle = { showAllTop = !showAllTop },
                                )
                            }
                        }
                        items(topPreview, key = { "top:${it.source}:${it.sourceId}" }) { track ->
                            ArtistTrackRow(
                                track = track,
                                isLiked = "${track.source}:${track.sourceId}" in uiState.likedTrackIds,
                                onClick = {
                                    viewModel.onTrackClicked(track, artist.topTracks)
                                    onTrackSelected()
                                },
                                onLikeClick = { viewModel.onLikeToggled(track) },
                                onAddToPlaylistClick = { viewModel.onAddToPlaylistClicked(track) },
                                onAddToQueueClick = { viewModel.onAddToQueueClicked(track) },
                                onDownloadClick = { viewModel.onDownloadClicked(track) },
                            )
                        }

                        if (artist.latestTracks.isNotEmpty()) {
                            item {
                                ShelfHeader(
                                    title = "Neueste Songs",
                                    expanded = showAllLatest,
                                    total = artist.latestTracks.size,
                                    onToggle = { showAllLatest = !showAllLatest },
                                )
                            }
                        }
                        items(latestPreview, key = { "latest:${it.source}:${it.sourceId}" }) { track ->
                            ArtistTrackRow(
                                track = track,
                                isLiked = "${track.source}:${track.sourceId}" in uiState.likedTrackIds,
                                onClick = {
                                    viewModel.onTrackClicked(track, artist.latestTracks)
                                    onTrackSelected()
                                },
                                onLikeClick = { viewModel.onLikeToggled(track) },
                                onAddToPlaylistClick = { viewModel.onAddToPlaylistClicked(track) },
                                onAddToQueueClick = { viewModel.onAddToQueueClicked(track) },
                                onDownloadClick = { viewModel.onDownloadClicked(track) },
                            )
                        }
                    }
                }
            }
        }
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

private const val BIO_COLLAPSED_LINES = 3

/** Collapsed to [BIO_COLLAPSED_LINES] lines with a "Mehr anzeigen" toggle - shown
 * only if the bio actually overflows at that height (detected via onTextLayout's
 * hasVisualOverflow, not just "is the text long"), so a short bio never grows a
 * pointless toggle it wouldn't need. */
@Composable
private fun ArtistBio(description: String) {
    var expanded by remember { mutableStateOf(false) }
    var isOverflowing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else BIO_COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (!expanded) isOverflowing = result.hasVisualOverflow
            },
        )
        if (isOverflowing || expanded) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(if (expanded) "Weniger anzeigen" else "Mehr anzeigen")
            }
        }
    }
}

@Composable
private fun ShelfHeader(title: String, expanded: Boolean, total: Int, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp, 8.dp, 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (total > SHELF_PREVIEW_COUNT) {
            TextButton(onClick = onToggle) {
                Text(if (expanded) "Weniger anzeigen" else "Alle anzeigen")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistTrackRow(
    track: TrackResultDto,
    isLiked: Boolean,
    onClick: () -> Unit,
    onLikeClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onDownloadClick: () -> Unit,
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
            leadingContent = { TrackThumbnail(track.thumbnailUrl) },
            headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(track.artist ?: track.source, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = {
                Row {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(
                                if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                            )
                            onLikeClick()
                        },
                    ) {
                        Icon(
                            if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Gefällt mir",
                            tint = if (isLiked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
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
