package dev.schlubbe.musicagent.ui.player

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import coil.compose.AsyncImage
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.playback.EqPreset
import dev.schlubbe.musicagent.ui.components.EqualizerBadge
import dev.schlubbe.musicagent.ui.components.NocturneIconButton
import dev.schlubbe.musicagent.ui.components.NocturneTag
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.playlist.AddToPlaylistDialog
import dev.schlubbe.musicagent.ui.theme.Nocturne
import dev.schlubbe.musicagent.ui.util.shareText
import kotlinx.coroutines.delay
import kotlin.random.Random
import java.util.concurrent.TimeUnit

private fun formatMs(ms: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun eqPresetLabel(preset: EqPreset): String = when (preset) {
    EqPreset.FLAT -> "Flach"
    EqPreset.BASS_BOOST -> "Bass-Boost"
    EqPreset.TREBLE_BOOST -> "Höhen-Boost"
    EqPreset.VOCAL -> "Vocal"
}

@Composable
fun PlayerScreen(
    onNavigateBack: () -> Unit,
    onArtistSelected: (source: String, sourceId: String) -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val playbackState by viewModel.playbackState.collectAsState()
    val isLiked by viewModel.isLiked.collectAsState()
    val artistNavState by viewModel.artistNavState.collectAsState()
    val sleepTimerEndAtMs by viewModel.sleepTimerEndAtMs.collectAsState()
    val eqPreset by viewModel.eqPreset.collectAsState()
    val playerStyle by viewModel.playerStyle.collectAsState()
    val addToPlaylistState by viewModel.addToPlaylistState.collectAsState()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    var positionMs by remember { mutableLongStateOf(0L) }
    var polledDurationMs by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var sleepTimerRemainingMs by remember { mutableLongStateOf(0L) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showEqMenu by remember { mutableStateOf(false) }
    var dragAccumulatedPx by remember { mutableFloatStateOf(0f) }

    if (showSleepTimerDialog) {
        SleepTimerDialog(
            isActive = sleepTimerEndAtMs != null,
            onDismiss = { showSleepTimerDialog = false },
            onSelect = { minutes ->
                viewModel.startSleepTimer(minutes)
                showSleepTimerDialog = false
            },
            onCancel = {
                viewModel.cancelSleepTimer()
                showSleepTimerDialog = false
            },
        )
    }

    LaunchedEffect(artistNavState.artistNavTarget) {
        artistNavState.artistNavTarget?.let { (source, sourceId) ->
            onArtistSelected(source, sourceId)
            viewModel.onArtistNavigated()
        }
    }
    LaunchedEffect(artistNavState.artistLookupError) {
        artistNavState.artistLookupError?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.onArtistLookupErrorShown()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (!isSeeking) positionMs = viewModel.currentPositionMs()
            polledDurationMs = viewModel.currentDurationMs()
            val endAt = viewModel.sleepTimerEndAtMs.value
            sleepTimerRemainingMs = if (endAt != null) (endAt - System.currentTimeMillis()).coerceAtLeast(0L) else 0L
            delay(500)
        }
    }

    // Prefer the search result's own duration metadata: HLS-sourced streams (e.g.
    // SoundCloud) are proxied as an unbounded transcoded pipe, so ExoPlayer's own
    // duration never resolves for them.
    val durationMs = playbackState.durationMs.takeIf { it > 0 } ?: polledDurationMs
    val progress = (positionMs.toFloat() / durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)

    val upNext = if (playbackState.queueIndex >= 0) {
        playbackState.queue.drop(playbackState.queueIndex + 1)
    } else {
        emptyList()
    }
    // With repeat-all, skip wraps around the ends of the queue - hasNextMediaItem()/
    // hasPreviousMediaItem() (used by skipToNext/Previous) already account for this,
    // so mirror it here too rather than leaving the buttons looking disabled at the
    // queue's edges when they'd actually still work.
    val wrapsAround = playbackState.repeatMode == Player.REPEAT_MODE_ALL && playbackState.queue.size > 1
    val hasPrevious = wrapsAround || playbackState.queueIndex > 0
    val hasNext = wrapsAround || playbackState.queueIndex in 0 until playbackState.queue.size - 1
    val sourceLabel = when (playbackState.currentTrackId?.substringBefore(":")) {
        "soundcloud" -> "SoundCloud"
        "ytmusic" -> "YouTube Music"
        else -> null
    }

    Column(modifier = Modifier.fillMaxSize().background(Nocturne.bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NocturneIconButton(icon = phosphorIcon("caret-left"), onClick = onNavigateBack, iconSize = 22.dp)
            if (sourceLabel != null) NocturneTag(sourceLabel)
            Box {
                NocturneIconButton(icon = phosphorIcon("dots-three"), onClick = { showMoreMenu = true }, iconSize = 20.dp)
                DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Zu Playlist hinzufügen") },
                        leadingIcon = { Icon(phosphorIcon("plus-circle"), contentDescription = null, tint = Nocturne.accent) },
                        onClick = { showMoreMenu = false; viewModel.onAddToPlaylistClicked() },
                        enabled = playbackState.currentTrackId != null,
                    )
                    DropdownMenuItem(
                        text = { Text("Herunterladen") },
                        leadingIcon = { Icon(phosphorIcon("download-simple"), contentDescription = null, tint = Nocturne.accent) },
                        onClick = { showMoreMenu = false; viewModel.onDownloadClicked() },
                        enabled = playbackState.currentTrackId != null,
                    )
                    DropdownMenuItem(
                        text = { Text("Zum Künstler") },
                        leadingIcon = { Icon(phosphorIcon("user-circle"), contentDescription = null, tint = Nocturne.accent) },
                        onClick = {
                            showMoreMenu = false
                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            viewModel.onArtistClicked()
                        },
                        enabled = !playbackState.artist.isNullOrBlank(),
                    )
                    DropdownMenuItem(
                        text = { Text(if (isLiked) "Nicht mehr gefällt mir" else "Gefällt mir") },
                        leadingIcon = { Icon(phosphorIcon("heart", filled = isLiked), contentDescription = null, tint = Nocturne.accent) },
                        onClick = {
                            showMoreMenu = false
                            haptic.performHapticFeedback(if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                            viewModel.toggleLike()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Teilen") },
                        leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Nocturne.accent) },
                        onClick = {
                            showMoreMenu = false
                            viewModel.currentTrackWebpageUrl()?.let { context.shareText(it) }
                        },
                        enabled = playbackState.currentTrackId != null,
                    )
                }
            }
        }

        Column(
            modifier = if (upNext.isEmpty()) {
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp)
            } else {
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp)
            },
            verticalArrangement = if (upNext.isEmpty()) Arrangement.Center else Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                AlbumArt(
                    url = playbackState.artworkUrl,
                    modifier = Modifier
                        .fillMaxWidth(if (upNext.isEmpty()) 0.72f else 0.5f)
                        .aspectRatio(1f)
                        // Swipe left/right (or a trackpad's two-finger horizontal
                        // scroll, which Compose also surfaces as a horizontal drag)
                        // to skip - matches the design's cover-art gesture rather
                        // than a full ViewPager2-style pager, since this is a single
                        // in-place swap, not a continuously scrollable strip.
                        .pointerInput(hasNext, hasPrevious) {
                            detectHorizontalDragGestures(
                                onDragStart = { dragAccumulatedPx = 0f },
                                onDragEnd = {
                                    val thresholdPx = 40.dp.toPx()
                                    if (dragAccumulatedPx <= -thresholdPx && hasNext) {
                                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                        viewModel.skipToNext()
                                    } else if (dragAccumulatedPx >= thresholdPx && hasPrevious) {
                                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                        viewModel.skipToPrevious()
                                    }
                                    dragAccumulatedPx = 0f
                                },
                            ) { _, dragAmount -> dragAccumulatedPx += dragAmount }
                        },
                )
                // Resolving a track's stream on-device is a real network round trip
                // (unlike the old backend's near-instant proxy) - without this, tapping
                // a track just kept showing whatever played before with no visible
                // change, which read as "my tap didn't register" and prompted a second,
                // duplicate tap (see PlayerController.pendingTrackKey for the other half
                // of this fix).
                if (playbackState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (upNext.isEmpty()) 0.72f else 0.5f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
                EqualizerBadge(
                    isPlaying = playbackState.isPlaying,
                    size = 30.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                )
            }

            Column(
                modifier = Modifier.padding(top = 24.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (playbackState.isLoading) "Wird geladen…" else playbackState.title ?: "Kein Titel ausgewählt",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (playbackState.isLoading) "" else playbackState.artist ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Nocturne.neutral500,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable(enabled = !playbackState.artist.isNullOrBlank() && !playbackState.isLoading) {
                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            viewModel.onArtistClicked()
                        },
                )
            }

            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                if (playerStyle == "bars") {
                    Bars(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                    )
                } else {
                    Waveform(
                        progress = progress,
                        seed = playbackState.currentTrackId?.hashCode() ?: 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                    )
                }
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
                    onValueChange = {
                        isSeeking = true
                        positionMs = it.toLong()
                    },
                    onValueChangeFinished = {
                        viewModel.seekTo(positionMs)
                        isSeeking = false
                    },
                    valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                    colors = SliderDefaults.colors(
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent,
                    ),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatMs(positionMs), style = MaterialTheme.typography.labelSmall, color = Nocturne.neutral500)
                Text(formatMs(durationMs), style = MaterialTheme.typography.labelSmall, color = Nocturne.neutral500)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NocturneIconButton(
                    icon = phosphorIcon("shuffle"),
                    onClick = {
                        haptic.performHapticFeedback(
                            if (playbackState.shuffleEnabled) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                        )
                        viewModel.toggleShuffle()
                    },
                    iconSize = 18.dp,
                )
                NocturneIconButton(
                    icon = phosphorIcon(if (playbackState.repeatMode == Player.REPEAT_MODE_ONE) "repeat-once" else "repeat"),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        viewModel.cycleRepeatMode()
                    },
                    iconSize = 18.dp,
                )
                SleepEqPill(
                    icon = phosphorIcon("moon"),
                    label = "Sleep",
                    highlighted = sleepTimerEndAtMs != null,
                    onClick = { showSleepTimerDialog = true },
                )
                Box {
                    SleepEqPill(
                        icon = phosphorIcon("sliders-horizontal"),
                        label = eqPresetLabel(eqPreset),
                        highlighted = true,
                        onClick = { showEqMenu = true },
                    )
                    DropdownMenu(expanded = showEqMenu, onDismissRequest = { showEqMenu = false }) {
                        EqPresetMenuItem("Flach", EqPreset.FLAT, eqPreset) { showEqMenu = false; viewModel.setEqPreset(it) }
                        EqPresetMenuItem("Bass-Boost", EqPreset.BASS_BOOST, eqPreset) { showEqMenu = false; viewModel.setEqPreset(it) }
                        EqPresetMenuItem("Höhen-Boost", EqPreset.TREBLE_BOOST, eqPreset) { showEqMenu = false; viewModel.setEqPreset(it) }
                        EqPresetMenuItem("Vocal", EqPreset.VOCAL, eqPreset) { showEqMenu = false; viewModel.setEqPreset(it) }
                    }
                }
            }
            if (sleepTimerEndAtMs != null) {
                Text(
                    "Schlaf-Timer: noch ${(sleepTimerRemainingMs / 60_000L) + 1} Min",
                    style = MaterialTheme.typography.labelSmall,
                    color = Nocturne.accent,
                    modifier = Modifier.padding(top = 9.dp),
                )
            }

            Row(
                modifier = Modifier.padding(top = 22.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NocturneIconButton(
                    icon = phosphorIcon("heart", filled = isLiked),
                    onClick = {
                        haptic.performHapticFeedback(if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        viewModel.toggleLike()
                    },
                )
                NocturneIconButton(
                    icon = phosphorIcon("skip-back"),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        viewModel.skipToPrevious()
                    },
                    enabled = hasPrevious,
                    iconSize = 22.dp,
                )
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, Nocturne.accent, CircleShape)
                        .clickable {
                            haptic.performHapticFeedback(
                                if (playbackState.isPlaying) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                            )
                            viewModel.togglePlayPause()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        phosphorIcon(if (playbackState.isPlaying) "pause" else "play"),
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                        tint = Nocturne.accent,
                        modifier = Modifier.size(26.dp),
                    )
                }
                NocturneIconButton(
                    icon = phosphorIcon("skip-forward"),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        viewModel.skipToNext()
                    },
                    enabled = hasNext,
                    iconSize = 22.dp,
                )
                if (playbackState.hasLocalDownload) {
                    NocturneIconButton(
                        icon = phosphorIcon(if (playbackState.isLocalPlayback) "check-circle" else "cloud-arrow-up", filled = playbackState.isLocalPlayback),
                        onClick = {
                            haptic.performHapticFeedback(
                                if (playbackState.isLocalPlayback) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                            )
                            viewModel.toggleSource()
                        },
                        variant = dev.schlubbe.musicagent.ui.components.NocturneButtonVariant.Primary,
                    )
                } else if (playbackState.currentTrackId != null) {
                    NocturneIconButton(
                        icon = phosphorIcon("download-simple"),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            viewModel.onDownloadClicked()
                        },
                    )
                }
            }
        }

        if (upNext.isNotEmpty()) {
            Text(
                "Als Nächstes",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(upNext, key = { _, track -> "${track.source}:${track.sourceId}" }) { i, track ->
                    val queueIndex = playbackState.queueIndex + 1 + i
                    UpNextRow(track = track, onClick = { viewModel.playQueueItem(queueIndex) })
                }
            }
        }
    }

    if (addToPlaylistState.pending) {
        AddToPlaylistDialog(
            playlists = addToPlaylistState.playlists,
            onDismiss = viewModel::dismissAddToPlaylist,
            onPlaylistPicked = viewModel::onPlaylistPicked,
            onCreatePlaylist = viewModel::onCreatePlaylistAndAdd,
        )
    }
}

