package dev.schlubbe.musicagent.ui.downloads

import dev.schlubbe.musicagent.data.tags.TagRepository
import dev.schlubbe.musicagent.data.repository.LikesRepository
import kotlinx.coroutines.Job
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.repository.DownloadRepository
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import dev.schlubbe.musicagent.data.local.entity.DownloadEntity
import dev.schlubbe.musicagent.download.DownloadWorker
import dev.schlubbe.musicagent.playback.PlayerController
import dev.schlubbe.musicagent.ui.library.DownloadUiItem
import dev.schlubbe.musicagent.ui.library.toTrackResultDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Storage line under the Downloads header. [usedBytes] is what this app's own
 * completed downloads occupy (summed from their recorded sizes), not the whole
 * device -- the design's "2,1 / 8 GB" pairs it with total device capacity. */
data class StorageInfo(val usedBytes: Long, val totalBytes: Long)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadDao: DownloadDao,
    private val trackDao: TrackDao,
    private val workManager: WorkManager,
    private val downloadRepository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
    private val playerController: PlayerController,
    likesRepository: LikesRepository,
    private val tagRepository: TagRepository,
) : ViewModel() {

    /** Bumped when the tracks' info changed ("Neu einlesen"), so the list reads it again. */
    private val tracksChanged = MutableStateFlow(0)

    // Same live-progress composition LibraryViewModel uses for its Downloads
    // sub-view, reused rather than duplicated so both stay in step.
    val downloads: StateFlow<List<DownloadUiItem>> = downloadDao.observeAll()
        .flatMapLatest { entities ->
            if (entities.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(entities.map { entity -> entity.toLiveFlow(workManager) }) { it.toList() }
            }
        }
        .combine(tracksChanged) { items, _ -> items }
        .map { items -> items.map { it.copy(track = trackDao.getById(it.entity.trackId)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val likedTrackIds: StateFlow<Set<String>> = likesRepository.likedTrackIds
    val tagsByTrack: StateFlow<Map<String, List<String>>> = tagRepository.tagsByTrack

    init {
        // tags of downloads from before tags were kept
        viewModelScope.launch {
            downloads.collect { list -> tagRepository.fetchMissing(list.mapNotNull { it.track?.let { t -> t.source to t.sourceId } }) }
        }
    }

    private val _filter = MutableStateFlow(DownloadFilter())
    val filter: StateFlow<DownloadFilter> = _filter.asStateFlow()
    fun setFilter(f: DownloadFilter) { _filter.value = f }

    private val _refreshing = MutableStateFlow<String?>(null)
    /** Progress of "Neu einlesen" ("12 / 40"), null when it is not running. */
    val refreshing: StateFlow<String?> = _refreshing.asStateFlow()
    private var refreshJob: Job? = null

    /**
     * "Neu einlesen": finds files on the phone again and fetches title, artist, cover and tags
     * of every offline track anew; the covers are kept on the phone.
     */
    fun refreshOffline() {
        if (refreshJob?.isActive == true) return
        _refreshing.value = "Suche …"
        refreshJob = viewModelScope.launch {
            val r = runCatching {
                downloadRepository.refreshOffline { done, total -> _refreshing.value = "$done / $total" }
            }.getOrNull()
            _refreshing.value = null
            tracksChanged.value++
            _found.value = when {
                r == null -> "Neu einlesen fehlgeschlagen"
                else -> buildList {
                    if (r.found > 0) add(if (r.found == 1) "1 Datei gefunden" else "${r.found} Dateien gefunden")
                    add(if (r.updated == 1) "1 Titel aktualisiert" else "${r.updated} Titel aktualisiert")
                    if (r.failed > 0) add("${r.failed} nicht erreichbar")
                }.joinToString(" · ")
            }
        }
    }

    val dataSaverMode: StateFlow<Boolean> = settingsRepository.dataSaverMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val storage: StateFlow<StorageInfo> = downloads
        .map { items ->
            val used = items
                .filter { it.entity.state == DownloadState.COMPLETED }
                .sumOf { it.entity.totalBytes ?: 0L }
            StorageInfo(usedBytes = used, totalBytes = deviceTotalBytes())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageInfo(0L, 0L))

    private val _found = MutableStateFlow<String?>(null)
    /** What the last scan of the phone added ("3 Downloads auf dem Handy gefunden"). */
    val found: StateFlow<String?> = _found.asStateFlow()

    /** Looks for downloaded files on the phone that the list does not show yet. */
    fun scanDevice(): Job =
        viewModelScope.launch {
            val n = runCatching { downloadRepository.scanDevice() }.getOrDefault(0)
            if (n > 0) _found.value = if (n == 1) "1 Download auf dem Handy gefunden" else "$n Downloads auf dem Handy gefunden"
        }

    fun setDataSaverMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDataSaverMode(enabled) }
    }

    fun pauseAll() {
        downloads.value
            .filter { it.entity.state == DownloadState.DOWNLOADING || it.entity.state == DownloadState.QUEUED }
            .forEach { downloadRepository.pauseDownload(it.entity.trackId) }
    }

    // Mirrors LibraryViewModel.playDownload - kept per-file rather than shared per
    // this codebase's existing convention for small ViewModel-local glue (see the
    // toLiveFlow() note below).
    fun playDownload(item: DownloadUiItem) {
        val uri = item.entity.mediaStoreUri ?: return
        viewModelScope.launch {
            val track = item.track?.toTrackResultDto()
            if (track != null) {
                playerController.playLocalDownload(track, uri)
            } else {
                playerController.playFromUri(uri)
            }
        }
    }

    fun resume(trackId: String) = downloadRepository.resumeDownload(trackId)

    fun retry(trackId: String) = downloadRepository.retryDownload(trackId)

    fun cancel(trackId: String) = downloadRepository.deleteDownload(trackId)

    private fun deviceTotalBytes(): Long = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        stat.blockSizeLong * stat.blockCountLong
    }.getOrDefault(0L)

    // Same shape as LibraryViewModel's own converter of this name. Kept per-file
    // rather than shared because it's an instance-member extension there too --
    // matching this codebase's existing convention for small ViewModel-local glue
    // (see the note on DownloadUiItem).
    private fun DownloadEntity.toLiveFlow(workManager: WorkManager) =
        if (state == DownloadState.DOWNLOADING) {
            workManager.getWorkInfosForUniqueWorkFlow(trackId).map { infos ->
                val pct = infos.firstOrNull()?.progress?.getInt(DownloadWorker.PROGRESS_KEY, -1)
                DownloadUiItem(this, pct?.takeIf { it >= 0 })
            }
        } else {
            flowOf(DownloadUiItem(this, null))
        }
}
