package dev.schlubbe.musicagent.desktop

import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * "Neue Uploads von gefolgten Künstlern": polls followed artists' latest tracks and
 * reports ones not seen before. What was seen is kept in the library, so uploads made
 * while the app was closed are reported at the next start. A newly followed artist's
 * existing tracks are taken in silently the first time.
 */
class NewUploadsWatcher(private val graph: AppGraph) {
    suspend fun check(): List<TrackResultDto> {
        val data = graph.store.current
        val seen = data.seenUploads.toHashSet()
        val checked = data.uploadArtists.toHashSet()
        val fresh = mutableListOf<TrackResultDto>()
        val newSeen = mutableListOf<String>()
        val newArtists = mutableListOf<String>()
        data.followed.forEach { a ->
            val artistKey = "${a.source}:${a.sourceId}"
            val latest = runCatching { graph.search.getArtist(a.source, a.sourceId).latestTracks.take(3) }.getOrNull() ?: return@forEach
            val known = artistKey in checked
            latest.forEach { t ->
                val k = "${t.source}:${t.sourceId}"
                if (k !in seen) { newSeen += k; if (known) fresh += t }
            }
            if (!known) newArtists += artistKey
        }
        if (newSeen.isNotEmpty() || newArtists.isNotEmpty()) graph.store.markUploadsSeen(newSeen, newArtists)
        return fresh
    }

    suspend fun run(intervalMs: Long = 3 * 60 * 60 * 1000L, notify: (TrackResultDto) -> Unit) {
        while (coroutineContext.isActive) {
            if (graph.settings.current.notifyNewUploads) check().forEach(notify)
            delay(intervalMs)
        }
    }
}
