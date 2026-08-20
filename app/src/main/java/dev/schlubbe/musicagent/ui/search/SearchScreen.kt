package dev.schlubbe.musicagent.ui.search

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.remote.dto.AlbumResultDto
import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.ui.util.shareText
import dev.schlubbe.musicagent.ui.components.DrmLockIcon
import dev.schlubbe.musicagent.ui.components.NocturneIconButton
import dev.schlubbe.musicagent.ui.components.SegmentedControl
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.playlist.AddToPlaylistDialog
import dev.schlubbe.musicagent.ui.theme.Nocturne

private val SOURCES = listOf("all" to "Alle", "ytmusic" to "YT Music", "soundcloud" to "SoundCloud")
private val RESULT_TYPES = listOf(
    "tracks" to "Titel",
    "artists" to "Künstler",
    "playlists" to "Playlists",
    "albums" to "Alben",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onTrackSelected: () -> Unit,
    onArtistSelected: (source: String, sourceId: String) -> Unit,
    onPlaylistSelected: (source: String, sourceId: String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
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
    LaunchedEffect(uiState.remotePlaylistNavTarget) {
        uiState.remotePlaylistNavTarget?.let { (source, sourceId) ->
            onPlaylistSelected(source, sourceId)
            viewModel.onRemotePlaylistNavigated()
        }
    }

    Scaffold(containerColor = Nocturne.bg) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Suche",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp),
            )

            TextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChanged,
                placeholder = { Text("Titel, Artist, ...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(
                    onSearch = { viewModel.runSearch() },
                ),
                shape = RoundedCornerShape(8.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Nocturne.surface,
                    unfocusedContainerColor = Nocturne.surface,
                    focusedIndicatorColor = Nocturne.divider,
                    unfocusedIndicatorColor = Nocturne.divider,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )

            SegmentedControl(
                options = SOURCES,
                selected = SOURCES.first { it.first == uiState.source },
                onSelect = { viewModel.onSourceChanged(it.first) },
                label = { it.second },
                fillWidth = true,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            if (uiState.query.isNotBlank()) {
                SegmentedControl(
                    options = RESULT_TYPES,
                    selected = RESULT_TYPES.first { it.first == uiState.resultType },
                    onSelect = { viewModel.onResultTypeChanged(it.first) },
                    label = { it.second },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }

            when {
                uiState.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Nocturne.accent)
                }
                uiState.error != null -> CenteredHint("Fehler: ${uiState.error}", isError = true)
                uiState.query.isBlank() -> CenteredHint("Titel, Künstler oder Playlist suchen")
                uiState.resultType == "artists" -> if (uiState.artistResults.isEmpty()) {
                    CenteredHint("Keine Treffer")
                } else {
                    LazyColumn {
                        items(uiState.artistResults, key = { "${it.source}:${it.sourceId}" }) { artist ->
                            ArtistRow(
                                artist = artist,
                                onClick = { viewModel.onArtistResultClicked(artist) },
                            )
                        }
                    }
                }
                uiState.resultType == "playlists" -> if (uiState.playlistResults.isEmpty()) {
                    CenteredHint("Keine Treffer")
                } else {
                    LazyColumn {
                        items(uiState.playlistResults, key = { "${it.source}:${it.sourceId}" }) { playlist ->
                            PlaylistResultRow(
                                playlist = playlist,
                                onClick = { viewModel.onPlaylistResultClicked(playlist.source, playlist.sourceId) },
                            )
                        }
                    }
                }
                uiState.resultType == "albums" -> if (uiState.albumResults.isEmpty()) {
                    CenteredHint("Keine Treffer")
                } else {
                    LazyColumn {
                        items(uiState.albumResults, key = { "${it.source}:${it.sourceId}" }) { album ->
                            AlbumResultRow(
                                album = album,
                                onClick = { viewModel.onAlbumResultClicked(album.source, album.sourceId) },
                            )
                        }
                    }
                }
                uiState.results.isEmpty() -> CenteredHint("Keine Treffer")
                else -> LazyColumn {
                    items(uiState.results) { track ->
                        TrackRow(
                            track = track,
                            isLiked = "${track.source}:${track.sourceId}" in uiState.likedTrackIds,
                            onClick = {
                                viewModel.onTrackClicked(track)
                                onTrackSelected()
                            },
                            onDownloadClick = { viewModel.onDownloadClicked(track) },
                            onLikeClick = { viewModel.onLikeToggled(track) },
                            onAddToPlaylistClick = { viewModel.onAddToPlaylistClicked(track) },
                            onAddToQueueClick = { viewModel.onAddToQueueClicked(track) },
                            onArtistClick = { viewModel.onTrackArtistClicked(track) },
                        )
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

/** Plain centered text for empty/no-results/prompt states -- per the design's own
 * "no illustration" note, this is deliberately just a text label, not a graphic. */
@Composable
private fun CenteredHint(text: String, isError: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = if (isError) MaterialTheme.colorScheme.error else Nocturne.neutral500,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Wraps [content] with a start-to-end swipe gesture that adds the row's track to the
 * playback queue without disturbing playback, then snaps back (this isn't a dismissal). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToQueueRow(
    onSwipeToQueue: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onSwipeToQueue()
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
                Icon(
                    phosphorIcon("list-plus"),
                    contentDescription = "Zur Warteschlange hinzufügen",
                    tint = Nocturne.accent100,
                )
            }
        },
    ) {
        content()
    }
}

@Composable
private fun TrackRow(
    track: TrackResultDto,
    isLiked: Boolean,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onLikeClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onArtistClick: (String) -> Unit,
) {
    SwipeToQueueRow(onSwipeToQueue = onAddToQueueClick) {
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
                TrackActions(
                    isLiked = isLiked,
                    artist = track.artist,
                    webpageUrl = track.webpageUrl,
                    onLikeClick = onLikeClick,
                    onAddToPlaylistClick = onAddToPlaylistClick,
                    onAddToQueueClick = onAddToQueueClick,
                    onDownloadClick = onDownloadClick,
                    onArtistClick = onArtistClick,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        )
    }
}

@Composable
private fun ShareIconButton(webpageUrl: String) {
    val context = LocalContext.current
    NocturneIconButton(
        icon = phosphorIcon("share-network"),
        onClick = { context.shareText(webpageUrl) },
    )
}

@Composable
private fun ArtistRow(
    artist: ArtistResultDto,
    onClick: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Nocturne.bg),
        leadingContent = { TrackThumbnail(artist.thumbnailUrl) },
        headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = artist.subscriberCount?.let { count ->
            { Text("$count Abonnenten", maxLines = 1, overflow = TextOverflow.Ellipsis, color = Nocturne.neutral500) }
        },
        trailingContent = { ShareIconButton(artist.webpageUrl) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@Composable
private fun PlaylistResultRow(playlist: PlaylistResultDto, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Nocturne.bg),
        leadingContent = { TrackThumbnail(playlist.thumbnailUrl) },
        headlineContent = { Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val subtitle = listOfNotNull(playlist.owner, playlist.trackCount?.let { "$it Titel" })
                .joinToString(" · ")
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Nocturne.neutral500)
        },
        trailingContent = { ShareIconButton(playlist.webpageUrl) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
private fun AlbumResultRow(album: AlbumResultDto, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Nocturne.bg),
        leadingContent = { TrackThumbnail(album.thumbnailUrl) },
        headlineContent = { Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(album.artist ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, color = Nocturne.neutral500)
        },
        trailingContent = { ShareIconButton(album.webpageUrl) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

/** Inline like toggle plus an overflow menu with the rest of the per-track actions,
 * kept in every track row across the app (search, feed, likes, playlist detail). */
@Composable
private fun TrackActions(
    isLiked: Boolean,
    artist: String?,
    webpageUrl: String,
    onLikeClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onArtistClick: (String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    Row {
        NocturneIconButton(
            icon = phosphorIcon("heart", filled = isLiked),
            onClick = {
                haptic.performHapticFeedback(if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                onLikeClick()
            },
        )
        Box {
            NocturneIconButton(
                icon = phosphorIcon("dots-three"),
                onClick = { menuExpanded = true },
            )
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Zu Playlist hinzufügen") },
                    leadingIcon = { Icon(phosphorIcon("plus-circle"), contentDescription = null, tint = Nocturne.accent) },
                    onClick = {
                        menuExpanded = false
                        onAddToPlaylistClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Zur Warteschlange hinzufügen") },
                    leadingIcon = { Icon(phosphorIcon("list-plus"), contentDescription = null, tint = Nocturne.accent) },
                    onClick = {
                        menuExpanded = false
                        onAddToQueueClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Herunterladen") },
                    leadingIcon = { Icon(phosphorIcon("download-simple"), contentDescription = null, tint = Nocturne.accent) },
                    onClick = {
                        menuExpanded = false
                        onDownloadClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (isLiked) "Nicht mehr gefällt mir" else "Gefällt mir") },
                    leadingIcon = {
                        Icon(phosphorIcon("heart", filled = isLiked), contentDescription = null, tint = Nocturne.accent)
                    },
                    onClick = {
                        menuExpanded = false
                        onLikeClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Zum Künstler") },
                    leadingIcon = { Icon(phosphorIcon("user-circle"), contentDescription = null, tint = Nocturne.accent) },
                    onClick = {
                        menuExpanded = false
                        artist?.let(onArtistClick)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Teilen") },
                    leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Nocturne.accent) },
                    onClick = {
                        menuExpanded = false
                        context.shareText(webpageUrl)
                    },
                )
            }
        }
    }
}
