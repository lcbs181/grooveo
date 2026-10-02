package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.ArrowClockwise
import com.adamglin.phosphoricons.regular.Clock
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.X
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.desktop.data.Playlist
import dev.schlubbe.musicagent.desktop.data.SavedPlaylist
import dev.schlubbe.musicagent.desktop.data.key
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.Cover
import dev.schlubbe.musicagent.desktop.ui.IconBtn
import dev.schlubbe.musicagent.desktop.ui.LibraryTab
import dev.schlubbe.musicagent.desktop.ui.LocalUi
import dev.schlubbe.musicagent.desktop.ui.Pill
import dev.schlubbe.musicagent.desktop.ui.PrimaryButton
import dev.schlubbe.musicagent.desktop.ui.Route
import dev.schlubbe.musicagent.desktop.ui.SectionHeader
import dev.schlubbe.musicagent.desktop.ui.Shelf
import dev.schlubbe.musicagent.desktop.ui.TrackRow
import dev.schlubbe.musicagent.desktop.ui.sourceLabel
import dev.schlubbe.musicagent.desktop.ui.trackMenuItems
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDateTime

private val PAGE_H = 32.dp
private const val SHELF_MAX = 20

@Composable
fun HomeScreen() {
    val ui = LocalUi.current
    val g = ui.graph
    val model = remember(ui) { HomeModel.of(ui) }
    val data by g.store.data.collectAsState()
    val settings by g.settings.state.collectAsState()
    LaunchedEffect(model) { model.onEnter() }

    // greeting re-evaluated every minute (time-of-day buckets)
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = LocalDateTime.now() } }
    val greeting = HomeLogic.greetingFor(now, settings.profileName.ifBlank { null })
    val stat = HomeLogic.weeklyStatLine(remember(data.listenSecondsByDay) { g.store.weeklyListenSeconds() })

    val history = data.history.map { it.track }
    val likes = data.likes.map { it.track }
    val topArtists = remember(data.followed, data.history, data.likes) { HomeLogic.topArtists(data.followed, data.history, data.likes) }
    val genreChips = remember(settings.preferredGenres) { HomeLogic.genreChips(settings.preferredGenres) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 980.dp
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            item(key = "hero") { HomeHero(greeting, stat, model, history, wide) }

            if (settings.showFeatured) item(key = "featured") {
                Pad {
                    HomeSection(
                        "Im Fokus", model.trending, model::refresh, subtitle = "Was gerade wirklich groß ist",
                        hideWhenEmpty = { it.isEmpty() }, skeleton = { HomeSkeletonShelf(5, 232.dp) },
                    ) {
                        val list = model.featured
                        Shelf {
                            items(list, key = { "f" + it.key }) { t ->
                                HomeCard(
                                    t.title, t.artist, t.thumbnailUrl, width = 232.dp, badge = "#${list.indexOf(t) + 1}",
                                    menu = trackMenuItems(t, list), onPlay = { g.player.playQueue(list, list.indexOf(t)) },
                                ) { g.player.playQueue(list, list.indexOf(t)) }
                            }
                        }
                    }
                }
            }

            item(key = "pick+mood") {
                Pad {
                    val pick = model.dailyPick
                    if (wide) Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        DailyPickCard(pick, model, Modifier.weight(1.3f))
                        if (settings.showMixControls) MoodCard(model, Modifier.weight(1f))
                    } else Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        DailyPickCard(pick, model, Modifier.fillMaxWidth())
                        if (settings.showMixControls) MoodCard(model, Modifier.fillMaxWidth())
                    }
                }
            }

            if (settings.showMixControls) item(key = "mixes") {
                Pad {
                    HomeSection("Deine Mixes", model.mixes, model::refresh, subtitle = "Für dich abgemischt", hideWhenEmpty = { it.isEmpty() }) { mixes ->
                        Shelf {
                            items(mixes, key = { it.title }) { m ->
                                val play = { g.player.playQueue(m.pool, 0, shuffle = true) }
                                HomeCard(
                                    m.title, m.subtitle, m.thumbnailUrl, badge = m.badge, caption = "${m.pool.size} Titel",
                                    menu = {
                                        listOf(
                                            ContextMenuItem("Zufallswiedergabe") { play() },
                                            ContextMenuItem("Zur Warteschlange hinzufügen") { g.player.addToQueue(m.pool); ui.toast("${m.pool.size} Titel zur Warteschlange hinzugefügt") },
                                            ContextMenuItem("Als Playlist speichern") { g.store.createPlaylist(m.title, m.pool); ui.toast("Playlist „${m.title}“ erstellt") },
                                            ContextMenuItem("Alle herunterladen") { ui.download(m.pool) },
                                        )
                                    },
                                    onPlay = play, onClick = play,
                                )
                            }
                        }
                    }
                }
            }

            item(key = "feed") {
                Pad {
                    val sub = (model.feed as? HomeLoad.Ready)?.value?.let { if (it.isTasteBased) "Basierend auf deinen Lieblingsgenres" else "Auf dem Gerät aus Verlauf und Likes berechnet" }
                    HomeSection("Für dich", model.feed, model::refresh, subtitle = sub, hideWhenEmpty = { it.items.isEmpty() },
                        action = "Neu mischen", onAction = model::refresh) { res ->
                        val q = res.items.map { it.track }
                        Shelf {
                            items(res.items, key = { "fd" + it.track.key }) { item ->
                                val t = item.track
                                val i = q.indexOf(t)
                                HomeCard(t.title, t.artist, t.thumbnailUrl, caption = item.reason, menu = trackMenuItems(t, q), onPlay = { g.player.playQueue(q, i) }) { g.player.playQueue(q, i) }
                            }
                        }
                    }
                }
            }

            item(key = "genres") {
                Pad {
                    Column {
                        SectionHeader("Trends nach Genre", subtitle = "Echte Genre-Tags von SoundCloud")
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            genreChips.forEach { gname -> Pill(gname, model.selectedGenre.equals(gname, true)) { model.selectGenre(gname) } }
                        }
                        Spacer(Modifier.height(12.dp))
                        when (val l = model.genreTracks) {
                            HomeLoad.Loading -> HomeSkeletonRows(4)
                            is HomeLoad.Failed -> HomeShelfError(l.message) { model.selectedGenre?.let { model.selectGenre(it, force = true) } }
                            is HomeLoad.Ready ->
                                if (l.value.isEmpty()) Text("Für dieses Genre gerade keine Trends verfügbar.", style = MaterialTheme.typography.bodyMedium, color = C.c.textMuted, modifier = Modifier.padding(8.dp))
                                else TrackGrid(l.value, columns = if (wide) 2 else 1, numbered = false)
                        }
                    }
                }
            }

            item(key = "charts") {
                Pad {
                    var expanded by remember { mutableStateOf(false) }
                    HomeSection(
                        "Charts", model.trending, model::refresh, subtitle = "Täglich neu gemischt aus den YouTube-Music-Trends",
                        hideWhenEmpty = { it.isEmpty() }, skeleton = { HomeSkeletonRows(6) },
                        action = if (expanded) "Weniger anzeigen" else "Alle anzeigen", onAction = { expanded = !expanded },
                    ) {
                        val charts = model.charts
                        Column {
                            TrackGrid(if (expanded) charts else charts.take(10), columns = if (wide) 2 else 1, numbered = true, queue = charts)
                            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PrimaryButton("Alle abspielen", PhosphorIcons.Fill.Play) { g.player.playQueue(charts, 0) }
                                PrimaryButton("Zufallswiedergabe", PhosphorIcons.Regular.Shuffle, accent = false) { g.player.playQueue(charts, 0, shuffle = true) }
                            }
                        }
                    }
                }
            }

            if (settings.showNewUploads && topArtists.isNotEmpty()) item(key = "artists") {
                Pad {
                    Column {
                        val followed = data.followed.isNotEmpty()
                        SectionHeader(
                            if (followed) "Neu von Künstlern, denen du folgst" else "Neu von Künstlern, die du hörst",
                            subtitle = if (followed) null else "Aus deinem Verlauf und deinen Likes",
                            action = "Alle", onAction = { ui.navigate(Route.Library(LibraryTab.FOLLOWING)) },
                        )
                        Shelf {
                            items(topArtists, key = { "a" + it.source + it.name }) { a ->
                                HomeCard(
                                    a.name, sourceLabel(a.source), a.thumbnailUrl, width = 148.dp, circle = true,
                                    menu = {
                                        listOf(
                                            ContextMenuItem("Künstler öffnen") { openArtist(ui, a) },
                                            ContextMenuItem("Sender starten") { model.startStation(a) },
                                        )
                                    },
                                ) { openArtist(ui, a) }
                            }
                        }
                    }
                }
            }

            if (topArtists.isNotEmpty()) item(key = "stations") {
                Pad {
                    Column {
                        SectionHeader("Sender entdecken", subtitle = "Endlos-Mix rund um einen Künstler")
                        Shelf {
                            items(topArtists.take(8), key = { "s" + it.source + it.name }) { a ->
                                HomeCard(
                                    "Sender: ${a.name}", "Basierend auf ${a.name}", a.thumbnailUrl, badge = "SENDER",
                                    menu = { listOf(ContextMenuItem("Sender starten") { model.startStation(a) }, ContextMenuItem("Künstler öffnen") { openArtist(ui, a) }) },
                                    onPlay = { model.startStation(a) },
                                ) { model.startStation(a) }
                            }
                        }
                    }
                }
            }

            if (history.isNotEmpty()) item(key = "recent") {
                Pad {
                    Column {
                        SectionHeader("Zuletzt gespielt", action = "Verlauf", onAction = { ui.navigate(Route.Library(LibraryTab.HISTORY)) })
                        TrackShelf(history.take(SHELF_MAX))
                    }
                }
            }

            if (likes.isNotEmpty()) item(key = "likes") {
                Pad {
                    Column {
                        SectionHeader("Deine Likes", subtitle = "${likes.size} Titel", action = "Alle anzeigen", onAction = { ui.navigate(Route.Library(LibraryTab.LIKES)) })
                        TrackShelf(likes.take(SHELF_MAX), queue = likes)
                    }
                }
            }

            if (data.playlists.isNotEmpty() || data.savedPlaylists.isNotEmpty()) item(key = "playlists") {
                Pad {
                    Column {
                        SectionHeader("Deine Playlists", action = "Alle anzeigen", onAction = { ui.navigate(Route.Library(LibraryTab.PLAYLISTS)) })
                        Shelf {
                            items(data.playlists, key = { "p" + it.id }) { p -> LocalPlaylistCard(p) }
                            items(data.savedPlaylists, key = { "sp" + it.source + it.sourceId }) { p -> SavedPlaylistCard(p) }
                        }
                    }
                }
            }

            if (!settings.homeScPromoDismissed) item(key = "promo") { Pad { ScPromoCard() } }
        }
    }
}

