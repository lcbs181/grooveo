package dev.schlubbe.musicagent.desktop.playback

import dev.schlubbe.musicagent.desktop.audio.Curve
import dev.schlubbe.musicagent.desktop.audio.DeckRole
import dev.schlubbe.musicagent.desktop.audio.EQ_KILL_DB
import dev.schlubbe.musicagent.desktop.audio.FxParam
import dev.schlubbe.musicagent.desktop.audio.Lane
import dev.schlubbe.musicagent.desktop.audio.LanePoint
import dev.schlubbe.musicagent.desktop.audio.MusicalKey
import dev.schlubbe.musicagent.desktop.audio.TransitionPlan
import dev.schlubbe.musicagent.desktop.data.TrackAnalysis
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * What a DJ (the user's setting or an AI over MCP) wants for one transition. Every
 * field is optional; [TransitionPlanner] fills the rest from the two tracks' analyses.
 */
data class TransitionRecipe(
    /** auto | blend | bass_swap | filter | echo_out | cut | fade */
    val style: String = "auto",
    /** Length in bars (4 beats); null = fitted to the outro and intro. */
    val bars: Int? = null,
    /**
     * Where the outgoing track starts the transition: mix_out (default: the outro, or
     * early enough to fit), outro_start, breakdown, drop, build, bar:N, next_bar,
     * next_phrase (next 8-bar phrase).
     */
    val outCue: String? = null,
    /** Where the incoming track is heard from: mix_in (default), first_beat, drop, breakdown, build, bar:N. */
    val inCue: String? = null,
    /** sync (stretch the incoming track to the outgoing tempo) or none. */
    val tempo: String = "sync",
    /** Afterwards keep the mixed tempo instead of gliding back to the track's own. */
    val keepTempo: Boolean = false,
    /** Bars over which the incoming track glides back to its own tempo. */
    val releaseBars: Int = 8,
    /** Transpose the incoming track to a key that fits the outgoing one (Camelot). */
    val harmonic: Boolean = false,
    /** Explicit transposition in semitones (overrides [harmonic]). */
    val semitones: Int? = null,
    /** Echo delay in beats. */
    val echoBeats: Double = 0.75,
    /** Extra or replacing automation; a lane replaces the style's lane for the same deck and parameter. */
    val lanes: List<Lane> = emptyList(),
)

/** The plan plus what it decided, for the UI and the AI. */
data class PlannedTransition(val plan: TransitionPlan, val summary: Map<String, Any?>)

object TransitionPlanner {
    val STYLES = linkedMapOf(
        "auto" to "Picks a style from tempo, key and the tracks' intros and outros",
        "blend" to "Long EQ blend: the incoming track fades in without bass, the basses swap on a bar halfway, the outgoing track fades out",
        "bass_swap" to "Incoming track up quickly with its bass cut, hard bass swap halfway, outgoing fader down at the end",
        "filter" to "Outgoing track disappears behind a rising high-pass, incoming track opens up through a low-pass",
        "echo_out" to "Outgoing track is thrown into a beat-synced echo and cut; the incoming track drops in on the next bar",
        "cut" to "Hard cut on a downbeat",
        "fade" to "Equal-power crossfade",
    )

    /** Largest tempo change (as a rate) a synced transition accepts; beyond it the style falls back. */
    const val MAX_STRETCH = 0.08

    /**
     * Plans the transition from [out] (playing, at [positionSec] seconds) to [inn] with
     * id [nextId]. Null when either track has no beat grid, so the caller falls back to
     * a plain crossfade.
     */
    fun plan(
        out: TrackAnalysis, inn: TrackAnalysis, nextId: String, recipe: TransitionRecipe,
        positionSec: Double, outDurationSec: Double?, canSync: Boolean,
    ): PlannedTransition? {
        val og = out.grid ?: return null
        val ig = inn.grid ?: return null
        val outBeat = og.beatMs / 1000
        // half/double tempo counts as a match: 70 BPM over 140 runs on every other beat
        val inBeatNative = ig.beatMs / 1000
        val inBeat = listOf(inBeatNative, inBeatNative / 2, inBeatNative * 2).minBy { abs(it / outBeat - 1) }
        val stretch = abs(inBeat / outBeat - 1)
        val syncable = canSync && stretch <= MAX_STRETCH
        val wantSync = recipe.tempo != "none" && syncable

        var style = recipe.style.lowercase()
        if (style !in STYLES) style = "auto"
        if (style == "auto") style = autoStyle(out, inn, syncable)
        // two beats running on top of each other only work in sync
        if (!wantSync && style in setOf("blend", "bass_swap", "filter")) style = if (stretch > MAX_STRETCH) "echo_out" else "fade"

        val outBar = 4 * outBeat
        val outD0 = (og.firstBeatMs + og.downbeatOffset * og.beatMs) / 1000
        val outLast = out.structure?.cue("last_beat")?.ms?.div(1000.0) ?: (outDurationSec ?: Double.MAX_VALUE)
        val inD0 = (ig.firstBeatMs + ig.downbeatOffset * ig.beatMs) / 1000

        // length: what fits into the outgoing outro and the incoming intro
        val bars = (recipe.bars ?: defaultBars(style, out, inn, outLast, outBar)).coerceIn(1, 64)
        val lengthBeats = bars * 4.0

        // where the outgoing track starts the transition (always on a downbeat)
        fun barAt(sec: Double, roundUp: Boolean) = (if (roundUp) ceil((sec - outD0) / outBar - 1e-6) else floor((sec - outD0) / outBar + 1e-6)).toInt()
        val lead = positionSec + 2.0 // time to prime the incoming deck
        val outCue = recipe.outCue ?: "mix_out"
        var startBar: Int = when {
            outCue == "next_bar" -> barAt(lead, true)
            outCue == "next_phrase" -> barAt(lead, true).let { b -> b + Math.floorMod(-b, 8) }
            outCue.startsWith("bar:") -> (outCue.removePrefix("bar:").toIntOrNull() ?: 1) - 1
            outCue == "mix_out" -> {
                val mixOut = out.structure?.cue("mix_out")?.ms?.div(1000.0)
                // early enough that the transition ends before the beat does
                val latest = barAt(outLast - bars * outBar, false)
                minOf(mixOut?.let { barAt(it, false) } ?: latest, latest)
            }
            else -> out.structure?.cues?.lastOrNull { it.name == outCue && it.ms / 1000.0 > lead }?.ms?.let { barAt(it / 1000.0, false) }
                ?: error("The playing track has no cue '$outCue'")
        }
        // never in the past
        if (outD0 + startBar * outBar < lead) startBar = barAt(lead, true)
        val outStart = outD0 + startBar * outBar

        // incoming cue, heard at beat [inAt] of the transition
        val inAt = if (style == "echo_out") 4.0 else 0.0
        val inCueName = recipe.inCue ?: "mix_in"
        val inCue = when {
            inCueName.startsWith("bar:") -> inD0 + ((inCueName.removePrefix("bar:").toIntOrNull() ?: 1) - 1) * 4 * inBeatNative
            inCueName == "first_beat" -> ig.firstBeatMs / 1000
            else -> inn.structure?.cue(inCueName)?.ms?.div(1000.0)
                ?: if (inCueName == "mix_in") inD0 - floor(inD0 / (4 * inBeatNative)) * 4 * inBeatNative else error("The next track has no cue '$inCueName'")
        }
        val inStart = (inCue - inAt * inBeat).let { if (it < 0) it + ceil(-it / (4 * inBeat)) * 4 * inBeat else it }

        val semitones = when {
            !wantSync -> 0
            recipe.semitones != null -> recipe.semitones.coerceIn(-6, 6)
            recipe.harmonic -> { val a = inn.key?.camelot; val b = out.key?.camelot; if (a != null && b != null) MusicalKey.shiftToMatch(a, b) else 0 }
            else -> 0
        }

        val lanes = mergeLanes(styleLanes(style, lengthBeats), recipe.lanes).let(::neutralEnd)
        val plan = TransitionPlan(
            nextId = nextId,
            outStartSec = outStart,
            outBeatSec = outBeat,
            inCueSec = inStart,
            inBeatSec = inBeat,
            lengthBeats = lengthBeats,
            lanes = lanes,
            sync = wantSync,
            releaseBeats = if (recipe.keepTempo) null else recipe.releaseBars.coerceAtLeast(1) * 4.0,
            semitones = semitones.toFloat(),
            echoBeats = recipe.echoBeats.coerceIn(0.125, 4.0),
            style = style,
        )
        val keyDist = out.key?.camelot?.let { a -> inn.key?.camelot?.let { b -> MusicalKey.camelotDistance(a, b) } }
        return PlannedTransition(
            plan,
            linkedMapOf(
                "style" to style,
                "bars" to bars,
                "starts_at_sec" to round2(outStart),
                "starts_in_sec" to round2(outStart - positionSec),
                "outgoing_bar" to startBar + 1,
                "incoming_from_sec" to round2(inStart + inAt * inBeat),
                "tempo_sync" to wantSync,
                "outgoing_bpm" to round2(60 / outBeat),
                "incoming_bpm" to round2(60 / inBeatNative),
                "incoming_rate" to if (wantSync) round2(inBeat / outBeat * 1000) / 1000 else 1.0,
                "after" to if (!wantSync) "own tempo" else if (recipe.keepTempo) "keeps the mixed tempo" else "glides back to its own tempo over ${recipe.releaseBars} bars",
                "semitones" to semitones,
                "key_distance" to keyDist,
                "note" to when {
                    !canSync && recipe.tempo != "none" -> "Tempo sync is not available (native library missing)"
                    recipe.tempo != "none" && !syncable -> "Tempos differ by ${Math.round(stretch * 100)} %, more than ${Math.round(MAX_STRETCH * 100)} %: no sync"
                    keyDist != null && keyDist > 1 && semitones == 0 -> "Keys clash (Camelot distance $keyDist); harmonic=true transposes the incoming track"
                    else -> null
                },
            ),
        )
    }

    private fun autoStyle(out: TrackAnalysis, inn: TrackAnalysis, syncable: Boolean): String {
        if (!syncable) return "echo_out"
        val outro = out.structure?.sections?.lastOrNull()
        val intro = inn.structure?.sections?.firstOrNull()
        // long DJ-friendly outro and intro: a long blend; else the quicker bass swap
        return if ((outro?.label == "outro" && outro.bars >= 16) && (intro?.label == "intro" && intro.bars >= 16)) "blend" else "bass_swap"
    }

    private fun defaultBars(style: String, out: TrackAnalysis, inn: TrackAnalysis, outLastSec: Double, outBar: Double): Int = when (style) {
        "cut" -> 1
        "echo_out" -> 2
        else -> {
            val outro = out.structure?.sections?.lastOrNull()?.takeIf { it.label == "outro" }?.bars ?: 16
            val intro = inn.structure?.sections?.firstOrNull()?.takeIf { it.label == "intro" }?.bars ?: 16
            val base = if (style == "blend") 32 else 16
            (minOf(base, outro, intro) / 4 * 4).coerceAtLeast(8)
        }
    }

    fun styleLanes(style: String, len: Double): List<Lane> {
        fun lane(d: DeckRole, p: FxParam, vararg pts: LanePoint) = Lane(d, p, pts.toList())
        fun pt(b: Double, v: Double, c: Curve = Curve.LINEAR) = LanePoint(b, v, c)
        val half = (floor(len / 2 / 4) * 4).coerceAtLeast(4.0).coerceAtMost(len) // on a bar
        return when (style) {
            "blend" -> listOf(
                lane(DeckRole.IN, FxParam.VOLUME, pt(0.0, 0.0), pt(half, 1.0, Curve.EASE_OUT)),
                lane(DeckRole.IN, FxParam.EQ_LOW, pt(0.0, EQ_KILL_DB), pt(half, 0.0, Curve.STEP)),
                lane(DeckRole.IN, FxParam.EQ_HIGH, pt(0.0, -6.0), pt(half, 0.0, Curve.SMOOTH)),
                lane(DeckRole.OUT, FxParam.EQ_LOW, pt(0.0, 0.0), pt(half, EQ_KILL_DB, Curve.STEP)),
                lane(DeckRole.OUT, FxParam.EQ_HIGH, pt(half, 0.0), pt(len, -12.0, Curve.SMOOTH)),
                lane(DeckRole.OUT, FxParam.VOLUME, pt(half, 1.0), pt(len, 0.0, Curve.EASE_IN)),
            )
            "bass_swap" -> listOf(
                lane(DeckRole.IN, FxParam.VOLUME, pt(0.0, 0.0), pt(4.0, 1.0, Curve.EASE_OUT)),
                lane(DeckRole.IN, FxParam.EQ_LOW, pt(0.0, EQ_KILL_DB), pt(half, 0.0, Curve.STEP)),
                lane(DeckRole.OUT, FxParam.EQ_LOW, pt(0.0, 0.0), pt(half, EQ_KILL_DB, Curve.STEP)),
                lane(DeckRole.OUT, FxParam.EQ_MID, pt(half, 0.0), pt(len, -6.0)),
                lane(DeckRole.OUT, FxParam.VOLUME, pt((len - 8).coerceAtLeast(half), 1.0), pt(len, 0.0, Curve.EASE_IN)),
            )
            "filter" -> listOf(
                lane(DeckRole.OUT, FxParam.HIGHPASS, pt(0.0, 20.0), pt(len, 1500.0, Curve.EASE_IN)),
                lane(DeckRole.OUT, FxParam.VOLUME, pt(len * 0.75, 1.0), pt(len, 0.0, Curve.EASE_IN)),
                lane(DeckRole.IN, FxParam.LOWPASS, pt(0.0, 250.0), pt(len, 20_000.0, Curve.EASE_IN)),
                lane(DeckRole.IN, FxParam.VOLUME, pt(0.0, 0.0), pt(len * 0.25, 1.0, Curve.EASE_OUT)),
            )
            "echo_out" -> listOf(
                // feed the echo during the last beat, then cut: the repeats carry over
                lane(DeckRole.OUT, FxParam.ECHO, pt(0.0, 0.0), pt(3.0, 0.0), pt(4.0, 0.9), pt(4.0, 0.0, Curve.STEP)),
                lane(DeckRole.OUT, FxParam.ECHO_FEEDBACK, pt(0.0, 0.6)),
                lane(DeckRole.OUT, FxParam.VOLUME, pt(0.0, 1.0), pt(4.0, 0.0, Curve.STEP)),
                lane(DeckRole.IN, FxParam.VOLUME, pt(0.0, 0.0), pt(4.0, 1.0, Curve.STEP)),
            )
            "cut" -> listOf(
                lane(DeckRole.OUT, FxParam.VOLUME, pt(0.0, 0.0)),
                lane(DeckRole.IN, FxParam.VOLUME, pt(0.0, 1.0)),
            )
            else -> listOf( // fade
                lane(DeckRole.OUT, FxParam.VOLUME, pt(0.0, 1.0), pt(len, 0.0, Curve.EASE_IN)),
                lane(DeckRole.IN, FxParam.VOLUME, pt(0.0, 0.0), pt(len, 1.0, Curve.EASE_OUT)),
            )
        }
    }

    private fun mergeLanes(style: List<Lane>, custom: List<Lane>): List<Lane> =
        style.filter { s -> custom.none { it.deck == s.deck && it.param == s.param } } + custom.map { l -> l.copy(points = l.points.sortedBy { it.beat }) }

    /** The incoming track keeps playing afterwards: lanes that end off neutral return to it over one bar. */
    private fun neutralEnd(lanes: List<Lane>): List<Lane> = lanes.map { l ->
        val last = l.points.lastOrNull() ?: return@map l
        if (l.deck != DeckRole.IN || last.value == l.param.neutral) l
        else l.copy(points = l.points + LanePoint(last.beat + 4, l.param.neutral, Curve.SMOOTH))
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0
}
