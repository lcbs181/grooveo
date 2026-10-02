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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.ClockCounterClockwise
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.X
import dev.schlubbe.musicagent.data.remote.dto.AlbumResultDto
import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.CardTile
import dev.schlubbe.musicagent.desktop.ui.Cover
import dev.schlubbe.musicagent.desktop.ui.EmptyState
import dev.schlubbe.musicagent.desktop.ui.ErrorBox
import dev.schlubbe.musicagent.desktop.ui.IconBtn
import dev.schlubbe.musicagent.desktop.ui.LoadingBox
import dev.schlubbe.musicagent.desktop.ui.LocalUi
import dev.schlubbe.musicagent.desktop.ui.Pill
import dev.schlubbe.musicagent.desktop.ui.Route
import dev.schlubbe.musicagent.desktop.ui.SectionHeader
import dev.schlubbe.musicagent.desktop.ui.TrackRow
import dev.schlubbe.musicagent.desktop.ui.formatDuration
import dev.schlubbe.musicagent.desktop.ui.sourceLabel
import dev.schlubbe.musicagent.desktop.ui.trackMenuItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Survives navigation away from and back to the search page (one window, one search). */
private object SearchMemory {
    val cache = SearchCache()
    var text = ""
    var submitted: String? = null
    var type = SearchType.TRACKS
    var source: SearchSource? = null
}

private sealed interface Load {
    data object Idle : Load
    data object Loading : Load
    data class Done(val items: List<Any>) : Load
    data class Failed(val message: String) : Load
}

