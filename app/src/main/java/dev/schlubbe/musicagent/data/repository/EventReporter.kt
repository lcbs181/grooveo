package dev.schlubbe.musicagent.data.repository

import dev.schlubbe.musicagent.data.local.dao.PlayEventDao
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.PlayEventEntity
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.recommend.EndReason
import dev.schlubbe.musicagent.data.recommend.PlaybackSignals
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
 * a flaky connection never affects playback or navigation. Every play is also kept
 * locally ([PlayEventDao]) with how long it was heard, which the recommendations
 * learn from, and marks its track as recently played. */
@Singleton
class EventReporter @Inject constructor(
    private val backendApi: BackendApi,
    private val playEventDao: PlayEventDao,
    private val trackDao: TrackDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The play under way: its track and when it started. */
    @Volatile private var current: Pair<TrackResultDto, Long>? = null

    private fun send(event: EventCreateDto) {
        // the PC's live mix is no track of its own
        if (event.track?.source == "pc") return
        scope.launch { runCatching { backendApi.recordEvent(event) } }
    }

    fun playStart(track: TrackResultDto) {
        if (track.source == "pc") return
        val now = System.currentTimeMillis()
        current = track to now
        send(EventCreateDto(eventType = "play_start", track = track))
        scope.launch {
            runCatching {
                val id = "${track.source}:${track.sourceId}"
                val known = trackDao.getById(id)
                trackDao.upsert(
                    known?.copy(lastAccessedAt = now) ?: TrackEntity(
                        id = id, source = track.source, sourceId = track.sourceId, title = track.title,
                        artist = track.artist, album = track.album, durationSec = track.durationSec,
                        thumbnailUrl = track.thumbnailUrl, lastAccessedAt = now, webpageUrl = track.webpageUrl, genre = track.genre,
                    ),
                )
            }
        }
    }

    fun playComplete(track: TrackResultDto, durationMs: Long) {
        record(track, durationMs, natural = true)
        send(EventCreateDto(eventType = "play_complete", track = track, durationMs = durationMs))
    }

    /** The listener left [track] at [positionMs]: an early or late skip, or nearly heard out. */
    fun skip(track: TrackResultDto, positionMs: Long) {
        record(track, positionMs, natural = false)
        send(EventCreateDto(eventType = "skip", track = track))
    }

    private fun record(track: TrackResultDto, listenedMs: Long, natural: Boolean) {
        val (playing, startedAt) = current ?: return
        if (playing.source != track.source || playing.sourceId != track.sourceId) return
        current = null
        val durationMs = (track.durationSec ?: 0) * 1000L
        val reason: EndReason = PlaybackSignals.classifyEnd(listenedMs, durationMs, natural)
        scope.launch {
            runCatching {
                playEventDao.insert(
                    PlayEventEntity(
                        trackId = "${track.source}:${track.sourceId}", artist = track.artist, startedAt = startedAt,
                        listenedMs = listenedMs.coerceAtLeast(0), durationMs = durationMs, endReason = reason.name,
                    ),
                )
                if (startedAt % 50 == 0L) playEventDao.prune(System.currentTimeMillis() - PlaybackSignals.KEEP_MS)
            }
        }
    }

    fun search(query: String) = send(EventCreateDto(eventType = "search", query = query))

    fun feedImpression(track: TrackResultDto) = send(EventCreateDto(eventType = "feed_impression", track = track))

    fun feedClick(track: TrackResultDto) = send(EventCreateDto(eventType = "feed_click", track = track))
}
