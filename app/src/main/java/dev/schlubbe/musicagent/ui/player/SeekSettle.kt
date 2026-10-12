package dev.schlubbe.musicagent.ui.player

/** How far behind/ahead of the seek target the player's position may be and still count
 * as "arrived" - ahead allows for playback carrying on between seek and poll. */
private const val SEEK_ARRIVED_BEHIND_MS = 750L
private const val SEEK_ARRIVED_AHEAD_MS = 2_500L

/** After this long the polled position is shown regardless, so a seek the player
 * clamped or refused can't freeze the slider. */
private const val SEEK_SETTLE_TIMEOUT_MS = 2_000L

/**
 * Whether the Player screen's slider may go back to showing the polled position after
 * the user dropped it at [targetMs]. The seek travels to the service asynchronously, so
 * the first polls after a drag still report the old position - showing those made the
 * thumb jump back for a moment before landing where it was dropped.
 */
internal fun seekSettled(polledMs: Long, targetMs: Long, sinceSeekMs: Long): Boolean =
    sinceSeekMs >= SEEK_SETTLE_TIMEOUT_MS ||
        polledMs in (targetMs - SEEK_ARRIVED_BEHIND_MS)..(targetMs + SEEK_ARRIVED_AHEAD_MS)
