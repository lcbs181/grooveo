package dev.schlubbe.musicagent.data.extract.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicArtistPageTest {

    private val json = javaClass.getResource("/ytmusic/artist_kraftklub.json")!!.readText()
    private val url = "https://www.youtube.com/channel/UCoPAT-1R-D0wEokJ1xVzneQ"

    @Test fun `header carries name, audience, image and a trimmed bio`() {
        val d = YouTubeMusicArtistPage.parse(json, url).detail
        assertEquals("Kraftklub", d.name)
        assertEquals("436.000", d.monthlyListeners)
        assertEquals("321.000", d.subscriberCount)
        assertTrue(d.bannerUrl!!.startsWith("https://lh3.googleusercontent.com/"))
        assertTrue(d.thumbnailUrl!!.endsWith("=w544-h544-p-l90-rj"))
        assertTrue(d.description!!.startsWith("Kraftklub ist eine"))
        assertTrue("Quelle" !in d.description!!)
        assertEquals(url, d.sourceId)
    }

    @Test fun `top songs carry video id, plays and album`() {
        val page = YouTubeMusicArtistPage.parse(json, url)
        val first = page.detail.topTracks.first()
        assertEquals("CCCFJQ5Y8Gw", first.sourceId)
        assertEquals("Schüsse in die Luft", first.title)
        assertEquals("Kraftklub", first.artist)
        assertEquals("32 Mio. Wiedergaben", first.plays)
        assertEquals("OLAK5uy_kirrduWnGE7T-jSmd0Bqc0ID1gP_EXm_w", page.topSongsPlaylistId)
    }

    @Test fun `shelves are sorted into albums, singles, videos, playlists and related artists`() {
        val d = YouTubeMusicArtistPage.parse(json, url).detail
        val album = d.albums.first()
        assertEquals("Sterben in Karl-Marx-Stadt", album.title)
        assertEquals("2025", album.year)
        assertEquals("Album", album.kind)
        assertEquals("https://www.youtube.com/playlist?list=OLAK5uy_n2kMVGC1Y7O3txP0yCSGdcNR0PiMphuc8", album.sourceId)

        val single = d.singles.first()
        assertEquals("Single", single.kind)
        assertEquals("2025", single.year)

        assertEquals("e3qLmEfjXfs", d.videos.first().sourceId)
        assertEquals("https://www.youtube.com/playlist?list=RDCLAK5uy_n3nzP7Fn6Ot-2ZuLaaqpriz03GJ6O01nM", d.featuredOn.first().sourceId)
        assertEquals("YouTube Music", d.featuredOn.first().owner)
        assertTrue(d.playlists.isNotEmpty())

        val fan = d.related.first()
        assertEquals("KUMMER", fan.name)
        assertEquals("https://www.youtube.com/channel/UCUHQfA8uW2KNh-h5lFCz9Sg", fan.sourceId)
    }

    @Test fun `helpers`() {
        assertEquals("Bio.", YouTubeMusicArtistPage.cleanDescription("Bio.\n\nQuelle: Wikipedia (x)"))
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", YouTubeMusicArtistPage.squareImage("https://i.ytimg.com/vi/x/hq.jpg"))
    }
}
