package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.DotsSixVertical
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.ListPlus
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.MusicNotes
import com.adamglin.phosphoricons.regular.PencilSimple
import com.adamglin.phosphoricons.regular.Playlist
import com.adamglin.phosphoricons.regular.ShareNetwork
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.Waveform
import com.adamglin.phosphoricons.regular.X
import dev.schlubbe.musicagent.desktop.data.key
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.EmptyState
import dev.schlubbe.musicagent.desktop.ui.IconBtn
import dev.schlubbe.musicagent.desktop.ui.LocalUi
import dev.schlubbe.musicagent.desktop.ui.Pill
import dev.schlubbe.musicagent.desktop.ui.PrimaryButton
import dev.schlubbe.musicagent.desktop.ui.Route
import dev.schlubbe.musicagent.desktop.ui.TrackRow
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun PlaylistScreen(id: String) {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val data by g.store.data.collectAsState()
    val p = data.playlists.firstOrNull { it.id == id }
    if (p == null) {
        EmptyState(PhosphorIcons.Regular.Playlist, "Playlist nicht gefunden", "Diese Playlist wurde gelöscht.", action = "Zur Bibliothek") {
            ui.navigateRoot(Route.Library(dev.schlubbe.musicagent.desktop.ui.LibraryTab.PLAYLISTS))
        }
        return
    }
    val tracks = p.tracks.map { it.track }
    val playable = tracks.filterNot { it.isDrmProtected }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    // Drag-and-drop reordering state (handle on each row).
    val listState = rememberLazyListState()
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val currentTracks by rememberUpdatedState(tracks)

    fun onDrag(dy: Float) {
        val key = dragKey ?: return
        dragOffset += dy
        val info = listState.layoutInfo.visibleItemsInfo
        val cur = info.firstOrNull { it.key == key } ?: return
        val center = cur.offset + dragOffset + cur.size / 2f
        val target = info.firstOrNull { it.key != key && it.key is String && (it.key as String).startsWith("t:") && center >= it.offset && center <= it.offset + it.size }
            ?: return
        val from = currentTracks.indexOfFirst { "t:${it.key}" == key }
        val to = currentTracks.indexOfFirst { "t:${it.key}" == target.key }
        if (from < 0 || to < 0) return
        g.store.movePlaylistTrack(p.id, from, to)
        dragOffset += cur.offset - target.offset
    }

    fun move(i: Int, delta: Int) = stepTarget(i, delta, tracks.size)?.let { g.store.movePlaylistTrack(p.id, i, it) }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
        item(key = "header") {
            DetailHeader(fileModel(p.coverPath) ?: tracks.firstOrNull()?.thumbnailUrl, "Playlist", p.name, accentFor(p.accentColorKey, c)) {
                p.description?.takeIf { it.isNotBlank() }?.let { ExpandableText(it, Modifier.widthIn(max = 720.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(trackCountLabel(tracks.size, totalDurationSec(tracks)), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    p.moodTags.forEach { m -> PlaylistMoods.firstOrNull { it.first == m }?.let { Pill(it.second, false) { editing = true } } }
                }
            }
        }
        item(key = "actions") {
            ActionBar {
                if (playable.isNotEmpty()) {
                    PrimaryButton("Abspielen", PhosphorIcons.Fill.Play) { g.player.playQueue(playable, 0) }
                    PrimaryButton("Zufall", PhosphorIcons.Regular.Shuffle, accent = false) { g.player.playQueue(playable, 0, shuffle = true) }
                }
                PrimaryButton("Bearbeiten", PhosphorIcons.Regular.PencilSimple, accent = false) { editing = true }
                Spacer(Modifier.weight(1f))
                if (playable.isNotEmpty()) {
                    IconBtn(PhosphorIcons.Regular.ListPlus, "Alle zur Warteschlange hinzufügen", tint = c.text) {
                        g.player.addToQueue(playable); ui.toast("${playable.size} Titel zur Warteschlange hinzugefügt")
                    }
                    IconBtn(PhosphorIcons.Regular.DownloadSimple, "Alle herunterladen", tint = c.text) { ui.download(playable) }
                    IconBtn(PhosphorIcons.Regular.Waveform, "Übergänge analysieren", tint = c.text) { ui.analyze(playable) }
                }
                IconBtn(PhosphorIcons.Regular.ShareNetwork, "Teilen (Titelliste kopieren)", tint = c.text) {
                    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(playlistShareText(p)), null) }
                    ui.toast("Titelliste kopiert")
                }
                IconBtn(PhosphorIcons.Regular.Trash, "Playlist löschen", tint = c.text) { deleting = true }
            }
        }
        if (tracks.isEmpty()) item(key = "empty") {
            EmptyState(
                PhosphorIcons.Regular.MusicNotes, "Noch leer",
                "Füge Titel per Rechtsklick → „Zu Playlist hinzufügen …“ hinzu.", action = "Musik suchen",
            ) { ui.navigateRoot(Route.Search()) }
        } else item(key = "hint") {
            Text(
                "Ziehe Titel am Griff, um die Reihenfolge zu ändern · Doppelklick spielt",
                style = MaterialTheme.typography.bodySmall, color = c.textFaint, modifier = Modifier.padding(start = PagePad, bottom = 6.dp),
            )
        }
        itemsIndexed(tracks, key = { _, t -> "t:${t.key}" }) { i, t ->
            val itemKey = "t:${t.key}"
            val dragging = dragKey == itemKey
            Box(
                Modifier.padding(horizontal = PagePad - 10.dp)
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                    .then(if (dragging) Modifier.shadow(12.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(c.surface) else Modifier),
            ) {
                TrackRow(
                    t, index = i, queueContext = playable, highlighted = dragging,
                    extraMenu = buildList {
                        if (i > 0) add(ContextMenuItem("Nach oben") { move(i, -1) })
                        if (i < tracks.lastIndex) add(ContextMenuItem("Nach unten") { move(i, 1) })
                        add(ContextMenuItem("Aus Playlist entfernen") { g.store.removeFromPlaylist(p.id, i); ui.toast("Aus Playlist entfernt") })
                    },
                    trailing = {
                        IconBtn(PhosphorIcons.Regular.X, "Aus Playlist entfernen", size = 30.dp, iconSize = 15.dp) {
                            g.store.removeFromPlaylist(p.id, i); ui.toast("Aus Playlist entfernt")
                        }
                        Box(
                            Modifier.size(30.dp).pointerHoverIcon(PointerIcon.Hand).pointerInput(itemKey) {
                                detectDragGestures(
                                    onDragStart = { dragKey = itemKey; dragOffset = 0f },
                                    onDragEnd = { dragKey = null; dragOffset = 0f },
                                    onDragCancel = { dragKey = null; dragOffset = 0f },
                                    onDrag = { change, amount -> change.consume(); onDrag(amount.y) },
                                )
                            },
                            contentAlignment = Alignment.Center,
                        ) { Icon(PhosphorIcons.Regular.DotsSixVertical, "Ziehen zum Verschieben", tint = if (dragging) c.accent2 else c.textFaint, modifier = Modifier.size(18.dp)) }
                    },
                )
            }
        }
    }

    if (editing) PlaylistEditDialog(p, g.dataDir, onSave = { edited -> g.store.updatePlaylist(p.id) { edited.copy(tracks = it.tracks) } }) { editing = false }
    if (deleting) ConfirmDialog("Playlist löschen?", "„${p.name}“ wird endgültig gelöscht.", "Löschen", {
        g.store.deletePlaylist(p.id); ui.toast("Playlist gelöscht"); ui.back()
    }) { deleting = false }
}
