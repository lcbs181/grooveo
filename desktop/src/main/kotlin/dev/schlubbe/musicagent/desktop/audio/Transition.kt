package dev.schlubbe.musicagent.desktop.audio

/**
 * A transition from the playing track to the preloaded next one, in engine terms.
 * The planner (playback/TransitionPlanner) builds it from track analyses and a style.
 *
 * Time runs in beats of the outgoing track from [outStartSec]. A plain crossfade
 * without a beat grid uses one "beat" per second ([outBeatSec] = 1).
 */
data class TransitionPlan(
    val nextId: String,
    /** Outgoing source position (s) where the transition starts, on a beat. */
    val outStartSec: Double,
    /** Outgoing beat length in source seconds (native tempo). */
    val outBeatSec: Double,
    /** Incoming source position (s) heard at the start, on a beat. */
    val inCueSec: Double,
    /** Incoming beat length in source seconds (native tempo). */
    val inBeatSec: Double,
    val lengthBeats: Double,
    val lanes: List<Lane>,
    /** Stretch the incoming track to the outgoing tempo and keep the beats locked. */
    val sync: Boolean,
    /** Afterwards, glide the incoming track back to its own tempo over this many beats; null keeps the mixed tempo. */
    val releaseBeats: Double? = 16.0,
    /** Transpose the incoming track (harmonic mixing); needs [sync]. */
    val semitones: Float = 0f,
    /** Echo delay in beats (0.75 = dotted eighth, 1 = quarter). */
    val echoBeats: Double = 0.75,
    val style: String = "custom",
) {
    /** Last beat any lane moves on, or the length. */
    val endBeat: Double get() = maxOf(lengthBeats, lanes.maxOfOrNull { l -> l.points.maxOfOrNull { it.beat } ?: 0.0 } ?: 0.0)
}

data class TransitionStatus(
    val phase: Phase = Phase.NONE,
    val plan: TransitionPlan? = null,
    /** Beats since the start (negative while armed). */
    val beat: Double = 0.0,
    /** Playback rate of the incoming (or, while releasing, current) deck. */
    val rate: Double = 1.0,
    /** Beat-sync error of the incoming deck in milliseconds. */
    val syncErrorMs: Double? = null,
    val message: String? = null,
) {
    enum class Phase { NONE, ARMED, RUNNING, RELEASING }
}
