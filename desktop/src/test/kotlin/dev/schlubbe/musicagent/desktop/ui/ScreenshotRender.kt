package dev.schlubbe.musicagent.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.schlubbe.musicagent.desktop.APP_VERSION
import dev.schlubbe.musicagent.desktop.AppGraph
import dev.schlubbe.musicagent.playback.eq.EqProfile
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/** Renders screens offscreen to PNG for visual review. Opt-in: GROOVEO_SCREENSHOTS=<dir>. */
class ScreenshotRender {
    @Test fun render() {
        val out = System.getenv("GROOVEO_SCREENSHOTS")?.let(::File) ?: return
        out.mkdirs()
        val dataDir = Files.createTempDirectory("grooveo-shot").toFile()
        // GROOVEO_SHOT_DATA: copy library and settings from a real data folder (the copy is used, never the original)
        System.getenv("GROOVEO_SHOT_DATA")?.let { src -> listOf("library.json", "settings.json").forEach { f -> File(src, f).takeIf { it.isFile }?.copyTo(File(dataDir, f)) } }
        val graph = AppGraph(dataDir)
        graph.settings.update { it.copy(onboardingDone = true, lastSeenVersion = APP_VERSION, eq = EqProfile.PRESETS[1]) }
        val ui = AppUi(graph)
        val routes = (System.getenv("GROOVEO_ROUTES") ?: "eq").split(',')
        for (r in routes) {
            when (r) {
                "eq" -> ui.navigateRoot(Route.Equalizer)
                "home" -> ui.navigateRoot(Route.Home)
                "settings" -> ui.navigateRoot(Route.Settings)
                "search" -> ui.navigateRoot(Route.Search("daft punk"))
                "library" -> ui.navigateRoot(Route.Library())
                "downloads" -> ui.navigateRoot(Route.Downloads)
                // the assistant panel; with GROOVEO_AI_URL and GROOVEO_AI_PROMPT a real conversation up to its first question
                "ai" -> {
                    ui.navigateRoot(Route.Home)
                    ui.panel = SidePanel.AI
                    System.getenv("GROOVEO_AI_URL")?.let { url -> graph.settings.update { it.copy(aiProvider = "local", aiBaseUrl = url) } }
                    System.getenv("GROOVEO_AI_PROMPT")?.let { prompt ->
                        graph.ai.send(prompt)
                        val end = System.currentTimeMillis() + 300_000
                        while (System.currentTimeMillis() < end && graph.ai.state.value.let { s ->
                            s.items.none { it is dev.schlubbe.musicagent.desktop.ai.AiItem.Confirm } && (s.running || s.items.last() is dev.schlubbe.musicagent.desktop.ai.AiItem.User)
                        }) Thread.sleep(500)
                    }
                }
            }
            val scene = ImageComposeScene((System.getenv("GROOVEO_SHOT_W") ?: "1440").toInt(), (System.getenv("GROOVEO_SHOT_H") ?: "900").toInt(), Density(1f)) {
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
