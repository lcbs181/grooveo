package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.ArrowClockwise
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.FolderOpen
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.Pause
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.SortAscending
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.Waveform
import com.adamglin.phosphoricons.regular.X
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.desktop.data.DownloadRecord
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.Cover
import dev.schlubbe.musicagent.desktop.ui.EmptyState
import dev.schlubbe.musicagent.desktop.ui.IconBtn
import dev.schlubbe.musicagent.desktop.ui.LocalUi
import dev.schlubbe.musicagent.desktop.ui.Panel
import dev.schlubbe.musicagent.desktop.ui.Pill
import dev.schlubbe.musicagent.desktop.ui.PrimaryButton
import dev.schlubbe.musicagent.desktop.ui.TrackRow
import dev.schlubbe.musicagent.desktop.ui.formatBytes
import java.awt.Desktop
import java.io.File

enum class DownloadSort(val label: String) {
    RECENT("Zuletzt heruntergeladen"), TITLE("Titel A–Z"), ARTIST("Künstler A–Z"), SIZE("Größte zuerst"),
}

fun downloadStateLabel(s: DownloadState): String = when (s) {
    DownloadState.QUEUED -> "Warteschlange"
    DownloadState.DOWNLOADING -> "Lädt"
    DownloadState.PAUSED -> "Angehalten"
    DownloadState.FAILED -> "Fehlgeschlagen"
    DownloadState.COMPLETED -> "Fertig"
    else -> s.name
}

fun downloadSize(r: DownloadRecord): Long = r.totalBytes ?: r.bytesDownloaded

