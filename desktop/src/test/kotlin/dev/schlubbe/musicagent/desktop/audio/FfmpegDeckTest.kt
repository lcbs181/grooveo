package dev.schlubbe.musicagent.desktop.audio

import java.io.File
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FfmpegDeckTest {
    private val deck = FfmpegDeck("test")
    @AfterTest fun tearDown() = deck.close()

    private fun sineFile(freq: Int, seconds: Int, ext: String): File {
        val f = File.createTempFile("sine$freq", ".$ext").apply { delete(); deleteOnExit() }
        ProcessBuilder("ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "sine=f=$freq:d=$seconds:sample_rate=44100", "-ac", "2", f.path)
            .start().waitFor()
        return f
    }

    private fun readSeconds(sec: Double): FloatArray {
        val frames = (sec * SAMPLE_RATE).toInt()
        val out = FloatArray(frames * 2)
        val tmp = FloatArray(4096 * 2)
        var got = 0
        val end = System.currentTimeMillis() + 10_000
        while (got < frames && System.currentTimeMillis() < end) {
            val n = deck.read(tmp, minOf(4096, frames - got))
            System.arraycopy(tmp, 0, out, got * 2, n * 2)
            got += n
            if (n == 0) { if (deck.ended || deck.error != null) break; Thread.sleep(2) }
        }
        return out.copyOf(got * 2)
    }

    private fun rms(a: FloatArray) = sqrt(a.sumOf { it.toDouble() * it } / a.size.coerceAtLeast(1))

    @Test fun `decodes and resamples mp3, opus and m4a, seeks and ends`() {
        if (!hasBinary("ffmpeg")) return
        for (ext in listOf("mp3", "opus", "m4a")) {
            val file = sineFile(440, 4, ext)
            deck.load(AudioSource("t", file.path))
            val first = readSeconds(1.0)
            assertEquals(48000 * 2, first.size, ext)
            assertTrue(rms(first.copyOfRange(9600, first.size)) > 0.05, "$ext silent")
            assertEquals(4.0, deck.durationSec!!, 0.1)
            deck.seek(3.0)
            assertEquals(3.0, deck.positionSec, 1e-9)
            val rest = readSeconds(5.0)
            assertTrue(rest.size / 2 in 40000..56000, "$ext tail ${rest.size / 2}")
            assertTrue(deck.ended, ext)
        }
    }

    @Test fun `start offset is honoured`() {
        if (!hasBinary("ffmpeg")) return
        deck.load(AudioSource("t", sineFile(440, 3, "flac").path, startSec = 2.0))
        val rest = readSeconds(5.0)
        assertTrue(rest.size / 2 in 45000..51000, "got ${rest.size / 2}")
    }

    @Test fun `missing file reports error`() {
        deck.load(AudioSource("t", "/nonexistent/file.mp3"))
        readSeconds(1.0)
        assertNotNull(deck.error)
        assertTrue(deck.ended)
    }
}
