package dev.schlubbe.musicagent.ui.artist

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.ui.components.DrmLockIcon
import dev.schlubbe.musicagent.ui.components.NocturneButton
import dev.schlubbe.musicagent.ui.components.NocturneButtonVariant
import dev.schlubbe.musicagent.ui.components.NocturneIconButton
import dev.schlubbe.musicagent.ui.components.NocturneTag
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.playlist.AddToPlaylistDialog
import dev.schlubbe.musicagent.ui.theme.Nocturne
import dev.schlubbe.musicagent.ui.util.shareText

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
    // Keyed on the artist identity, not just remembered bare -- otherwise
    // navigating from one artist to another while this screen's composable
    // instance is reused (same route pattern) would leave a stale expanded/
    // collapsed state from the PREVIOUS artist's shelf sizes hanging around.
    // This is exactly the kind of state-hoisting bug that can look like "show
    // more stopped working" if it flips to the wrong artist mid-navigation.
    var showAllTop by remember(source, sourceId) { mutableStateOf(false) }
    var showAllLatest by remember(source, sourceId) { mutableStateOf(false) }
    var showTopMenu by remember(source, sourceId) { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(source, sourceId) {
        viewModel.load(source, sourceId)
    }
    LaunchedEffect(uiState.navigateToFollowers) {
        if (uiState.navigateToFollowers) {
            onFollowersSelected()
            viewModel.onFollowersNavigated()
        }
    }

    Scaffold(containerColor = Nocturne.bg) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                NocturneIconButton(icon = phosphorIcon("caret-left"), onClick = onNavigateBack, iconSize = 20.dp)
                uiState.artist?.let { artist ->
                    Box {
                        NocturneIconButton(icon = phosphorIcon("dots-three"), onClick = { showTopMenu = true }, iconSize = 20.dp)
                        DropdownMenu(expanded = showTopMenu, onDismissRequest = { showTopMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Teilen") },
                                leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Nocturne.accent) },
                                onClick = {
                                    showTopMenu = false
                                    context.shareText(artist.webpageUrl)
                                },
                            )
                        }
                    }
                }
            }
            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp), color = Nocturne.accent)
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
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TrackThumbnail(artist.thumbnailUrl, size = 72.dp)
                                Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                                    Text(artist.name, style = MaterialTheme.typography.headlineSmall)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 5.dp),
                                    ) {
                                        NocturneTag(if (artist.source == "soundcloud") "SoundCloud" else "YT Music")
                                        artist.subscriberCount?.let { count ->
                                            val followersClickable = artist.source == "soundcloud"
                                            Row(
                                                modifier = Modifier
                                                    .padding(start = 8.dp)
                                                    .clickable(enabled = followersClickable) {
                                                        viewModel.onFollowersClicked()
                                                    },
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    "$count Follower",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = Nocturne.neutral500,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Row(modifier = Modifier.padding(horizontal = 20.dp)) {
                                NocturneButton(
                                    text = if (uiState.isFollowing) "Gefolgt" else "Folgen",
                                    onClick = { viewModel.toggleFollow() },
                                    variant = if (uiState.isFollowing) NocturneButtonVariant.Secondary else NocturneButtonVariant.Primary,
                                    block = true,
                                )
                            }

                            artist.description?.takeIf { it.isNotBlank() }?.let { description ->
                                ArtistBio(description)
                            }

                            if (artist.topTracks.isNotEmpty()) {
                                ShelfHeader(
                                    title = "Top Titel",
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
                                    title = "Neueste Titel",
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

    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = Nocturne.text.copy(alpha = 0.85f),
            maxLines = if (expanded) Int.MAX_VALUE else BIO_COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (!expanded && result.hasVisualOverflow) isOverflowing = true
            },
        )
        if (isOverflowing || expanded) {
            Text(
                if (expanded) "Weniger anzeigen" else "Mehr anzeigen",
                color = Nocturne.accent,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun ShelfHeader(title: String, expanded: Boolean, total: Int, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        if (total > SHELF_PREVIEW_COUNT) {
            Text(
                if (expanded) "Weniger anzeigen" else "Alle anzeigen",
                color = Nocturne.accent,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(onClick = onToggle),
            )
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
            leadingContent = { TrackThumbnail(track.thumbnailUrl) },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (track.isDrmProtected) DrmLockIcon()
                }
            },
            supportingContent = {
                Text(track.artist ?: track.source, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Nocturne.neutral500)
            },
            trailingContent = {
                Row {
                    NocturneIconButton(
                        icon = phosphorIcon("heart", filled = isLiked),
                        onClick = {
                            haptic.performHapticFeedback(
                                if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                            )
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
                                text = { Text("Teilen") },
                                leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Nocturne.accent) },
                                onClick = { menuExpanded = false; context.shareText(track.webpageUrl) },
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
