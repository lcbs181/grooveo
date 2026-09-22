package dev.schlubbe.musicagent.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.data.repository.LyricLine
import dev.schlubbe.musicagent.ui.theme.Canopy

/** Index of the last line whose timestamp has already passed, or -1 before the
 * first line starts. [lines] must be sorted by [LyricLine.timeMs] (as
 * LyricsRepository.parseLrc already returns them). */
private fun currentLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var index = -1
    for (i in lines.indices) {
        if (lines[i].timeMs <= positionMs) index = i else break
    }
    return index
}

/** "Songtext" bottom sheet for the now-playing track. [positionMs] is passed in
 * from PlayerScreen's existing seek-bar polling loop rather than starting a
 * second one here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(
    title: String?,
    artist: String?,
    state: LyricsUiState,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Canopy.surface) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text("Songtext", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (!title.isNullOrBlank()) {
                Text(
                    if (!artist.isNullOrBlank()) "$title · $artist" else title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Canopy.neutral500,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(380.dp)) {
                when (state) {
                    is LyricsUiState.Loading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Canopy.accent,
                    )
                    is LyricsUiState.NotFound -> NotFoundText()
                    is LyricsUiState.Found -> {
                        val synced = state.lyrics.synced
                        val plain = state.lyrics.plain
                        if (synced != null) {
                            SyncedLyricsList(lines = synced, positionMs = positionMs, onSeek = onSeek)
                        } else if (!plain.isNullOrBlank()) {
                            Text(
                                plain,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Canopy.text,
                                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            )
                        } else {
                            NotFoundText()
                        }
                    }
                }
            }

            Text(
                "Songtexte von LRCLIB",
                style = MaterialTheme.typography.labelSmall,
                color = Canopy.neutral500,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun NotFoundText() {
    Text(
        "Kein Songtext gefunden",
        style = MaterialTheme.typography.bodyMedium,
        color = Canopy.neutral500,
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SyncedLyricsList(lines: List<LyricLine>, positionMs: Long, onSeek: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val currentIndex = currentLineIndex(lines, positionMs)

    // Negative scrollOffset places the target item further from the list's start
    // edge (i.e. lower on screen) rather than flush against it - about a third of
    // the visible sheet height down, so a few upcoming lines stay visible below it.
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            val viewportHeight = listState.layoutInfo.viewportSize.height
            listState.animateScrollToItem(currentIndex, scrollOffset = -(viewportHeight / 3))
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 120.dp),
    ) {
        itemsIndexed(lines) { i, line ->
            val isCurrent = i == currentIndex
            Text(
                line.text,
                style = if (isCurrent) {
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = if (isCurrent) Canopy.accent else Canopy.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeek(line.timeMs) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}
