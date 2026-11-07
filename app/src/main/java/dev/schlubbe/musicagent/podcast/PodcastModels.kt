package dev.schlubbe.musicagent.podcast

import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto

/** Track source for podcast and Hörspiel episodes; the track's sourceId is the audio URL. */
const val PODCAST_SOURCE = "podcast"

/** Spoken-word sources: no radio continuation, no recommendations, no YouTube fallback. */
fun isPodcastSource(source: String?): Boolean = source == PODCAST_SOURCE

enum class ShowKind(val key: String) {
    PODCAST("podcast"),
    HOERSPIEL("hoerspiel"),
    ;

    companion object {
        fun of(key: String?): ShowKind = entries.firstOrNull { it.key == key } ?: PODCAST
    }
}

/** Where a show's episodes come from: an RSS feed, or an ARD Audiothek program set
 * (many of those only exist there, their "feed" is an internal crid:// id). */
sealed class ShowId {
    // toString is the stored id; a data class would otherwise generate its own
    data class Rss(val feedUrl: String) : ShowId() {
        override fun toString(): String = "rss:$feedUrl"
    }

    data class Ard(val programSetId: String) : ShowId() {
        override fun toString(): String = "ard:$programSetId"
    }

    companion object {
        fun parse(value: String): ShowId? = when {
            value.startsWith("rss:") -> value.removePrefix("rss:").takeIf(::isWebUrl)?.let(::Rss)
            value.startsWith("ard:") -> value.removePrefix("ard:").takeIf { it.isNotBlank() && it.all(Char::isLetterOrDigit) }?.let(::Ard)
            else -> null
        }
    }
}

/** The app allows no cleartext traffic, and many older feeds still write http://
 * links that their hosts also serve over https. */
fun secureUrl(url: String): String {
    val trimmed = url.trim()
    return if (trimmed.startsWith("http://", ignoreCase = true)) "https://" + trimmed.substring(7) else trimmed
}

/** http(s) only; lenient like a browser (feeds do write spaces and umlauts into links). */
fun isWebUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (!trimmed.startsWith("https://", ignoreCase = true) && !trimmed.startsWith("http://", ignoreCase = true)) return false
    return okhttp3.HttpUrl.Companion.run { trimmed.toHttpUrlOrNull() } != null
}

data class PodcastShow(
    val id: String,
    val title: String,
    val author: String?,
    val imageUrl: String?,
    val description: String?,
    val kind: ShowKind,
    val episodeCount: Int? = null,
)

data class PodcastEpisode(
    val showId: String,
    val showTitle: String,
    val title: String,
    val description: String?,
    val audioUrl: String,
    val imageUrl: String?,
    val durationSec: Int?,
    val publishedAt: Long?,
    val link: String?,
) {
    fun toTrack(): TrackResultDto = TrackResultDto(
        source = PODCAST_SOURCE,
        sourceId = audioUrl,
        title = title,
        artist = showTitle,
        album = showId,
        durationSec = durationSec,
        thumbnailUrl = imageUrl,
        webpageUrl = link ?: audioUrl,
    )
}

data class ShowWithEpisodes(val show: PodcastShow, val episodes: List<PodcastEpisode>)
