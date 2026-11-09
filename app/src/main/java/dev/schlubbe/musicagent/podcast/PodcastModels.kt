package dev.schlubbe.musicagent.podcast

import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto

enum class ShowKind(val key: String) {
    PODCAST("podcast"),
    HOERSPIEL("hoerspiel"),
    HOERBUCH("hoerbuch"),
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

    /** A YouTube channel ("UC…") or playlist. */
    data class YouTube(val listId: String) : ShowId() {
        override fun toString(): String = "yt:$listId"
    }

    companion object {
        fun parse(value: String): ShowId? = when {
            value.startsWith("rss:") -> value.removePrefix("rss:").takeIf(::isWebUrl)?.let(::Rss)
            value.startsWith("ard:") -> value.removePrefix("ard:").takeIf { it.isNotBlank() && it.all(Char::isLetterOrDigit) }?.let(::Ard)
            value.startsWith("yt:") -> value.removePrefix("yt:").takeIf { it.length in 10..64 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }?.let(::YouTube)
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

/**
 * [episodes] in display order: newest first, or for a [serial] show (an audiobook, a
 * series meant to be heard from the start) part 1 first. Either way the episodes heard
 * after one are the ones published after it, see [following].
 */
data class ShowWithEpisodes(
    val show: PodcastShow,
    val episodes: List<PodcastEpisode>,
    val serial: Boolean = false,
) {
    val latestPublishedAt: Long? get() = episodes.mapNotNull { it.publishedAt }.maxOrNull()

    /** What comes after [index] in listening order, nearest first. */
    fun following(index: Int): List<PodcastEpisode> =
        if (serial) episodes.drop(index + 1) else episodes.take(index).asReversed()

    companion object {
        /** From episodes sorted oldest first. */
        fun of(show: PodcastShow, chronological: List<PodcastEpisode>, serial: Boolean) =
            ShowWithEpisodes(show, if (serial) chronological else chronological.asReversed(), serial)
    }
}
