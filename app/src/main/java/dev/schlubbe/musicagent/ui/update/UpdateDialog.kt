package dev.schlubbe.musicagent.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.schlubbe.musicagent.data.remote.dto.UpdateInfoDto

/**
 * Self-contained dialog for the in-app update flow. Renders itself based on
 * [UpdateViewModel.uiState] and shows nothing when idle/up-to-date, so it's
 * safe to always place at the call site and let the ViewModel drive it.
 */
@Composable
fun UpdateDialog(viewModel: UpdateViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    when (val state = uiState) {
        // The automatic once-per-launch check (NavGraph) calls checkForUpdate(silent
        // = true), which resolves straight to Idle instead of UpToDate/Error - so
        // Idle here covers both "nothing happened yet" and "silently checked, no
        // update". A manually-triggered check (Settings' "Nach Updates suchen") never
        // produces Idle, so Checking/UpToDate/Error below only ever show for that.
        is UpdateUiState.Idle -> Unit

        is UpdateUiState.Checking -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = {
                TextButton(onClick = viewModel::dismiss) { Text("Abbrechen") }
            },
            title = { Text("Suche nach Updates…") },
            text = { CircularProgressIndicator() },
        )

        is UpdateUiState.UpToDate -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = {
                TextButton(onClick = viewModel::dismiss) { Text("OK") }
            },
            title = { Text("Kein Update verfügbar") },
            text = { Text("Du hast bereits die neueste Version.") },
        )

        is UpdateUiState.Error -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = {
                TextButton(onClick = viewModel::dismiss) { Text("OK") }
            },
            title = { Text("Fehler") },
            text = { Text(state.message) },
        )

        is UpdateUiState.Available -> UpdateAvailableDialog(
            info = state.info,
            onDismiss = viewModel::dismiss,
            onUpdate = { viewModel.downloadAndInstall(state.info) },
        )

        is UpdateUiState.Downloading -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = {
                TextButton(onClick = viewModel::dismiss) { Text("Abbrechen") }
            },
            title = { Text("Update wird heruntergeladen…") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { state.progressPct / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${state.progressPct}%", style = MaterialTheme.typography.bodySmall)
                }
            },
        )

        is UpdateUiState.ReadyToInstall -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = {
                TextButton(onClick = viewModel::dismiss) { Text("OK") }
            },
            title = { Text("Installation gestartet") },
            text = { Text("Bitte bestätige die Installation im Systemdialog.") },
        )
    }
}

@Composable
private fun UpdateAvailableDialog(
    info: UpdateInfoDto,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ein Update ist verfügbar") },
        text = { Text("Version ${info.versionName} ist verfügbar. Jetzt herunterladen und installieren?") },
        confirmButton = {
            TextButton(onClick = onUpdate) { Text("Jetzt aktualisieren") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Später") }
        },
    )
}
