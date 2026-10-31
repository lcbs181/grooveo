package dev.schlubbe.musicagent.ui.spotify

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.schlubbe.musicagent.data.spotify.SpotifyImportStore
import dev.schlubbe.musicagent.data.spotify.SpotifyImportSummary
import dev.schlubbe.musicagent.data.spotify.SpotifyImportWorker
import dev.schlubbe.musicagent.data.spotify.SpotifyImportWorker.Companion as W
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class SpotifyImportProgress(
    val phase: String,
    val fetched: Int,
    val likesDone: Int,
    val likesTotal: Int,
    val playlistsDone: Int,
    val playlistsTotal: Int,
    val artistsDone: Int,
    val artistsTotal: Int,
    val current: String?,
    val currentDone: Int,
    val currentTotal: Int,
    val notFound: Int,
)

sealed interface SpotifyImportUiState {
    data object Intro : SpotifyImportUiState
    /** Exportify is open; [saving] once its export has been caught. */
    data class Exportify(val saving: Boolean) : SpotifyImportUiState
    data class Running(val progress: SpotifyImportProgress?) : SpotifyImportUiState
    data class Done(val summary: SpotifyImportSummary?) : SpotifyImportUiState
    data class Failed(val message: String) : SpotifyImportUiState
}

@HiltViewModel
class SpotifyImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager,
    private val store: SpotifyImportStore,
) : ViewModel() {

    private enum class WebState { CLOSED, OPEN, SAVING }

    private val web = MutableStateFlow(WebState.CLOSED)
    private val fileError = MutableStateFlow<String?>(null)

    private val work = workManager.getWorkInfosForUniqueWorkFlow(W.UNIQUE_WORK_NAME)
        .map { infos -> infos.lastOrNull()?.let(::toState) }
        .flowOn(Dispatchers.IO)

    val uiState: StateFlow<SpotifyImportUiState> = combine(web, work, fileError) { webState, workState, error ->
        when {
            workState is SpotifyImportUiState.Running -> workState
            webState != WebState.CLOSED -> SpotifyImportUiState.Exportify(saving = webState == WebState.SAVING)
            error != null -> SpotifyImportUiState.Failed(error)
            else -> workState ?: SpotifyImportUiState.Intro
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpotifyImportUiState.Intro)

    fun openExportify() {
        fileError.value = null
        web.value = WebState.OPEN
    }

    fun closeExportify() {
        web.value = WebState.CLOSED
    }

    /** The ZIP/CSV Exportify just tried to download. */
    fun onExportCaught(fileName: String, bytes: ByteArray) {
        if (web.value == WebState.CLOSED) return
        web.value = WebState.SAVING
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { writeImportFile(fileName) { it.write(bytes) } }
            start(file)
            web.value = WebState.CLOSED
        }
    }

    /** A file picked with the system file picker (Spotify data ZIP, CSV, ...). */
    fun importUri(uri: Uri) {
        fileError.value = null
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "spotify-export"
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        writeImportFile(name) { out -> input.copyTo(out) }
                    }
                }.getOrNull()
            }
            if (file == null) fileError.value = "Die Datei lässt sich nicht öffnen. Versuch's nochmal oder nimm eine andere."
            else start(file)
        }
    }

    private fun writeImportFile(name: String, write: (java.io.OutputStream) -> Unit): File {
        val dir = File(context.cacheDir, "spotify-import").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "spotify-export" }
        return File(dir, safe).apply { outputStream().use(write) }
    }

    private fun start(file: File) {
        workManager.enqueueUniqueWork(
            W.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SpotifyImportWorker>()
                .setInputData(workDataOf(W.KEY_FILE to file.absolutePath))
                .build(),
        )
    }

    fun cancelImport() {
        workManager.cancelUniqueWork(W.UNIQUE_WORK_NAME)
    }

    private fun toState(info: WorkInfo): SpotifyImportUiState? = when (info.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED ->
            SpotifyImportUiState.Running(progressOf(info))
        WorkInfo.State.SUCCEEDED -> SpotifyImportUiState.Done(store.loadSummary())
        WorkInfo.State.FAILED -> SpotifyImportUiState.Failed(
            when (info.outputData.getString(W.KEY_ERROR)) {
                W.ERROR_NO_FILE -> "Die Datei war weg, bevor der Import starten konnte. Versuch's bitte nochmal."
                W.ERROR_FILE -> "Mit dieser Datei kann ich nichts anfangen. Nimm die ZIP-Datei von Exportify oder von Spotifys Datenexport."
                W.ERROR_EMPTY -> "In der Datei stehen keine Songs. Hast du beim Export alles ausgewählt?"
                else -> "Die Suche war gerade nicht erreichbar. Check deine Verbindung und versuch's nochmal."
            },
        )
        WorkInfo.State.CANCELLED -> null
    }

    private fun progressOf(info: WorkInfo): SpotifyImportProgress? {
        val d = info.progress
        val phase = d.getString(W.KEY_PHASE) ?: return null
        return SpotifyImportProgress(
            phase = phase,
            fetched = d.getInt(W.KEY_FETCHED, 0),
            likesDone = d.getInt(W.KEY_LIKES_DONE, 0),
            likesTotal = d.getInt(W.KEY_LIKES_TOTAL, 0),
            playlistsDone = d.getInt(W.KEY_PLAYLISTS_DONE, 0),
            playlistsTotal = d.getInt(W.KEY_PLAYLISTS_TOTAL, 0),
            artistsDone = d.getInt(W.KEY_ARTISTS_DONE, 0),
            artistsTotal = d.getInt(W.KEY_ARTISTS_TOTAL, 0),
            current = d.getString(W.KEY_CURRENT),
            currentDone = d.getInt(W.KEY_CURRENT_DONE, 0),
            currentTotal = d.getInt(W.KEY_CURRENT_TOTAL, 0),
            notFound = d.getInt(W.KEY_NOT_FOUND, 0),
        )
    }
}
