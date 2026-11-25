package dev.schlubbe.musicagent.ui.playlist

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.repository.PlaylistRepository
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "Aus Playlist entfernen" for every song menu in the app: the menu entry only
 * shows when the song is in at least one playlist; one playlist removes it right
 * away, several open a picker. The picker lives at the app root
 * ([PlaylistRemoverHost]), because a dialog opened from inside a DropdownMenu would
 * disappear with the menu. */
@HiltViewModel
class PlaylistRemoverViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    data class Pick(val track: TrackResultDto, val playlists: List<PlaylistOutDto>)

    private val _pick = MutableStateFlow<Pick?>(null)
    val pick: StateFlow<Pick?> = _pick

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    suspend fun playlistsContaining(track: TrackResultDto): List<PlaylistOutDto> =
        runCatching { playlistRepository.playlistsContaining(track) }.getOrDefault(emptyList())

    fun request(track: TrackResultDto, playlists: List<PlaylistOutDto>) {
        when (playlists.size) {
            0 -> Unit
            1 -> remove(track, playlists.single())
            else -> _pick.value = Pick(track, playlists)
        }
    }

    fun remove(track: TrackResultDto, playlist: PlaylistOutDto) {
        _pick.value = null
        viewModelScope.launch {
            runCatching { playlistRepository.removeTrack(playlist.id, track) }
                .onSuccess { _messages.send("Aus „${playlist.name}“ entfernt") }
                .onFailure { _messages.send("Entfernen hat nicht geklappt") }
        }
    }

    fun dismiss() {
        _pick.value = null
    }
}

class PlaylistRemover internal constructor(private val viewModel: PlaylistRemoverViewModel) {
    suspend fun playlistsContaining(track: TrackResultDto) = viewModel.playlistsContaining(track)
    fun request(track: TrackResultDto, playlists: List<PlaylistOutDto>) = viewModel.request(track, playlists)
}

val LocalPlaylistRemover = staticCompositionLocalOf<PlaylistRemover?> { null }

/** Provides [LocalPlaylistRemover] to [content] and draws the playlist picker. */
@Composable
fun PlaylistRemoverHost(content: @Composable () -> Unit) {
    val viewModel: PlaylistRemoverViewModel = hiltViewModel()
    val remover = androidx.compose.runtime.remember(viewModel) { PlaylistRemover(viewModel) }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalPlaylistRemover provides remover, content = content)

    val pick by viewModel.pick.collectAsState()
    pick?.let { p ->
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("Aus welcher Playlist entfernen?") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(p.playlists, key = { it.id }) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.remove(p.track, playlist) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(phosphorIcon("minus-circle"), contentDescription = null, tint = Canopy.accent, modifier = Modifier.size(20.dp))
                            Text("${playlist.name} (${playlist.trackCount})", modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("Abbrechen") } },
        )
    }
}

/** Menu entry for per-track "⋯" menus. Hidden while loading and when the song is
 * in no playlist. [exceptPlaylistId] leaves out the playlist the menu belongs to
 * (that screen has its own "Aus dieser Playlist entfernen"). */
@Composable
fun RemoveFromPlaylistMenuItem(
    track: TrackResultDto,
    onDismissMenu: () -> Unit,
    exceptPlaylistId: String? = null,
) {
    val remover = LocalPlaylistRemover.current ?: return
    val playlists by produceState<List<PlaylistOutDto>>(emptyList(), track.source, track.sourceId, exceptPlaylistId) {
        value = remover.playlistsContaining(track).filter { it.id != exceptPlaylistId }
    }
    if (playlists.isEmpty()) return
    DropdownMenuItem(
        text = { Text(if (playlists.size == 1) "Aus „${playlists.single().name}“ entfernen" else "Aus Playlist entfernen") },
        leadingIcon = { Icon(phosphorIcon("minus-circle"), contentDescription = null, tint = Canopy.accent) },
        onClick = {
            onDismissMenu()
            remover.request(track, playlists)
        },
    )
}