@Composable
private fun Pad(content: @Composable () -> Unit) = Box(Modifier.padding(horizontal = PAGE_H)) { content() }

private fun openArtist(ui: dev.schlubbe.musicagent.desktop.ui.AppUi, a: HomeArtist) {
    if (a.sourceId != null) ui.navigate(Route.Artist(a.source, a.sourceId, a.name)) else ui.navigate(Route.ArtistByName(a.name, a.source))
}

// ---------------------------------------------------------------- hero

@Composable
private fun HomeHero(greeting: String, stat: String, model: HomeModel, history: List<TrackResultDto>, wide: Boolean) {
    val ui = LocalUi.current
    val c = C.c
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(c.accent.copy(alpha = if (c.isDark) 0.35f else 0.18f), c.accent2.copy(alpha = 0.04f), Color.Transparent)))
            .padding(horizontal = PAGE_H).padding(top = 36.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(greeting, style = MaterialTheme.typography.displayMedium, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (stat.isNotEmpty()) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(PhosphorIcons.Regular.Clock, null, tint = c.textMuted, modifier = Modifier.size(15.dp))
                    Text(stat, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(PhosphorIcons.Regular.MagnifyingGlass, "Suchen", tint = c.text, background = c.surfaceHigh) { ui.navigateRoot(Route.Search()); ui.searchFocusRequest++ }
                Box(contentAlignment = Alignment.Center) {
                    if (model.refreshing) CircularProgressIndicator(Modifier.size(36.dp), color = c.accent, strokeWidth = 2.dp)
                    IconBtn(PhosphorIcons.Regular.ArrowClockwise, "Aktualisieren", tint = c.text, background = c.surfaceHigh) { model.refresh() }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        ResumeCard(history.firstOrNull(), history)
        val quick = history.drop(1).take(if (wide) 6 else 4)
        if (quick.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val cols = if (wide) 3 else 2
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.chunked(cols).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { t -> QuickTile(t, history, Modifier.weight(1f)) }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** "Läuft gerade" / "Weiter hören" / "Zuletzt gespielt" card with live progress. */
@Composable
private fun ResumeCard(lastPlayed: TrackResultDto?, history: List<TrackResultDto>) {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val q by g.player.state.collectAsState()
    val e by g.player.engineState.collectAsState()
    val current = q.current
    val t = current ?: lastPlayed ?: return
    val positionMs = if (current != null) (e.positionSec * 1000).toLong() else 0L
    val label = HomeLogic.resumeStatusLabel(current != null && e.playing, positionMs)
    val dur = e.durationSec?.takeIf { current != null && it > 0 } ?: t.durationSec?.toDouble()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val action = { if (current != null) g.player.togglePlay() else g.player.playQueue(history, 0) }
    ContextMenuArea(items = trackMenuItems(t, if (current != null) null else history)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).hoverable(hover)
                .background(if (hovered) c.surfaceHigh else c.surface.copy(alpha = 0.85f)).clickable(onClick = { if (current != null) ui.playerOpen = true else action() })
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(t.thumbnailUrl, 72.dp, radius = 10.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.accent2)
                Text(t.title, style = MaterialTheme.typography.titleLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(t.artist, sourceLabel(t.source)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
                if (current != null && dur != null && dur > 0) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (e.positionSec / dur).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(0.6f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = c.accent, trackColor = c.divider, drawStopIndicator = {},
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(c.accent2).clickable(onClick = action),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (current != null && e.playing) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play, if (e.playing) "Pause" else "Abspielen", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun QuickTile(t: TrackResultDto, queue: List<TrackResultDto>, modifier: Modifier) {
    val g = LocalUi.current.graph
    val c = C.c
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val play = { g.player.playQueue(queue, queue.indexOfFirst { it.key == t.key }.coerceAtLeast(0)) }
    ContextMenuArea(items = trackMenuItems(t, queue)) {
        Row(
            modifier.clip(RoundedCornerShape(10.dp)).hoverable(hover).background(if (hovered) c.surfaceHigh else c.surface.copy(alpha = 0.7f)).clickable(onClick = play),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(t.thumbnailUrl, 52.dp, radius = 0.dp)
            Text(t.title, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            if (hovered) Box(Modifier.padding(end = 10.dp).size(32.dp).clip(CircleShape).background(c.accent2), contentAlignment = Alignment.Center) {
                Icon(PhosphorIcons.Fill.Play, "Abspielen", tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- daily pick / moods

@Composable
private fun DailyPickCard(pick: TrackResultDto?, model: HomeModel, modifier: Modifier) {
    val ui = LocalUi.current
    val c = C.c
    Box(
        modifier.height(196.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(c.accentStrong, c.accent, c.accent2.copy(alpha = 0.85f)))),
    ) {
        if (pick == null) {
            Column(Modifier.padding(24.dp)) {
                Text("DIE AUSWAHL VON HEUTE", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.height(12.dp))
                HomeSkeletonBox(Modifier.width(220.dp).height(22.dp), 6.dp)
                Spacer(Modifier.height(8.dp))
                HomeSkeletonBox(Modifier.width(140.dp).height(14.dp), 4.dp)
            }
            return@Box
        }
        val play = { val q = model.dailyPickQueue(pick); ui.graph.player.playQueue(q, q.indexOfFirst { it.key == pick.key }.coerceAtLeast(0)) }
        ContextMenuArea(items = trackMenuItems(pick)) {
            Row(Modifier.fillMaxSize().clickable(onClick = play).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(pick.thumbnailUrl, 148.dp, radius = 14.dp)
                Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Text("EMPFEHLUNG DES TAGES", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                    Spacer(Modifier.height(6.dp))
                    Text(pick.title, style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(pick.artist, sourceLabel(pick.source)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable(onClick = play).padding(horizontal = 18.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(PhosphorIcons.Fill.Play, null, tint = c.accentStrong, modifier = Modifier.size(16.dp))
                        Text("Abspielen", style = MaterialTheme.typography.labelLarge, color = Color(0xFF152922))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoodCard(model: HomeModel, modifier: Modifier) {
    val c = C.c
    var mood by remember { mutableStateOf(HomeMood.ALL) }
    Column(
        modifier.height(196.dp).clip(RoundedCornerShape(20.dp)).background(c.surface).padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Wonach ist dir?", style = MaterialTheme.typography.titleLarge, color = c.text)
            Text("Stimmung wählen und einen Mix starten", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeMood.entries.forEach { m -> Pill(m.label, mood == m) { mood = m } }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(if (model.mixLoading) "Mix wird erstellt …" else "Mix starten", PhosphorIcons.Regular.Shuffle) { model.startMood(mood) }
            if (model.mixLoading) CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
        }
    }
}

// ---------------------------------------------------------------- lists / cards

@Composable
private fun TrackGrid(tracks: List<TrackResultDto>, columns: Int, numbered: Boolean, queue: List<TrackResultDto> = tracks) {
    val perCol = (tracks.size + columns - 1) / columns
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        (0 until columns).forEach { col ->
            Column(Modifier.weight(1f)) {
                tracks.drop(col * perCol).take(perCol).forEachIndexed { i, t ->
                    TrackRow(t, index = if (numbered) col * perCol + i else null, queueContext = queue)
                }
            }
        }
    }
}

@Composable
private fun TrackShelf(tracks: List<TrackResultDto>, queue: List<TrackResultDto> = tracks) {
    val g = LocalUi.current.graph
    Shelf {
        items(tracks, key = { it.key }) { t ->
            val i = queue.indexOfFirst { it.key == t.key }.coerceAtLeast(0)
            HomeCard(t.title, t.artist, t.thumbnailUrl, menu = trackMenuItems(t, queue), onPlay = { g.player.playQueue(queue, i) }) { g.player.playQueue(queue, i) }
        }
    }
}

private fun playlistCover(p: Playlist): String? =
    p.coverPath?.takeIf { File(it).isFile }?.let { File(it).toURI().toString() } ?: p.tracks.firstOrNull { it.track.thumbnailUrl != null }?.track?.thumbnailUrl

@Composable
private fun LocalPlaylistCard(p: Playlist) {
    val ui = LocalUi.current
    val g = ui.graph
    val tracks = p.tracks.map { it.track }
    HomeCard(
        p.name, "${tracks.size} Titel", playlistCover(p),
        menu = {
            buildList {
                if (tracks.isNotEmpty()) {
                    add(ContextMenuItem("Abspielen") { g.player.playQueue(tracks, 0) })
                    add(ContextMenuItem("Zufallswiedergabe") { g.player.playQueue(tracks, 0, shuffle = true) })
                    add(ContextMenuItem("Zur Warteschlange hinzufügen") { g.player.addToQueue(tracks); ui.toast("${tracks.size} Titel zur Warteschlange hinzugefügt") })
                    add(ContextMenuItem("Alle herunterladen") { ui.download(tracks) })
                }
                add(ContextMenuItem("Öffnen") { ui.navigate(Route.Playlist(p.id)) })
            }
        },
        onPlay = if (tracks.isNotEmpty()) ({ g.player.playQueue(tracks, 0) }) else null,
    ) { ui.navigate(Route.Playlist(p.id)) }
}

@Composable
private fun SavedPlaylistCard(p: SavedPlaylist) {
    val ui = LocalUi.current
    val open = { ui.navigate(Route.RemotePlaylist(p.source, p.sourceId, p.isAlbum, p.title, p.thumbnailUrl)) }
    HomeCard(
        p.title, listOfNotNull(if (p.isAlbum) "Album" else "Playlist", p.owner).joinToString(" · "), p.thumbnailUrl,
        caption = sourceLabel(p.source),
        menu = { listOf(ContextMenuItem("Öffnen") { open() }, ContextMenuItem("Link kopieren (Teilen)") { ui.copyLink(p.webpageUrl) }) },
        onClick = open,
    )
}

@Composable
private fun ScPromoCard() {
    val ui = LocalUi.current
    val c = C.c
    Row(
        Modifier.padding(top = 32.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.accent2Soft).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(c.accent2), contentAlignment = Alignment.Center) {
            Icon(PhosphorIcons.Regular.DownloadSimple, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("SoundCloud-Downloads", style = MaterialTheme.typography.titleMedium, color = c.text)
            Text(
                "SoundCloud liefert HLS-Streams. Grooveo für den Desktop wandelt sie beim Herunterladen mit FFmpeg in M4A-Dateien um – offline hörbar wie jeder andere Titel.",
                style = MaterialTheme.typography.bodySmall, color = c.textMuted,
            )
        }
        TextButton(onClick = { ui.navigate(Route.Downloads) }) { Text("Zu den Downloads", color = c.accent2) }
        IconBtn(PhosphorIcons.Regular.X, "Ausblenden") { ui.graph.settings.update { it.copy(homeScPromoDismissed = true) } }
    }
}
