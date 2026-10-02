package dev.schlubbe.musicagent.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import com.adamglin.phosphoricons.regular.ArrowsOutSimple
import com.adamglin.phosphoricons.regular.Heart
import com.adamglin.phosphoricons.regular.Microphone
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.Repeat
import com.adamglin.phosphoricons.regular.RepeatOnce
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.SlidersHorizontal
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.adamglin.phosphoricons.regular.SpeakerLow
import com.adamglin.phosphoricons.regular.SpeakerX
import com.adamglin.phosphoricons.regular.Timer
import dev.schlubbe.musicagent.desktop.data.key
import dev.schlubbe.musicagent.desktop.playback.RepeatMode
import kotlin.math.abs
import kotlin.math.sin

/**
 * Seek bar in the two Android "Wiedergabestil" variants: "waveform" (deterministic
 * pseudo-waveform per track) or "bars" (plain progress line). Click or drag to seek.
 */
@Composable
fun SeekBar(
    positionSec: Double,
    durationSec: Double?,
    seed: String,
    style: String,
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
    onSeek: (Double) -> Unit,
) {
    val c = C.c
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val dur = durationSec?.takeIf { it > 0 }
    val fraction = dragFraction ?: dur?.let { (positionSec / it).toFloat().coerceIn(0f, 1f) } ?: 0f
    val bars = remember(seed) {
        val h = seed.hashCode()
        FloatArray(90) { i -> (0.35f + 0.65f * abs(sin(i * 0.37f + h * 0.001f) * sin(i * 0.11f + h * 0.0007f + 1f))).coerceIn(0.15f, 1f) }
    }
    Canvas(
        modifier.fillMaxWidth().height(height)
            .pointerInput(dur) { detectTapGestures { o -> dur?.let { onSeek((o.x / size.width).coerceIn(0f, 1f) * it) } } }
            .pointerInput(dur) {
                detectDragGestures(
                    onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dur?.let { d -> dragFraction?.let { onSeek(it * d) } }; dragFraction = null },
                    onDragCancel = { dragFraction = null },
                ) { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
    ) {
        if (style == "waveform") {
            val w = size.width / bars.size
            bars.forEachIndexed { i, b ->
                val bh = size.height * b
                val played = (i + 0.5f) / bars.size <= fraction
                drawRoundRect(
                    if (played) c.accent2 else c.textFaint.copy(alpha = 0.35f),
                    Offset(i * w + w * 0.2f, (size.height - bh) / 2), Size(w * 0.6f, bh), CornerRadius(w),
                )
            }
        } else {
            val y = size.height / 2
            drawRoundRect(c.textFaint.copy(alpha = 0.3f), Offset(0f, y - 2), Size(size.width, 4f), CornerRadius(2f))
            drawRoundRect(c.accent2, Offset(0f, y - 2), Size(size.width * fraction, 4f), CornerRadius(2f))
            drawCircle(c.accent2, 6f, Offset(size.width * fraction, y))
        }
    }
}

@Composable
fun VolumeControl(modifier: Modifier = Modifier) {
    val g = LocalUi.current.graph
    val s by g.settings.state.collectAsState()
    var beforeMute by remember { mutableStateOf(0.8f) }
    val v = s.volume
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconBtn(
            when { v == 0f -> PhosphorIcons.Regular.SpeakerX; v < 0.5f -> PhosphorIcons.Regular.SpeakerLow; else -> PhosphorIcons.Regular.SpeakerHigh },
            if (v == 0f) "Ton an" else "Stumm",
        ) { if (v > 0f) { beforeMute = v; g.player.setVolume(0f) } else g.player.setVolume(beforeMute.coerceAtLeast(0.1f)) }
        val c = C.c
        Canvas(
            Modifier.width(110.dp).height(20.dp)
                .pointerInput(Unit) { detectTapGestures { g.player.setVolume(it.x / size.width) } }
                .pointerInput(Unit) { detectDragGestures { ch, _ -> g.player.setVolume(ch.position.x / size.width) } },
        ) {
            val y = size.height / 2
            drawRoundRect(c.textFaint.copy(alpha = 0.3f), Offset(0f, y - 2), Size(size.width, 4f), CornerRadius(2f))
            drawRoundRect(c.accent, Offset(0f, y - 2), Size(size.width * v, 4f), CornerRadius(2f))
            drawCircle(c.text, 6f, Offset(size.width * v, y))
        }
    }
}

