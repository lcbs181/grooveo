package dev.schlubbe.musicagent.desktop.ui.screens

import dev.schlubbe.musicagent.desktop.audio.FxUnitSpec
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.OutlinedTextField
import dev.schlubbe.musicagent.desktop.data.imageCacheDir
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
import androidx.compose.material3.LinearProgressIndicator
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
    var progress by remember { mutableStateOf<Float?>(null) }
    var updateJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
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
                SettingsSwitchRow(
                    "DJ-Übergänge",
                    "Beat-synchrone Übergänge wie ein DJ: Tempo angleichen, Bässe tauschen, Filter und Echo. Titel werden dafür automatisch analysiert; ohne erkannten Beat gilt die Überblendung.",
                    s.djTransitions,
                ) { v -> set { it.copy(djTransitions = v) } }
                if (s.djTransitions) {
                    SettingsRow("Übergangsstil", dev.schlubbe.musicagent.desktop.playback.TransitionPlanner.STYLES[s.djStyle], wide = true) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DJ_STYLES.forEach { (k, label) -> Pill(label, s.djStyle == k) { set { it.copy(djStyle = k) } } }
                        }
                    }
                    SettingsSwitchRow("Harmonisch mischen", "Transponiert den nächsten Titel um bis zu 2 Halbtöne, wenn die Tonarten nicht zueinander passen", s.djHarmonic) { v -> set { it.copy(djHarmonic = v) } }
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
                val chain = s.fxChain
                SettingsRow(
                    "FX-Kette",
                    chain?.let { ch -> "${ch.name}: " + ch.units.joinToString(" › ") { u -> FxUnitSpec.of(u.type)?.label ?: u.type } }
                        ?: if (s.fxChainPresets.isEmpty()) "Aus · der KI-Assistent kann Ketten aus Filtern, EQ, Echo und Raum bauen" else "Aus",
                    wide = s.fxChainPresets.isNotEmpty(),
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("Aus", chain == null) { set { it.copy(fxChain = null) } }
                        s.fxChainPresets.forEach { p -> Pill(p.name, chain?.name == p.name) { set { it.copy(fxChain = p) } } }
                    }
                }
            }

            SettingsSection("KI-Assistent") {
                var baseUrl by remember { mutableStateOf(s.aiBaseUrl) }
                var model by remember { mutableStateOf(s.aiModel) }
                var key by remember { mutableStateOf("") }
                var test by remember { mutableStateOf<String?>(null) }
                var models by remember { mutableStateOf<List<String>>(emptyList()) }
                SettingsRow("Anbieter", "Lokal bleibt alles auf dem PC und kostet nichts. Claude ist bei langen Aufgaben deutlich zuverlässiger. Öffnen mit Strg+K.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("Lokal", s.aiProvider != "anthropic") { set { it.copy(aiProvider = "local", aiModel = "") }; model = "" }
                        Pill("Anthropic API", s.aiProvider == "anthropic") { set { it.copy(aiProvider = "anthropic", aiModel = "") }; model = "" }
                    }
                }
                if (s.aiProvider != "anthropic") {
                    SettingsRow("Server-Adresse", "llama.cpp (llama-server --jinja), Ollama, LM Studio: OpenAI-kompatible API", wide = true) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(baseUrl, { baseUrl = it; set { s2 -> s2.copy(aiBaseUrl = it.trim()) } }, singleLine = true, colors = fieldColors(), modifier = Modifier.weight(1f))
                            PrimaryButton("Testen", accent = false) {
                                test = "Verbinde …"
                                scope.launch {
                                    test = runCatching { dev.schlubbe.musicagent.desktop.ai.OpenAiProvider.listModels(baseUrl) }
                                        .fold({ m -> models = m; if (m.isEmpty()) "Verbunden, kein Modell gemeldet" else "Verbunden · Modell unten wählen" }, { models = emptyList(); "Keine Verbindung: ${it.message}" })
                                }
                            }
                        }
                    }
                    test?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted) }
                } else {
                    SettingsRow("API-Schlüssel", if (g.ai.hasApiKey) "Gespeichert (nur für dich lesbar auf diesem PC)" else "Von console.anthropic.com; wird nur auf diesem PC gespeichert", wide = true) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(key, { key = it }, singleLine = true, placeholder = { Text("sk-ant-…") }, visualTransformation = PasswordVisualTransformation(), colors = fieldColors(), modifier = Modifier.weight(1f))
                            PrimaryButton("Speichern", accent = false) { g.ai.saveApiKey(key); key = ""; ui.toast("API-Schlüssel gespeichert") }
                        }
                    }
                }
                SettingsRow("Modell", if (s.aiProvider == "anthropic") "Leer = ${dev.schlubbe.musicagent.desktop.ai.AnthropicProvider.DEFAULT_MODEL}" else "Leer = das Modell, das der Server geladen hat", wide = true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(model, { model = it; set { s2 -> s2.copy(aiModel = it.trim()) } }, singleLine = true, colors = fieldColors(), modifier = Modifier.weight(1f))
                        if (s.aiProvider == "anthropic") dev.schlubbe.musicagent.desktop.ai.AnthropicProvider.MODELS.forEach { m ->
                            Pill(m.removePrefix("claude-"), s.aiModel == m) { model = m; set { it.copy(aiModel = m) } }
                        }
                    }
                    if (s.aiProvider != "anthropic" && models.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        models.forEach { m -> Pill(m.substringAfterLast('/'), s.aiModel == m) { model = m; set { it.copy(aiModel = m) } } }
                    }
                }
                SettingsSwitchRow("Geschmack mitgeben", "Likes, Playlists und Verlauf als kurze Zusammenfassung", s.aiShareTaste) { v -> set { it.copy(aiShareTaste = v) } }
                SettingsSwitchRow("Vor dem Löschen fragen", "Playlists, Downloads, Verlauf, Titel aus Playlists", s.aiAskDelete) { v -> set { it.copy(aiAskDelete = v) } }
                SettingsSwitchRow("Vor FX-Ketten fragen", "Die KI darf Ketten aus Filtern, EQ, Echo und Raum bauen, schaltet sie aber erst nach deinem OK ein", s.aiAskFx) { v -> set { it.copy(aiAskFx = v) } }
                SettingsSwitchRow(
                    "Bei neuer Lautstärke fragen",
                    "Lautstärke, Lautstärke angleichen, Equalizer und Bass. Einmal erlaubt, gilt sie weiter, bis die KI wieder etwas ändern will. Blenden in Übergängen zählen nicht.",
                    s.aiAskLoudness,
                ) { v -> set { it.copy(aiAskLoudness = v) } }
                SettingsSwitchRow("Vor allen anderen Änderungen fragen", "Sonst laufen sie direkt; jede Antwort lässt sich rückgängig machen", s.aiAskOther) { v -> set { it.copy(aiAskOther = v) } }
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

            SettingsSection("Mit Smartphone synchronisieren") {
                var syncOn by remember { mutableStateOf(g.sync.running) }
                var pairing by remember { mutableStateOf(g.sync.pairingUri to g.sync.key) }
                SettingsSwitchRow(
                    "Synchronisierung bereitstellen",
                    "Likes, Playlists und gefolgte Künstler zwischen Handy und PC abgleichen – nur im selben WLAN, startet mit der App",
                    syncOn,
                ) { v ->
                    runCatching { if (v) g.sync.start() else g.sync.stop() }.onFailure { ui.toast("Sync konnte nicht gestartet werden: ${it.message}") }
                    syncOn = g.sync.running
                    pairing = g.sync.pairingUri to g.sync.key
                }
                if (syncOn) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        QrCode(pairing.first, size = 168.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Handy koppeln", color = c.text, fontWeight = FontWeight.SemiBold)
                            Text("In der Handy-App: Einstellungen → Mit PC synchronisieren → QR-Code scannen. Der Code bleibt gleich, die Kopplung muss nur einmal erfolgen.", color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                            Text("Oder Code eintippen: ${pairing.second}", color = c.text, fontWeight = FontWeight.SemiBold)
                            PrimaryButton("Neuen Code erzeugen", accent = false) {
                                g.sync.regenerateKey()
                                pairing = g.sync.pairingUri to g.sync.key
                                ui.toast("Neuer Code – bereits gekoppelte Handys müssen neu scannen")
                            }
                        }
                    }
                }
            }

            SettingsSection("KI-Steuerung (MCP)") {
                var mcpOn by remember { mutableStateOf(g.mcp.running) }
                var mcpReadOnly by remember { mutableStateOf(g.mcp.readOnly) }
                var mcpCmd by remember { mutableStateOf(g.mcp.claudeCodeCommand) }
                val activity by g.mcp.activity.collectAsState()
                SettingsSwitchRow(
                    "KI darf Grooveo steuern",
                    "Ein KI-Assistent (z. B. Claude) kann Musik suchen, abspielen, Playlists anlegen, EQ und Einstellungen ändern – nur auf diesem PC, mit Zugangsschlüssel",
                    mcpOn,
                ) { v ->
                    runCatching { if (v) g.mcp.start() else g.mcp.stop() }.onFailure { ui.toast("KI-Steuerung konnte nicht gestartet werden: ${it.message}") }
                    mcpOn = g.mcp.running
                    mcpCmd = g.mcp.claudeCodeCommand
                }
                if (mcpOn) {
                    SettingsSwitchRow("Nur lesen", "Die KI sieht Bibliothek und Wiedergabe, darf aber nichts ändern", mcpReadOnly) { v ->
                        g.mcp.setReadOnly(v); mcpReadOnly = v
                    }
                    SettingsRow("Mit Claude Code verbinden", mcpCmd) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton("Befehl kopieren", accent = false) { ui.copyLink(mcpCmd) }
                            PrimaryButton("Neuer Schlüssel", accent = false) {
                                g.mcp.regenerateToken(); mcpCmd = g.mcp.claudeCodeCommand
                                ui.toast("Neuer Schlüssel – verbundene KI-Programme neu einrichten")
                            }
                        }
                    }
                    if (activity.isNotEmpty()) {
                        SettingsRow("Letzte KI-Aktionen", activity.take(5).joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.tool + ": " + it.summary }) {}
                    }
                }
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
        val asset = remember(u) { g.updates.assetFor(u) }
        AlertDialog(
            onDismissRequest = { if (progress == null) update = null },
            title = { Text("Ein Update ist verfügbar") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Grooveo ${u.version} (installiert: $APP_VERSION)", fontWeight = FontWeight.SemiBold)
                    progress?.let { p ->
                        Text(if (p < 1f) "Wird heruntergeladen … ${(p * 100).toInt()} %" else "Wird installiert …", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = c.accent2)
                    }
                    if (u.notes.isNotBlank()) Text(u.notes, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    {
                        if (asset == null) {
                            update = null
                            runCatching { Desktop.getDesktop().browse(URI(u.url.ifBlank { "https://github.com/lcbs181/grooveo/releases/latest" })) }
                                .onFailure { ui.copyLink(u.url) }
                            return@TextButton
                        }
                        progress = 0f
                        updateJob = scope.launch {
                            runCatching {
                                val file = g.updates.download(asset) { p -> progress = p }
                                progress = 1f
                                g.updates.install(file)
                            }.onSuccess { quit ->
                                progress = null; update = null
                                if (quit) {
                                    g.close()
                                    kotlin.system.exitProcess(0)
                                } else ui.toast("Installer geöffnet – dort das Update bestätigen")
                            }.onFailure { e ->
                                progress = null
                                if (e !is kotlinx.coroutines.CancellationException) ui.toast("Update fehlgeschlagen: ${e.message}")
                            }
                        }
                    },
                    enabled = progress == null,
                ) { Text(if (asset == null) "Zur Download-Seite" else "Jetzt aktualisieren", color = c.accent2) }
            },
            dismissButton = {
                TextButton({
                    updateJob?.cancel(); updateJob = null; progress = null; update = null
                }) { Text(if (progress != null) "Abbrechen" else "Später") }
            },
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

private val DJ_STYLES = listOf(
    "auto" to "Automatisch", "blend" to "Blend", "bass_swap" to "Bass-Tausch", "filter" to "Filter",
    "echo_out" to "Echo-Out", "cut" to "Schnitt", "fade" to "Überblenden",
)

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