/** ".tag" pill with icon+label used for the Sleep-timer and EQ-preset quick
 * actions next to shuffle/repeat -- a border-only pill, not a filled chip. */
@Composable
private fun SleepEqPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, highlighted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Nocturne.divider, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (highlighted) Nocturne.accent else Nocturne.neutral400, modifier = Modifier.size(13.dp))
        Text(
            label,
            color = if (highlighted) Nocturne.accent else Nocturne.text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

@Composable
private fun EqPresetMenuItem(
    label: String,
    preset: EqPreset,
    selected: EqPreset,
    onSelect: (EqPreset) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(phosphorIcon("sliders-horizontal"), contentDescription = null, tint = Nocturne.accent) },
        trailingIcon = {
            if (preset == selected) {
                Icon(phosphorIcon("check-circle", filled = true), contentDescription = null, tint = Nocturne.accent)
            }
        },
        onClick = { onSelect(preset) },
    )
}

private val SLEEP_TIMER_PRESETS_MIN = listOf(15, 30, 45, 60, 90)
private const val SLEEP_TIMER_DEFAULT_MIN = 30

@Composable
private fun SleepTimerDialog(
    isActive: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep-Timer") },
        text = {
            Column {
                SLEEP_TIMER_PRESETS_MIN.forEach { minutes ->
                    ListItem(
                        headlineContent = { Text("$minutes Minuten") },
                        trailingContent = {
                            if (minutes == SLEEP_TIMER_DEFAULT_MIN) {
                                Text("Empfohlen", style = MaterialTheme.typography.labelSmall, color = Nocturne.accent)
                            }
                        },
                        modifier = Modifier.clickable { onSelect(minutes) },
                    )
                }
            }
        },
        confirmButton = {
            if (isActive) {
                TextButton(onClick = onCancel) { Text("Timer beenden") }
            } else {
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

@Composable
private fun UpNextRow(track: TrackResultDto, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Nocturne.bg),
        leadingContent = { TrackThumbnail(track.thumbnailUrl) },
        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(track.artist ?: track.source, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Nocturne.neutral500)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@Composable
private fun AlbumArt(url: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Nocturne.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                phosphorIcon("waveform"),
                contentDescription = null,
                tint = Nocturne.neutral600,
                modifier = Modifier.fillMaxSize(0.35f),
            )
        }
    }
}