@Composable
fun SearchScreen(initialQuery: String?) {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val settings by g.settings.state.collectAsState()
    val data by g.store.data.collectAsState()
    val sources = availableSources(settings.sourceSoundCloud, settings.sourceYouTube)

    val start = initialQuery?.takeIf { it.isNotBlank() } ?: SearchMemory.text
    var field by remember { mutableStateOf(TextFieldValue(start, TextRange(start.length))) }
    var submitted by remember { mutableStateOf(initialQuery?.takeIf { it.isNotBlank() } ?: SearchMemory.submitted) }
    var type by remember { mutableStateOf(SearchMemory.type) }
    var source by remember { mutableStateOf(SearchMemory.source?.takeIf { it in sources } ?: sources.first()) }
    var focused by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var selected by remember { mutableIntStateOf(-1) }
    var load by remember { mutableStateOf<Load>(Load.Idle) }
    var retry by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(field.text, submitted, type, source) {
        SearchMemory.text = field.text; SearchMemory.submitted = submitted; SearchMemory.type = type; SearchMemory.source = source
    }
    LaunchedEffect(ui.searchFocusRequest) { runCatching { focus.requestFocus() } }
    LaunchedEffect(initialQuery) { initialQuery?.takeIf { it.isNotBlank() }?.let { g.store.addSearch(it) } }

    // Suggestions, 250 ms debounce (YouTube's suggest endpoint).
    LaunchedEffect(field.text) {
        selected = -1
        val q = field.text
        if (q.isBlank() || q.trim() == submitted?.trim()) { suggestions = emptyList(); return@LaunchedEffect }
        delay(250)
        suggestions = withContext(Dispatchers.IO) { g.search.suggestions(q) }
    }

    // Results, cached per (query, source, tab).
    LaunchedEffect(submitted, type, source, retry) {
        val q = submitted?.trim().orEmpty()
        if (q.isEmpty()) { load = Load.Idle; return@LaunchedEffect }
        val key = SearchKey.of(q, source, type)
        SearchMemory.cache[key]?.let { load = Load.Done(it); return@LaunchedEffect }
        load = Load.Loading
        load = try {
            val items: List<Any> = withContext(Dispatchers.IO) {
                when (type) {
                    SearchType.TRACKS -> g.search.search(q, source.key)
                    SearchType.ARTISTS -> g.search.searchArtists(q, source.key)
                    SearchType.PLAYLISTS -> g.search.searchPlaylists(q, source.key)
                    SearchType.ALBUMS -> g.search.searchAlbums(q, source.key)
                }
            }
            SearchMemory.cache[key] = items
            Load.Done(items)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(e.message ?: "Suche fehlgeschlagen")
        }
    }

    fun submit(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        field = TextFieldValue(query, TextRange(query.length))
        suggestions = emptyList()
        selected = -1
        submitted = query
        g.store.addSearch(query)
    }

    val showSuggestions = shouldShowSuggestions(field.text, submitted, focused, suggestions)

    Column(Modifier.fillMaxSize()) {
        // ---------- header: field + filters ----------
        Column(Modifier.fillMaxWidth().padding(start = PagePad, end = PagePad, top = 24.dp).zIndex(2f)) {
            Text("Suche", style = MaterialTheme.typography.displayMedium, color = c.text)
            Spacer(Modifier.height(16.dp))
            var fieldWidth by remember { mutableStateOf(720.dp) }
            val density = LocalDensity.current
            Box(Modifier.widthIn(max = 720.dp).fillMaxWidth().onSizeChanged { fieldWidth = with(density) { it.width.toDp() } }) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(c.surfaceHigh)
                        .padding(start = 18.dp, end = 6.dp).height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PhosphorIcons.Regular.MagnifyingGlass, null, tint = if (focused) c.accent else c.textMuted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (field.text.isEmpty()) Text("Titel, Künstler, Playlists", style = MaterialTheme.typography.bodyLarge, color = c.textFaint)
                        BasicTextField(
                            field, { field = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                            cursorBrush = SolidColor(c.accent),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (e.key) {
                                        Key.Enter, Key.NumPadEnter -> { submit(suggestions.getOrNull(selected)?.takeIf { showSuggestions } ?: field.text); true }
                                        Key.DirectionDown -> if (showSuggestions) { selected = moveSelection(selected, 1, suggestions.size); true } else false
                                        Key.DirectionUp -> if (showSuggestions) { selected = moveSelection(selected, -1, suggestions.size); true } else false
                                        Key.Escape -> if (showSuggestions) { suggestions = emptyList(); true } else if (field.text.isNotEmpty()) { field = TextFieldValue(""); true } else false
                                        else -> false
                                    }
                                },
                        )
                    }
                    if (field.text.isNotEmpty()) IconBtn(PhosphorIcons.Regular.X, "Leeren (Esc)") {
                        field = TextFieldValue(""); submitted = null; runCatching { focus.requestFocus() }
                    }
                }
                if (showSuggestions) {
                    val offsetPx = with(LocalDensity.current) { 58.dp.roundToPx() }
                    Popup(offset = IntOffset(0, offsetPx), properties = PopupProperties(focusable = false), onDismissRequest = { suggestions = emptyList() }) {
                        Surface(Modifier.width(fieldWidth), shape = RoundedCornerShape(16.dp), color = c.surface, shadowElevation = 12.dp) {
                            Column(Modifier.padding(vertical = 6.dp)) {
                                suggestions.forEachIndexed { i, s -> SuggestionRow(s, i == selected) { submit(s) } }
                            }
                        }
                    }
                }
            }
            if (submitted != null) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SearchType.entries.forEach { t -> Pill(t.label, type == t) { type = t } }
                    if (sources.size > 1) {
                        Spacer(Modifier.width(16.dp))
                        Box(Modifier.width(1.dp).height(24.dp).background(c.divider))
                        Spacer(Modifier.width(16.dp))
                        sources.forEach { s -> Pill(s.label, source == s) { source = s } }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ---------- body ----------
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val q = submitted
            if (q == null) SearchHistory(data.searches, onPick = { submit(it) })
            else when (val l = load) {
                Load.Idle, Load.Loading -> LoadingBox()
                is Load.Failed -> ErrorBox(l.message) { retry++ }
                is Load.Done -> if (l.items.isEmpty()) EmptyState(PhosphorIcons.Regular.MagnifyingGlass, "Keine Treffer", "Für „$q“ wurde nichts gefunden. Probier eine andere Quelle oder Schreibweise.")
                else when (type) {
                    SearchType.TRACKS -> TrackResults(l.items.filterIsInstance<TrackResultDto>())
                    SearchType.ARTISTS -> ResultGrid(l.items.filterIsInstance<ArtistResultDto>()) { a ->
                        ArtistTile(a)
                    }
                    SearchType.PLAYLISTS -> ResultGrid(l.items.filterIsInstance<PlaylistResultDto>()) { p ->
                        CollectionTile(p.title, listOfNotNull(p.owner, p.trackCount?.let { "$it Titel" }).joinToString(" · ").ifEmpty { null }, p.thumbnailUrl, p.source, p.webpageUrl) {
                            ui.navigate(Route.RemotePlaylist(p.source, p.sourceId, false, p.title, p.thumbnailUrl))
                        }
                    }
                    SearchType.ALBUMS -> ResultGrid(l.items.filterIsInstance<AlbumResultDto>()) { a ->
                        CollectionTile(a.title, listOfNotNull(a.artist, a.year).joinToString(" · ").ifEmpty { null }, a.thumbnailUrl, a.source, a.webpageUrl) {
                            ui.navigate(Route.RemotePlaylist(a.source, a.sourceId, true, a.title, a.thumbnailUrl))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = C.c
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().hoverable(hover).background(if (selected || hovered) c.surfaceHigh else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PhosphorIcons.Regular.MagnifyingGlass, null, tint = c.textFaint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SearchHistory(searches: List<String>, onPick: (String) -> Unit) {
    val ui = LocalUi.current
    val c = C.c
    if (searches.isEmpty()) {
        EmptyState(PhosphorIcons.Regular.MagnifyingGlass, "Was möchtest du hören?", "Suche nach Titeln, Künstlern, Playlists und Alben auf SoundCloud und YouTube Music.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = PagePad - 10.dp, end = PagePad, bottom = 32.dp)) {
        item {
            Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Zuletzt gesucht", Modifier.weight(1f))
                TextButton(onClick = { ui.graph.store.clearSearches() }, modifier = Modifier.padding(top = 16.dp)) { Text("Alle löschen", color = c.accent) }
            }
        }
        itemsIndexed(searches, key = { _, s -> s }) { _, s ->
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            ContextMenuArea(items = { listOf(ContextMenuItem("Suchen") { onPick(s) }, ContextMenuItem("Aus Verlauf entfernen") { ui.graph.store.removeSearch(s) }) }) {
                Row(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).hoverable(hover)
                        .background(if (hovered) c.surfaceHigh else Color.Transparent).clickable { onPick(s) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
                        Icon(PhosphorIcons.Regular.ClockCounterClockwise, null, tint = c.textMuted, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(s, style = MaterialTheme.typography.bodyLarge, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconBtn(PhosphorIcons.Regular.X, "Aus Verlauf entfernen", modifier = Modifier.alpha(if (hovered) 1f else 0.4f)) { ui.graph.store.removeSearch(s) }
                }
            }
        }
    }
}

@Composable
private fun TrackResults(tracks: List<TrackResultDto>) {
    val top = topTrack(tracks)
    val rest = tracks
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = PagePad, end = PagePad, bottom = 32.dp)) {
        if (top != null) item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(380.dp)) {
                    SectionHeader("Top-Ergebnis")
                    TopResultCard(top, tracks)
                }
                Column(Modifier.weight(1f)) {
                    SectionHeader("Titel")
                    rest.take(4).forEachIndexed { i, t -> SearchTrackRow(t, i, tracks) }
                }
            }
        }
        if (rest.size > 4) {
            item { SectionHeader("Weitere Titel", subtitle = "${rest.size} Treffer · Doppelklick spielt, Rechtsklick für mehr") }
            itemsIndexed(rest.drop(4), key = { i, t -> "${t.source}:${t.sourceId}:$i" }) { i, t -> SearchTrackRow(t, i + 4, tracks) }
        }
    }
}

@Composable
private fun SearchTrackRow(t: TrackResultDto, index: Int, all: List<TrackResultDto>) {
    TrackRow(t, Modifier.alpha(if (t.isDrmProtected) 0.45f else 1f), index = index, queueContext = all.filterNot { it.isDrmProtected }.takeIf { !t.isDrmProtected })
}

@Composable
private fun TopResultCard(t: TrackResultDto, all: List<TrackResultDto>) {
    val ui = LocalUi.current
    val c = C.c
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val play = { val q = all.filterNot { it.isDrmProtected }; ui.graph.player.playQueue(q, q.indexOf(t).coerceAtLeast(0)) }
    ContextMenuArea(items = trackMenuItems(t, all)) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 220.dp).clip(RoundedCornerShape(16.dp)).hoverable(hover)
                .background(Brush.linearGradient(listOf(c.accent.copy(alpha = if (hovered) 0.5f else 0.35f), c.surfaceHigh)))
                .clickable(onClick = play).padding(20.dp),
        ) {
            Column {
                Cover(t.thumbnailUrl, 104.dp, radius = 10.dp)
                Spacer(Modifier.height(16.dp))
                Text(t.title, style = MaterialTheme.typography.headlineLarge, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t.artist ?: "Unbekannt", style = MaterialTheme.typography.bodyMedium, color = c.text, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { ui.goToArtist(t) },
                    )
                    Text(" · Titel · ${sourceLabel(t.source)} · ${formatDuration(t.durationSec)}", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
            }
            if (hovered) Box(
                Modifier.align(Alignment.BottomEnd).size(52.dp).clip(CircleShape).background(c.accent2).clickable(onClick = play),
                contentAlignment = Alignment.Center,
            ) { Icon(PhosphorIcons.Fill.Play, "Abspielen", tint = Color.White, modifier = Modifier.size(22.dp)) }
        }
    }
}

@Composable
private fun <T> ResultGrid(items: List<T>, tile: @Composable (T) -> Unit) {
    LazyVerticalGrid(
        GridCells.Adaptive(184.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = PagePad - 8.dp, end = PagePad, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { items(items) { tile(it) } }
}

@Composable
private fun ArtistTile(a: ArtistResultDto) {
    val ui = LocalUi.current
    val data by ui.graph.store.data.collectAsState()
    val following = data.followed.any { it.source == a.source && it.sourceId == a.sourceId }
    ContextMenuArea(items = {
        listOf(
            ContextMenuItem("Öffnen") { ui.navigate(Route.Artist(a.source, a.sourceId, a.name)) },
            ContextMenuItem(if (following) "Nicht mehr folgen" else "Folgen") {
                ui.graph.store.toggleFollow(dev.schlubbe.musicagent.desktop.data.FollowedArtist(a.source, a.sourceId, a.name, a.thumbnailUrl))
            },
            ContextMenuItem("Link kopieren (Teilen)") { ui.copyLink(a.webpageUrl) },
        )
    }) {
        CardTile(
            a.name, listOfNotNull(a.subscriberCount, sourceLabel(a.source)).joinToString(" · "), a.thumbnailUrl,
            width = 184.dp, circle = true, badge = if (following) "Gefolgt" else null,
        ) { ui.navigate(Route.Artist(a.source, a.sourceId, a.name)) }
    }
}

@Composable
private fun CollectionTile(title: String, subtitle: String?, image: String?, source: String, url: String, onClick: () -> Unit) {
    val ui = LocalUi.current
    ContextMenuArea(items = { listOf(ContextMenuItem("Öffnen", onClick), ContextMenuItem("Link kopieren (Teilen)") { ui.copyLink(url) }) }) {
        CardTile(title, subtitle ?: sourceLabel(source), image, width = 184.dp, onClick = onClick)
    }
}
