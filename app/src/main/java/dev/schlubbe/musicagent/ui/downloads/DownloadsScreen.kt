package dev.schlubbe.musicagent.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.ui.components.CanopySectionHeader
import dev.schlubbe.musicagent.ui.components.CanopyToggle
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.library.DownloadUiItem
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyPillShape
import dev.schlubbe.musicagent.ui.theme.CanopyShapes

// Canopy Downloads, from the handoff's board 08: data-saver card, storage line,
// the active queue with per-track progress, then what's already on the device.
private const val CONTENT_BOTTOM_PADDING = 150

@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel = hiltViewModel()) {
    val downloads by viewModel.downloads.collectAsState()
    val dataSaver by viewModel.dataSaverMode.collectAsState()
    val storage by viewModel.storage.collectAsState()

    val queue = downloads.filter { it.entity.state != DownloadState.COMPLETED }
    val onDevice = downloads.filter { it.entity.state == DownloadState.COMPLETED }

    Scaffold(containerColor = Canopy.bg) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 14.dp,
                bottom = CONTENT_BOTTOM_PADDING.dp,
            ),
        ) {
            item {
                Text(
                    "Downloads",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Canopy.text,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CanopyShapes.medium)
                        .background(Canopy.surface)
                        .border(1.dp, Canopy.divider, CanopyShapes.medium)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        phosphorIcon("cell-signal-slash"),
                        contentDescription = null,
                        tint = Canopy.neutral500,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Datensparmodus",
                            style = MaterialTheme.typography.titleMedium,
                            color = Canopy.text,
                        )
                        Text(
                            "Nur heruntergeladene Titel abspielen",
                            style = MaterialTheme.typography.bodySmall,
                            color = Canopy.neutral500,
                        )
                    }
                    CanopyToggle(checked = dataSaver, onCheckedChange = viewModel::setDataSaverMode)
                }
            }

            item { StorageLine(storage) }

            if (queue.isNotEmpty()) {
                item {
                    CanopySectionHeader(
                        title = "Warteschlange",
                        action = "Alle pausieren",
                        onActionClick = viewModel::pauseAll,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
                items(queue.size) { index ->
                    QueueRow(
                        item = queue[index],
                        onCancel = { viewModel.cancel(queue[index].entity.trackId) },
                        onRetry = { viewModel.retry(queue[index].entity.trackId) },
                        onResume = { viewModel.resume(queue[index].entity.trackId) },
                    )
                }
            }

            if (onDevice.isNotEmpty()) {
                item {
                    CanopySectionHeader(
                        title = "Auf dem Gerät",
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
                items(onDevice.size) { index -> OnDeviceRow(onDevice[index]) }
            }

            if (downloads.isEmpty()) {
                item {
                    Text(
                        "Noch keine Downloads. Titel mit dem Pfeil-Symbol offline speichern.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Canopy.neutral500,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageLine(storage: StorageInfo) {
    val fraction = if (storage.totalBytes > 0) {
        (storage.usedBytes.toFloat() / storage.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Speicher", style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500)
            Text(
                "${formatBytes(storage.usedBytes)} / ${formatBytes(storage.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = Canopy.neutral500,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(6.dp)
                .clip(CanopyPillShape)
                .background(Canopy.neutral200),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(CanopyPillShape)
                    .background(Canopy.accent),
            )
        }
    }
}

@Composable
private fun QueueRow(
    item: DownloadUiItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onResume: () -> Unit,
) {
    val pct = item.livePct ?: item.entity.progressPct
    val failed = item.entity.state == DownloadState.FAILED
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackThumbnail(
            url = item.track?.thumbnailUrl,
            size = 46.dp,
            seed = item.track?.title ?: item.entity.trackId,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.track?.title ?: item.entity.trackId,
                style = MaterialTheme.typography.labelLarge,
                color = Canopy.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.entity.state == DownloadState.DOWNLOADING) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(4.dp)
                        .clip(CanopyPillShape)
                        .background(Canopy.neutral200),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((pct / 100f).coerceIn(0f, 1f))
                            .height(4.dp)
                            .clip(CanopyPillShape)
                            .background(Canopy.accent),
                    )
                }
            }
            Text(
                queueStatusLabel(item.entity.state, pct),
                style = MaterialTheme.typography.bodySmall,
                // Coral for a failure, matching the design's own alert treatment.
                color = if (failed) Canopy.accent2 else Canopy.neutral500,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // A failed or paused download gets a retry/resume affordance instead of a
        // bare cancel, so the row isn't a dead end.
        if (failed) {
            Icon(
                phosphorIcon("arrow-clockwise"),
                contentDescription = "Erneut versuchen",
                tint = Canopy.accent,
                modifier = Modifier.size(20.dp).clickable(onClick = onRetry),
            )
        } else if (item.entity.state == DownloadState.PAUSED) {
            Icon(
                phosphorIcon("play", filled = true),
                contentDescription = "Fortsetzen",
                tint = Canopy.accent,
                modifier = Modifier.size(20.dp).clickable(onClick = onResume),
            )
        }
        Icon(
            phosphorIcon("x"),
            contentDescription = "Abbrechen",
            tint = Canopy.neutral400,
            modifier = Modifier.size(18.dp).clickable(onClick = onCancel),
        )
    }
}

@Composable
private fun OnDeviceRow(item: DownloadUiItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackThumbnail(
            url = item.track?.thumbnailUrl,
            size = 46.dp,
            seed = item.track?.title ?: item.entity.trackId,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.track?.title ?: item.entity.trackId,
                style = MaterialTheme.typography.labelLarge,
                color = Canopy.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    item.track?.artist,
                    item.entity.totalBytes?.takeIf { it > 0 }?.let { formatBytes(it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Canopy.neutral500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            phosphorIcon("check-circle", filled = true),
            contentDescription = "Gespeichert",
            tint = Canopy.accent,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun queueStatusLabel(state: DownloadState, pct: Int): String = when (state) {
    DownloadState.DOWNLOADING -> "$pct %"
    DownloadState.QUEUED -> "Wartet"
    DownloadState.PAUSED -> "Angehalten"
    DownloadState.FAILED -> "Fehlgeschlagen"
    DownloadState.COMPLETED -> "Fertiggestellt"
}

/** Decimal GB/MB, the convention Android's own storage UI uses. */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 MB"
    bytes >= 1_000_000_000L -> String.format("%.1f GB", bytes / 1_000_000_000.0)
    else -> String.format("%.0f MB", bytes / 1_000_000.0)
}
