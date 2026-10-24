package dev.schlubbe.musicagent.desktop.playback

import dev.schlubbe.musicagent.desktop.audio.MusicalKey
import dev.schlubbe.musicagent.desktop.data.TrackAnalysis
import kotlin.math.abs

/**
 * How well one track mixes into the next, 0..1, from tempo and key - the rules DJs use:
 * BPM within a few percent (half/double time counts), keys the same or next to each
 * other on the Camelot wheel. Tracks without an analysis count as neutral (0.5).
 */
object TransitionScore {
    fun tempo(a: Double, b: Double): Double {
        if (a <= 0 || b <= 0) return 0.5
        // 64 and 128 BPM mix fine: compare at the nearest half/double tempo
        val diff = listOf(b, b * 2, b / 2).minOf { abs(it - a) / a }
        return when {
            diff <= 0.03 -> 1.0
            diff <= 0.06 -> 0.5
            else -> 0.0
        }
    }

    fun key(a: String?, b: String?): Double {
        val d = if (a == null || b == null) null else MusicalKey.camelotDistance(a, b)
        return when (d) {
            null -> 0.5
            0, 1 -> 1.0
            2 -> 0.5
            else -> 0.0
        }
    }

    fun of(a: TrackAnalysis?, b: TrackAnalysis?): Double {
        val ga = a?.grid ?: return 0.5
        val gb = b?.grid ?: return 0.5
        return 0.6 * tempo(ga.bpm, gb.bpm) + 0.4 * key(a.key?.camelot, b.key?.camelot)
    }

    /**
     * [batch] reordered so each track mixes well into the next, starting after [from].
     * Greedy, with the recommender's order kept as a tie-breaker (earlier = more
     * relevant), so a batch nobody analysed stays as it was.
     */
    fun <T> order(from: TrackAnalysis?, batch: List<T>, analysis: (T) -> TrackAnalysis?): List<T> {
        val rest = batch.withIndex().toMutableList()
        val out = ArrayList<T>(batch.size)
        var prev = from
        while (rest.isNotEmpty()) {
            val best = rest.maxBy { (i, t) -> of(prev, analysis(t)) + RELEVANCE_WEIGHT * (1.0 - i.toDouble() / batch.size) }
            rest.remove(best)
            out += best.value
            prev = analysis(best.value)
        }
        return out
    }

    /** How much the recommender's order counts against a better transition. */
    private const val RELEVANCE_WEIGHT = 0.5
}
