package dev.schlubbe.musicagent.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto

@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistOutDto>,
    onDismiss: () -> Unit,
    onPlaylistPicked: (String) -> Unit,
    onCreatePlaylist: (String) -> Unit,
) {
    var newPlaylistName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zu Playlist hinzufügen") },
        text = {
            Column {
                if (playlists.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            Text(
                                "${playlist.name} (${playlist.trackCount})",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPlaylistPicked(playlist.id) }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Neue Playlist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreatePlaylist(newPlaylistName) },
                enabled = newPlaylistName.isNotBlank(),
            ) {
                Text("Erstellen & hinzufügen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        },
    )
}
