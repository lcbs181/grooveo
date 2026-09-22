package dev.schlubbe.musicagent.ui.search

import android.widget.Toast
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.util.shareText
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.ui.components.CanopyAvatar
import dev.schlubbe.musicagent.ui.components.CanopyChip
import dev.schlubbe.musicagent.ui.components.CanopySectionHeader
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.playlist.AddToPlaylistDialog
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyPillShape
import dev.schlubbe.musicagent.ui.theme.CanopyShapes

// Canopy Search, from GrooveoApp.dc.html's `isSearch` block: a pill search
// field, source chips, underline tabs, then result rows each carrying a state
// pill (coral for anything blocking, neutral otherwise).
//
// The design shows three tabs; this keeps the app's existing four, since
// "Alben" is a working result type and the mockup omitting it isn't a reason to
// delete a feature. The tab strip scrolls, so the extra one costs nothing.
private val SOURCES = listOf(
    "all" to "Alle Quellen",
    "soundcloud" to "SoundCloud",
    "ytmusic" to "YouTube Music",
)

private val RESULT_TABS = listOf(
    "tracks" to "Titel",
    "artists" to "Künstler",
    "playlists" to "Playlists",
    "albums" to "Alben",
)

private const val CONTENT_BOTTOM_PADDING = 150

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
    LaunchedEffect(uiState.remotePlaylistNavTarget) {
        uiState.remotePlaylistNavTarget?.let { (source, sourceId) ->
            onPlaylistSelected(source, sourceId)
            viewModel.onRemotePlaylistNavigated()
        }
    }
    LaunchedEffect(uiState.artistLookupError) {
        uiState.artistLookupError?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.onArtistLookupErrorShown()
        }
    }

    if (uiState.trackPendingPlaylistAdd != null) {
        AddToPlaylistDialog(
            playlists = uiState.playlists,
            onDismiss = viewModel::dismissAddToPlaylist,
            onPlaylistPicked = viewModel::onPlaylistPicked,
            onCreatePlaylist = viewModel::onCreatePlaylistAndAdd,
        )
    }

    Scaffold(containerColor = Canopy.bg, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SearchHeader(
                query = uiState.query,
                source = uiState.source,
                resultType = uiState.resultType,
                onQueryChanged = viewModel::onQueryChanged,
                onSubmit = viewModel::runSearch,
                onSourceChanged = viewModel::onSourceChanged,
                onResultTypeChanged = viewModel::onResultTypeChanged,
            )

            when {
                uiState.isLoading -> CenteredMessage { CircularProgressIndicator(color = Canopy.accent) }
                uiState.error != null -> CenteredMessage {
                    Text(
                        uiState.error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Canopy.accent2,
                    )
                }
                else -> ResultsList(
                    uiState = uiState,
                    onTrackClick = {
                        viewModel.onTrackClicked(it)
                        onTrackSelected()
                    },
                    onArtistClick = viewModel::onArtistResultClicked,
                    onPlaylistClick = viewModel::onPlaylistResultClicked,
                    onHistoryTapped = viewModel::onHistoryQueryTapped,
                    onHistoryDeleted = viewModel::onHistoryQueryDeleted,
                    trackActions = TrackActions(
                        onLikeToggle = viewModel::onLikeToggled,
                        onDownload = { track ->
                            viewModel.onDownloadClicked(track)
                            Toast.makeText(context, "„${track.title}“ wird heruntergeladen", Toast.LENGTH_SHORT).show()
                        },
                        onAddToQueue = { track ->
                            viewModel.onAddToQueueClicked(track)
                            Toast.makeText(context, "Als Nächstes eingereiht", Toast.LENGTH_SHORT).show()
                        },
                        onAddToPlaylist = viewModel::onAddToPlaylistClicked,
                        onArtist = viewModel::onTrackArtistClicked,
                        onShare = { track -> context.shareText(track.webpageUrl) },
                    ),
                )
            }
        }
    }
}

@Composable
private fun SearchHeader(
    query: String,
    source: String,
    resultType: String,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onSourceChanged: (String) -> Unit,
    onResultTypeChanged: (String) -> Unit,
) {
    Column(modifier = Modifier.background(Canopy.bg).padding(top = 14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(CanopyPillShape)
                .background(Canopy.surface)
                .border(1.dp, Canopy.divider, CanopyPillShape)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                phosphorIcon("magnifying-glass"),
                contentDescription = null,
                tint = Canopy.neutral500,
                modifier = Modifier.size(18.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Canopy.text),
                    cursorBrush = SolidColor(Canopy.accent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { onSubmit() },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (query.isEmpty()) {
                    Text(
                        "Titel, Künstler, Playlists",
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
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onQueryChanged("") },
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            items(SOURCES, key = { it.first }) { (key, label) ->
                CanopyChip(
                    label = label,
                    active = source == key,
                    onClick = { onSourceChanged(key) },
                )
            }
        }

        // Underline tabs: active gets full-strength text plus a 2dp accent rule,
        // inactive is neutral-500 with no rule.
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(RESULT_TABS, key = { it.first }) { (key, label) ->
                val selected = resultType == key
                Column(
                    modifier = Modifier.clickable { onResultTypeChanged(key) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = if (selected) Canopy.text else Canopy.neutral500,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (selected) Canopy.accent else androidx.compose.ui.graphics.Color.Transparent),
                    )
                }
            }
        }
        HorizontalDivider(color = Canopy.divider)
    }
}

