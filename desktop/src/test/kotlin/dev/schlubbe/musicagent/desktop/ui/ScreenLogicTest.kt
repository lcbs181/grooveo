package dev.schlubbe.musicagent.desktop.ui

import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.desktop.data.LikedTrack
import dev.schlubbe.musicagent.desktop.data.Playlist
import dev.schlubbe.musicagent.desktop.data.PlaylistTrack
import dev.schlubbe.musicagent.desktop.ui.screens.LikeSort
import dev.schlubbe.musicagent.desktop.ui.screens.SearchCache
import dev.schlubbe.musicagent.desktop.ui.screens.SearchKey
import dev.schlubbe.musicagent.desktop.ui.screens.SearchSource
import dev.schlubbe.musicagent.desktop.ui.screens.SearchType
import dev.schlubbe.musicagent.desktop.ui.screens.TrackColumn
import dev.schlubbe.musicagent.desktop.ui.screens.TrackSort
import dev.schlubbe.musicagent.desktop.ui.screens.availableSources
import dev.schlubbe.musicagent.desktop.ui.screens.coverTargetFile
import dev.schlubbe.musicagent.desktop.ui.screens.filterLikes
import dev.schlubbe.musicagent.desktop.ui.screens.moveSelection
import dev.schlubbe.musicagent.desktop.ui.screens.playlistShareText
import dev.schlubbe.musicagent.desktop.ui.screens.relativeTime
import dev.schlubbe.musicagent.desktop.ui.screens.shouldShowSuggestions
import dev.schlubbe.musicagent.desktop.ui.screens.sortTracks
import dev.schlubbe.musicagent.desktop.ui.screens.stepTarget
import dev.schlubbe.musicagent.desktop.ui.screens.topTrack
import dev.schlubbe.musicagent.desktop.ui.screens.trackCountLabel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenLogicTest {
    private fun t(id: String, title: String, artist: String? = null, dur: Int? = 100, drm: Boolean = false) =
        TrackResultDto("soundcloud", id, title, artist, null, dur, null, "https://x/$id", isDrmProtected = drm)

    private val likes = listOf(
        LikedTrack(t("1", "Zebra", "Ärzte"), 300),
        LikedTrack(t("2", "apfel", "Beatles"), 100),
        LikedTrack(t("3", "Öl", "Ärzte"), 200),
    )

    @Test fun likesSortRecent() = assertEquals(listOf("1", "3", "2"), filterLikes(likes).map { it.track.sourceId })

    @Test fun likesSortTitleIsLocaleAware() =
        assertEquals(listOf("apfel", "Öl", "Zebra"), filterLikes(likes, sort = LikeSort.TITLE).map { it.track.title })

    @Test fun likesSortArtistThenTitle() =
        assertEquals(listOf("Öl", "Zebra", "apfel"), filterLikes(likes, sort = LikeSort.ARTIST).map { it.track.title })

    @Test fun likesQueryMatchesTitleAndArtist() {
        assertEquals(listOf("2"), filterLikes(likes, query = "beat").map { it.track.sourceId })
        assertEquals(setOf("1", "3"), filterLikes(likes, query = " ärzte ").map { it.track.sourceId }.toSet())
    }

    @Test fun likesOfflineOnly() =
        assertEquals(listOf("2"), filterLikes(likes, offlineOnly = true, offlineKeys = setOf("soundcloud:2")).map { it.track.sourceId })

    @Test fun trackSortCycles() {
        val s = TrackSort().click(TrackColumn.TITLE)
        assertEquals(TrackSort(TrackColumn.TITLE, true), s)
        assertEquals(TrackSort(TrackColumn.TITLE, false), s.click(TrackColumn.TITLE))
        assertEquals(TrackSort(), s.click(TrackColumn.TITLE).click(TrackColumn.TITLE))
        assertEquals(TrackSort(TrackColumn.DURATION, true), s.click(TrackColumn.DURATION))
    }

    @Test fun sortTracksByDuration() {
        val list = listOf(t("a", "A", dur = 300), t("b", "B", dur = null), t("c", "C", dur = 10))
        assertEquals(listOf("c", "a", "b"), sortTracks(list, TrackSort(TrackColumn.DURATION)).map { it.sourceId })
        assertEquals(listOf("b", "a", "c"), sortTracks(list, TrackSort(TrackColumn.DURATION, false)).map { it.sourceId })
        assertEquals(list, sortTracks(list, TrackSort()))
    }

    @Test fun countLabel() {
        assertEquals("1 Titel", trackCountLabel(1, 0))
        assertEquals("3 Titel · 5 Min.", trackCountLabel(3, 300))
        assertEquals("20 Titel · 1 Std. 2 Min.", trackCountLabel(20, 3720))
    }

    @Test fun shareText() {
        val p = Playlist(name = "Mix", tracks = listOf(PlaylistTrack(t("1", "Song", "Band"), 0), PlaylistTrack(t("2", "Solo"), 0)))
        assertEquals("Mix (2 Titel)\n- Song – Band\n- Solo", playlistShareText(p))
    }

    @Test fun coverTarget() {
        assertEquals("abc-5.png", coverTargetFile(File("/c"), "abc", "Foto.PNG", 5).name)
        assertEquals("abc-5.jpg", coverTargetFile(File("/c"), "abc", "noext", 5).name)
    }

    @Test fun stepMoves() {
        assertEquals(0, stepTarget(1, -1, 3))
        assertNull(stepTarget(0, -1, 3))
        assertNull(stepTarget(2, 1, 3))
    }

    @Test fun relative() {
        assertEquals("gerade eben", relativeTime(1_000, 2_000))
        assertEquals("vor 5 Min.", relativeTime(0, 5 * 60_000L))
        assertEquals("gestern", relativeTime(0, 30 * 3_600_000L))
    }

    @Test fun searchCacheIsLruAndNormalized() {
        val cache = SearchCache(max = 2)
        val k1 = SearchKey.of("Daft Punk ", SearchSource.ALL, SearchType.TRACKS)
        cache[k1] = listOf("x")
        assertEquals(listOf<Any>("x"), cache[SearchKey.of("daft punk", SearchSource.ALL, SearchType.TRACKS)])
        assertNull(cache[SearchKey.of("daft punk", SearchSource.ALL, SearchType.ARTISTS)])
        cache[SearchKey.of("b", SearchSource.ALL, SearchType.TRACKS)] = emptyList()
        cache[k1] // touch -> most recent
        cache[SearchKey.of("c", SearchSource.ALL, SearchType.TRACKS)] = emptyList()
        assertEquals(2, cache.size())
        assertTrue(cache[k1] != null)
        assertNull(cache[SearchKey.of("b", SearchSource.ALL, SearchType.TRACKS)])
    }

    @Test fun suggestionsVisibility() {
        assertTrue(shouldShowSuggestions("daf", null, true, listOf("daft punk")))
        assertFalse(shouldShowSuggestions("daf", null, false, listOf("daft punk")))
        assertFalse(shouldShowSuggestions("daf", "daf ", true, listOf("daft punk")))
        assertFalse(shouldShowSuggestions(" ", null, true, listOf("x")))
    }

    @Test fun suggestionKeyboardNav() {
        assertEquals(0, moveSelection(-1, 1, 3))
        assertEquals(2, moveSelection(2, 1, 3))
        assertEquals(-1, moveSelection(0, -1, 3))
        assertEquals(-1, moveSelection(-1, 1, 0))
    }

    @Test fun sourcesFollowSettings() {
        assertEquals(SearchSource.entries, availableSources(true, true))
        assertEquals(listOf(SearchSource.YTMUSIC), availableSources(false, true))
    }

    @Test fun topTrackSkipsDrm() {
        assertEquals("b", topTrack(listOf(t("a", "A", drm = true), t("b", "B")))?.sourceId)
        assertNull(topTrack(emptyList()))
    }
}

