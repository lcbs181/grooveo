package dev.schlubbe.musicagent.playback

import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * Pure queue-order rules shared by [PlayerController] (what Next/Previous do) and the UI
 * (what "Als Nächstes" shows and whether the skip buttons are enabled), kept free of any
 * player instance so they can be unit tested.
 *
 * Two orders exist. With shuffle off, navigation walks the *logical* queue one slot at a
 * time - including slots that have nothing loaded in the player (a DRM-only track, or one
 * the background fill has not reached yet), which is how such a slot gets shown as
 * "Titel nicht verfügbar" instead of being skipped silently. With shuffle on, the order is
 * ExoPlayer's own shuffle order - the same one the notification, the lock screen and
 * [CrossfadeController] follow - so the app's own buttons and list can't disagree with
 * them. That order only knows loaded items, so unloaded slots are not part of it.
 */
internal object QueueNavigation {

    /** Next logical slot with shuffle off: one further, wrapping only with repeat-all. */
    fun logicalNext(current: Int, queueSize: Int, repeatMode: Int): Int? {
        if (current < 0 || queueSize == 0) return null
        val next = current + 1
        return when {
            next < queueSize -> next
            repeatMode == Player.REPEAT_MODE_ALL && queueSize > 1 -> 0
            else -> null
        }
    }

    /** Previous logical slot with shuffle off: one back, wrapping only with repeat-all. */
    fun logicalPrevious(current: Int, queueSize: Int, repeatMode: Int): Int? {
        if (current < 0 || queueSize == 0) return null
        val previous = current - 1
        return when {
            previous >= 0 -> previous
            repeatMode == Player.REPEAT_MODE_ALL && queueSize > 1 -> queueSize - 1
            else -> null
        }
    }

    /** Repeat-one only loops on its own; a skip still moves on - same as
     * ExoPlayer's own seekToNextMediaItem(), which treats it as repeat-off. */
    fun navigationRepeatMode(repeatMode: Int): Int =
        if (repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else repeatMode

    /**
     * The logical slot reached by following the player's own order from [anchorExo] -
     * [step] is the timeline's getNextWindowIndex/getPreviousWindowIndex for the wanted
     * repeat/shuffle mode, returning [C.INDEX_UNSET] at the end. Player items that map to
     * no logical slot (or back to [currentLogical]) are stepped over.
     */
    fun neighbourInPlayerOrder(
        anchorExo: Int,
        exoForLogical: List<Int?>,
        currentLogical: Int,
        step: (Int) -> Int,
    ): Int? {
        val logicalForExo = invert(exoForLogical)
        val seen = HashSet<Int>()
        var exo = anchorExo
        while (seen.add(exo)) {
            exo = step(exo)
            if (exo == C.INDEX_UNSET) return null
            val logical = logicalForExo[exo] ?: continue
            if (logical != currentLogical) return logical
        }
        return null
    }

    /** Every logical slot still to come after [anchorExo] in the player's order, in that
     * order - [step] must not wrap (repeat off), or the list would never end. */
    fun upNextInPlayerOrder(
        anchorExo: Int,
        exoForLogical: List<Int?>,
        currentLogical: Int,
        step: (Int) -> Int,
    ): List<Int> {
        val logicalForExo = invert(exoForLogical)
        val result = ArrayList<Int>()
        val seen = HashSet<Int>()
        var exo = anchorExo
        while (seen.add(exo)) {
            exo = step(exo)
            if (exo == C.INDEX_UNSET) break
            val logical = logicalForExo[exo] ?: continue
            if (logical != currentLogical) result += logical
        }
        return result
    }

    private fun invert(exoForLogical: List<Int?>): Map<Int, Int> {
        val map = HashMap<Int, Int>(exoForLogical.size * 2)
        exoForLogical.forEachIndexed { logical, exo -> if (exo != null) map[exo] = logical }
        return map
    }
}

/** Logical queue indices shown under "Als Nächstes", in the order they will play. */
val PlaybackUiState.upNextIndices: List<Int>
    get() = shuffledUpNext?.filter { it in queue.indices && it != queueIndex }
        ?: if (queueIndex >= 0) (queueIndex + 1 until queue.size).toList() else emptyList()

/** With repeat-all a skip wraps around the queue's ends, so the buttons stay enabled. */
private val PlaybackUiState.wrapsAround: Boolean
    get() = repeatMode == Player.REPEAT_MODE_ALL && queue.size > 1

/** Whether the app's Next button does anything - mirrors PlayerController.skipToNext. */
val PlaybackUiState.hasNext: Boolean
    get() = queueIndex >= 0 && (wrapsAround || upNextIndices.isNotEmpty())

/** Whether the app's Previous button does anything - mirrors PlayerController.skipToPrevious. */
val PlaybackUiState.hasPrevious: Boolean
    get() = queueIndex >= 0 && (wrapsAround || if (shuffledUpNext != null) shuffledHasPrevious else queueIndex > 0)
