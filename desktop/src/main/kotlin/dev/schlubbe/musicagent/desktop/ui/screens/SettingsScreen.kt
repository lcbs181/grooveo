package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Export
import com.adamglin.phosphoricons.regular.FolderOpen
import com.adamglin.phosphoricons.regular.SlidersHorizontal
import dev.schlubbe.musicagent.desktop.APP_VERSION
import dev.schlubbe.musicagent.desktop.audio.Sound3dPreset
import dev.schlubbe.musicagent.desktop.data.Settings
import dev.schlubbe.musicagent.desktop.data.UpdateInfo
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.LocalUi
import dev.schlubbe.musicagent.desktop.ui.Panel
import dev.schlubbe.musicagent.desktop.ui.Pill
import dev.schlubbe.musicagent.desktop.ui.PrimaryButton
import dev.schlubbe.musicagent.desktop.ui.Route
import dev.schlubbe.musicagent.desktop.ui.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

val CROSSFADE_OPTIONS = listOf(0, 2, 4, 6, 8, 12)

val SHORTCUTS = listOf(
    "Leertaste" to "Play/Pause",
    "← / →" to "5 Sekunden zurück / vor",
    "Strg + ← / →" to "Vorheriger / nächster Titel",
    "Strg + ↑ / ↓" to "Lautstärke",
    "Strg + F" to "Suche",
    "Strg + L" to "Gefällt mir",
    "Strg + S" to "Zufallswiedergabe",
    "Strg + R" to "Wiederholen",
    "Strg + E" to "Equalizer",
    "Alt + ←" to "Zurück",
    "Esc" to "Player schließen",
)

