package dev.schlubbe.musicagent.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy

/** Small inline marker for a SoundCloud track whose only transcodings are
 * DRM-encrypted (see TrackResultDto.isDrmProtected / SoundCloudMappers.isDrmOnly) -
 * shown next to the title on every discovery-surface track row (Search results,
 * Artist shelves, remote playlist browse) so a track that would fail to resolve on
 * tap is visible beforehand instead of only failing after the fact. */
@Composable
fun DrmLockIcon(modifier: Modifier = Modifier) {
    Icon(
        phosphorIcon("lock-simple"),
        contentDescription = "DRM-geschützt",
        tint = Canopy.neutral500,
        modifier = modifier.padding(start = 5.dp).size(13.dp),
    )
}
