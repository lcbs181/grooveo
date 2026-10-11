package dev.schlubbe.musicagent.desktop.audio

import kotlin.math.abs
import kotlin.math.floor

/** A part of a track: bars are 1-based and counted from the first downbeat. */
data class Section(val startBar: Int, val bars: Int, val startMs: Long, val label: String, val energy: Double)

/** A named position on a downbeat, e.g. "drop" at bar 33. */
data class Cue(val name: String, val ms: Long, val bar: Int)

data class TrackStructure(val sections: List<Section>, val cues: List<Cue>, val bars: Int) {
    fun cue(name: String) = cues.firstOrNull { it.name == name }
}

/**
 * Finds a track's sections (intro, build, drop, breakdown, groove, main, outro) and
 * DJ cue points from its beat grid and loudness envelopes.
 *
 * Every bar gets four features, each scaled to the track's own 90th percentile:
 * loudness, bass level, high-frequency level (hi-hats, cymbals) and kick strength
 * (bass onsets on the beats). Section boundaries are placed on 4-bar steps where the
 * features of the four bars before and after differ most (8-bar steps preferred, as
 * dance music is built in 8/16/32-bar phrases), at least 8 bars apart. Labels follow
 * from whether kick and bass are present and how loud a section is relative to the
 * loudest one.
 */
object StructureAnalyzer {
    const val MIN_SECTION_BARS = 8
    private const val BOUNDARY_SCORE = 0.32

    class Envelopes(
        /** RMS per [envMs] frame, full band. */
        val loud: FloatArray,
        /** RMS per [envMs] frame above ~3 kHz. */
        val high: FloatArray,
        val envMs: Double,
        /** RMS per [bassMs] frame below ~120 Hz, and its rise per frame (onsets). */
        val bass: FloatArray,
        val bassOnset: FloatArray,
        val bassMs: Double,
    )

