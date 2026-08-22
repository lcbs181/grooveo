package dev.schlubbe.musicagent.ui.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.BuildConfig
import dev.schlubbe.musicagent.playback.Sound3dPreset
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.CanopyChip
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.CanopySectionHeader
import dev.schlubbe.musicagent.ui.components.CanopyToggle
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyShapes
import dev.schlubbe.musicagent.ui.update.UpdateDialog
import dev.schlubbe.musicagent.ui.update.UpdateViewModel
import dev.schlubbe.musicagent.widget.PlaybackWidgetReceiver

// Design source: design_handoff_grooveo/GrooveoApp.dc.html lines 691-762 ("SETTINGS")
// and screenshots/08-android.png, rightmost phone ("09 - EINSTELLUNGEN"). Section order
// (Wiedergabe / Equalizer / PC-Backend / Erweitert / footer) and the card/row styling
// below follow that source. Everything the previous version of this screen exposed
// (Wiedergabestil, Downloads, Benachrichtigungen, Startseite personalisieren, Hi-Res
// Audio, What's New) is preserved under "Weitere Einstellungen" -- the mockup doesn't
// depict those, so rather than delete working features they're kept in the same
// card/row idiom as the rest of the screen. See the task report for details.

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
    onNavigateToEqualizer: () -> Unit = {},
    onConnectPcClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showSound3dSheet by remember { mutableStateOf(false) }
    var showBackendUrlDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showBackupSheet by remember { mutableStateOf(false) }

    val sound3dOn = uiState.sound3dPreset != Sound3dPreset.DISABLED

    fun setupWidget() {
        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, PlaybackWidgetReceiver::class.java)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(provider, null, null)
        } else {
            Toast.makeText(
                context,
                "Dein Homescreen unterstützt das direkte Anheften nicht — füge das Widget manuell über den Homescreen hinzu.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Scaffold(containerColor = Canopy.bg) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CanopyIconButton(icon = phosphorIcon("caret-left"), onClick = onNavigateBack, iconSize = 20.dp)
                Text("Einstellungen", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 6.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                // — Wiedergabe —
                Column {
                    CanopySectionHeader(title = "Wiedergabe")
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsToggleCard(
                            icon = phosphorIcon("cell-signal-slash"),
                            title = "Datensparmodus",
                            subtitle = "Spielt nur heruntergeladene Titel ab, es wird nie gestreamt.",
                            checked = uiState.dataSaverMode,
                            onCheckedChange = viewModel::onDataSaverModeChanged,
                        )
                        // "spiral" (ph-spiral) has no PhosphorIcon.kt mapping -- see task
                        // report. "circles-three" is used as the closest already-mapped
                        // stand-in rather than silently falling back to WarningCircle.
                        SettingsToggleCard(
                            icon = phosphorIcon("circles-three"),
                            title = "3D-Sound",
                            subtitle = if (sound3dOn) uiState.sound3dPreset.description else "Räumliche Wiedergabe über Kopfhörer",
                            checked = sound3dOn,
                            onCheckedChange = { on ->
                                if (on) showSound3dSheet = true else viewModel.onSound3dPresetChanged(Sound3dPreset.DISABLED)
                            },
                            onClick = { showSound3dSheet = true },
                        )
                    }
                }

                // — Equalizer —
                Column {
                    CanopySectionHeader(title = "Equalizer")
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EQ_PRESET_ORDER.forEach { (preset, label) ->
                            CanopyChip(
                                label = label,
                                active = uiState.eqPreset == preset,
                                onClick = { viewModel.onEqPresetChanged(preset) },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.padding(top = 14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CanopyShapes.medium)
                            .background(Canopy.surface)
                            .border(1.dp, Canopy.divider, CanopyShapes.medium)
                            .clickable(onClick = onNavigateToEqualizer)
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EqCurvePreview(
                            gains = EQ_PRESET_GAINS[uiState.eqPreset] ?: List(5) { 0f },
                            modifier = Modifier.size(width = 44.dp, height = 34.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bänder einstellen", style = MaterialTheme.typography.labelLarge)
                            Text(
                                eqPresetLabel(uiState.eqPreset),
                                style = MaterialTheme.typography.bodySmall,
                                color = Canopy.neutral500,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Icon(phosphorIcon("caret-right"), contentDescription = null, tint = Canopy.neutral400, modifier = Modifier.size(18.dp))
                    }
                }

                // — PC-Backend —
                Column {
                    CanopySectionHeader(title = "PC-Backend")
                    SettingsNavRow(
                        icon = phosphorIcon("desktop-tower"),
                        title = "Mit PC verbinden",
                        subtitle = if (uiState.backendBaseUrl.isBlank()) "Kein PC verbunden" else "Konfiguriert: ${uiState.backendBaseUrl}",
                        onClick = onConnectPcClick,
                    )
                }

                // — Erweitert —
                Column {
                    CanopySectionHeader(title = "Erweitert")
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsNavRow(
                            icon = phosphorIcon("globe"),
                            title = "Backend-URL",
                            subtitle = uiState.backendBaseUrl.ifBlank { "Nicht gesetzt" },
                            onClick = { showBackendUrlDialog = true },
                        )
                        SettingsNavRow(
                            icon = phosphorIcon("key"),
                            title = "API-Key",
                            subtitle = if (uiState.apiKey.isBlank()) "Nicht gesetzt" else "•••• gesetzt",
                            onClick = { showApiKeyDialog = true },
                        )
                        SettingsNavRow(
                            icon = phosphorIcon("cloud-arrow-up"),
                            title = "Backup exportieren",
                            subtitle = uiState.lastBackupText ?: "Noch keine Sicherung",
                            onClick = { showBackupSheet = true },
                        )
                        SettingsNavRow(
                            icon = phosphorIcon("squares-four"),
                            title = "Widget einrichten",
                            subtitle = "Player-Widget zum Homescreen hinzufügen",
                            onClick = ::setupWidget,
                        )
                    }
                }

                // — Weitere Einstellungen — (existing features the mockup doesn't depict)
                Column {
                    CanopySectionHeader(title = "Weitere Einstellungen")
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column {
                            SettingLabel("Wiedergabestil")
                            Spacer(modifier = Modifier.padding(top = 8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CanopyChip(
                                    label = "Waveform",
                                    active = uiState.playerStyle == "waveform",
                                    onClick = { viewModel.onPlayerStyleChanged("waveform") },
                                )
                                CanopyChip(
                                    label = "Balken",
                                    active = uiState.playerStyle == "bars",
                                    onClick = { viewModel.onPlayerStyleChanged("bars") },
                                )
                            }
                        }
                        ToggleRow(
                            title = "Automatische Weiterempfehlung",
                            subtitle = "Spielt ähnliche Titel, wenn die Warteschlange endet",
                            checked = uiState.autoplayRadio,
                            onCheckedChange = viewModel::onAutoplayRadioChanged,
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
                                Text("${uiState.cacheSizeMb} MB", style = MaterialTheme.typography.labelSmall, color = Canopy.neutral500)
                            }
                            CanopyButton(text = "Leeren", onClick = viewModel::clearCache, variant = CanopyButtonVariant.Secondary)
                        }
                        ToggleRow(
                            title = "Neue Uploads von gefolgten Künstlern",
                            subtitle = null,
                            checked = uiState.notifyNewUploads,
                            onCheckedChange = viewModel::onNotifyNewUploadsChanged,
                        )
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
                        ToggleRow(
                            title = "Hochauflösendes Audio",
                            subtitle = "Nutzt den bestmöglichen Wiedergabepfad — echtes bit-perfect Audio ist auf normalem Android ohne Root nicht garantiert.",
                            checked = uiState.hiResAudio,
                            onCheckedChange = viewModel::onHiResAudioChanged,
                        )
                    }
                }

                // — Footer —
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("Grooveo ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "Build ${BuildConfig.VERSION_CODE}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Canopy.neutral500,
                            )
                        }
                        CanopyButton(
                            text = "Prüfen",
                            onClick = updateViewModel::checkForUpdate,
                            variant = CanopyButtonVariant.Secondary,
                            leadingIcon = phosphorIcon("arrow-clockwise"),
                        )
                    }
                    Text(
                        "Was ist neu?",
                        color = Canopy.accent,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 12.dp).clickable(onClick = onWhatsNewClick),
                    )
                }
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

    if (showBackendUrlDialog) {
        BackendUrlDialog(
            value = uiState.backendBaseUrl,
            onValueChange = viewModel::onBackendBaseUrlChanged,
            onDismiss = { showBackendUrlDialog = false },
        )
    }

    if (showApiKeyDialog) {
        ApiKeyDialog(
            value = uiState.apiKey,
            onValueChange = viewModel::onApiKeyChanged,
            connectionTestState = uiState.connectionTestState,
            onTest = viewModel::testConnection,
            onDismiss = { showApiKeyDialog = false },
        )
    }

    if (showBackupSheet) {
        BackupSheet(
            uiState = uiState,
            onDismiss = { showBackupSheet = false },
            onBackupNow = viewModel::backupNow,
            onShare = viewModel::shareBackup,
            onAutoBackupChanged = viewModel::onAutoBackupChanged,
            onRestoreClick = viewModel::onRestoreClicked,
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

/** Canopy card row for a boolean setting with a leading icon (WIEDERGABE section):
 * icon + title/subtitle + [CanopyToggle]. [onClick] additionally makes the whole row
 * open a richer picker (used by 3D-Sound, which has more than an on/off state). */
@Composable
private fun SettingsToggleCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CanopyShapes.medium)
            .background(Canopy.surface)
            .border(1.dp, Canopy.divider, CanopyShapes.medium)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Canopy.accent, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500, modifier = Modifier.padding(top = 2.dp))
        }
        CanopyToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Canopy card row that navigates elsewhere (PC-BACKEND / ERWEITERT sections):
 * icon + title/subtitle + a trailing caret. */
