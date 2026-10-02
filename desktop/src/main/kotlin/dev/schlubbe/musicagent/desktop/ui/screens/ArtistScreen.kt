package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.ListPlus
import com.adamglin.phosphoricons.regular.MicrophoneStage
import com.adamglin.phosphoricons.regular.ShareNetwork
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.UserCheck
import com.adamglin.phosphoricons.regular.UserPlus
import dev.schlubbe.musicagent.data.remote.dto.ArtistDetailDto
import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.desktop.data.FollowedArtist
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.CardTile
import dev.schlubbe.musicagent.desktop.ui.Cover
import dev.schlubbe.musicagent.desktop.ui.EmptyState
import dev.schlubbe.musicagent.desktop.ui.ErrorBox
import dev.schlubbe.musicagent.desktop.ui.IconBtn
import dev.schlubbe.musicagent.desktop.ui.LoadingBox
import dev.schlubbe.musicagent.desktop.ui.LocalUi
import dev.schlubbe.musicagent.desktop.ui.Panel
import dev.schlubbe.musicagent.desktop.ui.PrimaryButton
import dev.schlubbe.musicagent.desktop.ui.Route
import dev.schlubbe.musicagent.desktop.ui.SectionHeader
import dev.schlubbe.musicagent.desktop.ui.Shelf
import dev.schlubbe.musicagent.desktop.ui.TrackRow
import dev.schlubbe.musicagent.desktop.ui.sourceLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface ArtistLoad {
    data object Loading : ArtistLoad
    data object NotFound : ArtistLoad
    data class Done(val artist: ArtistDetailDto) : ArtistLoad
    data class Failed(val message: String) : ArtistLoad
}

