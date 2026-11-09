package dev.schlubbe.musicagent.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeOrderTest {

    private val DAY = 24 * 60 * 60 * 1000L

    private fun ep(title: String, at: Long?) = PodcastEpisode("rss:x", "Show", title, null, "https://x/$title.mp3", null, null, at, null)

    @Test
    fun sameDayPartsAreOrderedByPartNumber() {
        // the source lists them newest first but both parts have the same date
        val source = listOf(
            ep("Hannibals Spuren (1/2) – Folge 75", DAY * 30),
            ep("Hannibals Spuren (2/2) – Folge 76", DAY * 30),
            ep("Das Zeichen der Sieben – Folge 74", DAY),
        )
        val chrono = EpisodeOrder.chronological(source, sourceNewestFirst = true)
        assertEquals(listOf(74, 75, 76), chrono.map { EpisodeOrder.partNumber(it.title) })
        val shown = ShowWithEpisodes.of(PodcastShow("rss:x", "Show", null, null, null, ShowKind.HOERSPIEL), chrono, serial = false)
        assertEquals("Hannibals Spuren (2/2) – Folge 76", shown.episodes.first().title)
        // after part 1 comes part 2
        assertEquals("Hannibals Spuren (2/2) – Folge 76", shown.following(1).first().title)
    }

    @Test
    fun partsStampedAMinuteApartInTheWrongOrder() {
        val source = listOf(
            ep("Hannibals Spuren (1/2) – Folge 75", 60_000),
            ep("Hannibals Spuren (2/2) – Folge 76", 0),
        )
        val chrono = EpisodeOrder.chronological(source, sourceNewestFirst = true, numbers = listOf(75, 76))
        assertEquals(listOf(75, 76), chrono.map { EpisodeOrder.partNumber(it.title) })
    }

    @Test
    fun undatedSerialFeedKeepsChapterOrder() {
        val source = listOf(ep("Kapitel 1", null), ep("Kapitel 2", null), ep("Kapitel 3", null))
        val chrono = EpisodeOrder.chronological(source, sourceNewestFirst = false)
        val shown = ShowWithEpisodes.of(PodcastShow("rss:x", "Buch", null, null, null, ShowKind.HOERBUCH), chrono, serial = true)
        assertEquals(listOf("Kapitel 1", "Kapitel 2", "Kapitel 3"), shown.episodes.map { it.title })
        assertEquals(listOf("Kapitel 2", "Kapitel 3"), shown.following(0).map { it.title })
    }

    @Test
    fun numberedHoerspielSeriesIsSerialButPodcastsAreNot() {
        val series = (70..76).map { ep("Professor van Dusen – Folge $it", it.toLong()) } + ep("Neue Serie", 80)
        assertTrue(EpisodeOrder.isSerial(ShowKind.HOERSPIEL, false, series))
        assertFalse(EpisodeOrder.isSerial(ShowKind.PODCAST, false, series))
        assertFalse(EpisodeOrder.isSerial(ShowKind.HOERSPIEL, false, listOf(ep("Krimi am Montag", 1), ep("Kurzhörspiel", 2))))
    }

    @Test
    fun publisherPlaylistWithoutDatesIsInCaseOrder() {
        val source = (12 downTo 1).map { ep("Prof. van Dusen (Die neuen Fälle) - Fall %02d: Titel (Komplett)".format(it), null) }
        val chrono = EpisodeOrder.chronological(source, sourceNewestFirst = false)
        assertEquals(1, EpisodeOrder.partNumber(chrono.first().title))
        assertTrue(EpisodeOrder.isSerial(ShowKind.HOERSPIEL, false, chrono))
    }

    @Test
    fun oneWorkInParts() {
        assertTrue(EpisodeOrder.isOneWorkInParts(listOf(ep("Das tickende Herz (1/36)", 1), ep("Das tickende Herz (2/36)", 2))))
        assertFalse(EpisodeOrder.isOneWorkInParts(listOf(ep("Fall Zola (1/2)", 1), ep("Mörderspiel – Folge 73", 2))))
        val schloss = (1..29).map { ep("Das Schloss ($it/29)", it.toLong()) } + ep("Der Verschollene (6/26)", 99)
        assertTrue(EpisodeOrder.isOneWorkInParts(schloss))
    }
}
