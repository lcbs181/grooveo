package dev.schlubbe.musicagent.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class PlayNextShuffleOrderTest {

    private fun PlayNextShuffleOrder.walk(): List<Int> {
        val out = ArrayList<Int>()
        var i = firstIndex
        while (i != C.INDEX_UNSET) { out += i; i = getNextIndex(i) }
        return out
    }

    @Test
    fun `initial order is a permutation and walks consistently`() {
        val o = PlayNextShuffleOrder(20, Random(1))
        assertEquals((0 until 20).toList(), o.order().sorted())
        assertEquals(o.order(), o.walk())
        assertEquals(o.order().last(), o.lastIndex)
    }

    @Test
    fun `play next lands right after the current item`() {
        var o = PlayNextShuffleOrder(10, Random(2))
        val current = o.order()[3]
        PlayNextShuffleOrder.playNextAt(current + 1)
        o = o.cloneAndInsert(current + 1, 1) as PlayNextShuffleOrder
        assertEquals(current + 1, o.getNextIndex(current))
        assertEquals((0 until 11).toList(), o.order().sorted())
    }

    @Test
    fun `play next is used once, later inserts are random`() {
        var o = PlayNextShuffleOrder(10, Random(3))
        PlayNextShuffleOrder.playNextAt(5)
        o = o.cloneAndInsert(5, 1) as PlayNextShuffleOrder
        val next = (0 until 30).map {
            o = o.cloneAndInsert(5, 1) as PlayNextShuffleOrder
            o.getNextIndex(4)
        }
        assert(next.any { it != 5 }) { "every insert went after item 4: $next" }
    }

    @Test
    fun `an announcement for another index is not applied`() {
        val o = PlayNextShuffleOrder(10, Random(4))
        PlayNextShuffleOrder.playNextAt(7)
        o.cloneAndInsert(3, 1)
        // still pending for index 7
        val after = o.cloneAndInsert(7, 1)
        assertEquals(7, after.getNextIndex(6))
    }

    @Test
    fun `remove shifts later indices down`() {
        val o = PlayNextShuffleOrder(8, Random(5))
        val r = o.cloneAndRemove(2, 5) as PlayNextShuffleOrder
        assertEquals((0 until 5).toList(), r.order().sorted())
        val expected = o.order().filter { it !in 2 until 5 }.map { if (it >= 5) it - 3 else it }
        assertEquals(expected, r.order())
    }
}
