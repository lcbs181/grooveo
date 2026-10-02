package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.BookmarkSimple
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.ClockCounterClockwise
import com.adamglin.phosphoricons.regular.CloudSlash
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.Heart
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.Playlist
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.SortAscending
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.Users
import com.adamglin.phosphoricons.regular.X
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.desktop.data.LibraryData
import dev.schlubbe.musicagent.desktop.data.Playlist
import dev.schlubbe.musicagent.desktop.data.key
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.CardTile
import dev.schlubbe.musicagent.desktop.ui.EmptyState
import dev.schlubbe.musicagent.desktop.ui.IconBtn
import dev.schlubbe.musicagent.desktop.ui.LibraryTab
import dev.schlubbe.musicagent.desktop.ui.LocalUi
import dev.schlubbe.musicagent.desktop.ui.Pill
import dev.schlubbe.musicagent.desktop.ui.PrimaryButton
import dev.schlubbe.musicagent.desktop.ui.Route
import dev.schlubbe.musicagent.desktop.ui.SectionHeader
import dev.schlubbe.musicagent.desktop.ui.TrackRow
import dev.schlubbe.musicagent.desktop.ui.sourceLabel

/** UI-state that survives navigating into a playlist and back. */
private object LibraryMemory {
    var query = ""
    var sort = LikeSort.RECENT
    var offlineOnly = false
}

@Composable
fun LibraryScreen(tab: LibraryTab) {
    val ui = LocalUi.current
    val c = C.c
    val data by ui.graph.store.data.collectAsState()
    var current by remember(tab) { mutableStateOf(tab) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = PagePad, end = PagePad, top = 24.dp)) {
            Text("Bibliothek", style = MaterialTheme.typography.displayMedium, color = c.text)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryTab.entries.forEach { t ->
                    val count = when (t) {
                        LibraryTab.LIKES -> data.likes.size
                        LibraryTab.PLAYLISTS -> data.playlists.size + data.savedPlaylists.size
                        LibraryTab.FOLLOWING -> data.followed.size
                        LibraryTab.HISTORY -> data.history.size
                    }
                    Pill(if (count > 0) "${t.label}  $count" else t.label, current == t) { current = t }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (current) {
                LibraryTab.LIKES -> LikesTab(data)
                LibraryTab.PLAYLISTS -> PlaylistsTab(data)
                LibraryTab.FOLLOWING -> FollowingTab(data)
                LibraryTab.HISTORY -> HistoryTab(data)
            }
        }
    }
}

// ---------------------------------------------------------------- Favoriten

@Composable
private fun LikesTab(data: LibraryData) {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    var query by remember { mutableStateOf(LibraryMemory.query) }
    var sort by remember { mutableStateOf(LibraryMemory.sort) }
    var offlineOnly by remember { mutableStateOf(LibraryMemory.offlineOnly) }
    LibraryMemory.query = query; LibraryMemory.sort = sort; LibraryMemory.offlineOnly = offlineOnly
    val offlineKeys = remember(data.downloads) { data.downloads.filter { it.state == DownloadState.COMPLETED }.map { it.track.key }.toSet() }
    val likes = remember(data.likes, query, sort, offlineOnly, offlineKeys) { filterLikes(data.likes, query, sort, offlineOnly, offlineKeys) }
    val tracks = likes.map { it.track }
    val playable = tracks.filterNot { it.isDrmProtected }

    if (data.likes.isEmpty()) {
        EmptyState(PhosphorIcons.Regular.Heart, "Noch keine Favoriten", "Tippe bei einem Titel aufs Herz oder wähle per Rechtsklick „Gefällt mir“.", action = "Musik suchen") {
            ui.navigateRoot(Route.Search())
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = PagePad - 10.dp, end = PagePad - 10.dp, bottom = 40.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Abspielen", PhosphorIcons.Fill.Play) { if (playable.isNotEmpty()) g.player.playQueue(playable, 0) }
                PrimaryButton("Zufall", PhosphorIcons.Regular.Shuffle, accent = false) { if (playable.isNotEmpty()) g.player.playQueue(playable, 0, shuffle = true) }
                IconBtn(PhosphorIcons.Regular.DownloadSimple, "Alle Favoriten herunterladen", tint = c.text) {
                    val missing = data.likes.map { it.track }.filter { it.key !in offlineKeys && !it.isDrmProtected }
                    if (missing.isEmpty()) ui.toast("Alle Favoriten sind bereits offline verfügbar") else ui.download(missing)
                }
                Spacer(Modifier.weight(1f))
                InlineSearch(query, "In Favoriten suchen") { query = it }
                SortMenu(sort) { sort = it }
                Pill("Nur offline", offlineOnly, icon = PhosphorIcons.Regular.CloudSlash) { offlineOnly = !offlineOnly }
            }
        }
        item {
            Text(
                trackCountLabel(likes.size, totalDurationSec(tracks)), style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                modifier = Modifier.padding(start = 10.dp, bottom = 8.dp),
            )
        }
        if (likes.isEmpty()) item {
            EmptyState(PhosphorIcons.Regular.MagnifyingGlass, "Keine Treffer", if (offlineOnly) "Keine passenden Favoriten offline verfügbar." else "Keine Favoriten passen zu „$query“.")
        }
        itemsIndexed(likes, key = { _, l -> l.track.key }) { i, l ->
            TrackRow(l.track, index = i, queueContext = playable, subtitle = listOfNotNull(l.track.artist, sourceLabel(l.track.source), relativeTime(l.createdAt)).joinToString(" · "))
        }
    }
}

