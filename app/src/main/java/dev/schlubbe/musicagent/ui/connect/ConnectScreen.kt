package dev.schlubbe.musicagent.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.CanopySectionHeader
import dev.schlubbe.musicagent.ui.components.LocalCanopyOverlay
import dev.schlubbe.musicagent.ui.components.canopyCard
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyShapes

// Design source: design_handoff_grooveo/GrooveoApp.dc.html lines 762-839 (the
// "CONNECT TO PC" sc-if block) and screenshots/11-android.png, centre phone
// ("12 - MIT PC VERBINDEN"). README's "### 12 Mit PC verbinden" and the
// "PC-Backend view" implementation note describe the intent.
//
// This repo has no LAN discovery, no pairing-code server and no Windows
// client (see ConnectViewModel's doc comment), so the parts of the design
// that assume those exist are deliberately NOT reproduced as scripted theatre:
//  - No hardcoded "DESKTOP-M found on the network" card -- the host card
//    reflects the real, persisted SettingsRepository.backendBaseUrl, or an
//    honest "not configured" state plus manual host/port fields.
//  - "Verbinden" makes a real /healthz request and can really fail.
//  - The four Freigaben toggles and the "1,4 GB transferred" stat are
//    replaced by a short note naming exactly what isn't wired up yet.

@Composable
fun ConnectScreen(
    onNavigateBack: () -> Unit,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val overlay = LocalCanopyOverlay.current

    LaunchedEffect(uiState.connectSuccessEvent) {
        if (uiState.connectSuccessEvent > 0) overlay.spray()
    }

    Scaffold(containerColor = Canopy.bg) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CanopyIconButton(icon = phosphorIcon("arrow-left"), onClick = onNavigateBack, contentDescription = "Zurück")
                Text(
                    "Mit PC verbinden",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    "Läuft Grooveo auf deinem PC, kann das Telefon dessen Bibliothek, Downloads und " +
                        "Speicher mitbenutzen. Alles bleibt im eigenen Netz.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Canopy.neutral500,
                )

                HostCard(uiState)

                if (uiState.paired) {
                    PairedContent(uiState = uiState, viewModel = viewModel)
                } else {
                    NotPairedContent(uiState = uiState, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun HostCard(uiState: ConnectUiState) {
    val (title, subtitle, dotColor) = if (uiState.paired) {
        when (uiState.existingReachable) {
            true -> Triple("Verbunden mit ${uiState.pairedHostPort}", "Backend erreichbar", Canopy.accent)
            false -> Triple("Konfiguriert: ${uiState.pairedHostPort}", "Gerade nicht erreichbar", Canopy.accent2)
            null -> Triple(
                "Konfiguriert: ${uiState.pairedHostPort}",
                if (uiState.checkingExisting) "Prüfe Erreichbarkeit …" else "Erreichbarkeit unbekannt",
                Canopy.neutral400,
            )
        }
    } else {
        Triple("Kein PC verbunden", "Automatische Suche im Netzwerk wird noch nicht unterstützt", Canopy.neutral400)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CanopyShapes.large)
            .background(Canopy.surface)
            .border(1.dp, Canopy.divider, CanopyShapes.large)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CanopyShapes.medium)
                .background(Canopy.accent100),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                phosphorIcon("desktop-tower", filled = true),
                contentDescription = null,
                tint = Canopy.accent,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500, modifier = Modifier.padding(top = 4.dp))
        }
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor))
    }
}

@Composable
private fun PairedContent(uiState: ConnectUiState, viewModel: ConnectViewModel) {
    // Honest stand-in for the design's green "Verbunden mit DESKTOP-M / Bibliothek
    // und Downloads laufen über den PC" panel: that claim would be false here --
    // nothing in this app currently routes library reads, downloads or streaming
    // through the configured backend, only healthz/login/analytics do (see
    // BackendApi.kt). Say so directly instead of implying a feature that isn't wired.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CanopyShapes.medium)
            .background(Canopy.accent100)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            phosphorIcon("check-circle", filled = true),
            contentDescription = null,
            tint = Canopy.accent,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Backend-URL und API-Key gespeichert",
                style = MaterialTheme.typography.labelLarge,
                color = Canopy.text,
            )
            Text(
                "Bibliothek, Downloads und Streaming vom PC sind in dieser Version noch nicht " +
                    "angebunden — nur die Verbindung selbst wird geprüft.",
                style = MaterialTheme.typography.bodySmall,
                color = Canopy.neutral600,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CanopyButton(
            text = "Erneut prüfen",
            onClick = viewModel::recheck,
            variant = CanopyButtonVariant.Secondary,
            leadingIcon = phosphorIcon("arrow-clockwise"),
            enabled = !uiState.checkingExisting,
        )
        if (uiState.checkingExisting) {
            CircularProgressIndicator(color = Canopy.accent, modifier = Modifier.size(18.dp))
        }
    }

    Column {
        CanopySectionHeader(title = "Freigaben")
        Text(
            "Die vier Freigaben aus dem Entwurf — PC-Bibliothek, aus PC-Speicher streamen, " +
                "Downloads übernehmen, PC fernsteuern — gibt es in dieser App-Version noch nicht. " +
                "Dafür fehlen eigene Einstellungen in SettingsRepository sowie die Anbindung im " +
                "Player, im Downloads-Flow und eine Fernsteuerungs-Schnittstelle zum PC.",
            style = MaterialTheme.typography.bodySmall,
            color = Canopy.neutral500,
            modifier = Modifier.fillMaxWidth().canopyCard(),
        )
    }

    CanopyButton(
        text = "Trennen",
        onClick = viewModel::onDisconnect,
        variant = CanopyButtonVariant.Secondary,
        block = true,
    )
}