@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CanopyShapes.medium)
            .background(Canopy.surface)
            .border(1.dp, Canopy.divider, CanopyShapes.medium)
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Canopy.accent, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(phosphorIcon("caret-right"), contentDescription = null, tint = Canopy.neutral400, modifier = Modifier.size(18.dp))
    }
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
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Canopy.neutral500)
            }
        }
        CanopyToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CanopyTextField(value: String, onValueChange: (String) -> Unit, label: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Canopy.neutral400)
        Spacer(modifier = Modifier.padding(top = 4.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
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

@Composable
private fun BackendUrlDialog(value: String, onValueChange: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backend-URL") },
        text = {
            Column {
                Text(
                    "Optionale Adresse eines eigenen FastAPI-Backends im selben Netz -- für Analytics und Update-Checks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Canopy.neutral500,
                )
                Spacer(modifier = Modifier.padding(top = 10.dp))
                CanopyTextField(value = value, onValueChange = onValueChange, label = "z. B. http://192.168.1.10:8000")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
    )
}

@Composable
private fun ApiKeyDialog(
    value: String,
    onValueChange: (String) -> Unit,
    connectionTestState: ConnectionTestState,
    onTest: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("API-Key") },
        text = {
            Column {
                CanopyTextField(value = value, onValueChange = onValueChange, label = "API-Key")
                Spacer(modifier = Modifier.padding(top = 12.dp))
                CanopyButton(
                    text = "Verbindung testen",
                    onClick = onTest,
                    variant = CanopyButtonVariant.Secondary,
                    enabled = connectionTestState != ConnectionTestState.Testing,
                )
                Spacer(modifier = Modifier.padding(top = 8.dp))
                when (connectionTestState) {
                    is ConnectionTestState.Idle -> Unit
                    is ConnectionTestState.Testing -> CircularProgressIndicator(color = Canopy.accent, modifier = Modifier.size(20.dp))
                    is ConnectionTestState.Success -> Text("Verbindung erfolgreich", color = Canopy.accent)
                    is ConnectionTestState.Error -> Text("Fehler: ${connectionTestState.message}", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupSheet(
    uiState: SettingsUiState,
    onDismiss: () -> Unit,
    onBackupNow: () -> Unit,
    onShare: () -> Unit,
    onAutoBackupChanged: (Boolean) -> Unit,
    onRestoreClick: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Canopy.surface) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Backup exportieren", style = MaterialTheme.typography.headlineSmall)
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
                        color = Canopy.neutral500,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CanopyIconButton(icon = phosphorIcon("share-network"), onClick = onShare, iconSize = 16.dp)
                    CanopyButton(
                        text = if (uiState.backupState == BackupState.Running) "…" else "Jetzt sichern",
                        onClick = onBackupNow,
                        variant = CanopyButtonVariant.Secondary,
                        enabled = uiState.backupState != BackupState.Running,
                    )
                }
            }
            when (val state = uiState.backupState) {
                is BackupState.Error -> Text(state.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Automatische Sicherung", style = MaterialTheme.typography.bodyMedium)
                CanopyToggle(checked = uiState.autoBackup, onCheckedChange = onAutoBackupChanged)
            }
            CanopyButton(
                text = "Aus Sicherung wiederherstellen",
                onClick = onRestoreClick,
                variant = CanopyButtonVariant.Secondary,
                leadingIcon = phosphorIcon("clock-counter-clockwise"),
                block = true,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sound3dPresetSheet(
    selected: Sound3dPreset,
    onDismiss: () -> Unit,
    onSelect: (Sound3dPreset) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Canopy.surface) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(
                "Raumklang-Vorlage",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Sound3dPreset.entries.forEach { preset ->
                val isSelected = preset == selected
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Canopy.surface),
                    leadingContent = {
                        Icon(phosphorIcon(sound3dIcon(preset)), contentDescription = null, tint = Canopy.accent)
                    },
                    headlineContent = { Text(preset.label) },
                    supportingContent = { Text(preset.description, color = Canopy.neutral500) },
                    trailingContent = {
                        if (isSelected) {
                            Icon(phosphorIcon("check-circle", filled = true), contentDescription = null, tint = Canopy.accent)
                        }
                    },
                    modifier = Modifier.clickable { onSelect(preset) },
                )
            }
        }
    }
}
