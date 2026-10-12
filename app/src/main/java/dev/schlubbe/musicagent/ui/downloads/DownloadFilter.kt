package dev.schlubbe.musicagent.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.repository.DownloadRepository
import dev.schlubbe.musicagent.data.tags.TrackTags
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.library.DownloadUiItem
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyPillShape

enum class DownloadsSort(val label: String) {
    NEWEST("Zuletzt heruntergeladen"),
    TITLE("Titel A–Z"),
    ARTIST("Künstler A–Z"),
    SIZE("Größte zuerst"),
}

enum class DownloadsSource(val label: String, val source: String?) {
    ALL("Alle Quellen", null),
    SOUNDCLOUD("SoundCloud", "soundcloud"),
    YTMUSIC("YouTube Music", "ytmusic"),
    LOCAL("Nur vom Handy", DownloadRepository.LOCAL_SOURCE),
}

/** Search, sort and filters of a list of offline tracks (Downloads tab and Bibliothek › Downloads). */
data class DownloadFilter(
    val query: String = "",
    val sort: DownloadsSort = DownloadsSort.NEWEST,
    val source: DownloadsSource = DownloadsSource.ALL,
    val likedOnly: Boolean = false,
    val tag: String? = null,
) {
    val isFiltered: Boolean get() = query.isNotBlank() || source != DownloadsSource.ALL || likedOnly || tag != null

    /** The finished downloads among [items] that match, sorted. */
    fun apply(items: List<DownloadUiItem>, liked: Set<String>, tags: Map<String, List<String>>): List<DownloadUiItem> {
        val q = query.trim()
        return items
            .filter { it.entity.state == DownloadState.COMPLETED }
            .filter {
                q.isEmpty() || it.track?.title?.contains(q, ignoreCase = true) == true ||
                    it.track?.artist?.contains(q, ignoreCase = true) == true
            }
            .filter { source.source == null || it.track?.source == source.source }
            .filter { !likedOnly || it.entity.trackId in liked }
            .filter { tag == null || TrackTags.has(tags[it.entity.trackId], tag) }
            .let { list ->
                when (sort) {
                    DownloadsSort.NEWEST -> list.sortedByDescending { it.entity.createdAt }
                    DownloadsSort.TITLE -> list.sortedBy { it.track?.title?.lowercase() ?: "" }
                    DownloadsSort.ARTIST -> list.sortedBy { it.track?.artist?.lowercase() ?: "" }
                    DownloadsSort.SIZE -> list.sortedByDescending { it.entity.totalBytes ?: 0L }
                }
            }
    }
}

/** Search field plus the sort and filter menu, like the one over the likes. */
@Composable
fun DownloadsSearchRow(
    filter: DownloadFilter,
    onChange: (DownloadFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = { onChange(filter.copy(query = it)) },
            placeholder = { Text("In Downloads suchen") },
            singleLine = true,
            leadingIcon = {
                Icon(phosphorIcon("magnifying-glass"), contentDescription = null, tint = Canopy.neutral500, modifier = Modifier.size(18.dp))
            },
            trailingIcon = if (filter.query.isBlank()) null else {
                {
                    CanopyIconButton(
                        icon = phosphorIcon("x"),
                        onClick = { onChange(filter.copy(query = "")) },
                        size = 32.dp,
                        contentDescription = "Suche löschen",
                    )
                }
            },
            shape = CanopyPillShape,
            modifier = Modifier.weight(1f),
        )
        var menu by remember { mutableStateOf(false) }
        Box {
            CanopyIconButton(
                icon = phosphorIcon("sliders-horizontal"),
                onClick = { menu = true },
                shape = CircleShape,
                variant = if (filter.source != DownloadsSource.ALL || filter.likedOnly) CanopyButtonVariant.Primary else CanopyButtonVariant.Secondary,
                contentDescription = "Sortieren und filtern",
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                @Composable
                fun check(on: Boolean) {
                    if (on) Icon(phosphorIcon("check"), contentDescription = null, tint = Canopy.accent)
                }
                DownloadsSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        leadingIcon = { check(option == filter.sort) },
                        onClick = { menu = false; onChange(filter.copy(sort = option)) },
                    )
                }
                HorizontalDivider(color = Canopy.divider)
                DownloadsSource.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        leadingIcon = { check(option == filter.source) },
                        onClick = { menu = false; onChange(filter.copy(source = option)) },
                    )
                }
                HorizontalDivider(color = Canopy.divider)
                DropdownMenuItem(
                    text = { Text("Nur Favoriten") },
                    leadingIcon = { check(filter.likedOnly) },
                    onClick = { menu = false; onChange(filter.copy(likedOnly = !filter.likedOnly)) },
                )
            }
        }
    }
}