@Composable
private fun NotPairedContent(uiState: ConnectUiState, viewModel: ConnectViewModel) {
    Column {
        CanopySectionHeader(title = "PC-Adresse")
        Text(
            "Eine automatische Suche im Netzwerk gibt es in Grooveo noch nicht — Adresse und Port " +
                "des Backends unten manuell eintragen (z. B. die IP, unter der ein FastAPI-Backend im " +
                "eigenen WLAN erreichbar ist).",
            style = MaterialTheme.typography.bodySmall,
            color = Canopy.neutral500,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ConnectTextField(
                value = uiState.host,
                onValueChange = viewModel::onHostChanged,
                label = "Host oder IP",
                modifier = Modifier.weight(2f),
            )
            ConnectTextField(
                value = uiState.port,
                onValueChange = viewModel::onPortChanged,
                label = "Port",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number,
            )
        }
    }

    Column {
        CanopySectionHeader(title = "Kopplungscode")
        CodeBoxes(uiState = uiState)
        Spacer(modifier = Modifier.height(18.dp))
        Keypad(onDigit = viewModel::onDigitPress, onBackspace = viewModel::onBackspace)
    }

    Column {
        CanopyButton(
            text = "Verbinden",
            onClick = viewModel::onConnect,
            leadingIcon = phosphorIcon("link"),
            enabled = uiState.canConnect,
            block = true,
        )
        when (uiState.phase) {
            ConnectPhase.Connecting -> Row(
                modifier = Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(color = Canopy.accent, modifier = Modifier.size(16.dp))
                Text("Prüfe Verbindung …", style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500)
            }
            ConnectPhase.Error -> Text(
                uiState.errorMessage ?: "Verbindung fehlgeschlagen.",
                style = MaterialTheme.typography.bodySmall,
                color = Canopy.accent2,
                modifier = Modifier.padding(top = 10.dp),
            )
            else -> Unit
        }
        Text(
            "Der Code wird als API-Key an die eingegebene Adresse gesendet. Ein echtes " +
                "Kopplungsprotokoll zwischen Grooveo und einem PC-Client gibt es noch nicht — " +
                "„Verbinden“ prüft nur, ob unter der Adresse tatsächlich ein erreichbares Backend läuft.",
            style = MaterialTheme.typography.bodySmall,
            color = Canopy.neutral500,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun CodeBoxes(uiState: ConnectUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (i in 0 until 6) {
            val isNext = uiState.nextBoxIndex == i
            val digit = uiState.codeDigits.getOrNull(i)?.toString() ?: ""
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(CanopyShapes.medium)
                    .background(Canopy.surface)
                    .border(1.dp, if (isNext) Canopy.accent else Canopy.divider, CanopyShapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                Text(digit, style = MaterialTheme.typography.headlineSmall, color = Canopy.text)
            }
        }
    }
}

@Composable
private fun Keypad(onDigit: (String) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { label ->
                    Box(modifier = Modifier.weight(1f).height(52.dp)) {
                        when (label) {
                            "" -> Unit
                            "⌫" -> Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .clip(CanopyShapes.medium)
                                    .background(Canopy.surface)
                                    .border(1.dp, Canopy.divider, CanopyShapes.medium)
                                    .clickable(onClick = onBackspace),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(phosphorIcon("backspace"), contentDescription = "Löschen", tint = Canopy.text, modifier = Modifier.size(20.dp))
                            }
                            else -> Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .clip(CanopyShapes.medium)
                                    .background(Canopy.surface)
                                    .border(1.dp, Canopy.divider, CanopyShapes.medium)
                                    .clickable { onDigit(label) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = Canopy.text,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Canopy.neutral400)
        Spacer(modifier = Modifier.height(4.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            shape = CanopyShapes.small,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Canopy.surface,
                unfocusedContainerColor = Canopy.surface,
                focusedIndicatorColor = Canopy.divider,
                unfocusedIndicatorColor = Canopy.divider,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
