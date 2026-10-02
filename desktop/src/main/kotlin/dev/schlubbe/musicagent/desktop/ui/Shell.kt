package dev.schlubbe.musicagent.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.Books
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.GearSix
import com.adamglin.phosphoricons.regular.Heart
import com.adamglin.phosphoricons.regular.House
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.SlidersHorizontal
import com.adamglin.phosphoricons.regular.UserCircle
import dev.schlubbe.musicagent.desktop.ui.screens.AccountScreen
import dev.schlubbe.musicagent.desktop.ui.screens.AddToPlaylistDialog
import dev.schlubbe.musicagent.desktop.ui.screens.ArtistScreen
import dev.schlubbe.musicagent.desktop.ui.screens.DownloadsScreen
import dev.schlubbe.musicagent.desktop.ui.screens.EqualizerScreen
import dev.schlubbe.musicagent.desktop.ui.screens.HomeScreen
import dev.schlubbe.musicagent.desktop.ui.screens.LibraryScreen
import dev.schlubbe.musicagent.desktop.ui.screens.LyricsPanel
import dev.schlubbe.musicagent.desktop.ui.screens.OnboardingScreen
import dev.schlubbe.musicagent.desktop.ui.screens.PlayerScreen
import dev.schlubbe.musicagent.desktop.ui.screens.PlaylistScreen
import dev.schlubbe.musicagent.desktop.ui.screens.QueuePanel
import dev.schlubbe.musicagent.desktop.ui.screens.RemotePlaylistScreen
import dev.schlubbe.musicagent.desktop.ui.screens.SearchScreen
import dev.schlubbe.musicagent.desktop.ui.screens.SettingsScreen
import dev.schlubbe.musicagent.desktop.ui.screens.WhatsNewDialog

/** Window content: sidebar · page · optional side panel, now-playing bar below. */
@Composable
fun AppShell() {
    val ui = LocalUi.current
    val c = C.c
    val settings by ui.graph.settings.state.collectAsState()
    if (!settings.onboardingDone) {
        Box(Modifier.fillMaxSize().background(c.bg)) { OnboardingScreen(tasteOnly = false) }
        return
    }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Sidebar(Modifier.width(232.dp).fillMaxHeight())
                Box(Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp, end = 8.dp).clip(RoundedCornerShape(16.dp)).background(c.surface.copy(alpha = if (c.isDark) 0.45f else 0.7f))) {
                    if (ui.playerOpen) PlayerScreen() else Page()
                }
                ui.panel?.let { p ->
                    Box(Modifier.width(340.dp).fillMaxHeight().padding(top = 8.dp, end = 8.dp).clip(RoundedCornerShape(16.dp)).background(c.surface)) {
                        when (p) {
                            SidePanel.QUEUE -> QueuePanel(Modifier.fillMaxSize())
                            SidePanel.LYRICS -> LyricsPanel(Modifier.fillMaxSize())
                        }
                    }
                }
            }
            HorizontalDivider(color = c.divider)
            NowPlayingBar()
        }
        SnackbarHost(ui.snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
        AddToPlaylistDialog()
        WhatsNewDialog()
    }
}

@Composable
private fun Page() {
    val ui = LocalUi.current
    Column(Modifier.fillMaxSize()) {
        if (ui.canGoBack) Row(Modifier.padding(start = 16.dp, top = 12.dp)) {
            IconBtn(PhosphorIcons.Regular.ArrowLeft, "Zurück (Alt+←)", tint = C.c.text, background = C.c.surfaceHigh) { ui.back() }
        }
        AnimatedContent(ui.route, transitionSpec = { fadeIn() togetherWith fadeOut() }, modifier = Modifier.weight(1f)) { r ->
            when (r) {
                Route.Home -> HomeScreen()
                is Route.Search -> SearchScreen(r.query)
                is Route.Library -> LibraryScreen(r.tab)
                is Route.Playlist -> PlaylistScreen(r.id)
                is Route.RemotePlaylist -> RemotePlaylistScreen(r)
                is Route.Artist, is Route.ArtistByName -> ArtistScreen(r)
                Route.Downloads -> DownloadsScreen()
                Route.Equalizer -> EqualizerScreen()
                Route.Settings -> SettingsScreen()
                Route.Account -> AccountScreen()
                is Route.Onboarding -> OnboardingScreen(r.tasteOnly)
            }
        }
    }
}

