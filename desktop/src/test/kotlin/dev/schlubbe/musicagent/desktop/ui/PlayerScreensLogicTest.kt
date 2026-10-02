package dev.schlubbe.musicagent.desktop.ui

import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.repository.LyricLine
import dev.schlubbe.musicagent.desktop.data.DownloadRecord
import dev.schlubbe.musicagent.desktop.data.Settings
import dev.schlubbe.musicagent.desktop.ui.screens.DownloadSort
import dev.schlubbe.musicagent.desktop.ui.screens.activeLyricIndex
import dev.schlubbe.musicagent.desktop.ui.screens.fibonacciSphere
import dev.schlubbe.musicagent.desktop.ui.screens.filterSortDownloads
import dev.schlubbe.musicagent.desktop.ui.screens.formatBackupTime
import dev.schlubbe.musicagent.desktop.ui.screens.formatSleepRemaining
import dev.schlubbe.musicagent.desktop.ui.screens.queueDragTarget
import dev.schlubbe.musicagent.desktop.ui.screens.queueKeys
import dev.schlubbe.musicagent.desktop.ui.screens.toggleSourceSetting
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerScreensLogicTest {
    private fun track(id: String, title: String = id, artist: String? = null) = TrackResultDto("soundcloud", id, title, artist, null, 100, null, "")

    @Test fun `sleep timer remaining is formatted and null when over`() {
        assertNull(formatSleepRemaining(null, 0))
        assertNull(formatSleepRemaining(1000, 1000))
        assertEquals("15:00", formatSleepRemaining(15 * 60_000L, 0))
        assertEquals("0:01", formatSleepRemaining(500, 0)) // rounds up
        assertEquals("1:30:00", formatSleepRemaining(90 * 60_000L, 0))
    }

    @Test fun `active lyric line lookup`() {
        val lines = listOf(LyricLine(1000, "a"), LyricLine(3000, "b"), LyricLine(5000, "c"))
        assertEquals(-1, activeLyricIndex(lines, 500))
        assertEquals(0, activeLyricIndex(lines, 1000))
        assertEquals(1, activeLyricIndex(lines, 4999))
        assertEquals(2, activeLyricIndex(lines, 99_000))
        assertEquals(-1, activeLyricIndex(emptyList(), 1000))
    }

    @Test fun `downloads filter and sort`() {
        val list = listOf(
            DownloadRecord(track("1", "Zebra", "Bob"), DownloadState.COMPLETED, totalBytes = 10, createdAt = 1),
            DownloadRecord(track("2", "alpha", "Carl"), DownloadState.FAILED, totalBytes = 30, createdAt = 3),
            DownloadRecord(track("3", "Mango", "Anna"), DownloadState.COMPLETED, totalBytes = 20, createdAt = 2),
        )
        assertEquals(listOf("2", "3", "1"), filterSortDownloads(list, "", null, DownloadSort.RECENT).map { it.track.sourceId })
        assertEquals(listOf("2", "3", "1"), filterSortDownloads(list, "", null, DownloadSort.TITLE).map { it.track.sourceId })
        assertEquals(listOf("3", "1", "2"), filterSortDownloads(list, "", null, DownloadSort.ARTIST).map { it.track.sourceId })
        assertEquals(listOf("2", "3", "1"), filterSortDownloads(list, "", null, DownloadSort.SIZE).map { it.track.sourceId })
        assertEquals(listOf("1"), filterSortDownloads(list, "bob", null, DownloadSort.RECENT).map { it.track.sourceId })
        assertEquals(listOf("3", "1"), filterSortDownloads(list, "", DownloadState.COMPLETED, DownloadSort.RECENT).map { it.track.sourceId })
    }

    @Test fun `fibonacci sphere points are on the unit sphere and span the spectrum`() {
        val pts = fibonacciSphere(1000, 256)
        assertEquals(1000, pts.size)
        pts.forEach { p -> assertTrue(abs(sqrt(p.x * p.x + p.y * p.y + p.z * p.z) - 1f) < 1e-3f) }
        assertEquals(0, pts.first().bandLow)
        assertEquals(255, pts.last().bandHigh)
        assertTrue(pts.zipWithNext().all { (a, b) -> b.y < a.y }) // index is latitude
        assertTrue(abs(pts.map { it.x }.average()) < 0.05) // evenly spread, no spokes
    }

    @Test fun `queue drag target and unique keys`() {
        assertEquals(3, queueDragTarget(1, 120f, 56f, 10))
        assertEquals(0, queueDragTarget(1, -500f, 56f, 10))
        assertEquals(9, queueDragTarget(5, 5000f, 56f, 10))
        assertEquals(listOf("soundcloud:a", "soundcloud:b", "soundcloud:a#2"), queueKeys(listOf(track("a"), track("b"), track("a"))))
    }

    @Test fun `settings helpers`() {
        assertEquals("01.10.2026, 12:30", formatBackupTime("2026-10-01T12:30:00Z", ZoneOffset.UTC))
        assertNull(formatBackupTime("garbage"))
        val s = Settings(sourceSoundCloud = true, sourceYouTube = false)
        assertNull(toggleSourceSetting(s, soundCloud = true, enabled = false))
        assertEquals(false, toggleSourceSetting(Settings(), soundCloud = false, enabled = false)?.sourceYouTube)
    }
}