/** Search (title/artist/album, case-insensitive), optional state filter and sort. */
fun filterSortDownloads(list: List<DownloadRecord>, query: String, state: DownloadState?, sort: DownloadSort): List<DownloadRecord> {
    val q = query.trim().lowercase()
    val filtered = list.filter { r ->
        (state == null || r.state == state) &&
            (q.isEmpty() || listOfNotNull(r.track.title, r.track.artist, r.track.album).any { it.lowercase().contains(q) })
    }
    return when (sort) {
        DownloadSort.RECENT -> filtered.sortedByDescending { it.createdAt }
        DownloadSort.TITLE -> filtered.sortedBy { it.track.title.lowercase() }
        DownloadSort.ARTIST -> filtered.sortedWith(compareBy({ it.track.artist?.lowercase() ?: "￿" }, { it.track.title.lowercase() }))
        DownloadSort.SIZE -> filtered.sortedByDescending { downloadSize(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen() {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val data by g.store.data.collectAsState()
    val settings by g.settings.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(DownloadSort.RECENT) }
    var stateFilter by remember { mutableStateOf<DownloadState?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<DownloadRecord?>(null) }
    val all = data.downloads
    val visible = filterSortDownloads(all, query, stateFilter, sort)
    val completed = visible.filter { it.state == DownloadState.COMPLETED }.map { it.track }
    val allCompleted = all.filter { it.state == DownloadState.COMPLETED }.map { it.track }
    val active = all.any { it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING }
    val resumable = all.any { it.state == DownloadState.PAUSED || it.state == DownloadState.FAILED }
    val used = remember(data.downloads) { g.downloads.totalBytes() }
    val free = remember(settings.downloadDir) { g.downloadDir().let { d -> generateSequence(d) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0L } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Downloads", style = MaterialTheme.typography.displayMedium, color = c.text)
                    Text("${allCompleted.size} Titel offline verfügbar · ${formatBytes(used)}", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                }
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (allCompleted.isNotEmpty()) {
                    PrimaryButton("Alle abspielen", PhosphorIcons.Fill.Play) { g.player.playQueue(completed.ifEmpty { allCompleted }) }
                    PrimaryButton("Zufällig", PhosphorIcons.Regular.Shuffle, accent = false) { g.player.playQueue(completed.ifEmpty { allCompleted }, shuffle = true) }
                }
                if (active) PrimaryButton("Alle pausieren", PhosphorIcons.Regular.Pause, accent = false) { g.downloads.pauseAll() }
                if (resumable) PrimaryButton("Alle fortsetzen", PhosphorIcons.Regular.ArrowClockwise, accent = false) { g.downloads.resumeAll() }
                if (allCompleted.isNotEmpty()) PrimaryButton("Alle analysieren", PhosphorIcons.Regular.Waveform, accent = false) { ui.analyze(allCompleted) }
                PrimaryButton("Ordner öffnen", PhosphorIcons.Regular.FolderOpen, accent = false) {
                    val dir = g.downloadDir().apply { mkdirs() }
                    runCatching { Desktop.getDesktop().open(dir) }.onFailure {
                        runCatching { ProcessBuilder("xdg-open", dir.path).start() }.onFailure { ui.toast("Ordner konnte nicht geöffnet werden") }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Panel(Modifier.weight(1f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Speicher", style = MaterialTheme.typography.titleMedium, color = c.text)
                        val frac = if (used + free > 0) (used.toDouble() / (used + free)).toFloat().coerceIn(0f, 1f) else 0f
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.surfaceHigh)) {
                            Box(Modifier.fillMaxWidth(maxOf(frac, if (used > 0) 0.01f else 0f)).fillMaxHeight().background(c.accent))
                        }
                        Text("${formatBytes(used)} von Grooveo belegt · ${formatBytes(free)} frei", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        Text(g.downloadDir().path, style = MaterialTheme.typography.labelSmall, color = c.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Panel(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Datensparmodus", style = MaterialTheme.typography.titleMedium, color = c.text)
                            Text("Nur heruntergeladene Titel abspielen", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        }
                        Switch(settings.dataSaverMode, { v -> g.settings.update { it.copy(dataSaverMode = v) } }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (all.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    query, { query = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("In Downloads suchen") },
                    leadingIcon = { Icon(PhosphorIcons.Regular.MagnifyingGlass, null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = { if (query.isNotEmpty()) IconBtn(PhosphorIcons.Regular.X, "Leeren", size = 30.dp, iconSize = 14.dp) { query = "" } },
                    shape = RoundedCornerShape(50),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.divider, focusedTextColor = c.text, unfocusedTextColor = c.text),
                )
                Box {
                    PrimaryButton(sort.label, PhosphorIcons.Regular.SortAscending, accent = false) { sortMenu = true }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        DownloadSort.entries.forEach { s -> DropdownMenuItem({ Text(s.label) }, { sort = s; sortMenu = false }) }
                    }
                }
            }
            if (all.isNotEmpty()) FlowRow(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Alle (${all.size})", stateFilter == null) { stateFilter = null }
                listOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.PAUSED, DownloadState.FAILED, DownloadState.COMPLETED).forEach { s ->
                    val n = all.count { it.state == s }
                    if (n > 0 || stateFilter == s) Pill("${downloadStateLabel(s)} ($n)", stateFilter == s) { stateFilter = if (stateFilter == s) null else s }
                }
            }
        }
        if (all.isEmpty()) item {
            EmptyState(PhosphorIcons.Regular.DownloadSimple, "Noch keine Downloads", "Lade Titel über das Kontextmenü herunter, um sie offline zu hören.")
        } else if (visible.isEmpty()) item {
            EmptyState(PhosphorIcons.Regular.MagnifyingGlass, "Keine Treffer", "Kein Download passt zu deiner Suche.")
        }
        items(visible, key = { it.track.source + ":" + it.track.sourceId }) { r ->
            if (r.state == DownloadState.COMPLETED) {
                TrackRow(
                    r.track, queueContext = completed,
                    subtitle = listOfNotNull(r.track.artist, formatBytes(downloadSize(r).takeIf { it > 0 } ?: r.filePath?.let { File(it).length() } ?: 0L)).joinToString(" · "),
                    extraMenu = listOf(ContextMenuItem("Download entfernen …") { confirmRemove = r }),
                    trailing = { IconBtn(PhosphorIcons.Regular.Trash, "Download entfernen", size = 30.dp, iconSize = 16.dp) { confirmRemove = r } },
                )
            } else PendingDownloadRow(r) { confirmRemove = r }
        }
    }

    confirmRemove?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Download entfernen?") },
            text = { Text("„${r.track.title}“ wird von diesem Gerät gelöscht. Du kannst den Titel weiterhin streamen.") },
            confirmButton = { TextButton({ g.downloads.remove(r.track.source + ":" + r.track.sourceId); confirmRemove = null; ui.toast("Download entfernt") }) { Text("Entfernen", color = c.accent2) } },
            dismissButton = { TextButton({ confirmRemove = null }) { Text("Abbrechen") } },
            containerColor = c.surface,
        )
    }
}

@Composable
private fun PendingDownloadRow(r: DownloadRecord, onRemove: () -> Unit) {
    val g = LocalUi.current.graph
    val c = C.c
    val key = r.track.source + ":" + r.track.sourceId
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(r.track.thumbnailUrl, 44.dp, radius = 6.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.track.title, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val chipColor = when (r.state) { DownloadState.FAILED -> c.accent2; DownloadState.DOWNLOADING -> c.accent; else -> c.textMuted }
                Text(
                    downloadStateLabel(r.state), style = MaterialTheme.typography.labelSmall, color = chipColor,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(chipColor.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Text(
                    when (r.state) {
                        DownloadState.FAILED -> r.error ?: "Download fehlgeschlagen"
                        DownloadState.DOWNLOADING, DownloadState.PAUSED -> "${r.progressPct} %" + (r.totalBytes?.let { " · ${formatBytes(r.bytesDownloaded)} von ${formatBytes(it)}" } ?: "")
                        else -> r.track.artist ?: ""
                    },
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (r.state == DownloadState.DOWNLOADING || r.state == DownloadState.PAUSED) LinearProgressIndicator(
                progress = { r.progressPct / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = if (r.state == DownloadState.PAUSED) c.textFaint else c.accent, trackColor = c.surfaceHigh,
            )
            else if (r.state == DownloadState.QUEUED) LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = c.accent.copy(alpha = 0.5f), trackColor = c.surfaceHigh)
        }
        Spacer(Modifier.width(12.dp))
        when (r.state) {
            DownloadState.DOWNLOADING, DownloadState.QUEUED -> TextButton({ g.downloads.pause(key) }) { Text("Pausieren", color = c.text) }
            DownloadState.PAUSED -> TextButton({ g.downloads.resume(key) }) { Text("Fortsetzen", color = c.accent) }
            DownloadState.FAILED -> TextButton({ g.downloads.resume(key) }) { Text("Erneut versuchen", color = c.accent2) }
            else -> {}
        }
        IconBtn(PhosphorIcons.Regular.X, "Download entfernen", size = 30.dp, iconSize = 16.dp, tint = c.textMuted, onClick = onRemove)
    }
}
