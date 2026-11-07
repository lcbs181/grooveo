package dev.schlubbe.musicagent.data.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkUrlsTest {

    @Test
    fun googleCoversAskForBigSquare() {
        val url = "https://yt3.googleusercontent.com/DNFuGZeu_K=w120-h120-l90-rj"
        assertEquals(
            listOf("https://yt3.googleusercontent.com/DNFuGZeu_K=w544-h544-l90-rj", url),
            ArtworkUrls.candidates(url),
        )
        assertEquals("https://yt3.googleusercontent.com/DNFuGZeu_K=w544-h544-l90-rj", ArtworkUrls.safe(url))
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj",
            ArtworkUrls.candidates("https://lh3.googleusercontent.com/abc=s60").first(),
        )
    }

    @Test
    fun videoStillsTryLargerRenditions() {
        val url = "https://i.ytimg.com/vi/GfQlfGyATpE/hqdefault.jpg?sqp=-oaymwEcCNACELwBSFXyq4qpAw4IARUAAIhCGAFwAcABBg==&rs=AOn4CLDI"
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/GfQlfGyATpE/maxresdefault.jpg",
                "https://i.ytimg.com/vi/GfQlfGyATpE/hq720.jpg",
                "https://i.ytimg.com/vi/GfQlfGyATpE/hqdefault.jpg",
                url,
            ),
            ArtworkUrls.candidates(url),
        )
        assertEquals("https://i.ytimg.com/vi/GfQlfGyATpE/hqdefault.jpg", ArtworkUrls.safe(url))
    }

    @Test
    fun otherUrlsStayAsTheyAre() {
        val sc = "https://i1.sndcdn.com/artworks-rZlknkAoRPwC-0-t500x500.jpg"
        assertEquals(listOf(sc), ArtworkUrls.candidates(sc))
        assertEquals(sc, ArtworkUrls.safe(sc))
    }
}
