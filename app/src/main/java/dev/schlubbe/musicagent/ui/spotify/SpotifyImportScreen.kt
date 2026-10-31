package dev.schlubbe.musicagent.ui.spotify

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.spotify.SpotifyImportSummary
import dev.schlubbe.musicagent.data.spotify.SpotifyImportWorker
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.CanopySectionHeader
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyShapes
import dev.schlubbe.musicagent.ui.theme.LocalChromeInset
import androidx.compose.ui.res.stringResource
import dev.schlubbe.musicagent.R

/** "Von Spotify importieren": intro → Exportify (WebView) or a picked export file
 * → progress → summary.
 * The import itself runs in [SpotifyImportWorker], so leaving this screen does not
 * stop it; coming back shows its live progress or the last result. */
@Composable
fun SpotifyImportScreen(
    onNavigateBack: () -> Unit,
    viewModel: SpotifyImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importUri(uri)
    }
    val openPicker = { pickFile.launch(arrayOf("*/*")) }
    val inWeb = state is SpotifyImportUiState.Exportify

    BackHandler(enabled = inWeb) { viewModel.closeExportify() }

    Scaffold(containerColor = Canopy.bg, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CanopyIconButton(
                    icon = phosphorIcon(if (inWeb) "x" else "caret-left"),
                    onClick = { if (inWeb) viewModel.closeExportify() else onNavigateBack() },
                    iconSize = 20.dp,
                    contentDescription = if (inWeb) "Export abbrechen" else "Zurück",
                )
                Text(
                    if (inWeb) "Spotify exportieren" else "Von Spotify importieren",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }

            when (val s = state) {
                is SpotifyImportUiState.Exportify -> ExportifyContent(s.saving, viewModel::onExportCaught)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + LocalChromeInset.current),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (s) {
                        SpotifyImportUiState.Intro -> introItems(viewModel::openExportify, openPicker)
                        is SpotifyImportUiState.Running -> runningItems(s, viewModel::cancelImport)
                        is SpotifyImportUiState.Done -> doneItems(s.summary, viewModel::openExportify)
                        is SpotifyImportUiState.Failed -> failedItems(s.message, viewModel::openExportify, openPicker)
                        is SpotifyImportUiState.Exportify -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportifyContent(saving: Boolean, onExport: (String, ByteArray) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            if (saving) "Export angekommen – der Import startet gleich …"
            else "1. Auf „Loslegen“ tippen und bei Spotify anmelden.\n2. Auf „Alles exportieren“ tippen – der Rest geht von selbst.",
            style = MaterialTheme.typography.bodySmall,
            color = Canopy.neutral600,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Box(modifier = Modifier.fillMaxSize()) {
            ExportifyWebView(onExport = onExport, modifier = Modifier.fillMaxSize())
            if (saving) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Canopy.bg.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = Canopy.accent) }
            }
        }
    }
}

