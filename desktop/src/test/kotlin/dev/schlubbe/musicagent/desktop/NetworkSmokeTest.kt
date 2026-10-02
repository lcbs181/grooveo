package dev.schlubbe.musicagent.desktop

import dev.schlubbe.musicagent.desktop.audio.AudioSource
import dev.schlubbe.musicagent.desktop.audio.FfmpegDeck
import dev.schlubbe.musicagent.desktop.audio.TrackAnalyzer
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end against live SoundCloud / YouTube Music: search, resolve, decode real
 * audio. Opt-in (`GROOVEO_NETWORK_TESTS=1`) because it depends on third-party services.
 */
class NetworkSmokeTest {
    private val enabled = System.getenv("GROOVEO_NETWORK_TESTS") == "1"

    @Test fun `search, resolve and decode from both sources`() = runBlocking {
        if (!enabled) return@runBlocking
        val g = AppGraph(Files.createTempDirectory("grooveo-net").toFile())
        for (source in listOf("soundcloud", "ytmusic")) {
            val results = g.search.search("daft punk around the world", source, 10)
            assertTrue(results.isNotEmpty(), "$source: no results")
            val track = results.first { !it.isDrmProtected }
            val stream = g.resolver.resolve(track.source, track.sourceId)
            val deck = FfmpegDeck("net")
            deck.load(AudioSource("x", stream.url, stream.httpHeaders))
            val buf = FloatArray(8192)
            var frames = 0
            var energy = 0.0
            val end = System.currentTimeMillis() + 20_000
            while (frames < 96_000 && System.currentTimeMillis() < end && deck.error == null) {
                val n = deck.read(buf, 4096)
                for (i in 0 until n * 2) energy += buf[i] * buf[i]
                frames += n
                if (n == 0) Thread.sleep(5)
            }
            deck.close()
            assertTrue(frames >= 96_000, "$source: decoded only $frames frames, error=${deck.error}")
            assertTrue(energy > 1.0, "$source: silent")
            println("$source OK: ${track.title} (${if (stream.isHls) "HLS" else "progressive"})")
        }
        val feed = g.feed.getFeed(10)
        assertTrue(feed.items.isNotEmpty(), "empty feed")
        val lyr = g.lyrics.lyricsFor("Around the World", "Daft Punk", 429)
        println("lyrics: ${lyr != null}")
        val yt = g.search.search("daft punk one more time", "ytmusic", 3).first()
        val s = g.resolver.resolve(yt.source, yt.sourceId)
        println("analysis: ${TrackAnalyzer.analyze(s.url, s.httpHeaders)}")
        g.close()
    }
}
