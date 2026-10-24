package dev.schlubbe.musicagent.ui.downloads
import dev.schlubbe.musicagent.ui.components.TagChips
import dev.schlubbe.musicagent.data.tags.TrackTags
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.ANALYZE_LABEL
import dev.schlubbe.musicagent.ui.components.LocalTrackAnalyzer

import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.ui.components.CanopySectionHeader
import dev.schlubbe.musicagent.ui.components.CanopyToggle
import dev.schlubbe.musicagent.ui.components.PullRefresh
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
fun DownloadsScreen(onDownloadPlayed: () -> Unit = {}, viewModel: DownloadsViewModel = hiltViewModel()) {
    val analyzer = LocalTrackAnalyzer.current
    val downloads by viewModel.downloads.collectAsState()
    val dataSaver by viewModel.dataSaverMode.collectAsState()
    val storage by viewModel.storage.collectAsState()
    val found by viewModel.found.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val audioPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        android.Manifest.permission.READ_MEDIA_AUDIO
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }
    fun hasAudioAccess() = androidx.core.content.ContextCompat.checkSelfPermission(context, audioPermission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    var audioAccess by remember { mutableStateOf(hasAudioAccess()) }
    val askAudio = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        audioAccess = granted
        viewModel.scanDevice()
    }
    // Downloads made before a reinstall are still on the phone: find them every time the tab opens.
    // Reading files of an earlier install needs the audio permission, asked once by itself.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("downloads_scan", android.content.Context.MODE_PRIVATE)
        if (!audioAccess && !prefs.getBoolean("asked", false)) {
            prefs.edit().putBoolean("asked", true).apply()
            askAudio.launch(audioPermission)
        } else {
            viewModel.scanDevice()
        }
    }

    val queue = downloads.filter { it.entity.state != DownloadState.COMPLETED }
    val filter by viewModel.filter.collectAsState()
    val liked by viewModel.likedTrackIds.collectAsState()
    val tagsByTrack by viewModel.tagsByTrack.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val allOnDevice = downloads.filter { it.entity.state == DownloadState.COMPLETED }
    val onDevice = remember(allOnDevice, filter, liked, tagsByTrack) { filter.apply(allOnDevice, liked, tagsByTrack) }
    val tagChips = remember(allOnDevice, tagsByTrack) { TrackTags.top(allOnDevice.mapNotNull { tagsByTrack[it.entity.trackId] }) }

    Scaffold(containerColor = Canopy.bg, contentWindowInsets = WindowInsets(0)) { padding ->
        PullRefresh(onRefresh = viewModel::scanDevice, modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
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

                found?.let { msg ->
                    item {
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = Canopy.accent, modifier = Modifier.padding(bottom = 12.dp))
                    }
                }
                if (!audioAccess) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                                .clip(CanopyShapes.medium)
                                .background(Canopy.surface)
                                .border(1.dp, Canopy.divider, CanopyShapes.medium)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Downloads auf dem Handy finden", style = MaterialTheme.typography.bodyMedium, color = Canopy.text)
                            Text(
                                "Titel, die Grooveo früher heruntergeladen hat (z. B. vor einer Neuinstallation), liegen noch in Musik/ und Download/. " +
                                    "Mit Zugriff auf Audiodateien zeigt Grooveo sie hier wieder an.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Canopy.neutral500,
                            )
                            dev.schlubbe.musicagent.ui.components.CanopyButton(
                                text = "Zugriff erlauben",
                                onClick = { askAudio.launch(audioPermission) },
                                variant = CanopyButtonVariant.Secondary,
                            )
                        }
                    }
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
                        // Decorative: the adjacent "Datensparmodus" text already labels
                        // this row for TalkBack, so the icon itself stays unlabeled
                        // rather than announcing redundantly.
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

                if (allOnDevice.isNotEmpty()) {
                    item {
                        DownloadsSearchRow(filter = filter, onChange = viewModel::setFilter, modifier = Modifier.padding(top = 16.dp))
                    }
                    if (tagChips.isNotEmpty() || filter.tag != null) {
                        item {
                            TagChips(
                                tags = tagChips,
                                selected = filter.tag,
                                onSelect = { viewModel.setFilter(filter.copy(tag = it)) },
                                horizontalPadding = 0.dp,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                if (filter.isFiltered) "Auf dem Gerät · ${onDevice.size} von ${allOnDevice.size}" else "Auf dem Gerät · ${allOnDevice.size}",
                                style = MaterialTheme.typography.titleMedium,
                                color = Canopy.text,
                                modifier = Modifier.weight(1f),
                            )
                            dev.schlubbe.musicagent.ui.components.CanopyButton(
                                text = refreshing ?: "Neu einlesen",
                                onClick = viewModel::refreshOffline,
                                variant = CanopyButtonVariant.Ghost,
                                leadingIcon = phosphorIcon("arrow-clockwise"),
                                enabled = refreshing == null,
                            )
                            CanopyIconButton(
                                icon = phosphorIcon("waveform"),
                                onClick = analyzer::analyzeAllDownloads,
                                shape = CircleShape,
                                variant = CanopyButtonVariant.Secondary,
                                contentDescription = ANALYZE_LABEL,
                            )
                        }
                    }
                    if (onDevice.isEmpty()) {
                        item {
                            Text("Keine Treffer", color = Canopy.neutral500, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }
                if (onDevice.isNotEmpty()) {
                    items(onDevice.size) { index ->
                        OnDeviceRow(
                            item = onDevice[index],
                            onClick = {
                                viewModel.playDownload(onDevice[index])
                                onDownloadPlayed()
                            },
                            onDelete = { viewModel.cancel(onDevice[index].entity.trackId) },
                        )
                    }
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
        // 48 dp touch targets: the bare 18-20 dp icons were hard to hit
        if (failed) {
            CanopyIconButton(icon = phosphorIcon("arrow-clockwise"), onClick = onRetry, size = 48.dp, iconSize = 20.dp, contentDescription = "Erneut versuchen")
        } else if (item.entity.state == DownloadState.PAUSED) {
            CanopyIconButton(icon = phosphorIcon("play", filled = true), onClick = onResume, size = 48.dp, iconSize = 20.dp, contentDescription = "Fortsetzen")
        }
        CanopyIconButton(icon = phosphorIcon("x"), onClick = onCancel, size = 48.dp, iconSize = 18.dp, contentDescription = "Abbrechen")
    }
}

@Composable
private fun OnDeviceRow(item: DownloadUiItem, onClick: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Download entfernen?") },
            text = { Text("„${item.track?.title ?: item.entity.trackId}“ wird vom Gerät gelöscht. Streamen geht weiterhin.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Entfernen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
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
        CanopyIconButton(
            icon = phosphorIcon("trash"),
            onClick = { confirmDelete = true },
            contentDescription = "Download entfernen",
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


