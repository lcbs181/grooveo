package dev.schlubbe.musicagent.data.repository

import dev.schlubbe.musicagent.data.remote.BackendApi
import dev.schlubbe.musicagent.data.remote.dto.EventCreateDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Fire-and-forget analytics/feed-signal reporting — failures are swallowed so
 * a flaky connection never affects playback or navigation. */
@Singleton
class EventReporter @Inject constructor(
    private val backendApi: BackendApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun send(event: EventCreateDto) {
        scope.launch { runCatching { backendApi.recordEvent(event) } }
    }

    fun playStart(track: TrackResultDto) = send(EventCreateDto(eventType = "play_start", track = track))

    fun playComplete(track: TrackResultDto, durationMs: Long) =
        send(EventCreateDto(eventType = "play_complete", track = track, durationMs = durationMs))

    fun skip(track: TrackResultDto) = send(EventCreateDto(eventType = "skip", track = track))

    fun search(query: String) = send(EventCreateDto(eventType = "search", query = query))

    fun feedImpression(track: TrackResultDto) = send(EventCreateDto(eventType = "feed_impression", track = track))

    fun feedClick(track: TrackResultDto) = send(EventCreateDto(eventType = "feed_click", track = track))
}