@Composable
private fun Sidebar(modifier: Modifier) {
    val ui = LocalUi.current
    val c = C.c
    val data by ui.graph.store.data.collectAsState()
    val settings by ui.graph.settings.state.collectAsState()
    val r = ui.route
    Column(modifier.padding(12.dp)) {
        Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(c.accent2), contentAlignment = Alignment.Center) {
                Text("G", color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(10.dp))
            Text("Grooveo", style = MaterialTheme.typography.headlineMedium, color = c.text)
        }
        NavItem(PhosphorIcons.Regular.House, "Start", r == Route.Home && !ui.playerOpen) { ui.navigateRoot(Route.Home) }
        NavItem(PhosphorIcons.Regular.MagnifyingGlass, "Suchen", r is Route.Search && !ui.playerOpen) { ui.navigateRoot(Route.Search()); ui.searchFocusRequest++ }
        NavItem(PhosphorIcons.Regular.Books, "Bibliothek", r is Route.Library && !ui.playerOpen) { ui.navigateRoot(Route.Library()) }
        NavItem(PhosphorIcons.Regular.DownloadSimple, "Downloads", r == Route.Downloads && !ui.playerOpen) { ui.navigateRoot(Route.Downloads) }
        NavItem(PhosphorIcons.Regular.SlidersHorizontal, "Equalizer", r == Route.Equalizer && !ui.playerOpen) { ui.navigateRoot(Route.Equalizer) }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PLAYLISTS", style = MaterialTheme.typography.labelSmall, color = c.textFaint, modifier = Modifier.weight(1f))
            IconBtn(PhosphorIcons.Regular.Plus, "Neue Playlist", size = 26.dp, iconSize = 15.dp) {
                val p = ui.graph.store.createPlaylist("Neue Playlist")
                ui.navigate(Route.Playlist(p.id))
            }
        }
        LazyColumn(Modifier.weight(1f).padding(top = 6.dp)) {
            item {
                SidebarEntry("Favoriten", "${data.likes.size} Titel", null, r == Route.Library(LibraryTab.LIKES), heart = true) { ui.navigateRoot(Route.Library(LibraryTab.LIKES)) }
            }
            items(data.playlists, key = { it.id }) { p ->
                SidebarEntry(p.name, "${p.tracks.size} Titel", p.coverPath?.let { "file:$it" } ?: p.tracks.firstOrNull()?.track?.thumbnailUrl, r == Route.Playlist(p.id)) { ui.navigate(Route.Playlist(p.id)) }
            }
            items(data.savedPlaylists, key = { "${it.source}:${it.sourceId}" }) { p ->
                SidebarEntry(p.title, p.owner ?: (if (p.isAlbum) "Album" else "Playlist"), p.thumbnailUrl, false) {
                    ui.navigate(Route.RemotePlaylist(p.source, p.sourceId, p.isAlbum, p.title, p.thumbnailUrl))
                }
            }
        }
        HorizontalDivider(color = c.divider, modifier = Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { ui.navigateRoot(Route.Account) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(PhosphorIcons.Regular.UserCircle, null, tint = c.accent, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Text(settings.profileName.ifBlank { "Lokales Profil" }, style = MaterialTheme.typography.labelLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconBtn(PhosphorIcons.Regular.GearSix, "Einstellungen") { ui.navigateRoot(Route.Settings) }
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = C.c
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) c.accentSoft else Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = if (selected) c.accentStrong else c.textMuted, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) c.accentStrong else c.text, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun SidebarEntry(title: String, subtitle: String, image: String?, selected: Boolean, heart: Boolean = false, onClick: () -> Unit) {
    val c = C.c
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (selected) c.surfaceHigh else Color.Transparent).clickable(onClick = onClick).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (heart) Box(Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).background(c.accent2), contentAlignment = Alignment.Center) {
            Icon(PhosphorIcons.Regular.Heart, null, tint = Color.White, modifier = Modifier.size(18.dp))
        } else Cover(image, 38.dp, radius = 6.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = c.textMuted, maxLines = 1)
        }
    }
}
