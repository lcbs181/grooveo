package dev.schlubbe.musicagent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.BuildConfig
import dev.schlubbe.musicagent.playback.EqPreset
import dev.schlubbe.musicagent.playback.Sound3dPreset
import dev.schlubbe.musicagent.ui.components.NocturneButton
import dev.schlubbe.musicagent.ui.components.NocturneButtonVariant
import dev.schlubbe.musicagent.ui.components.NocturneIconButton
import dev.schlubbe.musicagent.ui.components.SegmentedControl
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Nocturne
import dev.schlubbe.musicagent.ui.update.UpdateDialog
import dev.schlubbe.musicagent.ui.update.UpdateViewModel

private fun sound3dIcon(preset: Sound3dPreset): String = when (preset) {
    Sound3dPreset.DISABLED -> "prohibit"
    Sound3dPreset.KINO -> "film-slate"
    Sound3dPreset.HEIMKINO -> "house"
    Sound3dPreset.KONZERT -> "microphone-stage"
    Sound3dPreset.RAVE -> "vinyl-record"
    Sound3dPreset.STUDIO -> "waveform"
    Sound3dPreset.KIRCHE -> "church"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit = {},
    onWhatsNewClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showSound3dSheet by remember { mutableStateOf(false) }

    Scaffold(containerColor = Nocturne.bg) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NocturneIconButton(icon = phosphorIcon("caret-left"), onClick = onNavigateBack, iconSize = 20.dp)
                Text("Einstellungen", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 6.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // — Wiedergabe —
                SectionHeader("Wiedergabe")
                SettingLabel("Wiedergabestil")
                SegmentedControl(
                    options = listOf("waveform" to "Waveform", "bars" to "Balken"),
                    selected = listOf("waveform" to "Waveform", "bars" to "Balken").first { it.first == uiState.playerStyle },
                    onSelect = { viewModel.onPlayerStyleChanged(it.first) },
                    label = { it.second },
                    fillWidth = true,
                )
                SettingLabel("Equalizer-Standard")
                SegmentedControl(
                    options = listOf(
                        EqPreset.FLAT to "Flach",
                        EqPreset.BASS_BOOST to "Bass",
                        EqPreset.TREBLE_BOOST to "Höhen",
                        EqPreset.VOCAL to "Vocal",
                    ),
                    selected = listOf(
                        EqPreset.FLAT to "Flach",
                        EqPreset.BASS_BOOST to "Bass",
                        EqPreset.TREBLE_BOOST to "Höhen",
                        EqPreset.VOCAL to "Vocal",
                    ).first { it.first == uiState.eqPreset },
                    onSelect = { viewModel.onEqPresetChanged(it.first) },
                    label = { it.second },
                    fillWidth = true,
                )
                ToggleRow(
                    title = "Automatische Weiterempfehlung",
                    subtitle = "Spielt ähnliche Titel, wenn die Warteschlange endet",
                    checked = uiState.autoplayRadio,
                    onCheckedChange = viewModel::onAutoplayRadioChanged,
                )

                HorizontalDivider(color = Nocturne.divider)

                // — 3D-Sound —
                SectionHeader("3D-Sound")
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showSound3dSheet = true },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Raumklang-Vorlage", style = MaterialTheme.typography.bodyMedium)
                        Text(uiState.sound3dPreset.description, style = MaterialTheme.typography.labelSmall, color = Nocturne.neutral500)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(uiState.sound3dPreset.label, color = Nocturne.accent300, style = MaterialTheme.typography.labelMedium)
                        Icon(
                            phosphorIcon("caret-right"),
                            contentDescription = null,
                            tint = Nocturne.neutral500,
                            modifier = Modifier.padding(start = 6.dp).size(15.dp),
                        )
                    }
                }

                HorizontalDivider(color = Nocturne.divider)

                // — Downloads —
                SectionHeader("Downloads")
                ToggleRow(
                    title = "Datensparmodus",
                    subtitle = "Spielt nur heruntergeladene Titel ab, es wird nie gestreamt.",
                    checked = uiState.dataSaverMode,
                    onCheckedChange = viewModel::onDataSaverModeChanged,
                )
                ToggleRow(
                    title = "Nur über WLAN herunterladen",
                    subtitle = null,
                    checked = uiState.downloadsWifiOnly,
                    onCheckedChange = viewModel::onDownloadsWifiOnlyChanged,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Zwischenspeicher", style = MaterialTheme.typography.bodyMedium)
                        Text("${uiState.cacheSizeMb} MB", style = MaterialTheme.typography.labelSmall, color = Nocturne.neutral500)
                    }
                    NocturneButton(text = "Leeren", onClick = viewModel::clearCache, variant = NocturneButtonVariant.Secondary)
                }

                HorizontalDivider(color = Nocturne.divider)

                // — Benachrichtigungen —
                SectionHeader("Benachrichtigungen")
                ToggleRow(
                    title = "Neue Uploads von gefolgten Künstlern",
                    subtitle = null,
                    checked = uiState.notifyNewUploads,
                    onCheckedChange = viewModel::onNotifyNewUploadsChanged,
                )

                HorizontalDivider(color = Nocturne.divider)

                // — Startseite personalisieren —
                SectionHeader("Startseite personalisieren")
                ToggleRow(
                    title = "Mix & Stimmungen",
                    subtitle = null,
                    checked = uiState.showMixControls,
                    onCheckedChange = viewModel::onShowMixControlsChanged,
                )
                ToggleRow(
                    title = "Im Fokus",
                    subtitle = null,
                    checked = uiState.showFeatured,
                    onCheckedChange = viewModel::onShowFeaturedChanged,
                )
                ToggleRow(
                    title = "Neu von Künstlern",
                    subtitle = null,
                    checked = uiState.showNewUploads,
                    onCheckedChange = viewModel::onShowNewUploadsChanged,
                )

                HorizontalDivider(color = Nocturne.divider)

                // — Updates & Sicherungen —
                SectionHeader("Updates & Sicherungen")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("App-Version", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.labelSmall,
                            color = Nocturne.neutral500,
                        )
                    }
                    NocturneButton(text = "Nach Updates suchen", onClick = updateViewModel::checkForUpdate, variant = NocturneButtonVariant.Secondary)
                }
                Text(
                    "Was ist neu?",
                    color = Nocturne.accent,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onWhatsNewClick),
                )
                ToggleRow(
                    title = "Automatische Sicherung",
                    subtitle = null,
                    checked = uiState.autoBackup,
                    onCheckedChange = viewModel::onAutoBackupChanged,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Letzte Sicherung", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            uiState.lastBackupText ?: "Noch keine Sicherung",
                            style = MaterialTheme.typography.labelSmall,
                            color = Nocturne.neutral500,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        NocturneIconButton(icon = phosphorIcon("share-network"), onClick = viewModel::shareBackup, iconSize = 16.dp)
                        NocturneButton(
                            text = if (uiState.backupState == BackupState.Running) "…" else "Jetzt sichern",
                            onClick = viewModel::backupNow,
                            variant = NocturneButtonVariant.Secondary,
                            enabled = uiState.backupState != BackupState.Running,
                        )
                    }
                }
                when (val state = uiState.backupState) {
                    is BackupState.Error -> Text(
                        state.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Unit
                }
                NocturneButton(
                    text = "Aus Sicherung wiederherstellen",
                    onClick = viewModel::onRestoreClicked,
                    variant = NocturneButtonVariant.Secondary,
                    leadingIcon = phosphorIcon("clock-counter-clockwise"),
                    block = true,
                )

                HorizontalDivider(color = Nocturne.divider)

                // — Über —
                SectionHeader("Über")
                Text("Über die App", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Music Agent · ${BuildConfig.VERSION_NAME} (Standalone)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Nocturne.neutral500,
                )
                Text("Backend verbinden", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "Optional – nicht konfiguriert",
                    style = MaterialTheme.typography.labelSmall,
                    color = Nocturne.neutral500,
                )
                NocturneTextField(
                    value = uiState.backendBaseUrl,
                    onValueChange = viewModel::onBackendBaseUrlChanged,
                    label = "Backend-URL (z. B. http://192.168.1.10:8000)",
                )
                NocturneTextField(
                    value = uiState.apiKey,
                    onValueChange = viewModel::onApiKeyChanged,
                    label = "API-Key",
                )
                NocturneButton(
                    text = "Test Connection",
                    onClick = viewModel::testConnection,
                    enabled = uiState.connectionTestState != ConnectionTestState.Testing,
                )
                when (val state = uiState.connectionTestState) {
                    is ConnectionTestState.Idle -> Unit
                    is ConnectionTestState.Testing -> CircularProgressIndicator(color = Nocturne.accent)
                    is ConnectionTestState.Success -> Text("Verbindung erfolgreich", color = Nocturne.accent)
                    is ConnectionTestState.Error -> Text("Fehler: ${state.message}", color = MaterialTheme.colorScheme.error)
                }

                ToggleRow(
                    title = "Hochauflösendes Audio",
                    subtitle = "Nutzt den bestmöglichen Wiedergabepfad — echtes bit-perfect Audio ist auf normalem Android ohne Root nicht garantiert.",
                    checked = uiState.hiResAudio,
                    onCheckedChange = viewModel::onHiResAudioChanged,
                )
                Spacer(modifier = Modifier.padding(bottom = 24.dp))
            }
        }
    }

    if (showSound3dSheet) {
        Sound3dPresetSheet(
            selected = uiState.sound3dPreset,
            onDismiss = { showSound3dSheet = false },
            onSelect = { preset ->
                viewModel.onSound3dPresetChanged(preset)
                showSound3dSheet = false
            },
        )
    }

    if (uiState.showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRestoreConfirm,
            title = { Text("Aus Sicherung wiederherstellen?") },
            text = { Text("Playlists, Likes und Einstellungen werden durch den Stand der letzten Sicherung ersetzt.") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestore) { Text("Wiederherstellen") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRestoreConfirm) { Text("Abbrechen") }
            },
        )
    }

    UpdateDialog(updateViewModel)
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.titleSmall,
        color = Nocturne.accent,
    )
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Nocturne.neutral500)
            }
        }
        SegmentedControl(
            options = listOf(false, true),
            selected = checked,
            onSelect = onCheckedChange,
            label = { if (it) "An" else "Aus" },
        )
    }
}

@Composable
private fun NocturneTextField(value: String, onValueChange: (String) -> Unit, label: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Nocturne.neutral400)
        Spacer(modifier = Modifier.padding(top = 4.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Nocturne.surface,
                unfocusedContainerColor = Nocturne.surface,
                focusedIndicatorColor = Nocturne.divider,
                unfocusedIndicatorColor = Nocturne.divider,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sound3dPresetSheet(
    selected: Sound3dPreset,
    onDismiss: () -> Unit,
    onSelect: (Sound3dPreset) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Nocturne.surface) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(
                "Raumklang-Vorlage",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Sound3dPreset.entries.forEach { preset ->
                val isSelected = preset == selected
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Nocturne.surface),
                    leadingContent = {
                        Icon(phosphorIcon(sound3dIcon(preset)), contentDescription = null, tint = Nocturne.accent)
                    },
                    headlineContent = { Text(preset.label) },
                    supportingContent = { Text(preset.description, color = Nocturne.neutral500) },
                    trailingContent = {
                        if (isSelected) {
                            Icon(phosphorIcon("check-circle", filled = true), contentDescription = null, tint = Nocturne.accent)
                        }
                    },
                    modifier = Modifier.clickable { onSelect(preset) },
                )
            }
        }
    }
}
