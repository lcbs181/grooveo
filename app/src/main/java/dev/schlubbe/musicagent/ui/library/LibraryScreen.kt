package dev.schlubbe.musicagent.ui.library

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.local.entity.SavedPlaylistEntity
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.LikeOutDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import dev.schlubbe.musicagent.data.remote.dto.toTrackResultDto
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.CanopyChip
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.CanopySectionHeader
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.playlist.AddToPlaylistDialog
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyShapes
import dev.schlubbe.musicagent.ui.theme.accentColorFor
import dev.schlubbe.musicagent.ui.util.rememberResponsiveDimens
import dev.schlubbe.musicagent.ui.util.shareText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onDownloadPlayed: () -> Unit,
    onPlaylistClick: (String) -> Unit,
    onSavedPlaylistClick: (source: String, sourceId: String) -> Unit,
    onArtistSelected: (source: String, sourceId: String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val downloads by viewModel.downloads.collectAsState()
    val likedTrackIds by viewModel.likedTrackIds.collectAsState()
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsState()
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
    LaunchedEffect(uiState.downloadPlaylistMessage) {
        uiState.downloadPlaylistMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.onDownloadPlaylistMessageShown()
        }
    }

    // Downloads is the one remaining in-screen sub-view (Playlists/Likes/Verlauf
    // are now Bibliothek chips switched in place, see LibraryChip below) - it still
    // pushes its own back-header state, so the system back gesture/button needs to
    // return to the chip screen first instead of leaving the Library tab.
    BackHandler(enabled = uiState.selectedTab == LibraryTab.DOWNLOADS) {
        viewModel.backToHome()
    }

    Scaffold(containerColor = Canopy.bg) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (uiState.selectedTab) {
                LibraryTab.HOME, LibraryTab.LIKES, LibraryTab.PLAYLISTS -> LibraryHomeContent(
                    uiState = uiState,
                    recentlyPlayed = recentlyPlayed,
                    onOpenSettings = onOpenSettings,
                    onOpenDownloads = { viewModel.openSection(LibraryTab.DOWNLOADS) },
                    onDismissBanner = viewModel::dismissImportBanner,
                    onImportClick = {
                        Toast.makeText(context, "Bald verfügbar", Toast.LENGTH_SHORT).show()
                    },
                    onRefreshLikes = viewModel::refreshLikes,
                    onRefreshPlaylists = viewModel::refreshPlaylists,
                    onPlaylistClick = onPlaylistClick,
                    onSavedPlaylistClick = onSavedPlaylistClick,
                    onCreatePlaylistClick = { showCreatePlaylistDialog = true },
                    viewModel = viewModel,
                    onLikedTrackClick = { like ->
                        viewModel.playLikedTrack(like)
                        onDownloadPlayed()
                    },
                    onRecentlyPlayedClick = { track ->
                        viewModel.onRecentlyPlayedClicked(track)
                        onDownloadPlayed()
                    },
                    onRecentlyPlayedAddToPlaylist = { viewModel.onAddToPlaylistClicked(it.toTrackResultDto()) },
                    onRecentlyPlayedAddToQueue = { viewModel.onAddToQueueClicked(it.toTrackResultDto()) },
                    onRecentlyPlayedArtistClick = { viewModel.onTrackArtistClicked(it.toTrackResultDto()) },
                )
                LibraryTab.DOWNLOADS -> Column(modifier = Modifier.fillMaxSize()) {
                    LibrarySubViewHeader(title = "Downloads", onBack = viewModel::backToHome)
                    DownloadsTab(downloads, likedTrackIds, onDownloadPlayed, viewModel)
                }
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

/** The 4 chips under the "Bibliothek" headline (design 05): Playlists / Likes /
 * Verlauf / Künstler switch which content renders below, in place - unlike the
 * old landing menu's chevron rows, this never leaves [LibraryTab.HOME]. Only
 * Playlists and Likes have a real ViewModel-backed data source; Verlauf reuses
 * the same recently-played source Home's own shelf uses; Künstler has no
 * followed-artist source wired to this ViewModel at all, so it renders an
 * honest empty state instead of inventing content. */
private enum class LibraryChip(val label: String) {
    PLAYLISTS("Playlists"),
    LIKES("Likes"),
    VERLAUF("Verlauf"),
    KUENSTLER("Künstler"),
}

/** The unified Bibliothek screen: headline + chip row + whichever content the
 * active chip selects, followed by a persistent "Schnellzugriff" section and
 * the "Neue Playlist" button - matching the design's markup, where those two
 * blocks sit below the switchable content rather than being re-conditioned per
 * chip. Downloads has no chip of its own (it's already promoted to its own
 * top-level tab in NavGraph.kt), so its one remaining entry point from Library
 * lives in Schnellzugriff. */
@Composable
private fun LibraryHomeContent(
    uiState: LibraryUiState,
    recentlyPlayed: List<TrackEntity>,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onDismissBanner: () -> Unit,
    onImportClick: () -> Unit,
    onRefreshLikes: () -> Unit,
    onRefreshPlaylists: () -> Unit,
    onPlaylistClick: (String) -> Unit,
    onSavedPlaylistClick: (source: String, sourceId: String) -> Unit,
    onCreatePlaylistClick: () -> Unit,
    viewModel: LibraryViewModel,
    onLikedTrackClick: (LikeOutDto) -> Unit,
    onRecentlyPlayedClick: (TrackEntity) -> Unit,
    onRecentlyPlayedAddToPlaylist: (TrackEntity) -> Unit,
    onRecentlyPlayedAddToQueue: (TrackEntity) -> Unit,
    onRecentlyPlayedArtistClick: (TrackEntity) -> Unit,
) {
    val dimens = rememberResponsiveDimens()
    var selectedChip by remember { mutableStateOf(LibraryChip.PLAYLISTS) }

    // Mirrors the old openSection()'s "refresh on open" behavior, just keyed off
    // the in-place chip instead of a pushed sub-view.
    LaunchedEffect(selectedChip) {
        when (selectedChip) {
            LibraryChip.LIKES -> onRefreshLikes()
            LibraryChip.PLAYLISTS -> onRefreshPlaylists()
            LibraryChip.VERLAUF, LibraryChip.KUENSTLER -> Unit
        }
    }

    val playlistItems: List<LibraryPlaylistItem> =
        uiState.playlists.map { LibraryPlaylistItem.Local(it) } +
            uiState.savedPlaylists.map { LibraryPlaylistItem.Saved(it) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = dimens.horizontalPadding, end = dimens.horizontalPadding, top = 22.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Bibliothek", style = MaterialTheme.typography.headlineLarge)
                CanopyIconButton(
                    icon = phosphorIcon("gear-six"),
                    onClick = onOpenSettings,
                    shape = CircleShape,
                    variant = CanopyButtonVariant.Secondary,
                )
            }
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = dimens.horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                items(LibraryChip.entries.toList(), key = { it.name }) { chip ->
                    CanopyChip(label = chip.label, active = chip == selectedChip, onClick = { selectedChip = chip })
                }
            }
        }

        when (selectedChip) {
            LibraryChip.PLAYLISTS -> {
                if (!uiState.importBannerDismissed) {
                    item { ImportBanner(onDismiss = onDismissBanner, onImportClick = onImportClick) }
                }
                when {
                    uiState.isLoadingPlaylists -> item {
                        CircularProgressIndicator(modifier = Modifier.padding(dimens.horizontalPadding), color = Canopy.accent)
                    }
                    playlistItems.isEmpty() -> item {
                        Text("Noch keine Playlists", color = Canopy.neutral500, modifier = Modifier.padding(dimens.horizontalPadding))
                    }
                    else -> items(playlistItems.chunked(2), key = { row -> row.joinToString("|") { it.key } }) { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = dimens.horizontalPadding)
                                .padding(bottom = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            row.forEach { item ->
                                Box(modifier = Modifier.weight(1f)) {
                                    PlaylistGridCard(
                                        item = item,
                                        onClick = {
                                            when (item) {
                                                is LibraryPlaylistItem.Local -> onPlaylistClick(item.playlist.id)
                                                is LibraryPlaylistItem.Saved -> onSavedPlaylistClick(item.playlist.source, item.playlist.sourceId)
                                            }
                                        },
                                    )
                                }
                            }
                            if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            LibraryChip.LIKES -> {
                when {
                    uiState.isLoadingLikes -> item {
                        CircularProgressIndicator(modifier = Modifier.padding(dimens.horizontalPadding), color = Canopy.accent)
                    }
                    uiState.likes.isEmpty() -> item {
                        Text("Noch keine Likes", color = Canopy.neutral500, modifier = Modifier.padding(dimens.horizontalPadding))
                    }
                    else -> items(uiState.likes, key = { "like:${it.track.id}" }) { like ->
                        LikeRow(
                            like = like,
                            onClick = { onLikedTrackClick(like) },
                            onUnlikeClick = { viewModel.unlike(like) },
                            onAddToPlaylistClick = { viewModel.onAddToPlaylistClicked(like.track.toTrackResultDto()) },
                            onAddToQueueClick = { viewModel.onAddToQueueClicked(like.track.toTrackResultDto()) },
                            onDownloadClick = { viewModel.onDownloadClicked(like.track.toTrackResultDto()) },
                            onArtistClick = { viewModel.onTrackArtistClicked(like.track.toTrackResultDto()) },
                        )
                    }
                }
            }
            LibraryChip.VERLAUF -> {
                if (recentlyPlayed.isEmpty()) {
                    item {
                        Text("Noch keine Wiedergabe", color = Canopy.neutral500, modifier = Modifier.padding(dimens.horizontalPadding))
                    }
                } else {
                    item {
                        Text(
                            "Zuletzt gespielt",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Canopy.neutral500,
                            modifier = Modifier.padding(start = dimens.horizontalPadding, end = dimens.horizontalPadding, bottom = 8.dp),
                        )
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = dimens.horizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(recentlyPlayed, key = { "rail:${it.id}" }) { track ->
                                RecentlyPlayedAvatar(track = track, onClick = { onRecentlyPlayedClick(track) })
                            }
                        }
                    }
                    item {
                        Text(
                            "Wiedergabeverlauf",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Canopy.neutral500,
                            modifier = Modifier.padding(start = dimens.horizontalPadding, end = dimens.horizontalPadding, top = 14.dp, bottom = 6.dp),
                        )
                    }
                    items(recentlyPlayed, key = { "history:${it.id}" }) { track ->
                        HistoryRow(
                            track = track,
                            onClick = { onRecentlyPlayedClick(track) },
                            onAddToPlaylistClick = { onRecentlyPlayedAddToPlaylist(track) },
                            onAddToQueueClick = { onRecentlyPlayedAddToQueue(track) },
                            onArtistClick = { onRecentlyPlayedArtistClick(track) },
                        )
                    }
                }
            }
            LibraryChip.KUENSTLER -> item {
                // No followed-artist source is wired to LibraryViewModel (no
                // repository/DAO exposes one) - an honest placeholder instead of
                // fabricating a list. See the screen's task report for detail.
                Text(
                    "Künstlerübersicht noch nicht verfügbar",
                    color = Canopy.neutral500,
                    modifier = Modifier.padding(dimens.horizontalPadding),
                )
            }
        }

        item {
            Column(modifier = Modifier.padding(top = 22.dp)) {
                CanopySectionHeader(
                    title = "Schnellzugriff",
                    modifier = Modifier.padding(horizontal = dimens.horizontalPadding),
                )
                LibraryChevronRow("Downloads", onClick = onOpenDownloads)
            }
        }
        item {
            CanopyButton(
                text = "Neue Playlist",
                onClick = onCreatePlaylistClick,
                variant = CanopyButtonVariant.Secondary,
                leadingIcon = phosphorIcon("plus"),
                block = true,
                modifier = Modifier.padding(horizontal = dimens.horizontalPadding).padding(top = 4.dp, bottom = 18.dp),
            )
        }
    }
}

