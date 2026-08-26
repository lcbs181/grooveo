package dev.schlubbe.musicagent.data.repository

import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto

/**
 * Keeps explicit/adult content out of surfaces the app itself picks for the
 * user - Home's charts, mood mixes, genre shelves, the personalized feed, and the
 * artist "station" - as opposed to a search the user typed themselves.
 *
 * There is no upstream signal to lean on: neither SoundCloud's nor YouTube Music's
 * on-device extraction exposes an explicit/adult flag on [TrackResultDto] (see its
 * own field comments), and running an actual image classifier against every
 * candidate thumbnail is not viable on-device for a shelf that has to load
 * instantly. What SoundCloud's unmoderated upload pipeline does reliably produce
 * is *textual* co-occurrence: an upload with explicit cover art is, in practice,
 * tagged and titled explicitly too, because that is how it ends up surfaced by a
 * generic keyword search ("party music") in the first place - the same words that
 * put a track in front of the algorithm are the words that describe it. So this
 * filters on title/artist text rather than image content, which is cheap, has no
 * false negatives on the specific failure mode observed (an NSFW cover surfacing
 * on the Home "Party" mix), and costs nothing to run inline before a shelf builds.
 *
 * Deliberately does not touch [SearchRepository.search] itself: a user who types
 * an explicit query into the search box is asking for it directly, and second-
 * guessing that is a different, unrequested behavior change. This only wraps the
 * *algorithmic* entry points - [SearchRepository.getTrending],
 * [SearchRepository.getTrendingByGenre], and the specific mood/station/feed
 * searches in [dev.schlubbe.musicagent.ui.home.HomeViewModel] and
 * [FeedRepository] that pick content on the user's behalf without them having
 * typed anything.
 *
 * Substring matching on title/artist text is a blunt instrument - it cannot see
 * what a thumbnail actually shows, and it can just as easily hide a legitimate
 * track whose title or artist name happens to contain a matched substring in an
 * unrelated context. Both directions are real failure modes for other users on
 * other libraries, which is why this is gated by
 * [SettingsRepository.contentSafetyFilterCached] (Startseite personalisieren)
 * rather than being unconditional - every call site passes that flag in.
 */
private val EXPLICIT_TERMS = listOf(
    "porn", "pornhub", "xvideos", "xnxx", "xhamster", "onlyfans",
    "nsfw", "18+", "hentai", "camgirl", "cam girl", "sex tape", "creampie",
    "nackt", "nudes", "nude ", " nude", "striptease", "escort service",
    "fap", "jerk off", "cumshot", "blowjob", "handjob", "deepthroat",
    "anal sex", "gangbang", "bdsm", "fetish porn", "sextape",
)

/** True when nothing in [title]/[artist] matches [EXPLICIT_TERMS] - the allow-list
 * default, so a track with no match (the overwhelming majority) is never held back
 * by a false negative in the blocklist. Substring match on lowercased text: cheap,
 * and the blocklist is deliberately worded to avoid catching ordinary music terms. */
private fun isSafeText(title: String, artist: String?): Boolean {
    val haystack = buildString {
        append(title.lowercase())
        artist?.let { append(' '); append(it.lowercase()) }
    }
    return EXPLICIT_TERMS.none { haystack.contains(it) }
}

/** Filters [TrackResultDto.title]/[TrackResultDto.artist] against
 * [EXPLICIT_TERMS] when [enabled] - see the file-level kdoc for what this does and
 * does not cover, and why it's togglable. Apply at an algorithmic discovery
 * surface, never at the user's own search. */
fun List<TrackResultDto>.filterForDiscovery(enabled: Boolean): List<TrackResultDto> =
    if (enabled) filter { isSafeText(it.title, it.artist) } else this
