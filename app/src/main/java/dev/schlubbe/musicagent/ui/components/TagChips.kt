package dev.schlubbe.musicagent.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.data.tags.TrackTags

/**
 * A row of tags to filter a list by, scrolling sideways. Tapping a tag selects it, tapping
 * the selected one again shows everything. Nothing is shown without tags.
 */
@Composable
fun TagChips(
    tags: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
) {
    if (tags.isEmpty() && selected == null) return
    // a selected tag stays in the row even when the list no longer has it
    val shown = if (selected != null && tags.none { TrackTags.key(it) == TrackTags.key(selected) }) listOf(selected) + tags else tags
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(shown, key = { TrackTags.key(it) }) { tag ->
            val active = selected != null && TrackTags.key(tag) == TrackTags.key(selected)
            CanopyChip(label = tag, active = active, onClick = { onSelect(if (active) null else tag) })
        }
    }
}
