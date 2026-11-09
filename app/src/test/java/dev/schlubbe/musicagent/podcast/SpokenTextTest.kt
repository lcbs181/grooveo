package dev.schlubbe.musicagent.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenTextTest {

    @Test
    fun readsYouTubeTimedText() {
        val xml = """<?xml version="1.0"?><transcript>
            <text start="35.88" dur="1.8">Komm, lassen wir deine Mutter nicht</text>
            <text start="37.68" dur="2">warten.</text>
            <text start="40.1" dur="1">[Musik]</text>
            <text start="43.88" dur="2">Hast du das &amp;quot;gehört&amp;quot;?</text>
            </transcript>"""
        val lines = SpokenText.parseSrv1(xml)
        assertEquals(2, lines.size)
        assertEquals(35_880L, lines[0].timeMs)
        assertEquals("Komm, lassen wir deine Mutter nicht warten.", lines[0].text)
        assertEquals("Hast du das \"gehört\"?", lines[1].text)
    }

    @Test
    fun asksForSrv1WhateverFormatIsListed() {
        assertEquals("https://x/api/timedtext?v=1&fmt=srv1&lang=de", SpokenText.srv1Url("https://x/api/timedtext?v=1&fmt=ttml&lang=de"))
        assertEquals("https://x/api/timedtext?v=1&fmt=srv1", SpokenText.srv1Url("https://x/api/timedtext?v=1"))
    }

    @Test
    fun estimatedLinesStayInsideTheChapter() {
        val book = (1..200).joinToString("\n\n") { "Absatz $it hat einen Satz. Und noch einen zweiten Satz." }
        val lines = SpokenText.estimateLines(book, 0.5, 1.0, 600_000)
        assertTrue(lines.first().text.startsWith("Absatz 10") || lines.first().text.startsWith("Absatz 9"))
        assertTrue(lines.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs })
        assertTrue(lines.last().timeMs < 600_000)
        assertTrue(lines.last().text.contains("zweiten"))
    }

    @Test
    fun findsTheLibriVoxBook() {
        assertEquals("549", SpokenText.libriVoxId("rss:https://librivox.org/rss/549"))
        assertNull(SpokenText.libriVoxId("rss:https://example.org/feed"))
    }

    @Test
    fun cutsGutenbergHeaderAndLicence() {
        val text = "Header\n*** START OF THE PROJECT GUTENBERG EBOOK X ***\nProduced by Someone\n\nDer Text.\n*** END OF THE PROJECT GUTENBERG EBOOK X ***\nLicence"
        assertEquals("Der Text.\n", SpokenText.gutenbergBody(text))
    }
}
