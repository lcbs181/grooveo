package dev.schlubbe.musicagent.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.repository.TrackAnalysisRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrackAnalysisViewModel @Inject constructor(
    private val repository: TrackAnalysisRepository,
) : ViewModel() {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    fun analyze(trackIds: List<String>) {
        viewModelScope.launch { _messages.emit(repository.analyze(trackIds).toMessage()) }
    }

    fun analyzeAllDownloads() {
        viewModelScope.launch { _messages.emit(repository.analyzeAllDownloads().toMessage()) }
    }

    private fun TrackAnalysisRepository.Outcome.toMessage(): String = when {
        queued > 0 && notDownloaded > 0 ->
            "$queued Titel werden analysiert · $notDownloaded nicht heruntergeladen"
        queued > 0 -> "$queued Titel werden für smarte Übergänge analysiert"
        alreadyAnalyzed > 0 && notDownloaded == 0 -> "Bereits analysiert"
        alreadyAnalyzed > 0 -> "Heruntergeladene Titel sind bereits analysiert · $notDownloaded nicht heruntergeladen"
        else -> "Nur heruntergeladene Titel können analysiert werden"
    }
}

/** Menu entry label shared by every screen offering it. */
const val ANALYZE_LABEL = "Übergänge analysieren"

/** Screen-level handle for "Übergänge analysieren" menu entries: call [analyzeTracks]
 * with any track list (non-downloaded ones are skipped and reported); results show
 * as a toast. */
class TrackAnalyzer internal constructor(private val viewModel: TrackAnalysisViewModel) {
    fun analyzeTracks(tracks: List<TrackResultDto>) = viewModel.analyze(tracks.map { "${it.source}:${it.sourceId}" })
    fun analyzeIds(trackIds: List<String>) = viewModel.analyze(trackIds)
    fun analyzeAllDownloads() = viewModel.analyzeAllDownloads()
}

@Composable
fun rememberTrackAnalyzer(): TrackAnalyzer {
    val viewModel: TrackAnalysisViewModel = hiltViewModel()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    return TrackAnalyzer(viewModel)
}

/** Provided once at the NavGraph root (see rememberTrackAnalyzer) so every screen and
 * list row shares one toast collector instead of each row collecting its own. */
val LocalTrackAnalyzer = staticCompositionLocalOf<TrackAnalyzer> { error("LocalTrackAnalyzer not provided") }

/** Standard menu entry used by per-track "⋯" menus. */
@Composable
fun AnalyzeMenuItem(onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { androidx.compose.material3.Text(ANALYZE_LABEL) },
        leadingIcon = {
            androidx.compose.material3.Icon(
                dev.schlubbe.musicagent.ui.icons.phosphorIcon("waveform"),
                contentDescription = null,
                tint = dev.schlubbe.musicagent.ui.theme.Canopy.accent,
            )
        },
        onClick = onClick,
    )
}
