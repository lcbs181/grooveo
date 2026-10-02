package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.BookmarkSimple
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.BookmarkSimple
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.ListPlus
import com.adamglin.phosphoricons.regular.MusicNotes
import com.adamglin.phosphoricons.regular.ShareNetwork
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.Waveform
import dev.schlubbe.musicagent.data.remote.dto.RemotePlaylistDetailDto
import dev.schlubbe.musicagent.desktop.data.SavedPlaylist
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.EmptyState
import dev.schlubbe.musicagent.desktop.ui.ErrorBox
import dev.schlubbe.musicagent.desktop.ui.IconBtn
import dev.schlubbe.musicagent.desktop.ui.LoadingBox
import dev.schlubbe.musicagent.desktop.ui.LocalUi
import dev.schlubbe.musicagent.desktop.ui.Pill
import dev.schlubbe.musicagent.desktop.ui.PrimaryButton
import dev.schlubbe.musicagent.desktop.ui.Route
import dev.schlubbe.musicagent.desktop.ui.TrackRow
import dev.schlubbe.musicagent.desktop.ui.sourceLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RemotePlaylistScreen(route: Route.RemotePlaylist) {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val data by g.store.data.collectAsState()
    var detail by remember(route) { mutableStateOf<RemotePlaylistDetailDto?>(null) }
    var error by remember(route) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var sort by remember(route) { mutableStateOf(TrackSort()) }

    LaunchedEffect(route, retry) {
        error = null
        try {
            detail = withContext(Dispatchers.IO) { g.search.getPlaylistDetail(route.source, route.sourceId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Playlist konnte nicht geladen werden"
        }
    }

    val d = detail
    val tracks = d?.tracks.orEmpty()
    val playable = tracks.filterNot { it.isDrmProtected }
    val shown = remember(tracks, sort) { sortTracks(tracks, sort) }
    val saved = data.savedPlaylists.any { it.source == route.source && it.sourceId == route.sourceId }
    val kind = if (route.isAlbum) "Album" else "Playlist"

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            DetailHeader(d?.thumbnailUrl ?: route.thumbnailUrl, "$kind · ${sourceLabel(route.source)}", d?.title ?: route.title ?: kind, c.accent) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    d?.owner?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.text, fontWeight = FontWeight.SemiBold) }
                    if (d != null) Text(
                        (if (d.owner != null) "· " else "") + trackCountLabel(d.trackCount ?: tracks.size, totalDurationSec(tracks)),
                        style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
                    )
                }
                d?.description?.takeIf { it.isNotBlank() }?.let { ExpandableText(it, Modifier.widthIn(max = 720.dp).padding(top = 4.dp)) }
                if (!d?.tags.isNullOrEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    d!!.tags.take(8).forEach { t -> Pill("#$t", false) { ui.navigate(Route.Search(t)) } }
                }
            }
        }
        if (d != null) item {
            ActionBar {
                if (playable.isNotEmpty()) {
                    PrimaryButton("Alle abspielen", PhosphorIcons.Fill.Play) { g.player.playQueue(playable, 0) }
                    PrimaryButton("Zufall", PhosphorIcons.Regular.Shuffle, accent = false) { g.player.playQueue(playable, 0, shuffle = true) }
                }
                PrimaryButton(if (saved) "Gespeichert" else "Speichern", if (saved) PhosphorIcons.Fill.BookmarkSimple else PhosphorIcons.Regular.BookmarkSimple, accent = false) {
                    g.store.toggleSaved(SavedPlaylist(d.source, d.sourceId, d.title, d.thumbnailUrl, d.owner, d.trackCount ?: tracks.size, d.webpageUrl, route.isAlbum))
                    ui.toast(if (saved) "Aus Bibliothek entfernt" else "In Bibliothek gespeichert")
                }
                Spacer(Modifier.weight(1f))
                if (playable.isNotEmpty()) {
                    IconBtn(PhosphorIcons.Regular.ListPlus, "Alle zur Warteschlange hinzufügen", tint = c.text) {
                        g.player.addToQueue(playable); ui.toast("${playable.size} Titel zur Warteschlange hinzugefügt")
                    }
                    IconBtn(PhosphorIcons.Regular.DownloadSimple, "Alle herunterladen", tint = c.text) { ui.download(playable) }
                    IconBtn(PhosphorIcons.Regular.Waveform, "Übergänge analysieren", tint = c.text) { ui.analyze(playable) }
                }
                IconBtn(PhosphorIcons.Regular.ShareNetwork, "Link kopieren (Teilen)", tint = c.text) { ui.copyLink(d.webpageUrl) }
            }
        }
        when {
            error != null && d == null -> item { ErrorBox(error!!) { retry++ } }
            d == null -> item { LoadingBox() }
            tracks.isEmpty() -> item { EmptyState(PhosphorIcons.Regular.MusicNotes, "Keine Titel", "Diese $kind enthält keine abspielbaren Titel.") }
            else -> {
                item { TrackTableHeader(sort, { sort = it }, Modifier.padding(horizontal = PagePad - 10.dp)) }
                itemsIndexed(shown, key = { i, t -> "${t.source}:${t.sourceId}:$i" }) { i, t ->
                    TrackRow(
                        t, Modifier.padding(horizontal = PagePad - 10.dp).alpha(if (t.isDrmProtected) 0.45f else 1f),
                        index = i, queueContext = shown.filterNot { it.isDrmProtected },
                    )
                }
            }
        }
    }
}
