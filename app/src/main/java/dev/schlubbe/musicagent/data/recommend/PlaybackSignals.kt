package dev.schlubbe.musicagent.data.recommend

import dev.schlubbe.musicagent.data.local.entity.PlayEventEntity
import kotlin.math.pow

/** Why a play ended, the strongest signal the recommendations get. */
enum class EndReason {
    /** Heard to the end, or nearly (at least [PlaybackSignals.COMPLETE_FRACTION]). */
    COMPLETE,
    /** Skipped within [PlaybackSignals.EARLY_SKIP_MS]: the listener did not want it. */
    SKIP_EARLY,
    /** Skipped later on: liked it for a while, a weak "no". */
    SKIP_LATE,
}

/**
 * What the listening history says, computed from [PlayEventEntity] rows: which artists
 * the listener keeps hearing to the end and which they skip, what played recently and
 * what is on repeat. Pure, so the phone and the PC share it.
 */
class PlaybackSignals(events: List<PlayEventEntity>, private val now: Long) {
    private val byArtist = events.filter { !it.artist.isNullOrBlank() }.groupBy { normalizeArtist(it.artist!!) }

    /** Tracks skipped early in the last [SKIP_MEMORY_MS] and not heard to the end since. */
    val skippedTracks: Set<String> = events
        .filter { now - it.startedAt < SKIP_MEMORY_MS }
        .groupBy { it.trackId }
        .filterValues { plays -> plays.maxBy { it.startedAt }.endReason == EndReason.SKIP_EARLY.name }
        .keys

    /** Decayed taste per artist: hearing a track out counts +1, an early skip -1, a late one -0.2. */
    val artistAffinity: Map<String, Double> = byArtist.mapValues { (_, plays) ->
        plays.sumOf { weight(it) * decay(it.startedAt, AFFINITY_HALF_LIFE_MS) }
    }

    /** Early skips over plays, with Laplace smoothing so a single skip does not ban an artist. */
    fun skipRatio(artist: String?): Double {
        val plays = artist?.let { byArtist[normalizeArtist(it)] } ?: return 0.0
        val skips = plays.count { it.endReason == EndReason.SKIP_EARLY.name }
        return skips / (plays.size + 2.0)
    }

    /** Track ids played within [withinMs]. */
    fun playedWithin(withinMs: Long): Set<String> = byTrack.filterValues { plays -> plays.any { now - it.startedAt < withinMs } }.keys

    /** Track ids heard to the end at least [REPEAT_MIN] times in the last [REPEAT_WINDOW_MS], most first. */
    val onRepeat: List<String> by lazy {
        byTrack.mapValues { (_, plays) -> plays.count { it.endReason == EndReason.COMPLETE.name && now - it.startedAt < REPEAT_WINDOW_MS } }
            .filterValues { it >= REPEAT_MIN }
            .entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Track ids once heard out often but not played for [REDISCOVER_AFTER_MS], best first. */
    val rediscover: List<String> by lazy {
        byTrack.filterValues { plays ->
            plays.count { it.endReason == EndReason.COMPLETE.name } >= 2 && plays.all { now - it.startedAt > REDISCOVER_AFTER_MS }
        }.entries.sortedByDescending { it.value.size }.map { it.key }
    }

    /** The newest event per track, newest first: who played, for a seed or a history. */
    val recentTracks: List<PlayEventEntity> by lazy { byTrack.values.map { p -> p.maxBy { it.startedAt } }.sortedByDescending { it.startedAt } }

    /** True with no real history yet, when exploring new artists should weigh more. */
    val isColdStart: Boolean = events.size < COLD_START_EVENTS

    private val byTrack: Map<String, List<PlayEventEntity>> by lazy { events.groupBy { it.trackId } }

    private fun weight(e: PlayEventEntity) = when (e.endReason) {
        EndReason.COMPLETE.name -> 1.0
        EndReason.SKIP_EARLY.name -> -1.0
        else -> -0.2
    }

    private fun decay(at: Long, halfLife: Long) = 0.5.pow((now - at).coerceAtLeast(0).toDouble() / halfLife)

    companion object {
        const val EARLY_SKIP_MS = 30_000L
        const val COMPLETE_FRACTION = 0.8
        const val SKIP_MEMORY_MS = 30L * 24 * 3600_000
        const val AFFINITY_HALF_LIFE_MS = 14L * 24 * 3600_000
        const val REPEAT_WINDOW_MS = 30L * 24 * 3600_000
        const val REPEAT_MIN = 3
        const val REDISCOVER_AFTER_MS = 60L * 24 * 3600_000
        const val COLD_START_EVENTS = 20
        /** How much history is kept and read. */
        const val KEEP_MS = 180L * 24 * 3600_000

        /** How a play ended. [natural] is an end the player reached by itself. Very short
         * tracks count as skipped early below a quarter of their length. */
        fun classifyEnd(listenedMs: Long, durationMs: Long, natural: Boolean): EndReason = when {
            natural -> EndReason.COMPLETE
            durationMs > 0 && listenedMs >= durationMs * COMPLETE_FRACTION -> EndReason.COMPLETE
            listenedMs < EARLY_SKIP_MS && (durationMs <= 0 || durationMs >= 120_000 || listenedMs < durationMs / 4) -> EndReason.SKIP_EARLY
            else -> EndReason.SKIP_LATE
        }

        /** Lower-case, without "- Topic" and "VEVO", so one artist's uploads meet under one key. */
        fun normalizeArtist(artist: String): String =
            artist.lowercase().replace(Regex("""\s*-\s*topic$|vevo$"""), "").trim()
    }
}
