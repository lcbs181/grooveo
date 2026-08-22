package dev.schlubbe.musicagent.ui.downloads

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
import dev.schlubbe.musicagent.ui.library.DownloadUiItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
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
) : ViewModel() {

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
        .map { items -> items.map { it.copy(track = trackDao.getById(it.entity.trackId)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun setDataSaverMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDataSaverMode(enabled) }
    }

    fun pauseAll() {
        downloads.value
            .filter { it.entity.state == DownloadState.DOWNLOADING || it.entity.state == DownloadState.QUEUED }
            .forEach { downloadRepository.pauseDownload(it.entity.trackId) }
    }

    fun resume(trackId: String) = downloadRepository.resumeDownload(trackId)

    fun retry(trackId: String) = downloadRepository.retryDownload(trackId)

    fun cancel(trackId: String) {
        viewModelScope.launch { downloadDao.delete(trackId) }
    }

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
