package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Subtitles
import com.adamglin.phosphoricons.regular.X
import dev.schlubbe.musicagent.data.repository.LyricLine
import dev.schlubbe.musicagent.data.repository.Lyrics
import dev.schlubbe.musicagent.desktop.data.key
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.EmptyState
import dev.schlubbe.musicagent.desktop.ui.IconBtn
import dev.schlubbe.musicagent.desktop.ui.LoadingBox
import dev.schlubbe.musicagent.desktop.ui.LocalUi
import dev.schlubbe.musicagent.desktop.ui.SidePanel

/** Index of the line being sung at [posMs] (last line with timeMs <= pos), -1 before the first line. Binary search. */
fun activeLyricIndex(lines: List<LyricLine>, posMs: Long): Int {
    var lo = 0
    var hi = lines.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timeMs <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

/** Per-track cache that survives closing the panel; a null value = "no lyrics found". */
private object LyricsCache {
    val map = mutableStateMapOf<String, Lyrics?>()
}

@Composable
fun LyricsPanel(modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val q by g.player.state.collectAsState()
    val t = q.current
    val key = t?.key
    LaunchedEffect(key) {
        if (t == null || LyricsCache.map.containsKey(t.key)) return@LaunchedEffect
        LyricsCache.map[t.key] = runCatching { g.lyrics.lyricsFor(t.title, t.artist, t.durationSec) }.getOrNull()
    }
    val loaded = key != null && LyricsCache.map.containsKey(key)
    val lyrics = key?.let { LyricsCache.map[it] }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Songtext", style = MaterialTheme.typography.titleLarge, color = c.text)
                if (t != null) Text(listOfNotNull(t.title, t.artist).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
            }
            IconBtn(PhosphorIcons.Regular.X, "Schließen") { ui.togglePanel(SidePanel.LYRICS) }
        }
        Column(Modifier.weight(1f).fillMaxWidth()) {
            when {
                t == null -> EmptyState(PhosphorIcons.Regular.Subtitles, "Nichts läuft", "Spiele einen Titel ab.")
                !loaded -> LoadingBox()
                lyrics == null -> EmptyState(PhosphorIcons.Regular.Subtitles, "Keine Songtexte gefunden", "Für diesen Titel gibt es bei LRCLIB keinen Text.")
                lyrics.synced != null -> SyncedLyrics(lyrics.synced!!)
                else -> Text(
                    lyrics.plain.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = c.text,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        Text("Songtexte von LRCLIB", style = MaterialTheme.typography.labelSmall, color = c.textFaint, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

@Composable
private fun SyncedLyrics(lines: List<LyricLine>) {
    val g = LocalUi.current.graph
    val c = C.c
    val e by g.player.engineState.collectAsState()
    val active = activeLyricIndex(lines, (e.positionSec * 1000).toLong() + 250)
    val list = rememberLazyListState()
    var userScrolledAt by remember { mutableStateOf(0L) }
    val auto = remember { booleanArrayOf(false) }
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress && !auto[0]) userScrolledAt = System.currentTimeMillis() }
    LaunchedEffect(active) {
        // don't fight the user's own scrolling for a few seconds
        if (active >= 0 && System.currentTimeMillis() - userScrolledAt > 3000) {
            val viewport = list.layoutInfo.viewportSize.height
            auto[0] = true
            try { list.animateScrollToItem(active, scrollOffset = -viewport / 3) } finally { auto[0] = false }
        }
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 120.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(lines) { i, line ->
            val color by animateColorAsState(
                when {
                    i == active -> c.text
                    i < active -> c.textMuted.copy(alpha = 0.6f)
                    else -> c.textFaint
                },
            )
            Text(
                line.text, color = color,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = if (i == active) FontWeight.Bold else FontWeight.SemiBold),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable { userScrolledAt = 0L; g.player.seek(line.timeMs / 1000.0) }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
