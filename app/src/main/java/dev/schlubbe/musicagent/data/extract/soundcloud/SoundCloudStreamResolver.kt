package dev.schlubbe.musicagent.data.extract.soundcloud

import com.google.gson.JsonObject
import dev.schlubbe.musicagent.data.extract.ResolvedStream
import dev.schlubbe.musicagent.data.extract.StreamResolver
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when a track's only playable-looking transcodings are DRM-encrypted
 * ("cbc-encrypted-hls"/"ctr-encrypted-hls") - this app has no Widevine license
 * exchange, so those are permanently unplayable, not a transient failure. Distinct
 * from a plain resolve error so [dev.schlubbe.musicagent.data.extract.StreamResolverRegistry]
 * doesn't waste a retry on something that will deterministically fail again, and so
 * the user can be told the real reason instead of a generic "nicht aufgelöst". */
class SoundCloudDrmOnlyException(message: String) : Exception(message)

/** Resolves a SoundCloud permalink (e.g. "artist/track-slug") to a real, playable
 * CDN URL. A track's `media.transcodings[]` entries each carry a metadata-fetch
 * `url` (not itself playable) plus `format.protocol`; fetching that url with the
 * client_id attached returns JSON whose own `.url` field is the final signed stream
 * URL. Mirrors the (now-removed) backend's resolver.py + SoundcloudBaseIE.
 * _extract_info_dict's format-selection logic, with one addition that backend never
 * needed to handle explicitly (yt-dlp just fails the same way ours used to):
 * some tracks - major-label content observed so far - list only DRM-encrypted
 * transcodings alongside plain "hls"/"progressive" ones whose signed-url fetch now
 * permanently 404s (confirmed via direct API reproduction: a valid, freshly-fetched
 * client_id changes nothing). Both plain candidates are tried before giving up, and
 * DRM-only transcodings are never selected in the first place - building a MediaItem
 * against an encrypted HLS manifest with no DRM config would just fail later, more
 * confusingly, at actual playback time instead of here. */
@Singleton
class SoundCloudStreamResolver @Inject constructor(
    private val api: SoundCloudApi,
) : StreamResolver {

    override fun supports(source: String): Boolean = source == "soundcloud"

    override suspend fun resolve(sourceId: String): ResolvedStream {
        val track = api.get("resolve", mapOf("url" to "https://soundcloud.com/$sourceId"))
        // A track that's private/deleted/geo-blocked/premium-only can resolve to a
        // JSON shape with no "media" or an empty "transcodings" list rather than an
        // HTTP error — fail with a clear reason instead of an opaque NPE so it's
        // distinguishable in logs from an actual network/client_id problem.
        val media = track.getAsJsonObject("media")
            ?: error("SoundCloud resolve for '$sourceId' returned no 'media' field")
        val transcodingsJson = media.getAsJsonArray("transcodings")
            ?: error("SoundCloud track '$sourceId' has no 'transcodings' field")
        val transcodings = transcodingsJson.map { it.asJsonObject }
        if (transcodings.isEmpty()) {
            error("SoundCloud track '$sourceId' has zero transcodings (private/deleted/geo-blocked/premium-only?)")
        }

        val candidates = listOfNotNull(
            transcodings.firstOrNull { it.protocol() == "hls" },
            transcodings.firstOrNull { it.protocol() == "progressive" },
        )
        if (candidates.isEmpty()) {
            throw SoundCloudDrmOnlyException(
                "SoundCloud track '$sourceId' only has DRM-encrypted transcodings - not playable on-device",
            )
        }

        var lastError: Throwable? = null
        for (chosen in candidates) {
            val transcodingUrl = chosen.get("url").asString
            val result = runCatching { api.get(transcodingUrl) }
            val streamInfo = result.getOrElse { e -> lastError = e; continue }
            val streamUrl = streamInfo.get("url")?.takeIf { !it.isJsonNull }?.asString
            if (streamUrl == null) {
                lastError = IllegalStateException("SoundCloud transcoding fetch for '$sourceId' returned no 'url' field")
                continue
            }
            return ResolvedStream(url = streamUrl, isHls = chosen.protocol() == "hls")
        }

        // Both plain candidates failed (typically both 404, the DRM-only pattern) -
        // if there WERE encrypted transcodings alongside these dead plain ones,
        // that's almost certainly why; say so rather than a generic error.
        val hasEncryptedTranscodings = transcodings.any { it.protocol()?.contains("encrypted") == true }
        if (hasEncryptedTranscodings) {
            throw SoundCloudDrmOnlyException(
                "SoundCloud track '$sourceId' has only dead plain transcodings alongside DRM-encrypted " +
                    "ones - not playable on-device",
            )
        }
        throw lastError ?: IllegalStateException("SoundCloud track '$sourceId' has no reachable stream")
    }

    private fun JsonObject.protocol(): String? =
        getAsJsonObject("format")?.get("protocol")?.takeIf { !it.isJsonNull }?.asString
}