@Composable
private fun ImportBanner(onDismiss: () -> Unit, onImportClick: () -> Unit) {
    val dimens = rememberResponsiveDimens()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.horizontalPadding)
            .padding(bottom = 18.dp)
            .clip(CanopyShapes.large)
            .background(Canopy.surface)
            .padding(16.dp),
    ) {
        CanopyIconButton(
            icon = phosphorIcon("x"),
            onClick = onDismiss,
            variant = CanopyButtonVariant.Ghost,
            size = 26.dp,
            iconSize = 14.dp,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        Column(modifier = Modifier.padding(end = 26.dp)) {
            Text(
                "Bringe deine Playlists mit",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                "Importiere deine Musik von Spotify in nur drei Schritten.",
                style = MaterialTheme.typography.labelMedium,
                color = Canopy.neutral500,
                modifier = Modifier.padding(top = 5.dp),
            )
            CanopyButton(
                text = "Jetzt importieren",
                onClick = onImportClick,
                variant = CanopyButtonVariant.Primary,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }
}

@Composable
private fun LibraryChevronRow(label: String, onClick: () -> Unit) {
    val dimens = rememberResponsiveDimens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.horizontalPadding, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                phosphorIcon("download-simple"),
                contentDescription = null,
                tint = Canopy.text,
                modifier = Modifier.size(18.dp),
            )
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
        }
        Icon(
            phosphorIcon("caret-right"),
            contentDescription = null,
            tint = Canopy.neutral500,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun RecentlyPlayedAvatar(track: TrackEntity, onClick: () -> Unit) {
    val dimens = rememberResponsiveDimens()
    Column(
        modifier = Modifier
            .width(dimens.resumeThumbnail + 6.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.clip(CircleShape)) {
            TrackThumbnail(track.thumbnailUrl, size = dimens.resumeThumbnail, seed = track.title)
        }
        Text(
            track.title,
            style = MaterialTheme.typography.labelSmall,
            color = Canopy.neutral500,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** A real, duration-aware playback-history row - unlike the circular rail above,
 * this shows one row per recently-played track with an "artist · 3:24" subtitle,
 * plus the same kind of overflow menu the Downloads/Likes rows already offer. */
@Composable
private fun HistoryRow(
    track: TrackEntity,
    onClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onArtistClick: () -> Unit,
) {
    val context = LocalContext.current
    val dimens = rememberResponsiveDimens()
    var menuExpanded by remember { mutableStateOf(false) }
    val subtitle = listOfNotNull(track.artist, formatTrackDuration(track.durationSec).takeIf { it.isNotEmpty() })
        .joinToString(" · ")
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Canopy.bg),
        leadingContent = { TrackThumbnail(track.thumbnailUrl, size = dimens.listThumbnail, seed = track.title) },
        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (subtitle.isBlank()) null else {
            { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Canopy.neutral500) }
        },
        trailingContent = {
            Box {
                CanopyIconButton(icon = phosphorIcon("dots-three"), onClick = { menuExpanded = true })
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Zu Playlist hinzufügen") },
                        leadingIcon = { Icon(phosphorIcon("plus-circle"), contentDescription = null, tint = Canopy.accent) },
                        onClick = { menuExpanded = false; onAddToPlaylistClick() },
                    )
                    DropdownMenuItem(
                        text = { Text("Zur Warteschlange hinzufügen") },
                        leadingIcon = { Icon(phosphorIcon("list-plus"), contentDescription = null, tint = Canopy.accent) },
                        onClick = { menuExpanded = false; onAddToQueueClick() },
                    )
                    DropdownMenuItem(
                        text = { Text("Zum Künstler") },
                        leadingIcon = { Icon(phosphorIcon("user-circle"), contentDescription = null, tint = Canopy.accent) },
                        onClick = { menuExpanded = false; onArtistClick() },
                    )
                    DropdownMenuItem(
                        text = { Text("Teilen") },
                        leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Canopy.accent) },
                        onClick = { menuExpanded = false; context.shareText(track.webpageUrl) },
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

private fun formatTrackDuration(sec: Int?): String {
    if (sec == null || sec <= 0) return ""
    return "%d:%02d".format(sec / 60, sec % 60)
}

private fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return ""
    return when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

@Composable
private fun LibrarySubViewHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CanopyIconButton(icon = phosphorIcon("caret-left"), onClick = onBack, iconSize = 20.dp)
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 6.dp))
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
    val dimens = rememberResponsiveDimens()
    var menuExpanded by remember { mutableStateOf(false) }
    // Falls back to the persisted percentage (from a prior run, or right before the
    // first live WorkManager progress event arrives) instead of always starting the
    // bar at 0 - matters most for PAUSED, which has no live progress at all.
    val displayPct = item.livePct ?: entity.progressPct

    ListItem(
        colors = ListItemDefaults.colors(containerColor = Canopy.bg),
        leadingContent = { TrackThumbnail(track?.thumbnailUrl, size = dimens.listThumbnail, seed = track?.title ?: entity.trackId) },
        headlineContent = {
            Text(track?.title ?: entity.trackId, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            when (entity.state) {
                DownloadState.DOWNLOADING, DownloadState.PAUSED -> Column {
                    Box(
                        modifier = Modifier
                            .width(130.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Canopy.neutral800),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(displayPct / 100f)
                                .height(4.dp)
                                .background(Canopy.accent),
                        )
                    }
                    val sizeText = when {
                        entity.state == DownloadState.PAUSED -> "Pausiert · $displayPct%"
                        entity.totalBytes != null -> "${formatBytes(entity.bytesDownloaded)} / ${formatBytes(entity.totalBytes)}"
                        else -> "$displayPct%"
                    }
                    Text(
                        sizeText,
                        color = Canopy.neutral500,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                DownloadState.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        phosphorIcon("warning-circle"),
                        contentDescription = null,
                        tint = Canopy.neutral500,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        "Fehlgeschlagen",
                        color = Canopy.neutral500,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                DownloadState.COMPLETED -> {
                    val subtitle = listOfNotNull(
                        track?.artist ?: "Heruntergeladen",
                        formatBytes(entity.totalBytes).takeIf { it.isNotEmpty() }
                    ).joinToString(" · ")
                    Text(
                        subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Canopy.neutral500,
                    )
                }
                DownloadState.QUEUED -> Text("Wartet...", fontStyle = FontStyle.Italic, color = Canopy.neutral500)
            }
        },
        trailingContent = when (entity.state) {
            DownloadState.COMPLETED -> if (track == null) null else {
                {
                Row {
                    CanopyIconButton(
                        icon = phosphorIcon("heart", filled = isLiked),
                        onClick = {
                            haptic.performHapticFeedback(if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                            onLikeClick()
                        },
                    )
                    Box {
                        CanopyIconButton(icon = phosphorIcon("dots-three"), onClick = { menuExpanded = true })
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Zu Playlist hinzufügen") },
                                leadingIcon = { Icon(phosphorIcon("plus-circle"), contentDescription = null, tint = Canopy.accent) },
                                onClick = { menuExpanded = false; onAddToPlaylistClick() },
                            )
                            DropdownMenuItem(
                                text = { Text("Zur Warteschlange hinzufügen") },
                                leadingIcon = { Icon(phosphorIcon("list-plus"), contentDescription = null, tint = Canopy.accent) },
                                onClick = { menuExpanded = false; onAddToQueueClick() },
                            )
                            DropdownMenuItem(
                                text = { Text("Zum Künstler") },
                                leadingIcon = { Icon(phosphorIcon("user-circle"), contentDescription = null, tint = Canopy.accent) },
                                onClick = { menuExpanded = false; onArtistClick() },
                            )
                            DropdownMenuItem(
                                text = { Text("Teilen") },
                                leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Canopy.accent) },
                                onClick = { menuExpanded = false; context.shareText(track.webpageUrl) },
                            )
                        }
                    }
                }
                }
            }
            DownloadState.DOWNLOADING -> {
                { CanopyIconButton(icon = phosphorIcon("pause"), onClick = onPauseClick) }
            }
            DownloadState.PAUSED -> {
                { CanopyIconButton(icon = phosphorIcon("play"), onClick = onResumeClick) }
            }
            DownloadState.FAILED -> {
                { CanopyIconButton(icon = phosphorIcon("arrow-clockwise"), onClick = onRetryClick) }
            }
            DownloadState.QUEUED -> null
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entity.state == DownloadState.COMPLETED, onClick = onClick),
    )
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
    val dimens = rememberResponsiveDimens()
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
                    .background(Canopy.accent800)
                    .padding(start = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(phosphorIcon("list-plus"), contentDescription = "Zur Warteschlange hinzufügen", tint = Canopy.accent100)
            }
        },
    ) {
        var menuExpanded by remember { mutableStateOf(false) }
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Canopy.bg),
            leadingContent = { TrackThumbnail(like.track.thumbnailUrl, size = dimens.listThumbnail, seed = like.track.title) },
            headlineContent = { Text(like.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(like.track.artist ?: like.track.source, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Canopy.neutral500)
            },
            trailingContent = {
                Row {
                    CanopyIconButton(
                        icon = phosphorIcon("heart", filled = true),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.ToggleOff)
                            onUnlikeClick()
                        },
                    )
                    Box {
                        CanopyIconButton(icon = phosphorIcon("dots-three"), onClick = { menuExpanded = true })
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Zu Playlist hinzufügen") },
                                leadingIcon = { Icon(phosphorIcon("plus-circle"), contentDescription = null, tint = Canopy.accent) },
                                onClick = { menuExpanded = false; onAddToPlaylistClick() },
                            )
                            DropdownMenuItem(
                                text = { Text("Zur Warteschlange hinzufügen") },
                                leadingIcon = { Icon(phosphorIcon("list-plus"), contentDescription = null, tint = Canopy.accent) },
                                onClick = { menuExpanded = false; onAddToQueueClick() },
                            )
                            DropdownMenuItem(
                                text = { Text("Herunterladen") },
                                leadingIcon = { Icon(phosphorIcon("download-simple"), contentDescription = null, tint = Canopy.accent) },
                                onClick = { menuExpanded = false; onDownloadClick() },
                            )
                            DropdownMenuItem(
                                text = { Text("Nicht mehr gefällt mir") },
                                leadingIcon = { Icon(phosphorIcon("heart", filled = true), contentDescription = null, tint = Canopy.accent) },
                                onClick = { menuExpanded = false; onUnlikeClick() },
                            )
                            DropdownMenuItem(
                                text = { Text("Zum Künstler") },
                                leadingIcon = { Icon(phosphorIcon("user-circle"), contentDescription = null, tint = Canopy.accent) },
                                onClick = {
                                    menuExpanded = false
                                    like.track.artist?.let(onArtistClick)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Teilen") },
                                leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Canopy.accent) },
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
 * SavedPlaylistRepository) - merged into one list per the chosen "Playlists"
 * chip UX (a small badge in the card's meta line to tell them apart, rather
 * than a separate tab). */
