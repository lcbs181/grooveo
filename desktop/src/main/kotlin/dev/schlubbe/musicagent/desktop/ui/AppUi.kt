package dev.schlubbe.musicagent.desktop.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.desktop.AppGraph
import dev.schlubbe.musicagent.desktop.audio.TrackAnalyzer
import dev.schlubbe.musicagent.desktop.data.TrackAnalysis
import dev.schlubbe.musicagent.desktop.data.key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

enum class LibraryTab(val label: String) { LIKES("Favoriten"), PLAYLISTS("Playlists"), FOLLOWING("Folge ich"), HISTORY("Verlauf") }

/** Navigation destinations. */
sealed interface Route {
    data object Home : Route
    data class Search(val query: String? = null) : Route
    data class Library(val tab: LibraryTab = LibraryTab.LIKES) : Route
    data class Playlist(val id: String) : Route
    data class RemotePlaylist(val source: String, val sourceId: String, val isAlbum: Boolean, val title: String? = null, val thumbnailUrl: String? = null) : Route
    data class Artist(val source: String, val sourceId: String, val name: String? = null) : Route
    /** Artist known only by name (from a track); resolved via SearchRepository.findArtistByName. */
    data class ArtistByName(val name: String, val source: String) : Route
    data object Downloads : Route
    data object Equalizer : Route
    data object Settings : Route
    data object Account : Route
    data class Onboarding(val tasteOnly: Boolean = false) : Route
}

enum class SidePanel { QUEUE, LYRICS }

/** App-wide UI state and helpers, provided to every screen through [LocalUi]. */
class AppUi(val graph: AppGraph) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val snackbar = SnackbarHostState()
    private val stack = mutableStateListOf<Route>(Route.Home)
    val route: Route get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    var playerOpen by mutableStateOf(false)
    var panel by mutableStateOf<SidePanel?>(if (graph.settings.current.queuePanelOpen) SidePanel.QUEUE else null)
    /** Tracks waiting for the "Zu Playlist hinzufügen" dialog. */
    var addToPlaylist by mutableStateOf<List<TrackResultDto>?>(null)
    var showWhatsNew by mutableStateOf(false)
    var searchFocusRequest by mutableStateOf(0)
    var windowVisible by mutableStateOf(true)

    fun navigate(r: Route) {
        playerOpen = false
        if (stack.last() != r) stack.add(r)
    }

    /** Top-level sidebar destinations reset the stack. */
    fun navigateRoot(r: Route) {
        playerOpen = false
        stack.clear()
        stack.add(Route.Home)
        if (r != Route.Home) stack.add(r)
    }

    fun back() {
        if (playerOpen) { playerOpen = false; return }
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun togglePanel(p: SidePanel) {
        panel = if (panel == p) null else p
        graph.settings.update { it.copy(queuePanelOpen = panel == SidePanel.QUEUE) }
    }

    fun toast(message: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(message) } }

    fun copyLink(url: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null) }
        toast("Link kopiert")
    }

    fun toggleLike(t: TrackResultDto) {
        graph.store.toggleLike(t)
        toast(if (graph.store.isLiked(t)) "Zu Favoriten hinzugefügt" else "Aus Favoriten entfernt")
    }

    fun download(tracks: List<TrackResultDto>) {
        graph.downloads.enqueueAll(tracks)
        toast(if (tracks.size == 1) "Download gestartet" else "${tracks.size} Downloads gestartet")
    }

    fun goToArtist(t: TrackResultDto) = t.artist?.takeIf { it.isNotBlank() }?.let { navigate(Route.ArtistByName(it, t.source)) }

    fun dislikeArtist(t: TrackResultDto) {
        val a = t.artist ?: return
        graph.store.dislikeArtist(a)
        toast("$a wird seltener empfohlen")
    }

    /** "Übergänge analysieren" for one or many tracks (downloads are analysed from disk). */
    fun analyze(tracks: List<TrackResultDto>) {
        toast(if (tracks.size == 1) "Übergänge werden analysiert …" else "${tracks.size} Titel werden analysiert …")
        scope.launch(Dispatchers.IO) {
            var ok = 0
            tracks.forEach { t ->
                val src = runCatching { graph.player.resolveStream(t) }.getOrNull() ?: return@forEach
                TrackAnalyzer.analyze(src.url, src.headers)?.let {
                    graph.store.putAnalysis(t.key, TrackAnalysis(it.mixInMs, it.mixOutMs)); ok++
                }
            }
            toast(when {
                tracks.size == 1 && ok == 1 -> "Übergänge analysiert"
                ok == 0 -> "Analyse nicht möglich (Titel zu kurz oder nicht ladbar)"
                else -> "$ok von ${tracks.size} Titeln analysiert"
            })
        }
    }
}

val LocalUi = staticCompositionLocalOf<AppUi> { error("AppUi not provided") }
