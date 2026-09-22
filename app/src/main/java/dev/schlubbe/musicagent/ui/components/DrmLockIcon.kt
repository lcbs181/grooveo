package dev.schlubbe.musicagent.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy

/** Small inline marker for a SoundCloud track that SoundCloud won't play in full
 * (DRM-only or a 30s preview, see TrackResultDto.isDrmProtected /
 * SoundCloudMappers.isDrmOnly). Such tracks play via the matching YouTube Music
 * recording (data/extract/YouTubeFallback), so this marks the source swap rather
 * than a dead end. */
@Composable
fun DrmLockIcon(modifier: Modifier = Modifier) {
    Icon(
        phosphorIcon("youtube-logo"),
        contentDescription = "Wird über YouTube Music abgespielt",
        tint = Canopy.neutral500,
        modifier = modifier.padding(start = 5.dp).size(13.dp),
    )
}
