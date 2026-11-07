package dev.schlubbe.musicagent.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class RssParserTest {

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>Testcast</title>
            <itunes:author>Anna &amp; Ben</itunes:author>
            <image><url>https://example.org/logo.png</url><title>Logo title</title></image>
            <itunes:image href="https://example.org/big.jpg"/>
            <description><![CDATA[<p>Ein <b>Podcast</b></p>]]></description>
            <item>
              <title>Folge 1</title>
              <pubDate>Mon, 02 Nov 2026 06:00:00 +0100</pubDate>
              <enclosure url="https://example.org/1.mp3" type="audio/mpeg" length="1"/>
              <itunes:duration>1:02:03</itunes:duration>
            </item>
            <item>
              <title>Folge 2</title>
              <pubDate>Tue, 3 Nov 2026 06:00:00 GMT</pubDate>
              <enclosure url="https://example.org/2.mp3" type="audio/mpeg"/>
              <itunes:duration>754</itunes:duration>
              <itunes:image href="https://example.org/2.jpg"/>
            </item>
            <item>
              <title>Nur Text</title>
            </item>
            <item>
              <title>Kein Web</title>
              <enclosure url="file:///sdcard/x.mp3" type="audio/mpeg"/>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun readsShowAndEpisodesNewestFirst() {
        val result = RssParser.parse(StringReader(feed), "rss:https://example.org/feed", ShowKind.PODCAST)
        assertEquals("Testcast", result.show.title)
        assertEquals("Anna & Ben", result.show.author)
        assertEquals("https://example.org/big.jpg", result.show.imageUrl)
        assertEquals("Ein Podcast", result.show.description)
        assertEquals(listOf("Folge 2", "Folge 1"), result.episodes.map { it.title })
        assertEquals(754, result.episodes[0].durationSec)
        assertEquals(3723, result.episodes[1].durationSec)
        assertEquals("https://example.org/2.jpg", result.episodes[0].imageUrl)
        assertEquals("https://example.org/big.jpg", result.episodes[1].imageUrl)
        assertEquals("Testcast", result.episodes[0].showTitle)
    }

    @Test
    fun parsesDurationsAndDates() {
        assertEquals(90, RssParser.parseDuration("01:30"))
        assertNull(RssParser.parseDuration("abc"))
        assertEquals(1_793_595_600_000L, RssParser.parseDate("Mon, 02 Nov 2026 06:00:00 +0100"))
        assertEquals(1_793_595_600_000L, RssParser.parseDate("Mon, 2 Nov 2026 05:00:00 GMT"))
    }

    @Test
    fun showIdsRoundTripAndRejectOtherSchemes() {
        assertEquals(ShowId.Rss("https://a.org/f.xml"), ShowId.parse("rss:https://a.org/f.xml"))
        assertEquals(ShowId.Ard("123"), ShowId.parse("ard:123"))
        assertEquals("ard:123", ShowId.Ard("123").toString())
        assertEquals("rss:https://a.org/f.xml", ShowId.Rss("https://a.org/f.xml").toString())
        assertNull(ShowId.parse("rss:file:///etc/passwd"))
        assertNull(ShowId.parse("ard:1/../x"))
    }

    @Test
    fun upgradesHttpStripsTrackersAndHonorsDeclaredEncoding() {
        val latin = """<?xml version="1.0" encoding="ISO-8859-1"?>
            <rss version="2.0"><channel><title>Hörspiel</title>
            <item><title>Folge mit Ümlaut</title><enclosure url="http://dts.podtrac.com/redirect.mp3/example.org/a b.mp3" type="audio/mpeg"/></item>
            </channel></rss>""".toByteArray(Charsets.ISO_8859_1)
        val result = RssParser.parse(latin.inputStream(), "rss:x", ShowKind.HOERSPIEL)
        assertEquals("Hörspiel", result.show.title)
        assertEquals("Folge mit Ümlaut", result.episodes.single().title)
        assertEquals("https://example.org/a b.mp3", result.episodes.single().audioUrl)
    }

    @Test
    fun localHostsBypassTheProxy() {
        val local = dev.schlubbe.musicagent.data.remote.PrivacyProxySelector
        assert(local.isLocal("192.168.1.155"))
        assert(local.isLocal("10.0.0.2"))
        assert(local.isLocal("localhost"))
        assert(!local.isLocal("8.8.8.8"))
        assert(!local.isLocal("feeds.example.org"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonRss() {
        RssParser.parse(StringReader("<html><body>nope</body></html>"), "rss:x", ShowKind.PODCAST)
    }
}