@Composable
private fun ResultsList(
    uiState: SearchUiState,
    onTrackClick: (TrackResultDto) -> Unit,
    onArtistClick: (ArtistResultDto) -> Unit,
    onPlaylistClick: (source: String, sourceId: String) -> Unit,
    onHistoryTapped: (String) -> Unit,
    onHistoryDeleted: (String) -> Unit,
    trackActions: TrackActions,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = CONTENT_BOTTOM_PADDING.dp),
    ) {
        when (uiState.resultType) {
            "artists" -> items(uiState.artistResults, key = { it.source + it.sourceId }) { artist ->
                ResultRow(
                    title = artist.name,
                    subtitle = listOfNotNull(
                        "Künstler",
                        sourceLabel(artist.source),
                        artist.subscriberCount?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    thumbnailUrl = artist.thumbnailUrl,
                    seed = artist.name,
                    circular = true,
                    onClick = { onArtistClick(artist) },
                )
            }
            "playlists" -> items(uiState.playlistResults, key = { it.source + it.sourceId }) { playlist ->
                ResultRow(
                    title = playlist.title,
                    subtitle = "Playlist · ${sourceLabel(playlist.source)}",
                    thumbnailUrl = playlist.thumbnailUrl,
                    seed = playlist.title,
                    onClick = { onPlaylistClick(playlist.source, playlist.sourceId) },
                )
            }
            "albums" -> items(uiState.albumResults, key = { it.source + it.sourceId }) { album ->
                ResultRow(
                    title = album.title,
                    subtitle = listOfNotNull(album.artist, "Album", sourceLabel(album.source)).joinToString(" · "),
                    thumbnailUrl = album.thumbnailUrl,
                    seed = album.title,
                    // Albums open through the same remote-playlist browse screen
                    // (an album *is* a playlist with is_album=true upstream).
                    onClick = { onPlaylistClick(album.source, album.sourceId) },
                )
            }
            else -> items(uiState.results, key = { it.source + it.sourceId }) { track ->
                val key = "${track.source}:${track.sourceId}"
                val download = uiState.downloadStates[key]
                var menuExpanded by remember { mutableStateOf(false) }
                ResultRow(
                    title = track.title,
                    subtitle = listOfNotNull(
                        track.artist,
                        sourceLabel(track.source),
                        track.durationSec?.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60, it % 60) },
                    ).joinToString(" · "),
                    thumbnailUrl = track.thumbnailUrl,
                    seed = track.title,
                    state = stateFor(track, download),
                    onClick = { onTrackClick(track) },
                    onLongClick = { menuExpanded = true },
                    trailing = {
                        Box {
                            CanopyIconButton(
                                icon = phosphorIcon("dots-three-vertical"),
                                onClick = { menuExpanded = true },
                                size = 36.dp,
                                contentDescription = "Weitere Optionen",
                            )
                            TrackActionsMenu(
                                expanded = menuExpanded,
                                onDismiss = { menuExpanded = false },
                                track = track,
                                isLiked = key in uiState.likedTrackIds,
                                download = download,
                                actions = trackActions,
                            )
                        }
                    },
                )
            }
        }

        if (uiState.searchHistory.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(top = 18.dp)) {
                    CanopySectionHeader(title = "Zuletzt gesucht")
                    // Wraps rather than scrolling, matching the design's flex-wrap.
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        uiState.searchHistory.forEach { q ->
                            HistorySearchChip(
                                label = q,
                                onClick = { onHistoryTapped(q) },
                                onDelete = { onHistoryDeleted(q) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A "Zuletzt gesucht" chip: taps re-run that query, and a long-press surfaces a
 * one-item "Löschen" menu (a plain [DropdownMenu] anchored to the chip, opened via
 * `combinedClickable`'s `onLongClick` - the same trigger Android's own launcher/
 * settings use for "remove this" on a small tappable chip, where there's no room
 * for a persistent delete affordance and a swipe gesture would collide with the
 * row's horizontal wrap). Not built on [CanopyChip] itself, since that component
 * is shared by plenty of other chips (mood/genre filters) that have no delete
 * concept at all - this is deliberately its own composable rather than a new
 * optional parameter on a shared one. */
@Composable
private fun HistorySearchChip(
    label: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .clip(CanopyPillShape)
                .background(Canopy.surface)
                .border(1.dp, Canopy.divider, CanopyPillShape)
                .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true })
                .padding(horizontal = 18.dp, vertical = 9.dp),
        ) {
            Text(
                text = label,
                color = Canopy.text,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Löschen") },
                leadingIcon = { Icon(phosphorIcon("trash"), contentDescription = null, tint = Canopy.accent2) },
                onClick = {
                    menuExpanded = false
                    onDelete()
                },
            )
        }
    }
}

private class TrackActions(
    val onLikeToggle: (TrackResultDto) -> Unit,
    val onDownload: (TrackResultDto) -> Unit,
    val onAddToQueue: (TrackResultDto) -> Unit,
    val onAddToPlaylist: (TrackResultDto) -> Unit,
    val onArtist: (TrackResultDto) -> Unit,
    val onShare: (TrackResultDto) -> Unit,
)

@Composable
private fun TrackActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    track: TrackResultDto,
    isLiked: Boolean,
    download: DownloadState?,
    actions: TrackActions,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        @Composable
        fun item(label: String, icon: String, enabled: Boolean = true, filled: Boolean = false, action: () -> Unit) =
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Icon(phosphorIcon(icon, filled = filled), contentDescription = null, tint = Canopy.accent) },
                enabled = enabled,
                onClick = { onDismiss(); action() },
            )
        item(if (isLiked) "Nicht mehr gefällt mir" else "Gefällt mir", "heart", filled = isLiked) { actions.onLikeToggle(track) }
        item("Zur Warteschlange hinzufügen", "list-plus") { actions.onAddToQueue(track) }
        item("Zu Playlist hinzufügen", "plus-circle") { actions.onAddToPlaylist(track) }
        when {
            download == DownloadState.COMPLETED -> item("Offline verfügbar", "check-circle", enabled = false) {}
            download == DownloadState.DOWNLOADING || download == DownloadState.QUEUED ->
                item("Wird heruntergeladen …", "download-simple", enabled = false) {}
            else -> item("Herunterladen", "download-simple") { actions.onDownload(track) }
        }
        if (!track.artist.isNullOrBlank()) item("Zum Künstler", "user-circle") { actions.onArtist(track) }
        item("Teilen", "share-network") { actions.onShare(track) }
    }
}

