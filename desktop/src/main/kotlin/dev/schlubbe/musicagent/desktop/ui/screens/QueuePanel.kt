package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.DotsSixVertical
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.X
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.desktop.data.key
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.Cover
import dev.schlubbe.musicagent.desktop.ui.IconBtn
import dev.schlubbe.musicagent.desktop.ui.LocalUi
import dev.schlubbe.musicagent.desktop.ui.SidePanel
import dev.schlubbe.musicagent.desktop.ui.formatDuration
import kotlin.math.roundToInt

private val ROW_HEIGHT = 56.dp

/** Target slot of a drag that started at [from] and moved [offsetPx] with rows of [rowPx]. */
fun queueDragTarget(from: Int, offsetPx: Float, rowPx: Float, count: Int): Int =
    if (count <= 0 || rowPx <= 0f) from else (from + (offsetPx / rowPx).roundToInt()).coerceIn(0, count - 1)

/** Unique, stable list keys even if a track appears twice. */
fun queueKeys(tracks: List<TrackResultDto>): List<String> {
    val seen = HashMap<String, Int>()
    return tracks.map { t -> val n = seen.merge(t.key, 1, Int::plus)!!; if (n == 1) t.key else "${t.key}#$n" }
}

@Composable
fun QueuePanel(modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val q by g.player.state.collectAsState()
    val upNext = q.upNext
    val base = if (q.index < 0) 0 else q.index + 1 // absolute queue index of upNext[0]
    val history = if (q.index > 0) q.queue.take(q.index) else emptyList()
    var historyOpen by remember { mutableStateOf(false) }
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val target = dragFrom?.let { queueDragTarget(it, dragOffset, rowPx, upNext.size) }
    val keys = queueKeys(upNext)

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Warteschlange", style = MaterialTheme.typography.titleLarge, color = c.text)
                if (upNext.isNotEmpty()) Text(
                    "${upNext.size} Titel · ${formatDuration(upNext.sumOf { it.durationSec ?: 0 })}",
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                )
            }
            if (upNext.isNotEmpty()) TextButton({ g.player.clearUpNext(); ui.toast("Warteschlange geleert") }) { Text("Leeren", color = c.accent2) }
            IconBtn(PhosphorIcons.Regular.X, "Schließen") { ui.togglePanel(SidePanel.QUEUE) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
            q.current?.let { cur ->
                item(key = "now-header") { QueueLabel("Läuft gerade") }
                item(key = "now") {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.accentSoft).clickable { ui.playerOpen = true }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(cur.thumbnailUrl, 52.dp, radius = 8.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cur.title, style = MaterialTheme.typography.titleSmall, color = c.accentStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(cur.artist ?: "", style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item(key = "next-header") { QueueLabel("Als Nächstes") }
            if (upNext.isEmpty()) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(PhosphorIcons.Regular.Queue, null, tint = c.textFaint, modifier = Modifier.size(28.dp))
                    Text(
                        if (g.settings.current.autoplayRadio) "Danach geht es mit ähnlichen Titeln weiter." else "Die Warteschlange ist leer.",
                        style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                    )
                }
            }
            itemsIndexed(upNext, key = { i, _ -> keys[i] }) { i, t ->
                val from = dragFrom
                val shift = when {
                    from == null || target == null || i == from -> 0f
                    from < target && i in (from + 1)..target -> -rowPx
                    from > target && i in target until from -> rowPx
                    else -> 0f
                }
                val animShift by animateFloatAsState(shift)
                val dragging = i == from
                QueueRow(
                    t,
                    Modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer { translationY = if (dragging) dragOffset else animShift },
                    dragging = dragging,
                    onPlay = { g.player.skipTo(base + i) },
                    onRemove = { g.player.removeFromQueue(base + i) },
                    menu = {
                        buildList {
                            add(ContextMenuItem("Jetzt abspielen") { g.player.skipTo(base + i) })
                            if (i > 0) add(ContextMenuItem("Als Nächstes abspielen") { g.player.moveInQueue(base + i, base) })
                            if (i > 0) add(ContextMenuItem("Nach oben") { g.player.moveInQueue(base + i, base + i - 1) })
                            if (i < upNext.size - 1) add(ContextMenuItem("Nach unten") { g.player.moveInQueue(base + i, base + i + 1) })
                            add(ContextMenuItem("Aus Warteschlange entfernen") { g.player.removeFromQueue(base + i) })
                            if (!t.artist.isNullOrBlank()) add(ContextMenuItem("Zum Künstler") { ui.goToArtist(t) })
                        }
                    },
                    dragHandle = Modifier.pointerInput(i, upNext.size) {
                        detectDragGestures(
                            onDragStart = { dragFrom = i; dragOffset = 0f },
                            onDragEnd = {
                                val f = dragFrom; val to = f?.let { queueDragTarget(it, dragOffset, rowPx, upNext.size) }
                                if (f != null && to != null && to != f) g.player.moveInQueue(base + f, base + to)
                                dragFrom = null; dragOffset = 0f
                            },
                            onDragCancel = { dragFrom = null; dragOffset = 0f },
                        ) { ch, d -> ch.consume(); dragOffset += d.y }
                    },
                )
            }
            if (history.isNotEmpty()) {
                item(key = "hist-header") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(8.dp)).clickable { historyOpen = !historyOpen }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (historyOpen) PhosphorIcons.Regular.CaretDown else PhosphorIcons.Regular.CaretRight, null, tint = c.textMuted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Zuletzt gespielt (${history.size})", style = MaterialTheme.typography.labelLarge, color = c.textMuted)
                    }
                }
                if (historyOpen) itemsIndexed(history.reversed(), key = { i, t -> "h$i-${t.key}" }) { i, t ->
                    val abs = q.index - 1 - i
                    QueueRow(t, Modifier.graphicsLayer { alpha = 0.7f }, dragging = false, onPlay = { g.player.skipTo(abs) }, onRemove = { g.player.removeFromQueue(abs) },
                        menu = { listOf(ContextMenuItem("Erneut abspielen") { g.player.skipTo(abs) }, ContextMenuItem("Aus Warteschlange entfernen") { g.player.removeFromQueue(abs) }) },
                        dragHandle = null)
                }
            }
        }
        Text(
            "Doppelklick spielt ab · Ziehen zum Sortieren", style = MaterialTheme.typography.labelSmall, color = c.textFaint,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun QueueLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = C.c.textFaint, modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 6.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(
    t: TrackResultDto,
    modifier: Modifier,
    dragging: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    menu: () -> List<ContextMenuItem>,
    dragHandle: Modifier?,
) {
    val c = C.c
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(modifier) {
        ContextMenuArea(items = menu) {
            Row(
                Modifier.fillMaxWidth().height(ROW_HEIGHT)
                    .then(if (dragging) Modifier.shadow(8.dp, RoundedCornerShape(10.dp)) else Modifier)
                    .clip(RoundedCornerShape(10.dp)).hoverable(hover)
                    .background(if (dragging) c.surfaceHigh else if (hovered) c.surfaceHigh else Color.Transparent)
                    .combinedClickable(onClick = {}, onDoubleClick = onPlay)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dragHandle != null) Box(
                    dragHandle.size(width = 20.dp, height = ROW_HEIGHT).pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center,
                ) { Icon(PhosphorIcons.Regular.DotsSixVertical, "Ziehen zum Sortieren", tint = if (hovered || dragging) c.textMuted else c.textFaint.copy(alpha = 0.4f), modifier = Modifier.size(16.dp)) }
                else Spacer(Modifier.width(20.dp))
                Spacer(Modifier.width(4.dp))
                Cover(t.thumbnailUrl, 40.dp, radius = 6.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist ?: "", style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (hovered && !dragging) IconBtn(PhosphorIcons.Regular.X, "Aus Warteschlange entfernen", size = 28.dp, iconSize = 14.dp, onClick = onRemove)
                else Text(formatDuration(t.durationSec), style = MaterialTheme.typography.labelSmall, color = c.textFaint, modifier = Modifier.padding(end = 6.dp))
            }
        }
    }
}