@Composable
private fun InlineSearch(value: String, placeholder: String, onChange: (String) -> Unit) {
    val c = C.c
    Row(
        Modifier.width(260.dp).height(38.dp).clip(RoundedCornerShape(50)).background(c.surfaceHigh).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PhosphorIcons.Regular.MagnifyingGlass, null, tint = c.textMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = c.textFaint, maxLines = 1)
            BasicTextField(value, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) IconBtn(PhosphorIcons.Regular.X, "Leeren", size = 28.dp, iconSize = 14.dp) { onChange("") }
    }
}

@Composable
private fun SortMenu(sort: LikeSort, onSort: (LikeSort) -> Unit) {
    val c = C.c
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(c.surfaceHigh).clickable { open = true }.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(PhosphorIcons.Regular.SortAscending, null, tint = c.text, modifier = Modifier.size(15.dp))
            Text(sort.label, style = MaterialTheme.typography.labelLarge, color = c.text)
            Icon(PhosphorIcons.Regular.CaretDown, null, tint = c.textMuted, modifier = Modifier.size(12.dp))
        }
        DropdownMenu(open, { open = false }, containerColor = c.surface) {
            LikeSort.entries.forEach { s ->
                DropdownMenuItem({ Text(s.label, color = if (s == sort) c.accent else c.text) }, onClick = { onSort(s); open = false })
            }
        }
    }
}

// ---------------------------------------------------------------- Playlists

