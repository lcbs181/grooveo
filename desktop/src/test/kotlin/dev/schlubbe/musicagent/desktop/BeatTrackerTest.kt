package dev.schlubbe.musicagent.desktop

import dev.schlubbe.musicagent.desktop.audio.BeatGrid
import dev.schlubbe.musicagent.desktop.audio.BeatTracker
import dev.schlubbe.musicagent.desktop.audio.TrackAnalyzer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeatTrackerTest {
    /** Distance of [ms] to the nearest beat of [grid], in ms. */
    private fun gridError(grid: BeatGrid, ms: Double): Double {
        val r = ((ms - grid.firstBeatMs) % grid.beatMs + grid.beatMs) % grid.beatMs
        return minOf(r, grid.beatMs - r)
    }

    @Test fun fitGridRecoversTempoPhaseAndDownbeatFromNoisyBeats() {
        val rnd = Random(7)
        val period = 60_000.0 / 124
        val first = 310.0
        // jittered beats with a few dropouts and one spurious detection; from beat 120
        // on the tracker sits on the offbeat, as aubio does on hi-hat heavy tracks
        val beats = (0 until 300).filter { it % 37 != 5 }
            .map { first + it * period + (if (it >= 120) period / 2 else 0.0) + rnd.nextDouble(-8.0, 8.0) }.toMutableList()
        beats.add(150, beats[149] + period / 3)
        val downbeatIndex = 2 // bars start on beat 2, 6, 10, ...
        val frameMs = 5.0
        // kick onsets on every beat, stronger on "one", some hats on the offbeats
        val onset = FloatArray(((first + 301 * period) / frameMs).toInt())
        for (n in 0 until 300) {
            val k = ((first + n * period) / frameMs + 0.5).roundToInt()
            onset[k] += if (n % 4 == downbeatIndex) 1.0f else 0.6f
            onset[((first + (n + 0.5) * period) / frameMs + 0.5).roundToInt()] += 0.2f
        }
        val grid = BeatTracker.fitGrid(beats, 0.8f, onset, frameMs)
        assertNotNull(grid)
        assertEquals(124.0, grid.bpm, 0.05)
        assertTrue(gridError(grid, first) < 4, "phase off by ${gridError(grid, first)} ms")
        val firstDownbeat = grid.firstBeatMs + grid.downbeatOffset * grid.beatMs
        assertEquals(first + downbeatIndex * period, firstDownbeat, 4.0)
        assertTrue(grid.stability > 0.95)
        assertNull(grid.beatsMs, "steady track keeps no raw beats")
    }

    @Test fun fitGridKeepsTrackerPhaseWithoutClearKick() {
        val period = 500.0
        val beats = (0 until 100).map { 200 + it * period }
        val grid = assertNotNull(BeatTracker.fitGrid(beats, 0.5f, FloatArray(10_000) { 0.1f }, 5.0))
        assertEquals(200.0, grid.firstBeatMs, 1.0)
    }

    @Test fun fitGridGivesUpOnTooFewBeats() {
        assertNull(BeatTracker.fitGrid(listOf(0.0, 500.0, 1000.0), 0.5f, FloatArray(0), 5.0))
    }

    @Test fun aubioFindsTempoBeatsAndDownbeatOfSyntheticTrack() {
        assertTrue(BeatTracker.available, "libgrooveo_aubio must be built and loadable (grooveo.native.dir)")
        val rate = TrackAnalyzer.RATE
        val bpm = 128.0
        val period = 60.0 / bpm
        val start = 0.45 // first kick, a downbeat
        val seconds = 60
        val pcm = FloatArray(rate * seconds)
        val rnd = Random(3)
        var beat = 0
        while (start + beat * period < seconds) {
            val t0 = start + beat * period
            val down = beat % 4 == 0
            val i0 = (t0 * rate).toInt()
            // kick: 120 -> 45 Hz sweep, louder on the downbeat, plus a bass note on "one"
            for (k in 0 until (0.25 * rate).toInt()) {
                val i = i0 + k
                if (i >= pcm.size) break
                val t = k.toDouble() / rate
                val f = 45 + 75 * exp(-t * 30)
                pcm[i] += ((if (down) 0.9 else 0.55) * exp(-t * 14) * sin(2 * PI * f * t)).toFloat()
                if (down) pcm[i] += (0.35 * exp(-t * 4) * sin(2 * PI * 55 * t)).toFloat()
            }
            // rolling offbeat bass, as in techno: louder than the kick in the bass band
            val b0 = ((t0 + period / 2) * rate).toInt()
            for (k in 0 until (period / 2 * 0.9 * rate).toInt()) {
                val i = b0 + k
                if (i >= pcm.size) break
                val t = k.toDouble() / rate
                pcm[i] += (0.5 * minOf(1.0, t / 0.01) * exp(-t * 3) * sin(2 * PI * 55 * t)).toFloat()
            }
            // offbeat hi-hat
            val h0 = ((t0 + period / 2) * rate).toInt()
            for (k in 0 until (0.04 * rate).toInt()) {
                val i = h0 + k
                if (i < pcm.size) pcm[i] += (0.15 * exp(-k.toDouble() / rate * 120) * rnd.nextDouble(-1.0, 1.0)).toFloat()
            }
            beat++
        }
        val result = TrackAnalyzer.analyzeSamples { sink ->
            var pos = 0
            while (pos < pcm.size) {
                val n = minOf(4096, pcm.size - pos)
                sink(pcm.copyOfRange(pos, pos + n), n)
                pos += n
            }
        }
        val grid = assertNotNull(result.grid)
        assertEquals(bpm, grid.bpm, 0.5)
        val kickMs = start * 1000
        assertTrue(gridError(grid, kickMs) < 25, "beats off the kicks by ${gridError(grid, kickMs)} ms")
        val downbeatMs = grid.firstBeatMs + grid.downbeatOffset * grid.beatMs
        val barMs = 4 * grid.beatMs
        val barError = ((downbeatMs - kickMs) % barMs + barMs) % barMs
        val barOff = minOf(barError, barMs - barError)
        assertTrue(barOff < 25, "downbeat off by $barOff ms")
        assertTrue(grid.stability > 0.9, "stability ${grid.stability}")
        assertNotNull(result.mix)
    }
}
