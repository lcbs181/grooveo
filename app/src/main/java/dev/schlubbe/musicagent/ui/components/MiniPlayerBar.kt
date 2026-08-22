package dev.schlubbe.musicagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.player.PlayerViewModel
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyPillShape

/** Canopy `MiniPlayerBar`: a floating surface pill that sits above the tab bar
 * on every main screen once something is loaded, from GrooveoApp.dc.html.
 *
 * Carries three of the design's motion cues: the accent wave-sweep when
 * playback is (re)started from here, the heart pop-and-confetti on a like, and
 * the confetti spray on a follow. Renders nothing while nothing is loaded, so
 * callers can place it unconditionally. */
@Composable
fun MiniPlayerBar(
    onClick: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val playbackState by viewModel.playbackState.collectAsState()
    val isLiked by viewModel.isLiked.collectAsState()
    val isFollowing by viewModel.isCurrentArtistFollowed.collectAsState()
    val haptic = LocalHapticFeedback.current

    // Also shown while the very first track of the session is still resolving (no
    // currentTrackId yet) - without this, tapping a track gave no feedback at all
    // until resolution finished.
    if (playbackState.currentTrackId == null && !playbackState.isLoading) return

    val overlay = LocalCanopyOverlay.current
    var waveTrigger by remember { mutableIntStateOf(0) }
    var likeTrigger by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(CanopyPillShape)
            .background(Canopy.surface)
            .border(1.dp, Canopy.divider, CanopyPillShape)
            .clickable(onClick = onClick),
    ) {
        WaveSweep(trigger = waveTrigger, shape = CanopyPillShape)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 44dp accent circle, the design's own primary control here.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Canopy.accent)
                    .clickable(enabled = !playbackState.isUnavailable) {
                        haptic.performHapticFeedback(
                            if (playbackState.isPlaying) {
                                HapticFeedbackType.ToggleOff
                            } else {
                                HapticFeedbackType.ToggleOn
                            },
                        )
                        waveTrigger++
                        viewModel.togglePlayPause()
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (playbackState.isLoading) {
                    CircularProgressIndicator(
                        color = Canopy.neutral100,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Icon(
                        phosphorIcon(if (playbackState.isPlaying) "pause" else "play", filled = true),
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Abspielen",
                        tint = Canopy.neutral100,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            TrackThumbnail(
                url = playbackState.artworkUrl,
                size = 40.dp,
                seed = playbackState.title,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (playbackState.isLoading) "Wird geladen…" else playbackState.title.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Canopy.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (playbackState.isLoading) "" else playbackState.artist.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Canopy.neutral500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    phosphorIcon(if (isFollowing) "user" else "user-plus", filled = true),
                    contentDescription = if (isFollowing) "Nicht mehr folgen" else "Folgen",
                    tint = if (isFollowing) Canopy.accent else Canopy.neutral400,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(20.dp)
                        .clickable {
                            // The follow spray is window-level (position:fixed in the
                            // design), so it goes through the root overlay rather than
                            // being anchored to this button.
                            if (!isFollowing) overlay.spray()
                            viewModel.toggleFollowCurrentArtist()
                        },
                )

                Box(contentAlignment = Alignment.Center) {
                    val popScale = rememberHeartPopScale(likeTrigger)
                    Icon(
                        phosphorIcon("heart", filled = true),
                        contentDescription = if (isLiked) "Gefällt mir nicht mehr" else "Gefällt mir",
                        tint = if (isLiked) Canopy.accent2 else Canopy.neutral400,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(20.dp)
                            .graphicsLayer { scaleX = popScale; scaleY = popScale }
                            .clickable {
                                if (!isLiked) likeTrigger++
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                viewModel.toggleLike()
                            },
                    )
                    Confetti(trigger = likeTrigger, count = 8, spread = 60f)
                }
            }
        }
    }
}
