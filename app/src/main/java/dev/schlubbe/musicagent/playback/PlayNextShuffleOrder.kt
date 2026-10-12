package dev.schlubbe.musicagent.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The player's shuffle order, like Media3's DefaultShuffleOrder - except that an insert
 * announced with [PlayNextShuffleOrder.playNextAt] goes right after the item before it,
 * instead of to a random place.
 *
 * "Zur Warteschlange hinzufügen" inserts a track behind the current one in the player's
 * list. With shuffle on, the default order would then put it anywhere among the
 * remaining tracks - possibly at the very end of a long playlist. With this order it is
 * the next track, with shuffle on or off. Every other insert (the background fill of a
 * queue, the endless-play batches) is still shuffled randomly.
 */
internal class PlayNextShuffleOrder private constructor(
    private val shuffled: IntArray,
    private val random: Random,
) : ShuffleOrder {

    constructor(length: Int = 0, random: Random = Random.Default) :
        this(IntArray(length) { it }.also { shuffleInPlace(it, random) }, random)

    private val positionOf = IntArray(shuffled.size).also { pos -> shuffled.forEachIndexed { i, v -> pos[v] = i } }

    /** The order as a list of player indices, for tests. */
    fun order(): List<Int> = shuffled.toList()

    override fun getLength(): Int = shuffled.size

    override fun getNextIndex(index: Int): Int {
        val p = positionOf[index] + 1
        return if (p < shuffled.size) shuffled[p] else C.INDEX_UNSET
    }

    override fun getPreviousIndex(index: Int): Int {
        val p = positionOf[index] - 1
        return if (p >= 0) shuffled[p] else C.INDEX_UNSET
    }

    override fun getLastIndex(): Int = shuffled.lastOrNull() ?: C.INDEX_UNSET

    override fun getFirstIndex(): Int = shuffled.firstOrNull() ?: C.INDEX_UNSET

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        // existing items at or after the insertion point move up in the player's list
        val shifted = shuffled.map { if (it >= insertionIndex) it + insertionCount else it }.toMutableList()
        val inserted = (insertionIndex until insertionIndex + insertionCount).toList()
        if (consumePlayNext(insertionIndex) && insertionIndex > 0 && shifted.isNotEmpty()) {
            val after = shifted.indexOf(insertionIndex - 1)
            shifted.addAll(after + 1, inserted)
        } else {
            // DefaultShuffleOrder's rule: each new item at a random place
            for (index in inserted) shifted.add(random.nextInt(shifted.size + 1), index)
        }
        return PlayNextShuffleOrder(shifted.toIntArray(), random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        val kept = shuffled.filter { it !in indexFrom until indexToExclusive }
            .map { if (it >= indexToExclusive) it - count else it }
        return PlayNextShuffleOrder(kept.toIntArray(), random)
    }

    override fun cloneAndClear(): ShuffleOrder = PlayNextShuffleOrder(IntArray(0), random)

    companion object {
        private val pendingPlayNext = AtomicInteger(-1)

        /**
         * Announces that the next insert at player index [index] is a "play next": it
         * lands right after the item at [index] - 1 in the shuffle order. Call this just
         * before the matching addMediaItem; the player applies it when the insert arrives.
         */
        fun playNextAt(index: Int) = pendingPlayNext.set(index)

        private fun consumePlayNext(index: Int): Boolean = pendingPlayNext.compareAndSet(index, -1)

        private fun shuffleInPlace(a: IntArray, random: Random) {
            for (i in a.size - 1 downTo 1) {
                val j = random.nextInt(i + 1)
                val t = a[i]; a[i] = a[j]; a[j] = t
            }
        }
    }
}
