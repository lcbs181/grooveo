package dev.schlubbe.musicagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy

/**
 * What a track row reveals while it is swiped right to queue it. Only as wide as the
 * row has moved: rows are transparent in Dawn Music, so a full-width backdrop would sit
 * behind the title and artist and leave dark text on a dark colour.
 */
@Composable
fun QueueSwipeBackdrop(state: SwipeToDismissBoxState) {
    if (state.dismissDirection != SwipeToDismissBoxValue.StartToEnd) return
    val offset = runCatching { state.requireOffset() }.getOrDefault(0f).coerceAtLeast(0f)
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .width(with(LocalDensity.current) { offset.toDp() })
            .clipToBounds()
            .background(Canopy.accent800)
            .padding(start = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(phosphorIcon("list-plus"), contentDescription = "Zur Warteschlange hinzufügen", tint = Canopy.accent100)
    }
}
