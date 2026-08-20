package dev.schlubbe.musicagent.ui.playlist

import android.widget.Toast
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.ui.components.DrmLockIcon
import dev.schlubbe.musicagent.ui.components.NocturneButton
import dev.schlubbe.musicagent.ui.components.NocturneButtonVariant
import dev.schlubbe.musicagent.ui.components.NocturneIconButton
import dev.schlubbe.musicagent.ui.components.NocturneTag
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Nocturne
import dev.schlubbe.musicagent.ui.util.shareText

/** A public SoundCloud/YouTube playlist or album, reached by tapping a search
 * result - unlike [PlaylistDetailScreen] (the user's own Room-backed playlists),
 * this always re-fetches its track list live and its only local state is the
 * "Speichern" bookmark (see SavedPlaylistRepository). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemotePlaylistDetailScreen(
    onTrackSelected: () -> Unit,
    onNavigateBack: () -> Unit,
    onArtistSelected: (source: String, sourceId: String) -> Unit,
    viewModel: RemotePlaylistDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val detail = uiState.detail
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
    LaunchedEffect(uiState.downloadMessage) {
        uiState.downloadMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.onDownloadMessageShown()
        }
    }

    Scaffold(containerColor = Nocturne.bg) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                NocturneIconButton(icon = phosphorIcon("caret-left"), onClick = onNavigateBack, iconSize = 20.dp)
                if (detail != null) {
                    Box {
                        NocturneIconButton(icon = phosphorIcon("dots-three"), onClick = { showTopMenu = true }, iconSize = 20.dp)
                        DropdownMenu(expanded = showTopMenu, onDismissRequest = { showTopMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Herunterladen") },
                                leadingIcon = { Icon(phosphorIcon("download-simple"), contentDescription = null, tint = Nocturne.accent) },
                                onClick = {
                                    showTopMenu = false
                                    haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                    viewModel.onDownloadAllClicked()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Teilen") },
                                leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Nocturne.accent) },
                                onClick = {
                                    showTopMenu = false
                                    context.shareText(detail.webpageUrl)
                                },
                            )
                        }
                    }
                }
            }

            when {
                uiState.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp),
                    color = Nocturne.accent,
                )
                uiState.error != null -> Text(
                    "Fehler: ${uiState.error}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                detail == null -> Text("Playlist nicht gefunden", modifier = Modifier.padding(16.dp))
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TrackThumbnail(detail.thumbnailUrl, size = 80.dp)
                            Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                                Text(detail.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                                    NocturneTag(if (detail.source == "soundcloud") "SoundCloud" else "YT Music")
                                    val subtitle = listOfNotNull(detail.owner, detail.trackCount?.let { "$it Titel" })
                                        .joinToString(" · ")
                                    if (subtitle.isNotBlank()) {
                                        Text(
                                            subtitle,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Nocturne.neutral500,
                                            modifier = Modifier.padding(start = 8.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NocturneButton(
                                text = "Alle abspielen",
                                onClick = viewModel::playAll,
                                leadingIcon = phosphorIcon("play"),
                                variant = NocturneButtonVariant.Primary,
                                enabled = detail.tracks.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            NocturneIconButton(
                                icon = phosphorIcon("heart", filled = uiState.isSaved),
                                onClick = {
                                    haptic.performHapticFeedback(
                                        if (uiState.isSaved) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                                    )
                                    viewModel.toggleSaved()
                                },
                                variant = if (uiState.isSaved) NocturneButtonVariant.Secondary else NocturneButtonVariant.Primary,
                                size = 44.dp,
                                iconSize = 19.dp,
                            )
                        }
                    }
                    if (detail.tracks.isEmpty()) {
                        item {
                            Text(
                                "Keine Titel gefunden",
                                color = Nocturne.neutral500,
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                    } else {
                        items(detail.tracks, key = { "${it.source}:${it.sourceId}" }) { track ->
                            RemotePlaylistTrackRow(
                                track = track,
                                isLiked = "${track.source}:${track.sourceId}" in uiState.likedTrackIds,
                                onClick = {
                                    viewModel.playTrack(track)
                                    onTrackSelected()
                                },
                                onLikeClick = { viewModel.onLikeToggled(track) },
                                onAddToPlaylistClick = { viewModel.onAddToPlaylistClicked(track) },
                                onAddToQueueClick = { viewModel.onAddToQueueClicked(track) },
                                onDownloadClick = { viewModel.onDownloadClicked(track) },
                                onArtistClick = { viewModel.onTrackArtistClicked(track) },
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(20.dp)) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemotePlaylistTrackRow(
    track: TrackResultDto,
    isLiked: Boolean,
    onClick: () -> Unit,
    onLikeClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onArtistClick: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
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
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}