/** "dd.MM.yyyy, HH:mm" in [zone] for an ISO instant, or null if absent/unparseable. */
fun formatBackupTime(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String? =
    iso?.let { runCatching { DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm").withZone(zone).format(Instant.parse(it)) }.getOrNull() }

/** Applies a source toggle, refusing to switch off the last enabled source (null = refused). */
fun toggleSourceSetting(s: Settings, soundCloud: Boolean, enabled: Boolean): Settings? {
    val next = if (soundCloud) s.copy(sourceSoundCloud = enabled) else s.copy(sourceYouTube = enabled)
    return if (!next.sourceSoundCloud && !next.sourceYouTube) null else next
}

fun settingsDirSize(f: File): Long = f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

private fun imageCacheDir() = File(System.getProperty("user.home"), ".cache/grooveo/images")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val s by g.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmRestore by remember { mutableStateOf<File?>(null) }
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    fun set(f: (Settings) -> Settings) = g.settings.update(f)
    LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { settingsDirSize(imageCacheDir()) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp)) {
        Column(Modifier.widthIn(max = 860.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Einstellungen", style = MaterialTheme.typography.displayMedium, color = c.text)

            SettingsSection("Wiedergabe") {
                SettingsSwitchRow("Datensparmodus", "Spielt nur heruntergeladene Titel ab, es wird nie gestreamt.", s.dataSaverMode) { v -> set { it.copy(dataSaverMode = v) } }
                SettingsRow("Überblendung", if (s.crossfadeSeconds == 0) "Aus – Titel folgen lückenlos" else "${s.crossfadeSeconds} s weiche Übergänge zwischen Titeln") {
                    SettingsSegmented(CROSSFADE_OPTIONS.map { it to if (it == 0) "Aus" else "$it s" }, s.crossfadeSeconds) { v -> set { it.copy(crossfadeSeconds = v) } }
                }
                SettingsRow("Wiedergabestil", "Darstellung der Fortschrittsleiste") {
                    SettingsSegmented(listOf("waveform" to "Waveform", "bars" to "Balken"), s.playerStyle) { v -> set { it.copy(playerStyle = v) } }
                }
                SettingsSwitchRow("Automatische Weiterempfehlung", "Spielt ähnliche Titel, wenn die Warteschlange endet", s.autoplayRadio) { v -> set { it.copy(autoplayRadio = v) } }
                SettingsSwitchRow(
                    "Anstößige Inhalte ausblenden",
                    "Versucht, Titel mit expliziten Begriffen aus Charts, Mixes und Vorschlägen herauszuhalten. Basiert auf dem Titel-/Künstlertext – kein hundertprozentiger Schutz.",
                    s.contentSafetyFilter,
                ) { v -> set { it.copy(contentSafetyFilter = v) } }
            }

            SettingsSection("Klang") {
                SettingsRow("Equalizer", "${s.eq.name}${if (!s.eq.enabled) " (aus)" else ""} · Bänder und Vorlagen einstellen") {
                    PrimaryButton("Öffnen", PhosphorIcons.Regular.SlidersHorizontal, accent = false) { ui.navigate(Route.Equalizer) }
                }
                val preset = Sound3dPreset.of(s.sound3dPreset)
                SettingsRow("3D-Sound · Raumklang-Vorlage", preset.description, wide = true) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Sound3dPreset.entries.forEach { p -> Pill(p.label, p == preset) { set { it.copy(sound3dPreset = p.name) } } }
                    }
                }
            }

            SettingsSection("Darstellung") {
                SettingsRow("Design", "Hell, dunkel oder wie das System") {
                    SettingsSegmented(listOf("system" to "System", "light" to "Hell", "dark" to "Dunkel"), s.themeMode) { v -> set { it.copy(themeMode = v) } }
                }
            }

            SettingsSection("Startseite") {
                SettingsSwitchRow("Mix & Stimmungen", "Mixe und Stimmungs-Kacheln anzeigen", s.showMixControls) { v -> set { it.copy(showMixControls = v) } }
                SettingsSwitchRow("Im Fokus", "Hervorgehobene Titel anzeigen", s.showFeatured) { v -> set { it.copy(showFeatured = v) } }
                SettingsSwitchRow("Neu von Künstlern", "Neue Uploads gefolgter Künstler anzeigen", s.showNewUploads) { v -> set { it.copy(showNewUploads = v) } }
                SettingsRow("Musikgeschmack anpassen", "Genres & Künstler für Home-Vorschläge") {
                    PrimaryButton("Anpassen", accent = false) { ui.navigate(Route.Onboarding(tasteOnly = true)) }
                }
            }

            SettingsSection("Quellen") {
                SettingsSwitchRow("SoundCloud", "Suche, Charts und Wiedergabe über SoundCloud", s.sourceSoundCloud) { v ->
                    toggleSourceSetting(s, soundCloud = true, enabled = v)?.let { n -> set { n } } ?: ui.toast("Mindestens eine Quelle muss aktiv bleiben")
                }
                SettingsSwitchRow("YouTube Music", "Suche und Wiedergabe über YouTube Music", s.sourceYouTube) { v ->
                    toggleSourceSetting(s, soundCloud = false, enabled = v)?.let { n -> set { n } } ?: ui.toast("Mindestens eine Quelle muss aktiv bleiben")
                }
            }

            SettingsSection("Downloads & Benachrichtigungen") {
                SettingsRow("Speicherort", g.downloadDir().path) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.downloadDir.isNotBlank()) TextButton({ set { it.copy(downloadDir = "") } }) { Text("Standard", color = c.textMuted) }
                        PrimaryButton("Ändern …", PhosphorIcons.Regular.FolderOpen, accent = false) {
                            val ch = JFileChooser(g.downloadDir()).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Speicherort für Downloads" }
                            if (ch.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) set { it.copy(downloadDir = ch.selectedFile.path) }
                        }
                    }
                }
                SettingsSwitchRow("Neue Uploads von gefolgten Künstlern", "Benachrichtigung, wenn gefolgte Künstler etwas Neues hochladen", s.notifyNewUploads) { v -> set { it.copy(notifyNewUploads = v) } }
            }

            SettingsSection("Fenster") {
                SettingsSwitchRow("Beim Schließen in den Tray minimieren", "Musik läuft weiter, Grooveo bleibt im Systembereich", s.minimizeToTray) { v -> set { it.copy(minimizeToTray = v) } }
            }

            SettingsSection("Sicherung") {
                SettingsRow("Letzte Sicherung", formatBackupTime(s.lastBackupAt) ?: "Noch keine Sicherung") {
                    PrimaryButton("Jetzt sichern", accent = false) {
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { g.backup.export() } }
                                .onSuccess { ui.toast("Sicherung erstellt") }.onFailure { ui.toast("Sicherung fehlgeschlagen: ${it.message}") }
                        }
                    }
                }
                SettingsRow("Backup exportieren", "Likes, Playlists, gefolgte Künstler und Einstellungen als Datei – kompatibel mit der Android-App") {
                    PrimaryButton("Exportieren …", PhosphorIcons.Regular.Export, accent = false) {
                        val ch = JFileChooser().apply {
                            dialogTitle = "Sicherung exportieren"
                            selectedFile = File("grooveo_backup.json")
                            fileFilter = FileNameExtensionFilter("Grooveo-Sicherung (*.json)", "json")
                        }
                        if (ch.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                            val f = ch.selectedFile.let { if (it.extension.isEmpty()) File(it.path + ".json") else it }
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { g.backup.export(f) } }
                                    .onSuccess { ui.toast("Gesichert nach ${f.name}") }.onFailure { ui.toast("Export fehlgeschlagen: ${it.message}") }
                            }
                        }
                    }
                }
                SettingsRow("Aus Sicherung wiederherstellen", "Ersetzt Playlists, Likes und Einstellungen") {
                    PrimaryButton("Datei wählen …", accent = false) {
                        val ch = JFileChooser(g.backup.listBackups().firstOrNull()?.parentFile).apply {
                            dialogTitle = "Sicherung wiederherstellen"
                            fileFilter = FileNameExtensionFilter("Grooveo-Sicherung (*.json)", "json")
                        }
                        if (ch.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) confirmRestore = ch.selectedFile
                    }
                }
                SettingsSwitchRow("Automatische Sicherung", "Sichert deine Bibliothek regelmäßig im Datenordner", s.autoBackup) { v -> set { it.copy(autoBackup = v) } }
            }

            SettingsSection("Speicher") {
                SettingsRow("Bild-Cache", cacheBytes?.let { "${formatBytes(it)} zwischengespeicherte Cover" } ?: "Wird berechnet …") {
                    PrimaryButton("Leeren", accent = false) {
                        scope.launch {
                            withContext(Dispatchers.IO) { imageCacheDir().deleteRecursively() }
                            cacheBytes = 0L
                            ui.toast("Bild-Cache geleert")
                        }
                    }
                }
            }

            SettingsSection("Tastenkürzel") {
                SHORTCUTS.forEach { (keys, action) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(action, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
                        Text(
                            keys, style = MaterialTheme.typography.labelMedium, color = c.text,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.surfaceHigh).border(1.dp, c.divider, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            SettingsSection("Info") {
                SettingsRow("Grooveo $APP_VERSION", "Desktop-Version für Linux") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ ui.showWhatsNew = true }) { Text("Was ist neu?", color = c.accent) }
                        if (checking) CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).height(20.dp), color = c.accent, strokeWidth = 2.dp)
                        else PrimaryButton("Nach Updates suchen", accent = false) {
                            checking = true
                            scope.launch {
                                val r = runCatching { g.updates.check() }
                                checking = false
                                r.onSuccess { info -> if (info == null) ui.toast("Grooveo ist auf dem neuesten Stand") else update = info }
                                    .onFailure { ui.toast("Update-Prüfung fehlgeschlagen") }
                            }
                        }
                    }
                }
                SettingsRow("Lizenz", "GNU General Public License v3.0 (GPL-3.0)") {}
                SettingsRow("Danksagungen", "FFmpeg (Audio-Dekodierung) · EQ-Filterdesign nach Martin Vicanek · NewPipeExtractor (YouTube Music) · LRCLIB (Songtexte) · Phosphor Icons") {}
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    confirmRestore?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Aus Sicherung wiederherstellen?") },
            text = { Text("Playlists, Likes und Einstellungen werden durch den Stand der Sicherung „${f.name}“ ersetzt.") },
            confirmButton = {
                TextButton({
                    confirmRestore = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { g.backup.import(f) } }
                            .onSuccess { ui.toast("Sicherung wiederhergestellt") }.onFailure { ui.toast(it.message ?: "Wiederherstellen fehlgeschlagen") }
                    }
                }) { Text("Wiederherstellen", color = c.accent2) }
            },
            dismissButton = { TextButton({ confirmRestore = null }) { Text("Abbrechen") } },
            containerColor = c.surface,
        )
    }
    update?.let { u ->
        AlertDialog(
            onDismissRequest = { update = null },
            title = { Text("Ein Update ist verfügbar") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Grooveo ${u.version} (installiert: $APP_VERSION)", fontWeight = FontWeight.SemiBold)
                    if (u.notes.isNotBlank()) Text(u.notes, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton({
                    update = null
                    runCatching { Desktop.getDesktop().browse(URI(u.url.ifBlank { "https://github.com/lcbs181/grooveo/releases/latest" })) }
                        .onFailure { ui.copyLink(u.url) }
                }) { Text("Jetzt aktualisieren", color = c.accent2) }
            },
            dismissButton = { TextButton({ update = null }) { Text("Später") } },
            containerColor = c.surface,
        )
    }
}


@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.headlineMedium, color = C.c.text, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
    Panel(Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
internal fun SettingsRow(title: String, subtitle: String?, wide: Boolean = false, control: @Composable () -> Unit) {
    val c = C.c
    if (wide) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.text)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
            control()
        }
    } else Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        control()
    }
    HorizontalDivider(color = c.divider.copy(alpha = 0.5f))
}

@Composable
internal fun SettingsSwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) =
    SettingsRow(title, subtitle) {
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.c.accent))
    }

/** Segmented pill group (single choice). */
@Composable
internal fun <T> SettingsSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val c = C.c
    Row(Modifier.clip(RoundedCornerShape(50)).background(c.surfaceHigh).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (v, label) ->
            val on = v == selected
            Text(
                label, style = MaterialTheme.typography.labelLarge, color = if (on) androidx.compose.ui.graphics.Color.White else c.text,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) c.accent else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(v) }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
