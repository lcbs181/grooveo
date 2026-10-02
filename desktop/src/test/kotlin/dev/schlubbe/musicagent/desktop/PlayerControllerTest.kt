package dev.schlubbe.musicagent.desktop

import dev.schlubbe.musicagent.data.extract.ResolvedStream
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import dev.schlubbe.musicagent.desktop.audio.AudioEngine
import dev.schlubbe.musicagent.desktop.audio.CaptureSink
import dev.schlubbe.musicagent.desktop.audio.FakeDeck
import dev.schlubbe.musicagent.desktop.data.LibraryStore
import dev.schlubbe.musicagent.desktop.data.key
import dev.schlubbe.musicagent.desktop.data.track
import dev.schlubbe.musicagent.desktop.playback.PlayerController
import dev.schlubbe.musicagent.desktop.playback.RepeatMode
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerControllerTest {
    private val dir = Files.createTempDirectory("grooveo-pc").toFile()
    private val store = LibraryStore(dir)
    private val settings = SettingsRepository(File(dir, "settings.json")).apply {
        update { it.copy(autoplayRadio = false, eq = dev.schlubbe.musicagent.playback.eq.EqProfile(enabled = false), loudnessNormalization = false) }
    }
    private val resolved = mutableListOf<String>()
    private var radio: List<dev.schlubbe.musicagent.data.remote.dto.TrackResultDto> = emptyList()
    // ids are single digits so FakeDeck outputs id/10 as sample value
    /** Track length of the fake decks; short for flow tests, long so edits don't race the queue. */
    private var deckSeconds = 60.0
    private val engine by lazy { AudioEngine({ FakeDeck(deckSeconds) }, CaptureSink()).apply { volume = 1f } }
    private val pc by lazy { PlayerController(
        engine,
        resolveRemote = { t ->
            resolved += t.sourceId
            when {
                t.sourceId == "9" -> error("kaputt")
                // "8": the first URL breaks during playback (like an expired signed URL)
                t.sourceId == "8" && resolved.count { it == "8" } == 1 -> ResolvedStream("bad", false)
                else -> ResolvedStream("mem://${t.sourceId}", false)
            }
        },
        recommend = { _, _, _ -> radio },
        store = store,
        settings = settings,
    ) }
    private val q = (1..5).map { track("$it") }

    @AfterTest fun tearDown() = pc.close()

    private fun waitFor(ms: Long = 6000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { check(System.currentTimeMillis() < end) { "timeout: ${pc.state.value}" }; Thread.sleep(10) }
    }

    @Test fun `plays through the queue gaplessly and records history`() {
        deckSeconds = 0.4
        pc.playQueue(q.take(3))
        waitFor { store.current.history.size == 3 && !pc.engineState.value.playing }
        assertEquals(listOf("3", "2", "1"), store.current.history.map { it.track.sourceId })
    }

    @Test fun `queue editing keeps the current track`() {
        pc.playQueue(q, 1)
                pc.moveInQueue(4, 0)
        assertEquals("2", pc.state.value.current?.sourceId)
        assertEquals(listOf("5", "1", "2", "3", "4"), pc.state.value.queue.map { it.sourceId })
        pc.removeFromQueue(0)
        assertEquals(1, pc.state.value.index)
        pc.playNext(track("7"))
        assertEquals("7", pc.state.value.upNext.first().sourceId)
        pc.addToQueue(listOf(track("3"), track("8")))
        assertEquals(1, pc.state.value.queue.count { it.sourceId == "3" })
        pc.clearUpNext()
        assertEquals(listOf("1", "2"), pc.state.value.queue.map { it.sourceId })
    }

    @Test fun `shuffle keeps current first and restores order`() {
        pc.playQueue(q, 2)
                pc.toggleShuffle()
        val s = pc.state.value
        assertEquals("3", s.current?.sourceId)
        assertEquals(q.map { it.key }.toSet(), s.queue.map { it.key }.toSet())
        pc.toggleShuffle()
        assertEquals(q, pc.state.value.queue)
        assertEquals("3", pc.state.value.current?.sourceId)
    }

    @Test fun `repeat cycles and wraps`() {
        assertEquals(RepeatMode.OFF, pc.state.value.repeat)
        pc.cycleRepeat(); assertEquals(RepeatMode.ALL, pc.state.value.repeat)
        pc.playQueue(q.take(2), 1)
                pc.next()
        assertEquals(0, pc.state.value.index)
        pc.cycleRepeat(); pc.cycleRepeat(); assertEquals(RepeatMode.OFF, pc.state.value.repeat)
    }

    @Test fun `unplayable track is skipped`() {
        deckSeconds = 0.4
        pc.playQueue(listOf(track("9"), track("2")))
        waitFor { pc.state.value.current?.sourceId == "2" && pc.engineState.value.currentId == "soundcloud:2" }
        assertEquals(listOf("9", "2"), resolved.take(2))
    }

    @Test fun `broken stream is re-resolved and retried instead of skipped`() {
        pc.playQueue(listOf(track("8"), track("2")))
        waitFor { resolved.count { it == "8" } == 2 && pc.engineState.value.playing }
        assertEquals("8", pc.state.value.current?.sourceId)
        assertNull(pc.state.value.error)
    }

    @Test fun `downloaded copy is preferred and data saver blocks streaming`() {
        val f = File(dir, "local.mp3").apply { writeText("x") }
        store.upsertDownload(dev.schlubbe.musicagent.desktop.data.DownloadRecord(track("4"), dev.schlubbe.musicagent.data.local.entity.DownloadState.COMPLETED, 100, f.path))
        kotlinx.coroutines.runBlocking {
            assertEquals(f.path, pc.resolveStream(track("4")).url)
            settings.update { it.copy(dataSaverMode = true) }
            assertTrue(runCatching { pc.resolveStream(track("5")) }.isFailure)
        }
        assertTrue("5" !in resolved)
    }

    @Test fun `radio extends the queue when it runs out`() {
        deckSeconds = 2.5
        settings.update { it.copy(autoplayRadio = true) }
        radio = listOf(track("6"), track("7"))
        pc.playQueue(listOf(track("1")))
        waitFor { pc.state.value.queue.size == 3 }
        waitFor { pc.state.value.current?.sourceId == "6" }
    }

    @Test fun `sleep timer can be set and cancelled`() {
        pc.setSleepTimer(15)
        val end = assertNotNull(pc.state.value.sleepTimerEndAt)
        assertTrue(end - System.currentTimeMillis() in 14 * 60_000L..15 * 60_000L)
        pc.setSleepTimer(null)
        assertNull(pc.state.value.sleepTimerEndAt)
    }
}
