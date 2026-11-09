package dev.schlubbe.musicagent.podcast

// What the shared player and feed code needs to know about podcast tracks. Kept apart
// from the rest of the podcast package so the desktop app can compile it too.

/** Track source for podcast and Hörspiel episodes; the track's sourceId is the audio URL. */
const val PODCAST_SOURCE = "podcast"

/** Spoken-word sources: no radio continuation, no recommendations, no YouTube fallback. */
fun isPodcastSource(source: String?): Boolean = source == PODCAST_SOURCE

/** An episode whose "audio URL" is a YouTube watch page: resolved like a YouTube track,
 * and its stream address expires like one. */
fun isYouTubeEpisode(source: String?, sourceId: String): Boolean =
    isPodcastSource(source) && youTubeVideoId(sourceId) != null

/** The video id of a YouTube watch URL, or null. */
fun youTubeVideoId(url: String): String? =
    Regex("^https://(?:www\\.|m\\.|music\\.)?youtube\\.com/watch\\?(?:.*&)?v=([\\w-]{11})").find(url)?.groupValues?.get(1)
        ?: Regex("^https://youtu\\.be/([\\w-]{11})").find(url)?.groupValues?.get(1)

/** http(s) only; lenient like a browser (feeds do write spaces and umlauts into links). */
fun isWebUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (!trimmed.startsWith("https://", ignoreCase = true) && !trimmed.startsWith("http://", ignoreCase = true)) return false
    return okhttp3.HttpUrl.Companion.run { trimmed.toHttpUrlOrNull() } != null
}
