package dev.schlubbe.musicagent.playback

/** How long the sleep timer fades playback out before it pauses. */
internal const val SLEEP_FADE_MS = 8_000L

/** How often the sleep fade updates the volume - fine enough not to step audibly. */
internal const val SLEEP_FADE_STEP_MS = 50L

/**
 * The player volume [elapsedMs] into the sleep timer's fade-out. Quadratic rather than
 * linear: player volume is a linear amplitude factor, and a linear ramp sounds as if
 * almost nothing happens until the last couple of seconds, then drops off a cliff.
 */
internal fun sleepFadeVolume(elapsedMs: Long, fadeMs: Long): Float {
    if (fadeMs <= 0L) return 0f
    val left = 1f - (elapsedMs.toFloat() / fadeMs).coerceIn(0f, 1f)
    return left * left
}
