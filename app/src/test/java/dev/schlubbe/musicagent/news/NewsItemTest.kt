package dev.schlubbe.musicagent.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NewsItemTest {

    @Test
    fun parsesItemsAndSkipsBrokenOnes() {
        val items = NewsItem.parseAll(
            """
            {"items": [
              {"id": "a", "title": "Hallo", "text": "Neu!", "date": "2026-11-05", "apps": ["Dawn"], "minVersionCode": 3000,
               "url": "https://music.youtube.com/watch?v=abc"},
              {"id": "b", "title": "Ohne Text"},
              {"id": "c", "title": "Unsicher", "text": "x", "url": "http://example.com"},
              42
            ]}
            """,
        )
        assertEquals(listOf("a", "c"), items.map { it.id })
        val a = items[0]
        assertEquals(LocalDate.of(2026, 11, 5), a.date)
        assertEquals(listOf("dawn"), a.apps)
        assertEquals(3000L, a.minVersionCode)
        assertNull(items[1].url)
    }

    @Test
    fun recognisesMusicLinks() {
        assertTrue(NewsItem.isMusicLink("https://music.youtube.com/watch?v=abc"))
        assertTrue(NewsItem.isMusicLink("https://www.youtube.com/playlist?list=x"))
        assertTrue(NewsItem.isMusicLink("https://soundcloud.com/artist/song"))
        assertFalse(NewsItem.isMusicLink("https://github.com/lcbs181/grooveo"))
        assertFalse(NewsItem.isMusicLink("https://youtube.com.evil.example/x"))
    }
}