    fun analyze(grid: BeatGrid, durationMs: Double, e: Envelopes): TrackStructure? {
        val barMs = 4 * grid.beatMs
        val d0 = grid.firstBeatMs + grid.downbeatOffset * grid.beatMs
        val first = d0 - floor(d0 / barMs) * barMs // earliest downbeat
        val nBars = ((durationMs - first) / barMs).toInt()
        if (nBars < 2 * MIN_SECTION_BARS) return null
        // bar 1 is the first downbeat the beat grid found; earlier bars get numbers <= 0
        val barOffset = Math.round((d0 - first) / barMs).toInt()
        fun barStart(i: Int) = first + i * barMs
        fun barNumber(i: Int) = i - barOffset + 1

        val raw = Array(nBars) { i ->
            val s = barStart(i)
            doubleArrayOf(
                mean(e.loud, s, s + barMs, e.envMs),
                mean(e.bass, s, s + barMs, e.bassMs),
                mean(e.high, s, s + barMs, e.envMs),
                (0 until 4).sumOf { b -> peak(e.bassOnset, s + b * grid.beatMs - 10, s + b * grid.beatMs + 15, e.bassMs) } / 4,
            )
        }
        val f = normalize(raw)
        val energy = DoubleArray(nBars) { 0.35 * f[it][0] + 0.35 * f[it][1] + 0.15 * f[it][2] + 0.15 * f[it][3] }

        // boundaries on 4-bar steps from the first downbeat
        val anchor = Math.floorMod(barOffset, 4)
        val candidates = (anchor + 4 until nBars - 3 step 4).map { i ->
            val before = avg(f, i - 4, i)
            val after = avg(f, i, minOf(nBars, i + 4))
            var score = 0.0
            for (k in 0..3) score += abs(before[k] - after[k]) * WEIGHTS[k]
            if ((i - barOffset) % 8 == 0) score *= 1.25
            i to score
        }.filter { it.second > BOUNDARY_SCORE }.sortedByDescending { it.second }
        val bounds = sortedSetOf(0, nBars)
        for ((i, _) in candidates) if (bounds.all { abs(it - i) >= MIN_SECTION_BARS }) bounds += i
        val edges = bounds.toList()

        val maxE = edges.zipWithNext { a, b -> energy.slice(a until b).average() }.maxOrNull() ?: return null
        val sections = ArrayList<Section>()
        for ((idx, pair) in edges.zipWithNext().withIndex()) {
            val (a, b) = pair
            val m = avg(f, a, b)
            val en = energy.slice(a until b).average()
            val last = idx == edges.size - 2
            val full = m[1] >= 0.5 && m[3] >= 0.4
            val prev = sections.lastOrNull()?.label
            val label = when {
                !full && idx == 0 -> "intro"
                !full && last -> "outro"
                !full && m[1] < 0.35 -> "breakdown"
                !full -> "build"
                en >= 0.85 * maxE -> if (prev in setOf("breakdown", "build", "intro")) "drop" else "main"
                idx == 0 -> "intro"
                last -> "outro"
                else -> "groove"
            }
            sections += Section(barNumber(a), b - a, barStart(a).toLong(), label, Math.round(en / maxE * 100) / 100.0)
        }

        val cues = ArrayList<Cue>()
        fun cueAt(name: String, i: Int) { cues += Cue(name, barStart(i).toLong(), barNumber(i)) }
        cues += Cue("first_beat", grid.firstBeatMs.toLong(), barNumber(((grid.firstBeatMs - first) / barMs).toInt()))
        // the last bar that still has a beat (kick) or real loudness
        val lastBar = (nBars - 1 downTo 0).firstOrNull { f[it][3] >= 0.25 || energy[it] >= 0.3 * maxE } ?: (nBars - 1)
        // mix in where the kick starts (usually bar 1), on a 4-bar step
        val kickFrom = (0 until nBars).firstOrNull { f[it][3] >= 0.3 } ?: 0
        val mixIn = kickFrom - Math.floorMod(kickFrom - anchor, 4)
        cueAt("mix_in", mixIn.coerceAtLeast(0))
        for ((i, s) in sections.withIndex()) {
            val bar = edges[i]
            when (s.label) {
                "drop" -> cueAt("drop", bar)
                "breakdown" -> cueAt("breakdown", bar)
                "build" -> cueAt("build", bar)
            }
        }
        val outro = sections.lastOrNull()?.takeIf { it.label == "outro" }
        val outroIdx = edges[edges.size - 2]
        if (outro != null) cueAt("outro_start", outroIdx)
        // mix out at the outro if it leaves at least 8 bars, else 16 bars before the end of the beat
        val mixOut = if (outro != null && lastBar + 1 - outroIdx >= 8) outroIdx
        else (lastBar + 1 - 16).let { it - Math.floorMod(it - anchor, 4) }.coerceAtLeast(mixIn + 4)
        cueAt("mix_out", mixOut.coerceAtMost(nBars - 1))
        cues += Cue("last_beat", (barStart(lastBar) + barMs).toLong().coerceAtMost(durationMs.toLong()), barNumber(lastBar + 1))
        return TrackStructure(sections, cues.sortedBy { it.ms }, nBars)
    }

    private val WEIGHTS = doubleArrayOf(0.8, 1.2, 0.6, 1.0)

    private fun mean(a: FloatArray, fromMs: Double, toMs: Double, frameMs: Double): Double {
        val i0 = (fromMs / frameMs).toInt().coerceIn(0, a.size)
        val i1 = (toMs / frameMs).toInt().coerceIn(i0, a.size)
        if (i1 == i0) return 0.0
        var s = 0.0
        for (i in i0 until i1) s += a[i]
        return s / (i1 - i0)
    }

    private fun peak(a: FloatArray, fromMs: Double, toMs: Double, frameMs: Double): Double {
        val i0 = (fromMs / frameMs).toInt().coerceIn(0, a.size)
        val i1 = (toMs / frameMs).toInt().coerceIn(i0, a.size)
        var m = 0.0
        for (i in i0 until i1) if (a[i] > m) m = a[i].toDouble()
        return m
    }

    private fun normalize(raw: Array<DoubleArray>): Array<DoubleArray> {
        val ref = DoubleArray(4) { k -> raw.map { it[k] }.sorted().let { it[(it.size * 0.9).toInt().coerceAtMost(it.size - 1)] }.takeIf { it > 0 } ?: 1.0 }
        return Array(raw.size) { i -> DoubleArray(4) { k -> (raw[i][k] / ref[k]).coerceIn(0.0, 1.5) } }
    }

    private fun avg(f: Array<DoubleArray>, from: Int, to: Int): DoubleArray {
        val r = DoubleArray(4)
        val n = (to - from).coerceAtLeast(1)
        for (i in from until to) for (k in 0..3) r[k] += f[i][k]
        for (k in 0..3) r[k] /= n
        return r
    }
}