/** One result row: 48dp cover, title/subtitle, the optional state pill and trailing action. */
@Composable
private fun ResultRow(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    seed: String,
    onClick: () -> Unit,
    circular: Boolean = false,
    state: ResultState? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CanopyShapes.small)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 6.dp, end = if (trailing != null) 0.dp else 6.dp, top = 9.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (circular) {
            if (thumbnailUrl != null) {
                TrackThumbnail(url = thumbnailUrl, size = 48.dp, seed = seed, modifier = Modifier.clip(CircleShape))
            } else {
                CanopyAvatar(initials = seed, size = 48.dp)
            }
        } else {
            TrackThumbnail(url = thumbnailUrl, size = 48.dp, seed = seed)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = Canopy.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Canopy.neutral500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        state?.let { StatePill(it) }
        trailing?.invoke()
    }
}

/** The per-result state pill. "Alert" states (DRM, in-flight download) take the
 * coral treatment; settled states are neutral. Plain streamable results get no
 * pill at all - "Stream" on every row carried no information. */
private data class ResultState(val label: String, val iconName: String, val alert: Boolean)

private fun stateFor(track: TrackResultDto, download: DownloadState?): ResultState? = when {
    // Not fully playable from SoundCloud (DRM or 30s preview) - playback and
    // downloads use the matching YouTube Music recording instead.
    track.isDrmProtected && download == null -> ResultState("via YouTube", "youtube-logo", alert = false)
    download == DownloadState.DOWNLOADING || download == DownloadState.QUEUED ->
        ResultState("Lädt", "download-simple", alert = true)
    download == DownloadState.FAILED -> ResultState("Fehler", "warning-circle", alert = true)
    download == DownloadState.COMPLETED -> ResultState("Offline", "check-circle", alert = false)
    else -> null
}

@Composable
private fun StatePill(state: ResultState) {
    val fg = if (state.alert) Canopy.accent2 else Canopy.neutral500
    val bg = if (state.alert) Canopy.accent2_100 else Canopy.neutral200
    Row(
        modifier = Modifier
            .clip(CanopyPillShape)
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            phosphorIcon(state.iconName),
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(12.dp),
        )
        Text(
            state.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (state.alert) FontWeight.Bold else FontWeight.Normal,
            ),
            color = fg,
        )
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun sourceLabel(source: String) = if (source == "soundcloud") "SoundCloud" else "YT Music"