/** The "Balken" (bars) player-style alternative to [Waveform] -- evenly spaced
 * bars of a single fixed height rather than a randomized waveform shape, purely
 * a progress indicator (see the Einstellungen "Wiedergabestil" toggle). */
@Composable
private fun Bars(progress: Float, modifier: Modifier = Modifier) {
    val barCount = 32
    val activeColor = Nocturne.accent
    val inactiveColor = Nocturne.neutral800

    Canvas(modifier = modifier) {
        val gap = 5.dp.toPx()
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val activeBars = (progress * barCount).toInt()

        repeat(barCount) { index ->
            drawRoundRect(
                color = if (index < activeBars) activeColor else inactiveColor,
                topLeft = Offset(index * (barWidth + gap), 0f),
                size = Size(barWidth, size.height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/** A SoundCloud-style static waveform behind the (invisible-tracked) [Slider] —
 * bars are seeded per track so the shape stays stable across recompositions and
 * position updates, rather than re-randomizing every frame. */
@Composable
private fun Waveform(progress: Float, seed: Int, modifier: Modifier = Modifier) {
    val barCount = 56
    val barHeights = remember(seed) {
        val random = Random(seed)
        List(barCount) { 0.2f + random.nextFloat() * 0.8f }
    }
    val activeColor = Nocturne.accent
    val inactiveColor = Nocturne.neutral800

    Canvas(modifier = modifier) {
        val gap = 3.dp.toPx()
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val activeBars = (progress * barCount).toInt()

        barHeights.forEachIndexed { index, heightFraction ->
            val barHeight = (heightFraction * size.height).coerceAtLeast(barWidth)
            val x = index * (barWidth + gap)
            drawRoundRect(
                color = if (index < activeBars) activeColor else inactiveColor,
                topLeft = Offset(x, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
