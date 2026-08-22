package dev.schlubbe.musicagent.ui.player

import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import coil.compose.AsyncImage
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.playback.EqPreset
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.CanopyChip
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.CanopySectionHeader
import dev.schlubbe.musicagent.ui.components.CanopyToggle
import dev.schlubbe.musicagent.ui.components.Confetti
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.components.rememberBreathingScale
import dev.schlubbe.musicagent.ui.components.rememberEqBarHeights
import dev.schlubbe.musicagent.ui.components.rememberGlowAlpha
import dev.schlubbe.musicagent.ui.components.rememberHeartPopScale
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.playlist.AddToPlaylistDialog
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyShapes
import dev.schlubbe.musicagent.ui.util.rememberResponsiveDimens
import dev.schlubbe.musicagent.ui.util.shareText
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

/** The Player's artwork is a fixed 232dp square per the handoff (GrooveoApp.dc.html
 * line 293), not a fraction-of-screen-width like the previous Nocturne layout --
 * the glow/scrim/visualizer stack underneath is all sized off this one constant. */
private val PlayerArtSize = 232.dp

/** The 5 decorative visualizer variants (`_ds_bundle.js`'s `VIZ` table) shown under
 * the artwork's bottom scrim. This switch is local-only UI state: PlayerViewModel /
 * SettingsRepository only persist the *seekbar* shape ("Wiedergabestil": waveform vs.
 * bars -- see [Waveform]/[Bars] below), so there is no backing field to wire this
 * purely-decorative choice to, and nothing is lost by not persisting it across
 * process death. */