@Composable
fun ArtistScreen(route: Route) {
    val ui = LocalUi.current
    val g = ui.graph
    var load by remember(route) { mutableStateOf<ArtistLoad>(ArtistLoad.Loading) }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(route, retry) {
        load = ArtistLoad.Loading
        load = try {
            withContext(Dispatchers.IO) {
                val (source, id) = when (route) {
                    is Route.Artist -> route.source to route.sourceId
                    is Route.ArtistByName -> g.search.findArtistByName(route.name, route.source)?.let { it.source to it.sourceId }
                        ?: return@withContext ArtistLoad.NotFound
                    else -> return@withContext ArtistLoad.NotFound
                }
                ArtistLoad.Done(g.search.getArtist(source, id))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ArtistLoad.Failed(e.message ?: "Künstler konnte nicht geladen werden")
        }
    }

    when (val l = load) {
        ArtistLoad.Loading -> LoadingBox(Modifier.fillMaxSize())
        ArtistLoad.NotFound -> EmptyState(
            PhosphorIcons.Regular.MicrophoneStage, "Künstler nicht gefunden",
            (route as? Route.ArtistByName)?.let { "„${it.name}“ wurde auf ${sourceLabel(it.source)} nicht gefunden." } ?: "Dieser Künstler ist nicht verfügbar.",
        )
        is ArtistLoad.Failed -> ErrorBox(l.message) { retry++ }
        is ArtistLoad.Done -> ArtistContent(l.artist)
    }
}

@Composable
private fun ArtistContent(a: ArtistDetailDto) {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val data by g.store.data.collectAsState()
    val following = data.followed.any { it.source == a.source && it.sourceId == a.sourceId }
    val playable = (a.topTracks + a.latestTracks).distinctBy { it.source + it.sourceId }.filterNot { it.isDrmProtected }
    var showAllTop by remember { mutableStateOf(false) }

    // SoundCloud follower list, paged.
    var followers by remember(a.sourceId) { mutableStateOf(emptyList<ArtistResultDto>()) }
    var cursor by remember(a.sourceId) { mutableStateOf<String?>(null) }
    var followersLoading by remember(a.sourceId) { mutableStateOf(false) }
    var followersDone by remember(a.sourceId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun loadFollowers() {
        if (followersLoading || followersDone) return
        followersLoading = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { g.search.getFollowersPage(a.sourceId, cursor) } }
                .onSuccess { p -> followers = (followers + p.items).distinctBy { it.sourceId }; cursor = p.nextCursorUrl; followersDone = p.nextCursorUrl == null }
                .onFailure { followersDone = true; if (followers.isEmpty()) ui.toast("Follower konnten nicht geladen werden") }
            followersLoading = false
        }
    }
    LaunchedEffect(a.sourceId) { if (a.source == "soundcloud") loadFollowers() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        // ---------- hero ----------
        item {
            Box(Modifier.fillMaxWidth().height(340.dp)) {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.accent, c.accentStrong))))
                if (!a.bannerUrl.isNullOrBlank()) AsyncImage(a.bannerUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.25f), c.bg.copy(alpha = 0.95f)))))
                Row(Modifier.align(Alignment.BottomStart).padding(horizontal = PagePad, vertical = 24.dp), verticalAlignment = Alignment.Bottom) {
                    Cover(a.thumbnailUrl, 168.dp, circle = true)
                    Spacer(Modifier.width(28.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("KÜNSTLER · ${sourceLabel(a.source).uppercase()}", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                        Text(a.name, style = MaterialTheme.typography.displayLarge, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        a.subscriberCount?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textMuted) }
                    }
                }
            }
        }
        // ---------- actions ----------
        item {
            ActionBar {
                if (playable.isNotEmpty()) {
                    PrimaryButton("Abspielen", PhosphorIcons.Fill.Play) { g.player.playQueue(playable, 0) }
                    PrimaryButton("Zufällig abspielen", PhosphorIcons.Regular.Shuffle, accent = false) { g.player.playQueue(playable, 0, shuffle = true) }
                }
                PrimaryButton(if (following) "Gefolgt" else "Folgen", if (following) PhosphorIcons.Regular.UserCheck else PhosphorIcons.Regular.UserPlus, accent = false) {
                    g.store.toggleFollow(FollowedArtist(a.source, a.sourceId, a.name, a.thumbnailUrl))
                    ui.toast(if (following) "Du folgst ${a.name} nicht mehr" else "Du folgst jetzt ${a.name}")
                }
                if (playable.isNotEmpty()) IconBtn(PhosphorIcons.Regular.ListPlus, "Alle zur Warteschlange hinzufügen", tint = c.text) {
                    g.player.addToQueue(playable); ui.toast("${playable.size} Titel zur Warteschlange hinzugefügt")
                }
                IconBtn(PhosphorIcons.Regular.ShareNetwork, "Link kopieren (Teilen)", tint = c.text) { ui.copyLink(a.webpageUrl) }
            }
        }
        // ---------- tracks ----------
        if (a.topTracks.isNotEmpty()) {
            item { SectionHeader("Top-Titel", Modifier.padding(horizontal = PagePad), action = if (a.topTracks.size > 5) (if (showAllTop) "Weniger anzeigen" else "Alle anzeigen") else null) { showAllTop = !showAllTop } }
            val shown = if (showAllTop) a.topTracks else a.topTracks.take(5)
            itemsIndexed(shown, key = { i, t -> "top:${t.sourceId}:$i" }) { i, t -> ArtistTrack(t, i, a.topTracks) }
        }
        if (a.latestTracks.isNotEmpty()) {
            item { SectionHeader("Neueste Titel", Modifier.padding(horizontal = PagePad)) }
            itemsIndexed(a.latestTracks.take(10), key = { i, t -> "new:${t.sourceId}:$i" }) { i, t -> ArtistTrack(t, i, a.latestTracks) }
        }
        if (a.topTracks.isEmpty() && a.latestTracks.isEmpty()) item {
            EmptyState(PhosphorIcons.Regular.MicrophoneStage, "Noch keine Titel", "Von ${a.name} sind hier keine Titel verfügbar.")
        }
        // ---------- shelves ----------
        if (a.albums.isNotEmpty()) {
            item { SectionHeader("Alben & Singles", Modifier.padding(horizontal = PagePad)) }
            item {
                Shelf(Modifier.padding(start = PagePad - 8.dp)) {
                    items(a.albums) { al ->
                        ContextMenuArea(items = { listOf(ContextMenuItem("Link kopieren (Teilen)") { ui.copyLink(al.webpageUrl) }) }) {
                            CardTile(al.title, listOfNotNull(al.year, al.artist).joinToString(" · ").ifEmpty { "Album" }, al.thumbnailUrl) {
                                ui.navigate(Route.RemotePlaylist(al.source, al.sourceId, true, al.title, al.thumbnailUrl))
                            }
                        }
                    }
                }
            }
        }
        if (a.playlists.isNotEmpty()) {
            item { SectionHeader("Playlists", Modifier.padding(horizontal = PagePad)) }
            item {
                Shelf(Modifier.padding(start = PagePad - 8.dp)) {
                    items(a.playlists) { p ->
                        ContextMenuArea(items = { listOf(ContextMenuItem("Link kopieren (Teilen)") { ui.copyLink(p.webpageUrl) }) }) {
                            CardTile(p.title, p.trackCount?.let { "$it Titel" } ?: p.owner, p.thumbnailUrl) {
                                ui.navigate(Route.RemotePlaylist(p.source, p.sourceId, false, p.title, p.thumbnailUrl))
                            }
                        }
                    }
                }
            }
        }
        // ---------- followers (SoundCloud only) ----------
        if (a.source == "soundcloud" && (followers.isNotEmpty() || followersLoading)) {
            item { SectionHeader("Follower", Modifier.padding(horizontal = PagePad), subtitle = a.subscriberCount) }
            item {
                Shelf(Modifier.padding(start = PagePad - 8.dp)) {
                    items(followers, key = { it.sourceId }) { f ->
                        CardTile(f.name, f.subscriberCount, f.thumbnailUrl, width = 148.dp, circle = true) {
                            ui.navigate(Route.Artist(f.source, f.sourceId, f.name))
                        }
                    }
                    if (followersLoading) item { Box(Modifier.size(148.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) } }
                    else if (!followersDone) item {
                        Box(Modifier.size(148.dp), contentAlignment = Alignment.Center) { PrimaryButton("Mehr laden", accent = false) { loadFollowers() } }
                    }
                }
            }
        }
        // ---------- about ----------
        a.description?.takeIf { it.isNotBlank() }?.let { d ->
            item { SectionHeader("Über ${a.name}", Modifier.padding(horizontal = PagePad)) }
            item { Panel(Modifier.padding(horizontal = PagePad).widthIn(max = 820.dp)) { ExpandableText(d, collapsedLines = 4) } }
        }
    }
}

@Composable
private fun ArtistTrack(t: TrackResultDto, i: Int, list: List<TrackResultDto>) {
    TrackRow(
        t, Modifier.padding(horizontal = PagePad - 10.dp).alpha(if (t.isDrmProtected) 0.45f else 1f),
        index = i, queueContext = list.filterNot { it.isDrmProtected },
        subtitle = listOfNotNull(t.album, sourceLabel(t.source)).joinToString(" · ").takeIf { t.album != null },
    )
}
