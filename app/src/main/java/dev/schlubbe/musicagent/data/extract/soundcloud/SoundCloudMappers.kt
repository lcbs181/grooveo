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
    )
}
