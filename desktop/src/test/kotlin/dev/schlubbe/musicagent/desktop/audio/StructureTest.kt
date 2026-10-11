package dev.schlubbe.musicagent.desktop.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StructureTest {
    private val rate = TrackAnalyzer.RATE

    private fun midiHz(m: Int) = 440.0 * 2.0.pow((m - 69) / 12.0)

    /**
     * A dance track: 124 BPM, sections of [parts] (name to bars) built from kick, bass,
     * hats and chord pads; chords cycle through [chords] (MIDI notes) one per bar.
     */
    private fun track(parts: List<Pair<String, Int>>, chords: List<List<Int>>, bpm: Double = 124.0): FloatArray {
        val beat = 60.0 / bpm
        val start = 0.2
        val bars = parts.sumOf { it.second }
        val pcm = FloatArray(((start + bars * 4 * beat + 1) * rate).toInt())
        val rnd = Random(5)
        var bar = 0
        for ((name, n) in parts) repeat(n) {
            val t0 = start + bar * 4 * beat
            val kick = name in setOf("intro", "drop", "outro")
            val bassOn = name == "drop"
            val hats = name != "breakdown"
            val pads = name in setOf("drop", "breakdown")
            val chord = chords[bar % chords.size]
            for (b in 0 until 4) {
                val tb = t0 + b * beat
                if (kick) for (k in 0 until (0.2 * rate).toInt()) {
                    val i = (tb * rate).toInt() + k; if (i >= pcm.size) break
                    val t = k.toDouble() / rate
                    pcm[i] += (0.7 * exp(-t * 14) * sin(2 * PI * (45 + 75 * exp(-t * 30)) * t)).toFloat()
                }
                if (hats) { val h = ((tb + beat / 2) * rate).toInt(); for (k in 0 until 700) if (h + k < pcm.size) pcm[h + k] += (0.12 * exp(-k / 120.0) * rnd.nextDouble(-1.0, 1.0)).toFloat() }
                if (bassOn) { val h = ((tb + beat / 2) * rate).toInt(); val f = midiHz(chord[0] - 12); for (k in 0 until (beat / 2 * rate).toInt()) if (h + k < pcm.size) pcm[h + k] += (0.35 * sin(2 * PI * f * k / rate)).toFloat() }
            }
            if (pads) for (k in 0 until (4 * beat * rate).toInt()) {
                val i = (t0 * rate).toInt() + k; if (i >= pcm.size) break
                val t = k.toDouble() / rate
                var v = 0.0
                for (m in chord) { val f = midiHz(m); v += sin(2 * PI * f * t) + 0.3 * sin(4 * PI * f * t) }
                pcm[i] += (0.05 * v).toFloat()
            }
            bar++
        }
        return pcm
    }

    private fun analyze(pcm: FloatArray) = TrackAnalyzer.analyzeSamples { sink ->
        var pos = 0
        while (pos < pcm.size) { val n = minOf(4096, pcm.size - pos); sink(pcm.copyOfRange(pos, pos + n), n); pos += n }
    }

    // A minor: Am, Dm, Am, E (the G# of E major marks the minor key)
    private val aMinor = listOf(listOf(57, 60, 64), listOf(62, 65, 69), listOf(57, 60, 64), listOf(64, 68, 71))

    @Test fun `sections, cues and key of a dance track`() {
        requireNative() // bars come from the aubio beat grid
        val parts = listOf("intro" to 16, "drop" to 32, "breakdown" to 16, "drop" to 32, "outro" to 16)
        val r = analyze(track(parts, aMinor))
        val s = assertNotNull(r.structure, "no structure")
        assertEquals(listOf("intro" to 1, "drop" to 17, "breakdown" to 49, "drop" to 65, "outro" to 97), s.sections.map { it.label to it.startBar }, s.sections.toString())
        assertEquals(listOf(17, 65), s.cues.filter { it.name == "drop" }.map { it.bar })
        assertEquals(97, s.cue("outro_start")?.bar)
        assertEquals(97, s.cue("mix_out")?.bar)
        assertEquals(1, s.cue("mix_in")?.bar)
        assertEquals(113, s.cue("last_beat")?.bar)
        assertEquals("8A", r.key?.camelot, r.key.toString())
    }

    @Test fun `key of a major-key progression`() {
        // G, C, D, G
        val g = listOf(listOf(55, 59, 62), listOf(60, 64, 67), listOf(62, 66, 69), listOf(55, 59, 62))
        val r = analyze(track(listOf("drop" to 24), g))
        assertEquals("G major", r.key?.name, r.key.toString())
        assertEquals("9B", r.key?.camelot)
    }

    @Test fun `camelot distance and harmonic shift`() {
        assertEquals(0, MusicalKey.camelotDistance("8A", "8A"))
        assertEquals(1, MusicalKey.camelotDistance("8A", "9A"))
        assertEquals(1, MusicalKey.camelotDistance("8A", "8B"))
        assertEquals(1, MusicalKey.camelotDistance("12A", "1A"))
        assertEquals(5, MusicalKey.camelotDistance("8A", "1A"))
        // A minor (8A) up one semitone is Bb minor (3A)
        assertEquals(0, MusicalKey.shiftToMatch("8A", "9A"))
        assertEquals(1, MusicalKey.shiftToMatch("8A", "3A"))
    }
}
