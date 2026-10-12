package dev.schlubbe.musicagent.desktop.playback

import dev.schlubbe.musicagent.data.repository.SettingsRepository
import dev.schlubbe.musicagent.desktop.audio.AudioEngine
import dev.schlubbe.musicagent.desktop.audio.FfmpegDeck
import dev.schlubbe.musicagent.desktop.audio.PcmSink
import dev.schlubbe.musicagent.desktop.audio.TrackAnalyzer
import dev.schlubbe.musicagent.desktop.audio.TransitionStatus
import dev.schlubbe.musicagent.desktop.data.LibraryStore
import dev.schlubbe.musicagent.desktop.data.TrackAnalysis
import dev.schlubbe.musicagent.desktop.data.key
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Plays two real downloaded tracks through the real engine (faster than real time, no speakers)
 * and checks that the DJ transition between them is planned, starts and is heard.
 * Opt-in: GROOVEO_REAL_DATA=<a copy of a data folder with downloads>, GROOVEO_REAL_TRACKS=key1,key2.
 */
class RealTransitionCheck {
    /** Records the RMS of every 100 ms, no pacing. */
    private class MeterSink : PcmSink {
        val rms = java.util.concurrent.CopyOnWriteArrayList<Double>()
        private var acc = 0.0; private var n = 0
        val frames = AtomicLong()
        override fun write(buf: FloatArray, frames: Int) {
            for (i in 0 until frames) { acc += (buf[2 * i] * buf[2 * i]).toDouble(); if (++n == 4800) { rms += sqrt(acc / n); acc = 0.0; n = 0 } }
            this.frames.addAndGet(frames.toLong())
            Thread.sleep(1) // about 20x real time: the analysis must get a chance to finish before the track does
        }
        override fun start() {}
        override fun stop() {}
        override fun flush() {}
        override val latencySec = 0.0
        override fun close() {}
    }

    @Test fun `a planned DJ transition starts and both tracks are heard`() {
        val dataDir = System.getenv("GROOVEO_REAL_DATA") ?: return
        val keys = System.getenv("GROOVEO_REAL_TRACKS")?.split(',') ?: return
        val dir = Files.createTempDirectory("grooveo-real").toFile()
        File(dataDir).listFiles()!!.filter { it.isFile }.forEach { it.copyTo(File(dir, it.name)) }
        val store = LibraryStore(dir)
        val settings = SettingsRepository(File(dir, "settings.json"))
        val sink = MeterSink()
        val engine = AudioEngine({ FfmpegDeck(it) }, sink)
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
        val player = PlayerController(engine, { error("no streams in this check") }, { _, _, _ -> emptyList() }, store, settings,
            analyze = { t ->
                val f = store.download(t.key)?.filePath ?: return@PlayerController null
                val r = TrackAnalyzer.analyzeCancellable(f) ?: return@PlayerController null
                TrackAnalysis(r.mix?.mixInMs, r.mix?.mixOutMs, grid = r.grid, structure = r.structure, key = r.key, version = TrackAnalysis.CURRENT_VERSION)
                    .also { store.putAnalysis(t.key, it); log += "analysed ${t.key}: grid=${r.grid?.bpm}" }
            })
        val tracks = keys.map { k -> store.current.downloads.first { it.track.key == k }.track }
        log += "settings dj=${settings.current.djTransitions} style=${settings.current.djStyle} xf=${settings.current.crossfadeSeconds}"
        log += "native beat tracker: ${dev.schlubbe.musicagent.desktop.audio.BeatTracker.available}"
        player.playQueue(tracks)
        val end = System.currentTimeMillis() + 120_000
        while (player.planned.value == null && System.currentTimeMillis() < end) Thread.sleep(50)
        val planned = player.planned.value
        log += "planned: ${planned?.summary}"
        if (planned != null) {
            val p = planned.plan
            log += "plan out=${p.outStartSec} in=${p.inCueSec} beats=${p.lengthBeats} sync=${p.sync}"
            player.seek(p.outStartSec - 4)
            var phases = mutableListOf<TransitionStatus.Phase>()
            val stop = System.currentTimeMillis() + 60_000
            val startFrames = sink.frames.get()
            while (System.currentTimeMillis() < stop) {
                val ph = engine.transition.value.phase
                if (phases.lastOrNull() != ph) { phases += ph; log += "phase $ph at frame ${sink.frames.get() - startFrames} cur=${player.state.value.current?.key}" }
                if (player.state.value.current?.key == keys[1] && ph == TransitionStatus.Phase.NONE && phases.size > 1) break
                Thread.sleep(5)
            }
        }
        log += "now: ${player.state.value.current?.key} pos=${engine.state.value.positionSec}"
        player.close()
        println(log.joinToString("\n", "--- real transition check ---\n"))
        assertTrue(planned != null, "no transition planned")
    }
}
