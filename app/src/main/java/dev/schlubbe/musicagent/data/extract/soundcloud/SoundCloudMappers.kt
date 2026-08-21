package dev.schlubbe.musicagent.data.extract.soundcloud

import com.google.gson.JsonObject
import dev.schlubbe.musicagent.data.remote.dto.AlbumResultDto
import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import java.net.URI

/** Field-for-field port of the (now-removed) backend's soundcloud_service.py mapping
 * logic, adapted for raw api-v2.soundcloud.com JSON (this talks to the API directly
 * over OkHttp, not through yt-dlp's normalized entry shape). */

internal fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull }?.asString

internal fun JsonObject.longOrNull(key: String): Long? =
    get(key)?.takeIf { !it.isJsonNull }?.asLong

// SoundCloud's user "visuals" (banner image) is an object with a "visuals" array -
// defensive throughout since this is undocumented API shape, not a stable contract;
// any mismatch just means no banner shows instead of a crash.
internal fun JsonObject.bannerUrlOrNull(): String? = runCatching {
    getAsJsonObject("visuals")
        ?.getAsJsonArray("visuals")
        ?.firstOrNull()
        ?.asJsonObject
        ?.stringOrNull("visual_url")
}.getOrNull()

private fun permalinkPath(webpageUrl: String): String =
    URI(webpageUrl).path.trimStart('/')

// SoundCloud image URLs all end in "-<size>.<ext>"; swap it for a larger size the
// same way yt-dlp's own thumbnail extraction does internally.
private fun upsizeImage(url: String?): String? {
    if (url == null) return null
    return Regex("-[0-9a-z]+\\.(jpg|png)$").replace(url) { "-t500x500.${it.groupValues[1]}" }
}

private fun formatCountSimple(n: Long): String = when {
    n >= 1_000_000 -> trimTrailingZero(n / 1_000_000.0) + "M"
    n >= 1_000 -> trimTrailingZero(n / 1_000.0) + "K"
    else -> n.toString()
}

private fun trimTrailingZero(value: Double): String {
    val formatted = "%.1f".format(value)
    return if (formatted.endsWith(".0")) formatted.dropLast(2) else formatted
}

private fun JsonObject.protocol(): String? =
    getAsJsonObject("format")?.get("protocol")?.takeIf { !it.isJsonNull }?.asString

// Same signal SoundCloudStreamResolver.resolve() uses to decide whether to throw
// SoundCloudDrmOnlyException, checked here up front instead - search/charts/artist
// track JSON is the same api-v2 track resource shape the resolver fetches, so
// media.transcodings is already present without a second network round-trip.
// True only when transcodings exist but none are plain hls/progressive (i.e. the
// resolver would fail on this track) - a track with no media info at all (private/
// deleted) is left false rather than guessed, same "no signal, no claim" convention
// used elsewhere in this file.
private fun JsonObject.isDrmOnly(): Boolean {
    val transcodings = getAsJsonObject("media")
        ?.getAsJsonArray("transcodings")
        ?.map { it.asJsonObject }
        ?: return false
    if (transcodings.isEmpty()) return false
    return transcodings.none { it.protocol() == "hls" || it.protocol() == "progressive" }
}

fun JsonObject.toSoundCloudTrackResultDto(): TrackResultDto? {
    val webpageUrl = stringOrNull("permalink_url") ?: return null
    val title = stringOrNull("title") ?: return null
    val user = getAsJsonObject("user")
    val durationMs = longOrNull("duration")

    return TrackResultDto(
        source = "soundcloud",
        sourceId = permalinkPath(webpageUrl),
        title = title,
        artist = user?.stringOrNull("username"),
        album = null,
        durationSec = durationMs?.let { (it / 1000).toInt() },
        thumbnailUrl = upsizeImage(stringOrNull("artwork_url") ?: user?.stringOrNull("avatar_url")),
        webpageUrl = webpageUrl,
        isDrmProtected = isDrmOnly(),
        // Real field on SoundCloud's track resource, previously discarded here - see
        // TrackEntity.genre for where this ends up cached. Blank strings ("" is a real
        // value SoundCloud sends for untagged tracks) are treated the same as absent.
        genre = stringOrNull("genre")?.takeIf { it.isNotBlank() },
    )
}

fun JsonObject.toSoundCloudPlaylistResultDto(): PlaylistResultDto? {
    val webpageUrl = stringOrNull("permalink_url") ?: return null
    val title = stringOrNull("title") ?: return null

    return PlaylistResultDto(
        source = "soundcloud",
        sourceId = permalinkPath(webpageUrl),
        title = title,
        thumbnailUrl = upsizeImage(stringOrNull("artwork_url")),
        trackCount = longOrNull("track_count")?.toInt(),
        owner = getAsJsonObject("user")?.stringOrNull("username"),
        webpageUrl = webpageUrl,
    )
}

fun JsonObject.toSoundCloudAlbumResultDto(): AlbumResultDto? {
    val webpageUrl = stringOrNull("permalink_url") ?: return null
    val title = stringOrNull("title") ?: return null

    return AlbumResultDto(
        source = "soundcloud",
        sourceId = permalinkPath(webpageUrl),
        title = title,
        artist = getAsJsonObject("user")?.stringOrNull("username"),
        thumbnailUrl = upsizeImage(stringOrNull("artwork_url")),
        // SoundCloud's playlist/album search response has no reliable release-year
        // field (unlike ytmusicapi's album entries) - left null rather than guessed.
        year = null,
        webpageUrl = webpageUrl,
    )
}

fun JsonObject.toSoundCloudArtistResultDto(): ArtistResultDto? {
    val permalink = stringOrNull("permalink") ?: return null
    val name = stringOrNull("username") ?: return null

    return ArtistResultDto(
        source = "soundcloud",
        sourceId = permalink,
        name = name,
        thumbnailUrl = upsizeImage(stringOrNull("avatar_url")),
        subscriberCount = longOrNull("followers_count")?.let { formatCountSimple(it) },
        webpageUrl = stringOrNull("permalink_url") ?: "https://soundcloud.com/$permalink",
    )
}
