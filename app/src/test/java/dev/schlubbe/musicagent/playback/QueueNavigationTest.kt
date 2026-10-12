package dev.schlubbe.musicagent.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueNavigationTest {
    // A fake shuffle order over player (exo) indices, as a timeline would give it.
    private val order = listOf(2, 0, 3, 1)
    private fun next(wrap: Boolean) = { exo: Int ->
        val pos = order.indexOf(exo) + 1
        when {
            pos < order.size -> order[pos]
            wrap -> order[0]
            else -> C.INDEX_UNSET
        }
    }
    private fun previous(wrap: Boolean) = { exo: Int ->
        val pos = order.indexOf(exo) - 1
        when {
            pos >= 0 -> order[pos]
            wrap -> order.last()
            else -> C.INDEX_UNSET
        }
    }

    @Test fun `without shuffle the logical queue is walked one slot at a time`() {
        assertEquals(3, QueueNavigation.logicalNext(2, 5, Player.REPEAT_MODE_OFF))
        assertNull(QueueNavigation.logicalNext(4, 5, Player.REPEAT_MODE_OFF))
        assertEquals(0, QueueNavigation.logicalNext(4, 5, Player.REPEAT_MODE_ALL))
        assertNull(QueueNavigation.logicalPrevious(0, 5, Player.REPEAT_MODE_OFF))
        assertEquals(4, QueueNavigation.logicalPrevious(0, 5, Player.REPEAT_MODE_ALL))
        assertNull(QueueNavigation.logicalNext(-1, 5, Player.REPEAT_MODE_ALL))
    }

    @Test fun `with shuffle next and previous follow the player order`() {
        // logical 0..4, logical 1 is a DRM gap with nothing loaded
        val mapping = listOf(0, null, 1, 2, 3)
        // current logical 0 = exo 0; the shuffle order is exo 2, 0, 3, 1
        assertEquals(4, QueueNavigation.neighbourInPlayerOrder(0, mapping, 0, next(false)))
        assertEquals(4, QueueNavigation.neighbourInPlayerOrder(1, mapping, 2, previous(false)))
        assertEquals(3, QueueNavigation.neighbourInPlayerOrder(0, mapping, 0, previous(false)))
        assertNull(QueueNavigation.neighbourInPlayerOrder(2, mapping, 3, previous(false)))
        // end of the order: nothing without repeat, wraps with repeat-all
        assertNull(QueueNavigation.neighbourInPlayerOrder(1, mapping, 2, next(false)))
        assertEquals(3, QueueNavigation.neighbourInPlayerOrder(1, mapping, 2, next(true)))
    }

    @Test fun `up next lists the rest of the shuffle order as logical indices`() {
        val mapping = listOf(0, null, 1, 2, 3)
        // from exo 0 the order continues 3, 1 -> logical 4, 2
        assertEquals(listOf(4, 2), QueueNavigation.upNextInPlayerOrder(0, mapping, 0, next(false)))
        assertEquals(emptyList<Int>(), QueueNavigation.upNextInPlayerOrder(1, mapping, 2, next(false)))
        // a wrapping step stops once it comes back round instead of looping forever
        assertEquals(listOf(4, 2, 3), QueueNavigation.upNextInPlayerOrder(0, mapping, 0, next(true)))
    }

    @Test fun `repeat one still lets a skip move on`() {
        assertEquals(Player.REPEAT_MODE_OFF, QueueNavigation.navigationRepeatMode(Player.REPEAT_MODE_ONE))
        assertEquals(Player.REPEAT_MODE_ALL, QueueNavigation.navigationRepeatMode(Player.REPEAT_MODE_ALL))
    }

    private fun track(i: Int) = TrackResultDto(source = "soundcloud", sourceId = "$i", title = "T$i", artist = "A", album = null, durationSec = 180, thumbnailUrl = null, webpageUrl = "")

    @Test fun `next is enabled at the end of the queue with repeat all`() {
        val queue = (0..2).map(::track)
        val atEnd = PlaybackUiState(queue = queue, queueIndex = 2)
        assertFalse(atEnd.hasNext)
        assertTrue(atEnd.hasPrevious)
        assertTrue(atEnd.copy(repeatMode = Player.REPEAT_MODE_ALL).hasNext)
        assertEquals(emptyList<Int>(), atEnd.upNextIndices)
        assertEquals(listOf(1, 2), atEnd.copy(queueIndex = 0).upNextIndices)
    }

    @Test fun `with shuffle the ui uses the published shuffle order`() {
        val queue = (0..3).map(::track)
        val state = PlaybackUiState(queue = queue, queueIndex = 3, shuffleEnabled = true, shuffledUpNext = listOf(0, 2), shuffledHasPrevious = false)
        assertEquals(listOf(0, 2), state.upNextIndices)
        assertTrue(state.hasNext)
        assertFalse(state.hasPrevious)
        assertFalse(state.copy(shuffledUpNext = emptyList()).hasNext)
    }
}
