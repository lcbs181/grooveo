package dev.schlubbe.musicagent.desktop.ui.screens

/** Sleep-timer presets in minutes (Android SLEEP_TIMER_PRESETS_MIN). */
val SLEEP_TIMER_PRESETS = listOf(15, 30, 45, 60, 90)

/** Remaining sleep-timer time as "m:ss" (or "h:mm:ss"); null when no timer runs or it has elapsed. */
fun formatSleepRemaining(endAt: Long?, now: Long): String? {
    if (endAt == null) return null
    val ms = endAt - now
    if (ms <= 0) return null
    val s = (ms + 999) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
