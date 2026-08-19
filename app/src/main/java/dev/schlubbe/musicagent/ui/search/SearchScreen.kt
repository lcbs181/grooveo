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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
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
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.playlist.AddToPlaylistDialog

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
    onSettingsClick: () -> Unit,
    onLibraryClick: () -> Unit,
    onArtistSelected: (source: String, sourceId: String) -> Unit,
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
    LaunchedEffect(uiState.playlistTracksOpened) {
        if (uiState.playlistTracksOpened) {
            onTrackSelected()
            viewModel.onPlaylistTracksOpenedHandled()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Suche") },
                actions = {
                    IconButton(onClick = onLibraryClick) {
                        Icon(Icons.Filled.LibraryMusic, contentDescription = "Bibliothek")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChanged,
                label = { Text("Titel, Artist, ...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(
                    onSearch = { viewModel.runSearch() },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SOURCES.forEach { (value, label) ->
                    FilterChip(
                        selected = uiState.source == value,
                        onClick = { viewModel.onSourceChanged(value) },
                        label = { Text(label) },
                    )
                }
            }

            if (uiState.query.isNotBlank()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RESULT_TYPES.forEach { (value, label) ->
                        FilterChip(
                            selected = uiState.resultType == value,
                            onClick = { viewModel.onResultTypeChanged(value) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            when {
                uiState.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp),
                )
                uiState.error != null -> Text(
                    "Fehler: ${uiState.error}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                uiState.query.isBlank() -> Text(
                    "Titel, Künstler oder Playlist suchen",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                uiState.resultType == "artists" -> LazyColumn {
                    items(uiState.artistResults, key = { "${it.source}:${it.sourceId}" }) { artist ->
                        ArtistRow(
                            artist = artist,
                            onClick = { viewModel.onArtistResultClicked(artist) },
                        )
                    }
                }
                uiState.resultType == "playlists" -> LazyColumn {
                    items(uiState.playlistResults, key = { "${it.source}:${it.sourceId}" }) { playlist ->
                        PlaylistResultRow(
                            playlist = playlist,
                            onClick = { viewModel.onPlaylistResultClicked(playlist.source, playlist.sourceId) },
                        )
                    }
                }
                uiState.resultType == "albums" -> LazyColumn {
                    items(uiState.albumResults, key = { "${it.source}:${it.sourceId}" }) { album ->
                        AlbumResultRow(
                            album = album,
                            onClick = { viewModel.onAlbumResultClicked(album.source, album.sourceId) },
                        )
                    }
                }
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
            leadingContent = { TrackThumbnail(track.thumbnailUrl) },
            headlineContent = {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(track.artist ?: track.source, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = {
                TrackActions(
                    isLiked = isLiked,
                    artist = track.artist,
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
private fun ArtistRow(
    artist: ArtistResultDto,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = { TrackThumbnail(artist.thumbnailUrl) },
        headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = artist.subscriberCount?.let { count ->
            { Text("$count Abonnenten", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@Composable
private fun PlaylistResultRow(playlist: PlaylistResultDto, onClick: () -> Unit) {
    ListItem(
        leadingContent = { TrackThumbnail(playlist.thumbnailUrl) },
        headlineContent = { Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val subtitle = listOfNotNull(playlist.owner, playlist.trackCount?.let { "$it Titel" })
                .joinToString(" · ")
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
private fun AlbumResultRow(album: AlbumResultDto, onClick: () -> Unit) {
    ListItem(
        leadingContent = { TrackThumbnail(album.thumbnailUrl) },
        headlineContent = { Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(album.artist ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

/** Inline like toggle plus an overflow menu with the rest of the per-track actions,
 * kept in every track row across the app (search, feed, likes, playlist detail). */
@Composable
private fun TrackActions(
    isLiked: Boolean,
    artist: String?,
    onLikeClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onArtistClick: (String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
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
                    text = { Text(if (isLiked) "Nicht mehr gefällt mir" else "Gefällt mir") },
                    leadingIcon = {
                        Icon(
                            if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onLikeClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Zum Künstler") },
                    leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        artist?.let(onArtistClick)
                    },
                )
            }
        }
    }
}