private fun LazyListScope.introItems(onExportify: () -> Unit, onPickFile: () -> Unit) {
    item {
        Card {
            Text("Nimm deine Musik mit", style = MaterialTheme.typography.titleMedium)
            Text(
                "${stringResource(R.string.app_name)} holt deine Lieblingssongs und alle Playlists von Spotify. " +
                    "Jeder Song wird auf YouTube Music gesucht – was wir nicht finden, siehst du am Ende.",
                style = MaterialTheme.typography.bodyMedium,
                color = Canopy.neutral600,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    item {
        Card {
            Hint("key", "Dafür öffnet sich Exportify, ein kostenloses Export-Werkzeug. Du meldest dich dort direkt bei Spotify an – ${stringResource(R.string.app_name)} sieht dein Passwort nie.")
            Hint("info", "Anmelden mit Google oder Facebook klappt hier manchmal nicht – E-Mail und Passwort gehen immer.")
            Hint("microphone-stage", "Künstler, die oft in deiner Musik vorkommen, folgst du danach automatisch.")
        }
    }
    item {
        CanopyButton(
            text = "Von Spotify übernehmen",
            onClick = onExportify,
            leadingIcon = phosphorIcon("arrow-circle-down"),
            block = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item {
        CanopyButton(
            text = "Export-Datei öffnen",
            onClick = onPickFile,
            variant = CanopyButtonVariant.Secondary,
            leadingIcon = phosphorIcon("folder"),
            block = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Für die ZIP-Datei aus Spotifys „Daten herunterladen“ (Konto → Datenschutz) oder eine CSV-Datei von Exportify oder TuneMyMusic.",
            style = MaterialTheme.typography.bodySmall,
            color = Canopy.neutral500,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        )
    }
}

private fun LazyListScope.runningItems(state: SpotifyImportUiState.Running, onCancel: () -> Unit) {
    val p = state.progress
    item {
        Card {
            Text(
                when (p?.phase) {
                    null, SpotifyImportWorker.PHASE_FETCH -> "Lese deine Spotify-Datei …"
                    SpotifyImportWorker.PHASE_LIKES -> "Suche deine Likes …"
                    SpotifyImportWorker.PHASE_PLAYLISTS -> "Baue deine Playlists nach …"
                    else -> "Folge deinen Künstlern …"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (p?.phase == SpotifyImportWorker.PHASE_FETCH && p.fetched > 0) {
                Text("${p.fetched} Titel geladen", style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500)
            }
            Text(
                "Läuft im Hintergrund weiter – du kannst die App solange benutzen.",
                style = MaterialTheme.typography.bodySmall,
                color = Canopy.neutral500,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    item {
        Card {
            ProgressRow("Likes", p?.likesDone ?: 0, p?.likesTotal ?: 0, active = p?.phase == SpotifyImportWorker.PHASE_LIKES)
            ProgressRow("Playlists", p?.playlistsDone ?: 0, p?.playlistsTotal ?: 0, active = p?.phase == SpotifyImportWorker.PHASE_PLAYLISTS)
            if (p?.phase == SpotifyImportWorker.PHASE_PLAYLISTS && p.current != null) {
                Text(
                    "${p.current} · ${p.currentDone}/${p.currentTotal}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Canopy.neutral500,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            ProgressRow("Künstler", p?.artistsDone ?: 0, p?.artistsTotal ?: 0, active = p?.phase == SpotifyImportWorker.PHASE_ARTISTS)
            if ((p?.notFound ?: 0) > 0) {
                Text("Bisher nicht gefunden: ${p!!.notFound}", style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500)
            }
        }
    }
    item {
        CanopyButton(text = "Import abbrechen", onClick = onCancel, variant = CanopyButtonVariant.Secondary, block = true, modifier = Modifier.fillMaxWidth())
    }
}

private fun LazyListScope.doneItems(summary: SpotifyImportSummary?, onAgain: () -> Unit) {
    item {
        Card {
            Text("Fertig!", style = MaterialTheme.typography.titleMedium)
            if (summary == null) {
                Text("Der Import ist durch.", style = MaterialTheme.typography.bodyMedium, color = Canopy.neutral600)
            } else {
                ResultLine("Likes", "${summary.likesAdded + summary.likesAlreadyThere} von ${summary.likesTotal}" +
                    if (summary.likesAlreadyThere > 0) " (${summary.likesAlreadyThere} hattest du schon)" else "")
                ResultLine("Playlists", "${summary.playlistsImported} von ${summary.playlistsTotal} · ${summary.playlistTracksAdded} Titel neu")
                ResultLine("Künstler", "${summary.artistsFollowed} von ${summary.artistsTotal}")
                ResultLine("Nicht gefunden", "${summary.notFound.size}")
            }
        }
    }
    if (summary != null) {
        expandableList("Nicht gefunden: ${summary.notFound.size}", "notfound", summary.notFound)
        expandableList("Künstler nicht gefunden: ${summary.artistsNotFound.size}", "artists", summary.artistsNotFound)
        if (summary.playlistsUnavailable.isNotEmpty()) {
            item {
                Text(
                    "Bei ${summary.playlistsUnavailable.size} Playlists hat Spotify die Titel nicht rausgegeben " +
                        "(meist Spotify-eigene Mixe): ${summary.playlistsUnavailable.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Canopy.neutral500,
                )
            }
        }
    }
    item {
        CanopyButton(text = "Nochmal importieren", onClick = onAgain, variant = CanopyButtonVariant.Secondary, block = true, modifier = Modifier.fillMaxWidth())
    }
}

private fun LazyListScope.failedItems(message: String, onRetry: () -> Unit, onPickFile: () -> Unit) {
    item {
        Card {
            Text("Hat leider nicht geklappt", style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Canopy.neutral600, modifier = Modifier.padding(top = 6.dp))
        }
    }
    item {
        CanopyButton(text = "Nochmal versuchen", onClick = onRetry, block = true, modifier = Modifier.fillMaxWidth())
    }
    item {
        CanopyButton(text = "Export-Datei öffnen", onClick = onPickFile, variant = CanopyButtonVariant.Secondary, block = true, modifier = Modifier.fillMaxWidth())
    }
}

/** A collapsible list section; collapsed by default, since it can be long. */
private fun LazyListScope.expandableList(title: String, key: String, entries: List<String>) {
    if (entries.isEmpty()) return
    item(key = "$key-header") {
        var open by rememberSaveable(key) { mutableStateOf(false) }
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CanopyShapes.medium)
                    .background(Canopy.surface)
                    .border(1.dp, Canopy.divider, CanopyShapes.medium)
                    .clickable { open = !open }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Icon(phosphorIcon(if (open) "caret-up" else "caret-down"), contentDescription = null, tint = Canopy.neutral400, modifier = Modifier.size(18.dp))
            }
            if (open) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Inside one item rather than as lazy items: the collapsed state
                    // lives here, and even a few hundred short lines lay out quickly.
                    entries.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Canopy.neutral600) }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CanopyShapes.medium)
            .background(Canopy.surface)
            .border(1.dp, Canopy.divider, CanopyShapes.medium)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun Hint(icon: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(phosphorIcon(icon), contentDescription = null, tint = Canopy.accent, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Canopy.neutral600)
    }
}

@Composable
private fun ProgressRow(label: String, done: Int, total: Int, active: Boolean) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                if (total > 0) "$done/$total" else "–",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                color = if (active) Canopy.accent else Canopy.neutral500,
            )
        }
        LinearProgressIndicator(
            progress = { if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f },
            color = Canopy.accent,
            trackColor = Canopy.neutral200,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

@Composable
private fun ResultLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Canopy.neutral600)
    }
}
