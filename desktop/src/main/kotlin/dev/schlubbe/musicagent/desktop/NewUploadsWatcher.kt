package dev.schlubbe.musicagent.desktop

import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * "Neue Uploads von gefolgten Künstlern": polls followed artists' latest tracks and
 * reports ones not seen before. The first pass only primes the seen-set, so
 * starting the app never floods notifications.
 */
class NewUploadsWatcher(private val graph: AppGraph) {
    private val seen = mutableSetOf<String>()
    private var primed = false

    suspend fun check(): List<TrackResultDto> {
        val fresh = mutableListOf<TrackResultDto>()
        graph.store.current.followed.forEach { a ->
            val latest = runCatching { graph.search.getArtist(a.source, a.sourceId).latestTracks.take(3) }.getOrDefault(emptyList())
            latest.forEach { t -> if (seen.add("${t.source}:${t.sourceId}") && primed) fresh += t }
        }
        primed = true
        return fresh
    }

    suspend fun run(intervalMs: Long = 3 * 60 * 60 * 1000L, notify: (TrackResultDto) -> Unit) {
        while (coroutineContext.isActive) {
            if (graph.settings.current.notifyNewUploads) check().forEach(notify)
            delay(intervalMs)
        }
    }
}
