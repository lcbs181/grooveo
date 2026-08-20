package dev.schlubbe.musicagent.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.ui.player.PlayerViewModel

/** Persistent playback row shown above the bottom navigation bar on every main-app
 * screen once something is playing. Reuses [PlayerViewModel] (same one the full
 * Player screen uses) purely for its read-only playback state and togglePlayPause --
 * tapping the row itself (anywhere but the play/pause button) navigates to the full
 * Player screen. Renders nothing while nothing is loaded, so callers can place it
 * unconditionally. */
@Composable
fun MiniPlayerBar(
    onClick: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val playbackState by viewModel.playbackState.collectAsState()
    val haptic = LocalHapticFeedback.current
    // Also shown while the very first track of the session is still loading (no
    // currentTrackId yet) - without this, tapping a track for the first time gave no
    // feedback at all until either the Player screen or this bar's usual content
    // appeared once resolution finished.
    if (playbackState.currentTrackId == null && !playbackState.isLoading) return

    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TrackThumbnail(playbackState.artworkUrl, size = 40.dp)
                EqualizerBadge(
                    isPlaying = playbackState.isPlaying,
                    size = 15.dp,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    if (playbackState.isLoading) "Wird geladen…" else playbackState.title ?: "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (playbackState.isLoading) "" else playbackState.artist ?: "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (playbackState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(12.dp))
            } else {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(
                            if (playbackState.isPlaying) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                        )
                        viewModel.togglePlayPause()
                    },
                ) {
                    Icon(
                        if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Abspielen",
                    )
                }
            }
        }
    }
}