class HiResArtworkTest {
    @Test fun `soundcloud artwork is requested at 1080 px`() {
        kotlin.test.assertEquals("https://i1.sndcdn.com/artworks-abc-0-t1080x1080.jpg", hiResArtwork("https://i1.sndcdn.com/artworks-abc-0-t500x500.jpg"))
        kotlin.test.assertEquals("https://i1.sndcdn.com/avatars-x-large.png".replace("large", "t1080x1080"), hiResArtwork("https://i1.sndcdn.com/avatars-x-large.png"))
    }

    @Test fun `youtube artwork sizes are rewritten`() {
        kotlin.test.assertEquals("https://lh3.googleusercontent.com/abc=w1080-h1080-l90-rj", hiResArtwork("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj"))
        kotlin.test.assertEquals("https://yt3.ggpht.com/abc=s1080-c-k", hiResArtwork("https://yt3.ggpht.com/abc=s88-c-k"))
        kotlin.test.assertEquals("https://i.ytimg.com/vi/xyz/maxresdefault.jpg", hiResArtwork("https://i.ytimg.com/vi/xyz/hqdefault.jpg?sqp=abc&rs=def"))
    }

    @Test fun `unknown urls are unchanged`() {
        kotlin.test.assertEquals("https://example.com/a.jpg", hiResArtwork("https://example.com/a.jpg"))
    }
}