@Composable
private fun PlaylistsTab(data: LibraryData) {
    val ui = LocalUi.current
    val g = ui.graph
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }

    LazyVerticalGrid(
        GridCells.Adaptive(184.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = PagePad - 8.dp, end = PagePad, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Deine Playlists", Modifier.padding(start = 8.dp)) }
        item { NewPlaylistTile { creating = true } }
        items(data.playlists, key = { it.id }) { p ->
            val cover = fileModel(p.coverPath) ?: p.tracks.firstOrNull()?.track?.thumbnailUrl
            ContextMenuArea(items = {
                listOf(
                    ContextMenuItem("Öffnen") { ui.navigate(Route.Playlist(p.id)) },
                    ContextMenuItem("Abspielen") { p.tracks.map { it.track }.takeIf { it.isNotEmpty() }?.let { g.player.playQueue(it, 0) } },
                    ContextMenuItem("Zufällig abspielen") { p.tracks.map { it.track }.takeIf { it.isNotEmpty() }?.let { g.player.playQueue(it, 0, shuffle = true) } },
                    ContextMenuItem("Zur Warteschlange hinzufügen") { g.player.addToQueue(p.tracks.map { it.track }); ui.toast("Zur Warteschlange hinzugefügt") },
                    ContextMenuItem("Löschen …") { deleting = p },
                )
            }) {
                CardTile(
                    p.name, trackCountLabel(p.tracks.size, 0), cover, width = 184.dp,
                    onPlay = if (p.tracks.isNotEmpty()) ({ g.player.playQueue(p.tracks.map { it.track }, 0) }) else null,
                ) { ui.navigate(Route.Playlist(p.id)) }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Gespeichert", Modifier.padding(start = 8.dp), subtitle = "Playlists und Alben von SoundCloud und YouTube Music") }
        if (data.savedPlaylists.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState(PhosphorIcons.Regular.BookmarkSimple, "Nichts gespeichert", "Öffne eine Playlist oder ein Album aus der Suche und klicke auf „Speichern“.")
        }
        items(data.savedPlaylists, key = { "saved:${it.source}:${it.sourceId}" }) { s ->
            val open = { ui.navigate(Route.RemotePlaylist(s.source, s.sourceId, s.isAlbum, s.title, s.thumbnailUrl)) }
            ContextMenuArea(items = {
                listOf(
                    ContextMenuItem("Öffnen", open),
                    ContextMenuItem("Aus Bibliothek entfernen") { g.store.toggleSaved(s); ui.toast("Aus Bibliothek entfernt") },
                    ContextMenuItem("Link kopieren (Teilen)") { ui.copyLink(s.webpageUrl) },
                )
            }) {
                CardTile(
                    s.title, listOfNotNull(if (s.isAlbum) "Album" else "Playlist", s.owner).joinToString(" · "), s.thumbnailUrl,
                    width = 184.dp, badge = sourceLabel(s.source), onClick = open,
                )
            }
        }
    }
    if (creating) NameDialog("Neue Playlist", onConfirm = { name ->
        val p = g.store.createPlaylist(name)
        ui.navigate(Route.Playlist(p.id))
    }, onDismiss = { creating = false })
    deleting?.let { p ->
        ConfirmDialog("Playlist löschen?", "„${p.name}“ wird endgültig gelöscht.", "Löschen", { g.store.deletePlaylist(p.id); ui.toast("Playlist gelöscht") }) { deleting = null }
    }
}

@Composable
private fun NewPlaylistTile(onClick: () -> Unit) {
    val c = C.c
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(Modifier.width(184.dp).clip(RoundedCornerShape(12.dp)).hoverable(hover).background(if (hovered) c.surfaceHigh else Color.Transparent).clickable(onClick = onClick).padding(8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).border(2.dp, if (hovered) c.accent else c.divider, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(PhosphorIcons.Regular.Plus, null, tint = if (hovered) c.accent else c.textMuted, modifier = Modifier.size(40.dp)) }
        Spacer(Modifier.height(8.dp))
        Text("Neue Playlist", style = MaterialTheme.typography.titleSmall, color = c.text)
        Text("Erstellen", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
    }
}

// ---------------------------------------------------------------- Folge ich

@Composable
private fun FollowingTab(data: LibraryData) {
    val ui = LocalUi.current
    if (data.followed.isEmpty()) {
        EmptyState(PhosphorIcons.Regular.Users, "Du folgst noch niemandem", "Folge Künstlern auf ihrer Seite, um hier schnell zu ihnen zu kommen.")
        return
    }
    LazyVerticalGrid(
        GridCells.Adaptive(172.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = PagePad - 8.dp, end = PagePad, top = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(data.followed, key = { "${it.source}:${it.sourceId}" }) { a ->
            val open = { ui.navigate(Route.Artist(a.source, a.sourceId, a.name)) }
            ContextMenuArea(items = {
                listOf(
                    ContextMenuItem("Öffnen", open),
                    ContextMenuItem("Nicht mehr folgen") { ui.graph.store.toggleFollow(a); ui.toast("Du folgst ${a.name} nicht mehr") },
                )
            }) {
                CardTile(a.name, sourceLabel(a.source), a.thumbnailUrl, width = 172.dp, circle = true, onClick = open)
            }
        }
    }
}

// ---------------------------------------------------------------- Verlauf

@Composable
private fun HistoryTab(data: LibraryData) {
    val ui = LocalUi.current
    var confirm by remember { mutableStateOf(false) }
    if (data.history.isEmpty()) {
        EmptyState(PhosphorIcons.Regular.ClockCounterClockwise, "Noch nichts gehört", "Titel, die du abspielst, erscheinen hier.")
        return
    }
    val tracks = data.history.map { it.track }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = PagePad - 10.dp, end = PagePad - 10.dp, bottom = 40.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Wiedergabeverlauf", Modifier.weight(1f), subtitle = "${data.history.size} Titel")
                PrimaryButton("Verlauf löschen", PhosphorIcons.Regular.Trash, Modifier.padding(top = 16.dp), accent = false) { confirm = true }
            }
        }
        itemsIndexed(data.history, key = { _, h -> h.track.key }) { i, h ->
            TrackRow(
                h.track, index = i, queueContext = tracks,
                subtitle = listOfNotNull(h.track.artist, relativeTime(h.playedAt)).joinToString(" · "),
            )
        }
    }
    if (confirm) ConfirmDialog("Verlauf löschen?", "Dein gesamter Wiedergabeverlauf wird entfernt.", "Löschen", { ui.graph.store.clearHistory(); ui.toast("Verlauf gelöscht") }) { confirm = false }
}
