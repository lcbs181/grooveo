package dev.schlubbe.musicagent.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.schlubbe.musicagent.desktop.ui.AppShell
import dev.schlubbe.musicagent.desktop.ui.AppUi
import dev.schlubbe.musicagent.desktop.ui.GrooveoTheme
import dev.schlubbe.musicagent.desktop.ui.LocalUi
import dev.schlubbe.musicagent.desktop.ui.Route
import dev.schlubbe.musicagent.desktop.mpris.Mpris
import okio.Path.Companion.toOkioPath
import java.io.File

fun main() {
    System.setProperty("skiko.renderApi", System.getProperty("skiko.renderApi") ?: "OPENGL")
    val graph = AppGraph()
    val ui = AppUi(graph)
    val mpris = runCatching { Mpris(graph.player, ui, onRaise = { ui.windowVisible = true }) }
        .onFailure { java.util.logging.Logger.getLogger("Main").warning("MPRIS unavailable: $it") }.getOrNull()
    application(exitProcessOnExit = true) {
        val settings by graph.settings.state.collectAsState()
        val windowState = rememberWindowState(size = DpSize(1360.dp, 860.dp), position = WindowPosition.PlatformDefault)
        val q by graph.player.state.collectAsState()
        val e by graph.player.engineState.collectAsState()
        val quit = {
            mpris?.close()
            graph.close()
            exitApplication()
        }
        setSingletonImageLoaderFactory { ctx: PlatformContext ->
            ImageLoader.Builder(ctx)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
                .memoryCache { MemoryCache.Builder().maxSizePercent(ctx, 0.2).build() }
                .diskCache { DiskCache.Builder().directory(File(System.getProperty("user.home"), ".cache/grooveo/images").toOkioPath()).maxSizeBytes(256L shl 20).build() }
                .crossfade(true)
                .build()
        }
        val icon = painterResource("grooveo.png")
        val trayState = androidx.compose.ui.window.rememberTrayState()
        LaunchedEffect(Unit) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                NewUploadsWatcher(graph).run { t ->
                    trayState.sendNotification(androidx.compose.ui.window.Notification("Neu von ${t.artist ?: "einem Künstler"}", t.title))
                }
            }
        }
        Tray(
            icon = icon,
            state = trayState,
            tooltip = q.current?.let { "${it.title} – ${it.artist ?: ""}" } ?: "Grooveo",
            onAction = { ui.windowVisible = true },
            menu = {
                Item(if (e.playing) "Pause" else "Abspielen", onClick = graph.player::togglePlay)
                Item("Weiter", onClick = graph.player::next)
                Item("Zurück", onClick = graph.player::previous)
                Separator()
                Item(if (ui.windowVisible) "Fenster ausblenden" else "Fenster zeigen", onClick = { ui.windowVisible = !ui.windowVisible })
                Item("Beenden", onClick = quit)
            },
        )
        Window(
            onCloseRequest = { if (settings.minimizeToTray && q.current != null && e.playing) ui.windowVisible = false else quit() },
            visible = ui.windowVisible,
            state = windowState,
            title = q.current?.let { "${it.title} · ${it.artist ?: ""} – Grooveo" } ?: "Grooveo",
            icon = icon,
            onPreviewKeyEvent = { handleShortcut(it, ui) },
        ) {
            window.minimumSize = java.awt.Dimension(980, 640)
            LaunchedEffect(Unit) {
                if (graph.settings.current.lastSeenVersion != APP_VERSION && graph.settings.current.onboardingDone) ui.showWhatsNew = true
            }
            CompositionLocalProvider(LocalUi provides ui) {
                GrooveoTheme(settings.themeMode) { AppShell() }
            }
        }
    }
}

/** Global keyboard shortcuts. Text fields keep plain keys (space, arrows) for themselves. */
internal fun handleShortcut(e: KeyEvent, ui: AppUi): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val p = ui.graph.player
    val focusInText = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.javaClass?.name?.contains("Text") == true
    return when {
        e.isCtrlPressed && e.key == Key.F -> { ui.navigateRoot(Route.Search()); ui.searchFocusRequest++; true }
        e.isCtrlPressed && e.key == Key.DirectionRight -> { p.next(); true }
        e.isCtrlPressed && e.key == Key.DirectionLeft -> { p.previous(); true }
        e.isCtrlPressed && e.key == Key.DirectionUp -> { p.setVolume(ui.graph.settings.current.volume + 0.05f); true }
        e.isCtrlPressed && e.key == Key.DirectionDown -> { p.setVolume(ui.graph.settings.current.volume - 0.05f); true }
        e.isCtrlPressed && e.key == Key.S -> { p.toggleShuffle(); true }
        e.isCtrlPressed && e.key == Key.R -> { p.cycleRepeat(); true }
        e.isCtrlPressed && e.key == Key.L -> { p.state.value.current?.let(ui::toggleLike); true }
        e.isCtrlPressed && e.key == Key.E -> { ui.navigateRoot(Route.Equalizer); true }
        e.isAltPressed && e.key == Key.DirectionLeft -> { ui.back(); true }
        e.key == Key.MediaPlayPause -> { p.togglePlay(); true }
        e.key == Key.MediaNext -> { p.next(); true }
        e.key == Key.MediaPrevious -> { p.previous(); true }
        focusInText -> false
        e.key == Key.Spacebar -> { p.togglePlay(); true }
        e.key == Key.DirectionRight -> { p.seek(p.engineState.value.positionSec + 5); true }
        e.key == Key.DirectionLeft -> { p.seek((p.engineState.value.positionSec - 5).coerceAtLeast(0.0)); true }
        e.key == Key.Escape && ui.playerOpen -> { ui.playerOpen = false; true }
        else -> false
    }
}