private data class VizOption(val id: String, val icon: String)
private val VizOptions = listOf(
    VizOption("wave", "waveform"),
    VizOption("bars", "chart-bar"),
    // Design's icon here is "ph-circle-half", which isn't in PhosphorIcon.kt's map
    // (its fallback would silently render a warning-circle glyph) -- "circles-three"
    // is the closest mapped stand-in for a circular/orb concept.
    VizOption("orb", "circles-three"),
    VizOption("particles", "sphere"),
    VizOption("pulse", "broadcast"),
)

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
    val dimens = rememberResponsiveDimens()
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var positionMs by remember { mutableLongStateOf(0L) }
    var polledDurationMs by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var sleepTimerRemainingMs by remember { mutableLongStateOf(0L) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showEqMenu by remember { mutableStateOf(false) }
    var dragAccumulatedPx by remember { mutableFloatStateOf(0f) }
    var vizVariant by remember { mutableStateOf("wave") }
    var likeTrigger by remember { mutableIntStateOf(0) }

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
            // While isUnavailable, the MediaController's real position/duration belong
            // to whatever real track (if any) is actually loaded underneath - not the
            // DRM-blocked slot on screen - so show 0 instead of a stale/unrelated value.
            if (!isSeeking) {
                positionMs = if (playbackState.isUnavailable) 0L else viewModel.currentPositionMs()
            }
            polledDurationMs = if (playbackState.isUnavailable) 0L else viewModel.currentDurationMs()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            // GrooveoApp.dc.html line 282: linear-gradient(180deg, accent-200 0%, bg 55%).
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(0f to Canopy.accent200, 0.55f to Canopy.bg, 1f to Canopy.bg),
                ),
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CanopyIconButton(
                icon = phosphorIcon("caret-down"),
                onClick = onNavigateBack,
                contentDescription = "Schließen",
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Läuft aus".uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = Canopy.neutral600,
                )
                // Design's second line is the *queue's* source (e.g. a chart or playlist
                // name, "Charts · SoundCloud") - PlaybackUiState has no such concept, only
                // the now-playing track's own source, so that real value is shown instead
                // of inventing a queue title.
                Text(
                    sourceLabel ?: "Wiedergabe",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Box {
                CanopyIconButton(
                    icon = phosphorIcon("dots-three-vertical"),
                    onClick = { showMoreMenu = true },
                    contentDescription = "Mehr",
                )
                DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Zu Playlist hinzufügen") },
                        leadingIcon = { Icon(phosphorIcon("plus-circle"), contentDescription = null, tint = Canopy.accent) },
                        onClick = { showMoreMenu = false; viewModel.onAddToPlaylistClicked() },
                        enabled = playbackState.currentTrackId != null,
                    )
                    DropdownMenuItem(
                        text = { Text("Herunterladen") },
                        leadingIcon = { Icon(phosphorIcon("download-simple"), contentDescription = null, tint = Canopy.accent) },
                        onClick = { showMoreMenu = false; viewModel.onDownloadClicked() },
                        enabled = playbackState.currentTrackId != null,
                    )
                    DropdownMenuItem(
                        text = { Text("Zum Künstler") },
                        leadingIcon = { Icon(phosphorIcon("user-circle"), contentDescription = null, tint = Canopy.accent) },
                        onClick = {
                            showMoreMenu = false
                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            viewModel.onArtistClicked()
                        },
                        enabled = !playbackState.artist.isNullOrBlank(),
                    )
                    DropdownMenuItem(
                        text = { Text(if (isLiked) "Nicht mehr gefällt mir" else "Gefällt mir") },
                        leadingIcon = { Icon(phosphorIcon("heart", filled = isLiked), contentDescription = null, tint = Canopy.accent) },
                        onClick = {
                            showMoreMenu = false
                            haptic.performHapticFeedback(if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                            viewModel.toggleLike()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Teilen") },
                        leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Canopy.accent) },
                        onClick = {
                            showMoreMenu = false
                            viewModel.currentTrackWebpageUrl()?.let { context.shareText(it) }
                        },
                        enabled = playbackState.currentTrackId != null,
                    )
                }
            }
        }

        // Everything below the top bar scrolls as one column in the design (`gv-scroll`
        // wraps art through the chip row) - the top bar itself is kept pinned outside the
        // scroll here, which is the more standard player pattern and matches how this
        // screen already behaved before this pass.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = dimens.playerContentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.padding(top = 10.dp), contentAlignment = Alignment.Center) {
                val breatheScale = rememberBreathingScale(playbackState.isPlaying)
                val glowAlpha = rememberGlowAlpha(playbackState.isPlaying)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(PlayerArtSize)
                        .graphicsLayer { scaleX = breatheScale; scaleY = breatheScale },
                ) {
                    // The glow's `inset:-18px` in CSS -> a child 36dp larger than the art,
                    // centered; Box doesn't clip its children by default, so the overflow
                    // paints outside the 232dp footprint without disturbing layout.
                    Box(
                        modifier = Modifier
                            .size(PlayerArtSize + 36.dp)
                            .blur(18.dp)
                            .background(
                                brush = Brush.radialGradient(listOf(Canopy.accent.copy(alpha = glowAlpha), Color.Transparent)),
                                shape = RoundedCornerShape(34.dp),
                            ),
                    )
                    AlbumArt(
                        url = playbackState.artworkUrl,
                        modifier = Modifier
                            .size(PlayerArtSize)
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
                                .size(PlayerArtSize)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                    // Bottom scrim + Visualizer overlay (GrooveoApp.dc.html lines 296-298):
                    // 104/232 of the art's height, rounded only on the bottom corners.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .size(PlayerArtSize, PlayerArtSize * (104f / 232f))
                            .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Visualizer(
                            variant = vizVariant,
                            isPlaying = playbackState.isPlaying,
                            modifier = Modifier
                                .padding(bottom = 14.dp)
                                .width(200.dp)
                                .height(64.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                VizOptions.forEach { option ->
                    CanopyIconButton(
                        icon = phosphorIcon(option.icon),
                        onClick = { vizVariant = option.id },
                        variant = if (vizVariant == option.id) CanopyButtonVariant.Primary else CanopyButtonVariant.Ghost,
                        size = 34.dp,
                        contentDescription = option.id,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (playbackState.isLoading) "Wird geladen…" else playbackState.title ?: "Kein Titel ausgewählt",
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (playbackState.isLoading) "" else playbackState.artist ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Canopy.neutral600,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .clickable(enabled = !playbackState.artist.isNullOrBlank() && !playbackState.isLoading) {
                                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                viewModel.onArtistClicked()
                            },
                    )
                    // DRM-only SoundCloud track (see PlayerController's isUnavailable kdoc) -
                    // cover/title/artist above still show, but playback is paused here and
                    // won't auto-advance; only a manual skip moves on, matching the disabled
                    // play/pause button below. Not part of the design (a purely local edge
                    // case), styled to match its existing accent-caption treatment.
                    if (playbackState.isUnavailable) {
                        Text(
                            playbackState.unavailableMessage ?: "Titel nicht verfügbar",
                            style = MaterialTheme.typography.bodySmall,
                            color = Canopy.accent,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                // A manual "surface" IconButton rather than [CanopyIconButton]: the design's
                // like control carries its own pop/burst motion (rememberHeartPopScale +
                // Confetti), which needs a custom Modifier on the inner icon that
                // CanopyIconButton's fixed icon slot doesn't expose.
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Canopy.surface)
                        .border(1.dp, Canopy.divider, CircleShape)
                        .clickable {
                            haptic.performHapticFeedback(if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                            if (!isLiked) likeTrigger++
                            viewModel.toggleLike()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val popScale = rememberHeartPopScale(likeTrigger)
                    Icon(
                        phosphorIcon("heart", filled = isLiked),
                        contentDescription = if (isLiked) "Gefällt mir nicht mehr" else "Gefällt mir",
                        tint = if (isLiked) Canopy.accent2 else Canopy.text,
                        modifier = Modifier
                            .size(21.dp)
                            .graphicsLayer { scaleX = popScale; scaleY = popScale },
                    )
                    Confetti(trigger = likeTrigger, count = 8)
                }
            }

            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(top = 10.dp).fillMaxWidth()) {
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
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatMs(positionMs), style = MaterialTheme.typography.labelMedium, color = Canopy.neutral500)
                Text(formatMs(durationMs), style = MaterialTheme.typography.labelMedium, color = Canopy.neutral500)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CanopyIconButton(
                    icon = phosphorIcon("shuffle"),
                    onClick = {
                        haptic.performHapticFeedback(
                            if (playbackState.shuffleEnabled) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                        )
                        viewModel.toggleShuffle()
                    },
                    variant = if (playbackState.shuffleEnabled) CanopyButtonVariant.Primary else CanopyButtonVariant.Ghost,
                    size = 44.dp,
                )
                CanopyIconButton(
                    icon = phosphorIcon("skip-back"),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        viewModel.skipToPrevious()
                    },
                    enabled = hasPrevious,
                    size = 52.dp,
                )
                // Disabled (skip-only) while isUnavailable - see PlayerController's kdoc
                // on that flag: there is nothing loaded to play/pause for a DRM-blocked
                // track, only skip-back/skip-forward (unaffected above/below) can move
                // the user off of it.
                val transportEnabled = !playbackState.isUnavailable
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(Canopy.accent)
                        .alpha(if (transportEnabled) 1f else 0.5f)
                        .clickable(enabled = transportEnabled) {
                            haptic.performHapticFeedback(
                                if (playbackState.isPlaying) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                            )
                            viewModel.togglePlayPause()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        phosphorIcon(if (playbackState.isPlaying) "pause" else "play", filled = true),
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                        // The design's play/pause circle uses --accent-900 text on the accent
                        // fill (not white, unlike every other "filled" IconButton), for extra
                        // contrast on its own biggest control.
                        tint = Canopy.accent900,
                        modifier = Modifier.size(30.dp),
                    )
                }
                CanopyIconButton(
                    icon = phosphorIcon("skip-forward"),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        viewModel.skipToNext()
                    },
                    enabled = hasNext,
                    size = 52.dp,
                )
                CanopyIconButton(
                    icon = phosphorIcon(if (playbackState.repeatMode == Player.REPEAT_MODE_ONE) "repeat-once" else "repeat"),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        viewModel.cycleRepeatMode()
                    },
                    variant = if (playbackState.repeatMode != Player.REPEAT_MODE_OFF) CanopyButtonVariant.Primary else CanopyButtonVariant.Ghost,
                    size = 44.dp,
                )
            }

            // Stream/offline card (GrooveoApp.dc.html lines 335-342). The design's toggle
            // assumes a track is always either fully offline or not; this app instead
            // separates "download" (a one-way action, [PlayerViewModel.onDownloadClicked])
            // from "switch source" ([PlayerViewModel.toggleSource], only meaningful once a
            // local copy already exists) - so the trailing control swaps between the two
            // depending on hasLocalDownload, keeping both existing actions reachable.
            if (playbackState.currentTrackId != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .clip(CanopyShapes.medium)
                        .background(Canopy.surface)
                        .border(1.dp, Canopy.divider, CanopyShapes.medium)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        phosphorIcon(if (playbackState.isLocalPlayback) "check-circle" else "cloud", filled = playbackState.isLocalPlayback),
                        contentDescription = null,
                        tint = Canopy.accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (playbackState.isLocalPlayback) "Offline verfügbar" else "Wird gestreamt",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            when {
                                // The design's subtitle carries a hardcoded file size
                                // ("Auf dem Gerät gespeichert · 7,4 MB") - PlaybackUiState
                                // has no byte count for the current track, so that stat is
                                // dropped rather than invented (same call made for Home's
                                // like-count card during Phase 2).
                                playbackState.isLocalPlayback -> "Auf dem Gerät gespeichert"
                                playbackState.hasLocalDownload -> "Lokale Kopie verfügbar"
                                else -> "Zum Offline-Hören herunterladen"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Canopy.neutral500,
                        )
                    }
                    if (playbackState.hasLocalDownload) {
                        CanopyToggle(
                            checked = playbackState.isLocalPlayback,
                            onCheckedChange = {
                                haptic.performHapticFeedback(
                                    if (playbackState.isLocalPlayback) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                                )
                                viewModel.toggleSource()
                            },
                        )
                    } else {
                        CanopyIconButton(
                            icon = phosphorIcon("download-simple"),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                viewModel.onDownloadClicked()
                            },
                            size = 36.dp,
                        )
                    }
                }
            }

            val sleepChipLabel = if (sleepTimerEndAtMs != null) {
                "Noch ${(sleepTimerRemainingMs / 60_000L) + 1} Min"
            } else {
                "Sleep-Timer"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CanopyChip(
                    label = sleepChipLabel,
                    active = sleepTimerEndAtMs != null,
                    onClick = { showSleepTimerDialog = true },
                )
                Box {
                    CanopyChip(
                        label = "Equalizer: ${eqPresetLabel(eqPreset)}",
                        active = true,
                        onClick = { showEqMenu = true },
                    )
                    DropdownMenu(expanded = showEqMenu, onDismissRequest = { showEqMenu = false }) {
                        EqPresetMenuItem("Flach", EqPreset.FLAT, eqPreset) { showEqMenu = false; viewModel.setEqPreset(it) }
                        EqPresetMenuItem("Bass-Boost", EqPreset.BASS_BOOST, eqPreset) { showEqMenu = false; viewModel.setEqPreset(it) }
                        EqPresetMenuItem("Höhen-Boost", EqPreset.TREBLE_BOOST, eqPreset) { showEqMenu = false; viewModel.setEqPreset(it) }
                        EqPresetMenuItem("Vocal", EqPreset.VOCAL, eqPreset) { showEqMenu = false; viewModel.setEqPreset(it) }
                    }
                }
                // The design's "Zur Warteschlange" chip just flashes a toast in the mockup;
                // there's no dedicated queue screen wired into this app's NavGraph (out of
                // scope to add here), so instead it scrolls this same screen down to the
                // "Als Nächstes" list below, which is real, already-working data.
                CanopyChip(
                    label = "Zur Warteschlange",
                    active = false,
                    onClick = {
                        if (upNext.isNotEmpty()) {
                            coroutineScope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                        }
                    },
                )
            }

            if (upNext.isNotEmpty()) {
                CanopySectionHeader(title = "Als Nächstes", modifier = Modifier.padding(top = 22.dp))
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    upNext.forEachIndexed { i, track ->
                        val queueIndex = playbackState.queueIndex + 1 + i
                        UpNextRow(track = track, onClick = { viewModel.playQueueItem(queueIndex) })
                    }
                }
            } else {
                Box(modifier = Modifier.height(24.dp))
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

@Composable
private fun EqPresetMenuItem(
    label: String,
    preset: EqPreset,
    selected: EqPreset,
    onSelect: (EqPreset) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(phosphorIcon("sliders-horizontal"), contentDescription = null, tint = Canopy.accent) },
        trailingIcon = {
            if (preset == selected) {
                Icon(phosphorIcon("check-circle", filled = true), contentDescription = null, tint = Canopy.accent)
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
                                Text("Empfohlen", style = MaterialTheme.typography.labelSmall, color = Canopy.accent)
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
    val dimens = rememberResponsiveDimens()
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { TrackThumbnail(track.thumbnailUrl, size = dimens.listThumbnail) },
        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(track.artist ?: track.source, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Canopy.neutral500)
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
            .background(Canopy.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                phosphorIcon("waveform"),
                contentDescription = null,
                tint = Canopy.neutral600,
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
    val activeColor = Canopy.accent
    val inactiveColor = Canopy.neutral800

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
 * position updates, rather than re-randomizing every frame. 46 bars per the
 * handoff's "Waveform seek: 46 bars" spec. */
@Composable
private fun Waveform(progress: Float, seed: Int, modifier: Modifier = Modifier) {
    val barCount = 46
    val barHeights = remember(seed) {
        val random = Random(seed)
        List(barCount) { 0.2f + random.nextFloat() * 0.8f }
    }
    val activeColor = Canopy.accent
    val inactiveColor = Canopy.neutral300

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

/** The Player's five-way "Visualizer" overlay drawn on the artwork's bottom scrim
 * (design_handoff_grooveo's `Visualizer` component, `_ds_bundle.js` variants
 * wave/bars/orb/particles/pulse). No existing CanopyMotion helper covers a
 * multi-variant visualizer like this, so [rememberEqBarHeights] is reused for the
 * two bar-shaped variants and the radial ones (orb/pulse/particles) get their own
 * small animations here, all gated on [isPlaying] the same way the motion helpers
 * gate their own loops (never feeding a zero-length duration into `tween`). */
@Composable
private fun Visualizer(
    variant: String,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.92f),
) {
    when (variant) {
        "bars" -> {
            val heights = rememberEqBarHeights(isPlaying, barCount = 10)
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Bottom,
            ) {
                heights.forEach { h ->
                    Box(
                        modifier = Modifier
                            .width(8.dp)
                            .fillMaxHeight(h)
                            .clip(RoundedCornerShape(50))
                            .background(color),
                    )
                }
            }
        }
        "orb" -> {
            val spin = if (isPlaying) {
                val transition = rememberInfiniteTransition(label = "vizOrb")
                val s by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing)),
                    label = "vizOrbSpin",
                )
                s
            } else {
                0f
            }
            val accent200 = Canopy.accent200
            Canvas(modifier = modifier) {
                val r = size.minDimension / 2f
                val centre = Offset(size.width / 2f, size.height / 2f)
                val strokeWidth = r * 0.14f
                rotate(spin, centre) {
                    drawArc(
                        color = color,
                        startAngle = 0f,
                        sweepAngle = 120f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth),
                        topLeft = Offset(centre.x - r, centre.y - r),
                        size = Size(r * 2, r * 2),
                    )
                    drawArc(
                        color = accent200,
                        startAngle = 170f,
                        sweepAngle = 90f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth),
                        topLeft = Offset(centre.x - r, centre.y - r),
                        size = Size(r * 2, r * 2),
                    )
                }
                drawCircle(color = Color.Black.copy(alpha = 0.7f), radius = r * 0.55f, center = centre)
                drawCircle(color = color, radius = r * 0.55f, center = centre, style = Stroke(width = 1.dp.toPx()))
            }
        }
        "particles" -> {
            val phases = rememberEqBarHeights(isPlaying, barCount = 16)
            Canvas(modifier = modifier) {
                val centre = Offset(size.width / 2f, size.height / 2f)
                val r = size.minDimension * 0.42f
                phases.forEachIndexed { i, phase ->
                    val angle = (i.toFloat() / phases.size) * 2f * Math.PI.toFloat()
                    val pos = Offset(centre.x + cos(angle) * r, centre.y + sin(angle) * r * 0.5f)
                    drawCircle(
                        color = color.copy(alpha = 0.4f + 0.6f * phase),
                        radius = 2.5.dp.toPx() * phase,
                        center = pos,
                    )
                }
            }
        }
        "pulse" -> {
            val ringProgresses = if (isPlaying) {
                val transition = rememberInfiniteTransition(label = "vizPulse")
                (0 until 3).map { i ->
                    val p by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2100, easing = LinearEasing, delayMillis = i * 700),
                            repeatMode = RepeatMode.Restart,
                        ),
                        label = "vizPulseRing$i",
                    )
                    p
                }
            } else {
                listOf(0.3f, 0.3f, 0.3f)
            }
            Canvas(modifier = modifier) {
                val centre = Offset(size.width / 2f, size.height / 2f)
                val maxR = size.minDimension / 2f
                ringProgresses.forEach { p ->
                    drawCircle(
                        color = color.copy(alpha = (1f - p) * 0.8f),
                        radius = maxR * (0.3f + 0.7f * p),
                        center = centre,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
                drawCircle(color = color, radius = maxR * 0.18f, center = centre)
            }
        }
        else -> { // "wave", the default variant
            val heights = rememberEqBarHeights(isPlaying, barCount = 22)
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                heights.forEach { h ->
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight(0.35f + 0.65f * h)
                            .clip(RoundedCornerShape(50))
                            .background(color.copy(alpha = 0.35f + 0.65f * h)),
                    )
                }
            }
        }
    }
}