@Composable
fun PlayPauseButton(size: Dp = 44.dp) {
    val g = LocalUi.current.graph
    val e by g.player.engineState.collectAsState()
    val q by g.player.state.collectAsState()
    val c = C.c
    Box(Modifier.size(size).clip(CircleShape).background(c.text).clickable { g.player.togglePlay() }, contentAlignment = Alignment.Center) {
        if (q.resolving || (e.buffering && e.playing)) CircularProgressIndicator(Modifier.size(size), color = c.accent2, strokeWidth = 2.dp)
        Icon(if (e.playing) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play, if (e.playing) "Pause" else "Abspielen", tint = c.bg, modifier = Modifier.size(size * 0.42f))
    }
}

@Composable
fun TransportControls(big: Boolean = false) {
    val g = LocalUi.current.graph
    val q by g.player.state.collectAsState()
    val c = C.c
    val s = if (big) 48.dp else 36.dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (big) 18.dp else 8.dp)) {
        IconBtn(PhosphorIcons.Regular.Shuffle, if (q.shuffle) "Zufallswiedergabe aus" else "Zufallswiedergabe an", tint = if (q.shuffle) c.accent2 else c.textMuted, size = s) { g.player.toggleShuffle() }
        IconBtn(PhosphorIcons.Fill.SkipBack, "Zurück", tint = c.text, size = s) { g.player.previous() }
        PlayPauseButton(if (big) 64.dp else 40.dp)
        IconBtn(PhosphorIcons.Fill.SkipForward, "Weiter", tint = c.text, size = s) { g.player.next() }
        IconBtn(
            if (q.repeat == RepeatMode.ONE) PhosphorIcons.Regular.RepeatOnce else PhosphorIcons.Regular.Repeat, q.repeat.label,
            tint = if (q.repeat != RepeatMode.OFF) c.accent2 else c.textMuted, size = s,
        ) { g.player.cycleRepeat() }
    }
}

@Composable
fun NowPlayingBar(modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val q by g.player.state.collectAsState()
    val e by g.player.engineState.collectAsState()
    val settings by g.settings.state.collectAsState()
    val data by g.store.data.collectAsState()
    val t = q.current
    Row(
        modifier.fillMaxWidth().height(88.dp).background(c.surface).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(0.3f).clickable(enabled = t != null) { ui.playerOpen = !ui.playerOpen }, verticalAlignment = Alignment.CenterVertically) {
            Cover(t?.thumbnailUrl, 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(t?.title ?: "Nichts läuft", style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(q.error ?: t?.artist ?: "Wähle einen Titel aus", style = MaterialTheme.typography.bodySmall, color = if (q.error != null) c.accent2 else c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = t?.artist != null) { t?.let(ui::goToArtist) })
            }
            if (t != null) {
                val liked = data.likes.any { it.track.key == t.key }
                IconBtn(if (liked) PhosphorIcons.Fill.Heart else PhosphorIcons.Regular.Heart, if (liked) "Gefällt mir nicht mehr" else "Gefällt mir", tint = if (liked) c.accent2 else c.textMuted) { ui.toggleLike(t) }
            }
        }
        Column(Modifier.weight(0.4f), horizontalAlignment = Alignment.CenterHorizontally) {
            TransportControls()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration(e.positionSec), style = MaterialTheme.typography.labelSmall, color = c.textFaint, modifier = Modifier.width(44.dp))
                SeekBar(e.positionSec, e.durationSec ?: t?.durationSec?.toDouble(), t?.key ?: "", settings.playerStyle, Modifier.weight(1f), height = 22.dp) { g.player.seek(it) }
                Text(formatDuration(e.durationSec ?: t?.durationSec?.toDouble()), style = MaterialTheme.typography.labelSmall, color = c.textFaint, modifier = Modifier.width(44.dp).padding(start = 8.dp))
            }
        }
        Row(Modifier.weight(0.3f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (q.sleepTimerEndAt != null) Icon(PhosphorIcons.Regular.Timer, "Sleep-Timer aktiv", tint = c.accent2, modifier = Modifier.size(18.dp).padding(end = 4.dp))
            IconBtn(PhosphorIcons.Regular.Microphone, "Songtext", tint = if (ui.panel == SidePanel.LYRICS) c.accent2 else c.textMuted) { ui.togglePanel(SidePanel.LYRICS) }
            IconBtn(PhosphorIcons.Regular.Queue, "Warteschlange", tint = if (ui.panel == SidePanel.QUEUE) c.accent2 else c.textMuted) { ui.togglePanel(SidePanel.QUEUE) }
            IconBtn(PhosphorIcons.Regular.SlidersHorizontal, "Equalizer", tint = if (settings.eq.enabled && settings.eq.name != "Flach") c.accent else c.textMuted) { ui.navigate(Route.Equalizer) }
            VolumeControl()
            IconBtn(PhosphorIcons.Regular.ArrowsOutSimple, "Player öffnen", enabled = t != null) { ui.playerOpen = !ui.playerOpen }
        }
    }
}
