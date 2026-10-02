package dev.schlubbe.musicagent.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.schlubbe.musicagent.desktop.APP_VERSION
import dev.schlubbe.musicagent.desktop.AppGraph
import dev.schlubbe.musicagent.desktop.audio.EqProfile
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/** Renders screens offscreen to PNG for visual review. Opt-in: GROOVEO_SCREENSHOTS=<dir>. */
class ScreenshotRender {
    @Test fun render() {
        val out = System.getenv("GROOVEO_SCREENSHOTS")?.let(::File) ?: return
        out.mkdirs()
        val graph = AppGraph(Files.createTempDirectory("grooveo-shot").toFile())
        graph.settings.update { it.copy(onboardingDone = true, lastSeenVersion = APP_VERSION, eq = EqProfile.PRESETS[1]) }
        val ui = AppUi(graph)
        val routes = (System.getenv("GROOVEO_ROUTES") ?: "eq").split(',')
        for (r in routes) {
            when (r) {
                "eq" -> ui.navigateRoot(Route.Equalizer)
                "home" -> ui.navigateRoot(Route.Home)
                "settings" -> ui.navigateRoot(Route.Settings)
                "search" -> ui.navigateRoot(Route.Search("daft punk"))
            }
            val scene = ImageComposeScene(1440, (System.getenv("GROOVEO_SHOT_H") ?: "900").toInt(), Density(1f)) {
                CompositionLocalProvider(LocalUi provides ui) { GrooveoTheme("dark") { AppShell() } }
            }
            var t = 0L
            repeat(60) { scene.render(t); t += 100_000_000L; Thread.sleep(if (r == "eq") 10 else 150) }
            val img = scene.render(t)
            File(out, "$r.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
        graph.close()
    }
}