private sealed class LibraryPlaylistItem {
    abstract val key: String
    data class Local(val playlist: PlaylistOutDto) : LibraryPlaylistItem() {
        override val key = "local:${playlist.id}"
    }
    data class Saved(val playlist: SavedPlaylistEntity) : LibraryPlaylistItem() {
        override val key = "saved:${playlist.source}:${playlist.sourceId}"
    }
}

/** The design's 2-column playlist card: a square cover, then title + meta.
 * Local (Room) playlists have no real artwork at all, so - per the accent-color
 * system [dev.schlubbe.musicagent.ui.theme.accentColorFor]'s own doc, which
 * names "Library's playlist cards" as one of its four agreeing call sites -
 * they show the flat accent block + stack icon rather than a generated tile.
 * Saved (remote) playlists do have a real thumbnail, so they use
 * [TrackThumbnail] with the title as a generated-artwork fallback seed.
 *
 * The design's offline pill (bottom-left, frosted, "Offline") needs a per-
 * playlist downloaded-track count that no repository exposed to this
 * ViewModel provides (PlaylistOutDto only carries a trackCount int, not track
 * ids, so it can't be intersected with the downloads table) - omitted rather
 * than invented; see the task report. */
@Composable
private fun PlaylistGridCard(item: LibraryPlaylistItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CanopyShapes.medium)
            .background(Canopy.surface)
            .border(1.dp, Canopy.divider, CanopyShapes.medium)
            .clickable(onClick = onClick),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val coverSize = maxWidth
            when (item) {
                is LibraryPlaylistItem.Local -> Box(
                    modifier = Modifier
                        .size(coverSize)
                        .background(accentColorFor(item.playlist.accentColorKey, item.playlist.id)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        phosphorIcon("stack"),
                        contentDescription = null,
                        tint = Canopy.text,
                        modifier = Modifier.size(coverSize * 0.22f),
                    )
                }
                is LibraryPlaylistItem.Saved -> TrackThumbnail(
                    item.playlist.thumbnailUrl,
                    size = coverSize,
                    seed = item.playlist.title,
                )
            }
        }
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
            val title = when (item) {
                is LibraryPlaylistItem.Local -> item.playlist.name
                is LibraryPlaylistItem.Saved -> item.playlist.title
            }
            val meta = when (item) {
                // "Gespeichert" reads correctly here: local playlists live on-device
                // in Room, so they're inherently "saved" - not a fabricated status.
                is LibraryPlaylistItem.Local -> "${item.playlist.trackCount} Titel · Gespeichert"
                is LibraryPlaylistItem.Saved -> {
                    val sourceLabel = if (item.playlist.source == "soundcloud") "SoundCloud" else "YT Music"
                    listOfNotNull(item.playlist.trackCount?.let { "$it Titel" }, sourceLabel).joinToString(" · ")
                }
            }
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = Canopy.neutral500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
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
