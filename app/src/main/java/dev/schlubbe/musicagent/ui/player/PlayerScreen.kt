package dev.schlubbe.musicagent.ui.player

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import kotlinx.coroutines.delay
import kotlin.random.Random
import java.util.concurrent.TimeUnit

private fun formatMs(ms: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
fun PlayerScreen(
    onArtistSelected: (source: String, sourceId: String) -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val playbackState by viewModel.playbackState.collectAsState()
    val isLiked by viewModel.isLiked.collectAsState()
    val artistNavState by viewModel.artistNavState.collectAsState()
    val sleepTimerEndAtMs by viewModel.sleepTimerEndAtMs.collectAsState()
    val eqPreset by viewModel.eqPreset.collectAsState()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    var positionMs by remember { mutableLongStateOf(0L) }
    var polledDurationMs by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var sleepTimerRemainingMs by remember { mutableLongStateOf(0L) }
    var showMoreMenu by remember { mutableStateOf(false) }

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

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = if (upNext.isEmpty()) {
                Modifier.weight(1f).fillMaxWidth().padding(32.dp)
            } else {
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp)
            },
            verticalArrangement = if (upNext.isEmpty()) Arrangement.Center else Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                AlbumArt(
                    url = playbackState.artworkUrl,
                    modifier = Modifier
                        .fillMaxWidth(if (upNext.isEmpty()) 0.72f else 0.5f)
                        .aspectRatio(1f),
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Waveform(
                    progress = progress,
                    seed = playbackState.currentTrackId?.hashCode() ?: 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                )
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
                Text(
                    formatMs(positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatMs(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(
                            if (playbackState.shuffleEnabled) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                        )
                        viewModel.toggleShuffle()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = if (playbackState.shuffleEnabled) "Zufallswiedergabe aus" else "Zufallswiedergabe an",
                        tint = if (playbackState.shuffleEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        viewModel.cycleRepeatMode()
                    },
                ) {
                    Icon(
                        imageVector = if (playbackState.repeatMode == Player.REPEAT_MODE_ONE) {
                            Icons.Filled.RepeatOne
                        } else {
                            Icons.Filled.Repeat
                        },
                        contentDescription = when (playbackState.repeatMode) {
                            Player.REPEAT_MODE_ALL -> "Wiederholt Warteschlange – zu Einzeltitel wechseln"
                            Player.REPEAT_MODE_ONE -> "Wiederholt Titel – Wiederholung aus"
                            else -> "Wiederholung an"
                        },
                        tint = if (playbackState.repeatMode != Player.REPEAT_MODE_OFF) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = { showSleepTimerDialog = true }) {
                    Icon(
                        imageVector = Icons.Filled.Bedtime,
                        contentDescription = if (sleepTimerEndAtMs != null) {
                            "Sleep-Timer: noch ${(sleepTimerRemainingMs / 60_000L) + 1} Min"
                        } else {
                            "Sleep-Timer einstellen"
                        },
                        tint = if (sleepTimerEndAtMs != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Weitere Steuerelemente",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                        Text(
                            "Equalizer",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        EqPresetMenuItem("Flach", EqPreset.FLAT, eqPreset, viewModel::setEqPreset)
                        EqPresetMenuItem("Bass-Boost", EqPreset.BASS_BOOST, eqPreset, viewModel::setEqPreset)
                        EqPresetMenuItem("Höhen-Boost", EqPreset.TREBLE_BOOST, eqPreset, viewModel::setEqPreset)
                        EqPresetMenuItem("Vocal", EqPreset.VOCAL, eqPreset, viewModel::setEqPreset)
                    }
                }
            }
            if (sleepTimerEndAtMs != null) {
                Text(
                    "Schlaf-Timer: noch ${(sleepTimerRemainingMs / 60_000L) + 1} Min",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        viewModel.toggleLike()
                    },
                ) {
                    Icon(
                        imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Gefällt mir",
                        tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        viewModel.skipToPrevious()
                    },
                    enabled = hasPrevious,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "Vorheriger Titel",
                        tint = if (hasPrevious) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(
                            if (playbackState.isPlaying) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                        )
                        viewModel.togglePlayPause()
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .size(72.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        viewModel.skipToNext()
                    },
                    enabled = hasNext,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Nächster Titel",
                        tint = if (hasNext) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (playbackState.hasLocalDownload) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(
                                if (playbackState.isLocalPlayback) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                            )
                            viewModel.toggleSource()
                        },
                    ) {
                        Icon(
                            imageVector = if (playbackState.isLocalPlayback) {
                                Icons.Filled.DownloadDone
                            } else {
                                Icons.Filled.Cloud
                            },
                            contentDescription = if (playbackState.isLocalPlayback) {
                                "Spielt heruntergeladene Datei – zum Stream wechseln"
                            } else {
                                "Spielt Stream – zur heruntergeladenen Datei wechseln"
                            },
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (playbackState.currentTrackId != null) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            viewModel.onDownloadClicked()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "Titel herunterladen",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (upNext.isNotEmpty()) {
            Text(
                "Als Nächstes",
                style = MaterialTheme.typography.titleMedium,
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
        leadingIcon = { Icon(Icons.Filled.Equalizer, contentDescription = null) },
        trailingIcon = {
            if (preset == selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                                Text("Empfohlen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
        leadingContent = { TrackThumbnail(track.thumbnailUrl) },
        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(track.artist ?: track.source, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@Composable
private fun AlbumArt(url: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .shadow(elevation = 16.dp, shape = shape, clip = false)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(0.35f),
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
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant

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
