package dev.schlubbe.musicagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.playback.EqPreset
import dev.schlubbe.musicagent.ui.update.UpdateDialog
import dev.schlubbe.musicagent.ui.update.UpdateViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // "Backend" here only backs analytics events + update checks in this
        // backend-less variant -- search/streaming/likes/playlists are all
        // on-device, no login concept exists in this app at all.
        Text("Backend-Verbindung (Analyse & Updates)", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = uiState.backendBaseUrl,
            onValueChange = viewModel::onBackendBaseUrlChanged,
            label = { Text("Backend-URL (z. B. http://192.168.1.10:8000)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = uiState.apiKey,
            onValueChange = viewModel::onApiKeyChanged,
            label = { Text("API-Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Button(
            onClick = viewModel::testConnection,
            enabled = uiState.connectionTestState != ConnectionTestState.Testing,
        ) {
            Text("Test Connection")
        }

        when (val state = uiState.connectionTestState) {
            is ConnectionTestState.Idle -> Unit
            is ConnectionTestState.Testing -> CircularProgressIndicator()
            is ConnectionTestState.Success -> Text(
                "Verbindung erfolgreich",
                color = MaterialTheme.colorScheme.primary,
            )
            is ConnectionTestState.Error -> Text(
                "Fehler: ${state.message}",
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Wiedergabe", style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Datensparmodus")
                Text(
                    "Spielt nur heruntergeladene Titel ab, es wird nie gestreamt. Titel ohne Download werden übersprungen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = uiState.dataSaverMode, onCheckedChange = viewModel::onDataSaverModeChanged)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Hochauflösendes Audio")
                Text(
                    "Nutzt den bestmöglichen Wiedergabepfad — echtes bit-perfect Audio ist auf normalem Android ohne Root nicht garantiert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = uiState.hiResAudio, onCheckedChange = viewModel::onHiResAudioChanged)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Equalizer", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            EqPresetOption("Flach", EqPreset.FLAT, uiState.eqPreset, viewModel::onEqPresetChanged)
            EqPresetOption("Bass-Boost", EqPreset.BASS_BOOST, uiState.eqPreset, viewModel::onEqPresetChanged)
            EqPresetOption("Höhen-Boost", EqPreset.TREBLE_BOOST, uiState.eqPreset, viewModel::onEqPresetChanged)
            EqPresetOption("Vocal", EqPreset.VOCAL, uiState.eqPreset, viewModel::onEqPresetChanged)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Über", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = updateViewModel::checkForUpdate) {
            Text("Nach Updates suchen")
        }
    }

    UpdateDialog(updateViewModel)
}

@Composable
private fun EqPresetOption(
    label: String,
    preset: EqPreset,
    selected: EqPreset,
    onSelect: (EqPreset) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected == preset,
                onClick = { onSelect(preset) },
                role = Role.RadioButton,
            )
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        RadioButton(selected = selected == preset, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
