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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.remote.dto.PlaylistDetailOutDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistTrackOutDto
import dev.schlubbe.musicagent.data.remote.dto.toTrackResultDto
import dev.schlubbe.musicagent.ui.components.CanopyBadge
import dev.schlubbe.musicagent.ui.components.CanopyBadgeTone
import dev.schlubbe.musicagent.ui.components.CanopyBadgeVariant
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.LocalCanopyOverlay
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.components.WaveSweep
import dev.schlubbe.musicagent.ui.components.rememberFadeUp
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyShapes
import dev.schlubbe.musicagent.ui.theme.accentColorFor
import dev.schlubbe.musicagent.ui.util.shareText
import kotlinx.coroutines.delay

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
    val overlay = LocalCanopyOverlay.current

    // Inline quick-edit (design 06): name + accent colour only, toggled by the
    // pencil/X icon. PlaylistEditSheet.kt (description + mood tags + the same
    // accentColorKey) stays reachable from the overflow menu below so nothing
    // that screen already covers is lost.
    var isEditing by remember { mutableStateOf(false) }
    var editedName by remember(playlist?.id) { mutableStateOf(playlist?.name ?: "") }
    var editedAccentColorKey by remember(playlist?.id) { mutableStateOf(playlist?.accentColorKey) }
    var showSavedFlash by remember { mutableStateOf(false) }

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
    // "toast 2.4s" per the handoff's motion table.
    LaunchedEffect(showSavedFlash) {
        if (showSavedFlash) {
            delay(2400)
            showSavedFlash = false
        }
    }

    Scaffold(containerColor = Canopy.bg) { padding ->
        when {
            uiState.isLoading -> CircularProgressIndicator(
                modifier = Modifier.padding(padding).padding(16.dp),
                color = Canopy.accent,
            )
            playlist == null -> Text(
                "Playlist nicht gefunden",
                modifier = Modifier.padding(padding).padding(16.dp),
            )
            else -> LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        CanopyIconButton(icon = phosphorIcon("caret-left"), onClick = onNavigateBack, iconSize = 20.dp)
                        Row {
                            Box {
                                CanopyIconButton(icon = phosphorIcon("dots-three"), onClick = { showTopMenu = true })
                                DropdownMenu(expanded = showTopMenu, onDismissRequest = { showTopMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Beschreibung & Stimmung") },
                                        leadingIcon = { Icon(phosphorIcon("pencil-simple"), contentDescription = null, tint = Canopy.accent) },
                                        onClick = { showTopMenu = false; showEditSheet = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Löschen") },
                                        leadingIcon = { Icon(phosphorIcon("trash"), contentDescription = null, tint = Canopy.accent) },
                                        onClick = {
                                            showTopMenu = false
                                            viewModel.delete(onDeleted)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Teilen") },
                                        leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Canopy.accent) },
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
                            // Pencil <-> filled X (design 06): toggles the inline quick-edit
                            // below instead of opening PlaylistEditSheet directly.
                            CanopyIconButton(
                                icon = if (isEditing) phosphorIcon("x") else phosphorIcon("pencil-simple"),
                                onClick = {
                                    if (isEditing) {
                                        // Cancel: discard local edits.
                                        editedName = playlist.name
                                        editedAccentColorKey = playlist.accentColorKey
                                    }
                                    isEditing = !isEditing
                                },
                                iconSize = 17.dp,
                            )
                        }
                    }
                }
                item {
                    PlaylistHeader(
                        playlist = playlist,
                        isEditing = isEditing,
                        editedName = editedName,
                        onEditedNameChange = { editedName = it },
                        editedAccentColorKey = editedAccentColorKey,
                        onEditedAccentColorKeyChange = { editedAccentColorKey = it },
                    )
                }
                if (playlist.tracks.isEmpty()) {
                    item {
                        Text(
                            "Noch keine Titel",
                            color = Canopy.neutral500,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                } else if (!isEditing) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CanopyButton(
                                text = "Abspielen",
                                onClick = {
                                    viewModel.playTrack(playlist.tracks.first())
                                    onTrackSelected()
                                },
                                leadingIcon = phosphorIcon("play", filled = true),
                            )
                            CanopyButton(
                                text = "Zufall",
                                onClick = {
                                    // PlaylistDetailViewModel has no shuffle-the-whole-queue
                                    // action - this starts playback from a random track
                                    // instead (playTrack still queues the rest in list
                                    // order from there). See the task report.
                                    viewModel.playTrack(playlist.tracks.random())
                                    onTrackSelected()
                                },
                                variant = CanopyButtonVariant.Secondary,
                                leadingIcon = phosphorIcon("shuffle"),
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            CanopyIconButton(
                                icon = phosphorIcon("download-simple"),
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                    viewModel.onDownloadPlaylistClicked()
                                },
                                variant = CanopyButtonVariant.Secondary,
                                size = 42.dp,
                            )
                        }
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 18.dp)
                                .clip(CanopyShapes.medium)
                                .background(Canopy.surface)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(phosphorIcon("info"), contentDescription = null, tint = Canopy.neutral500, modifier = Modifier.size(16.dp))
                            Text(
                                "Ordne Titel mit den Pfeilen neu an oder entferne sie unten.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Canopy.neutral500,
                            )
                        }
                    }
                }
                if (playlist.tracks.isNotEmpty()) {
                    itemsIndexed(playlist.tracks, key = { _, item -> item.track.id }) { zeroBasedIndex, item ->
                        PlaylistTrackRow(
                            index = zeroBasedIndex + 1,
                            item = item,
                            isLiked = item.track.id in uiState.likedTrackIds,
                            isEditing = isEditing,
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
                if (isEditing) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CanopyButton(
                                text = "Abbrechen",
                                onClick = {
                                    editedName = playlist.name
                                    editedAccentColorKey = playlist.accentColorKey
                                    isEditing = false
                                },
                                variant = CanopyButtonVariant.Secondary,
                                modifier = Modifier.weight(1f),
                            )
                            CanopyButton(
                                text = "Speichern",
                                onClick = {
                                    viewModel.updateDetails(
                                        name = editedName.trim().ifBlank { playlist.name },
                                        description = playlist.description,
                                        accentColorKey = editedAccentColorKey,
                                        moodTags = playlist.moodTags,
                                    )
                                    isEditing = false
                                    showSavedFlash = true
                                    overlay.spray(6)
                                },
                                leadingIcon = phosphorIcon("check"),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (showSavedFlash) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, start = 16.dp, end = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Row(
                                modifier = Modifier
                                    .then(rememberFadeUp(key = showSavedFlash))
                                    .clip(CanopyShapes.large)
                                    .background(Canopy.accent100)
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    phosphorIcon("check-circle", filled = true),
                                    contentDescription = null,
                                    tint = Canopy.accent,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text("Playlist gespeichert", style = MaterialTheme.typography.labelMedium, color = Canopy.accent)
                            }
                        }
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
    index: Int,
    item: PlaylistTrackOutDto,
    isLiked: Boolean,
    isEditing: Boolean,
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
    var sweepTrigger by remember { mutableIntStateOf(0) }
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Canopy.bg)
                .clickable(onClick = {
                    sweepTrigger++
                    onClick()
                })
                .padding(vertical = 9.dp),
        ) {
            WaveSweep(trigger = sweepTrigger, shape = CanopyShapes.small)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "$index",
                    style = MaterialTheme.typography.labelMedium,
                    color = Canopy.neutral400,
                    modifier = Modifier.width(18.dp),
                )
                TrackThumbnail(item.track.thumbnailUrl, size = 44.dp, seed = item.track.title)
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.padding(top = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The design's per-row "state" icon has no download/playing
                        // status this ViewModel exposes (no per-track download join) -
                        // reused for the real like state instead, and kept clickable so
                        // the like toggle isn't lost. See the task report.
                        Icon(
                            phosphorIcon("heart", filled = isLiked),
                            contentDescription = "Gefällt mir",
                            tint = Canopy.neutral500,
                            modifier = Modifier
                                .size(14.dp)
                                .clickable {
                                    haptic.performHapticFeedback(if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                                    onLikeClick()
                                },
                        )
                        Text(
                            item.track.artist ?: item.track.source,
                            style = MaterialTheme.typography.bodySmall,
                            color = Canopy.neutral500,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (isEditing) {
                    Row {
                        CanopyIconButton(icon = phosphorIcon("arrow-up"), onClick = onMoveUp, size = 32.dp)
                        CanopyIconButton(icon = phosphorIcon("arrow-down"), onClick = onMoveDown, size = 32.dp)
                        CanopyIconButton(icon = phosphorIcon("minus-circle"), onClick = onRemove, size = 32.dp)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    text = { Text("Zum Künstler") },
                                    leadingIcon = { Icon(phosphorIcon("user-circle"), contentDescription = null, tint = Canopy.accent) },
                                    onClick = { menuExpanded = false; item.track.artist?.let(onArtistClick) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Teilen") },
                                    leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Canopy.accent) },
                                    onClick = { menuExpanded = false; context.shareText(item.track.webpageUrl) },
                                )
                            }
                        }
                        // Static drag-handle glyph per the design - reordering itself
                        // happens via the explicit up/down/remove buttons in edit mode
                        // (PlaylistDetailViewModel.moveTrack), not a real drag gesture.
                        Icon(
                            phosphorIcon("dots-six-vertical"),
                            contentDescription = null,
                            tint = Canopy.neutral400,
                            modifier = Modifier.size(18.dp).padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private data class DetailAccentOption(val key: String?, val label: String)

private val DetailAccentOptions = listOf(
    DetailAccentOption(null, "Auto"),
    DetailAccentOption("accent", "Grün"),
    DetailAccentOption("accent2", "Koralle"),
    DetailAccentOption("neutral", "Neutral"),
)

/** Derived, never invented: title count is real; the duration and source-mix
 * segments are computed from the tracks' own durationSec/source fields (both
 * on TrackOutDto already), so a playlist with only YT Music tracks gets that
 * source's name and a mix of sources gets "gemischte Quellen" - never a
 * fabricated total. Tracks with an unknown duration simply don't contribute
 * to the sum instead of being guessed. */
private fun playlistMetaLine(tracks: List<PlaylistTrackOutDto>): String {
    val parts = mutableListOf("${tracks.size} Titel")
    val totalSec = tracks.sumOf { it.track.durationSec ?: 0 }
    if (totalSec > 0) parts += "${(totalSec / 60).coerceAtLeast(1)} Min"
    val sources = tracks.map { it.track.source }.distinct()
    when {
        sources.size > 1 -> parts += "gemischte Quellen"
        sources.size == 1 -> parts += if (sources.first() == "soundcloud") "SoundCloud" else "YT Music"
    }
    return parts.joinToString(" · ")
}

/** 120dp cover + name/meta column (design 06). Read mode shows the headline,
 * the derived meta line, description and mood tags (all real, existing data);
 * edit mode swaps the name for an underlined inline input and shows the four
 * accent swatches, mirroring PlaylistEditSheet's own accentColorKey options
 * under new (design-specified) labels. The offline-count Badge the design
 * shows here ("9 von 14 offline") needs a per-track download join no
 * repository exposes to this ViewModel - omitted rather than invented. */
@Composable
private fun PlaylistHeader(
    playlist: PlaylistDetailOutDto,
    isEditing: Boolean,
    editedName: String,
    onEditedNameChange: (String) -> Unit,
    editedAccentColorKey: String?,
    onEditedAccentColorKeyChange: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.size(120.dp)) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CanopyShapes.medium)
                    .background(accentColorFor(playlist.accentColorKey, playlist.id)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(phosphorIcon("stack"), contentDescription = null, tint = Canopy.text, modifier = Modifier.size(30.dp))
            }
            // Camera overlay is visual-only, matching the prototype: there's no
            // image-picker action wired to PlaylistDetailViewModel to back it.
            if (isEditing) {
                Column(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(CanopyShapes.medium)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(phosphorIcon("camera"), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Text(
                        "Cover",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f).padding(start = 16.dp).height(120.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (isEditing) {
                BasicTextField(
                    value = editedName,
                    onValueChange = onEditedNameChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Canopy.text,
                        fontSize = MaterialTheme.typography.headlineSmall.fontSize,
                        fontWeight = MaterialTheme.typography.headlineSmall.fontWeight,
                        fontFamily = MaterialTheme.typography.headlineSmall.fontFamily,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Canopy.accent),
                )
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DetailAccentOptions.forEach { option ->
                        DetailAccentSwatch(
                            color = accentColorFor(option.key, playlist.id),
                            selected = editedAccentColorKey == option.key,
                            onClick = { onEditedAccentColorKeyChange(option.key) },
                        )
                    }
                    Text(
                        DetailAccentOptions.firstOrNull { it.key == editedAccentColorKey }?.label ?: "Auto",
                        style = MaterialTheme.typography.labelSmall,
                        color = Canopy.neutral500,
                    )
                }
            } else {
                Text(playlist.name, style = MaterialTheme.typography.headlineMedium)
                Text(
                    playlistMetaLine(playlist.tracks),
                    style = MaterialTheme.typography.bodySmall,
                    color = Canopy.neutral500,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (!playlist.description.isNullOrBlank()) {
                    Text(
                        playlist.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Canopy.neutral500,
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
                            CanopyBadge(label, variant = CanopyBadgeVariant.Outline, tone = CanopyBadgeTone.Accent)
                        }
                    }
                }
            }
        }
    }
}

/** A smaller (26dp) version of PlaylistEditSheet's own swatch, with the
 * design's "2 + 4px selection ring" instead of that sheet's 2+3. Kept local
 * to this screen rather than sharing the sheet's private composable. */
@Composable
private fun DetailAccentSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val ringColor = if (selected) Canopy.text else Color.Transparent
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(ringColor)
            .padding(2.dp)
            .clip(CircleShape)
            .background(Canopy.bg)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
    )
}
