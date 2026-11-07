package dev.schlubbe.musicagent.data.artwork

import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult

/**
 * YouTube Music hands out small covers: search results come as Google image URLs
 * sized "=w120-h120-…" and radio/related tracks as video stills like
 * "…/hqdefault.jpg?sqp=…" resized to 336x188 - blurry on the player's large cover
 * and in the widget. Both hosts serve bigger renditions of the same image, so
 * [candidates] lists them, best first. SoundCloud is already asked for 500x500 at
 * mapping time (SoundCloudMappers.upsizeImage).
 */
object ArtworkUrls {
    private val googleSize = Regex("""=(?:[swh]\d+[^/?#]*)$""")
    private val ytimg = Regex("""^https://i\d?\.ytimg\.com/vi(?:_webp)?/([A-Za-z0-9_-]{6,})/[^/]+$""")

    /** Larger versions of [url] to try in order, ending with [url] itself. */
    fun candidates(url: String): List<String> {
        val clean = url.substringBefore('?')
        if (("googleusercontent.com" in url || "ggpht.com" in url) && googleSize.containsMatchIn(url)) {
            val big = url.replace(googleSize, "=w544-h544-l90-rj")
            return listOf(big, url).distinct()
        }
        ytimg.matchEntire(clean)?.let { m ->
            val id = m.groupValues[1]
            // maxres/hq720 are 16:9 without black bars but missing on some videos;
            // hqdefault (480x360) always exists
            return listOf(
                "https://i.ytimg.com/vi/$id/maxresdefault.jpg",
                "https://i.ytimg.com/vi/$id/hq720.jpg",
                "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                url,
            ).distinct()
        }
        return listOf(url)
    }

    /** The largest rendition that always exists, for places that cannot fall back
     * (media session / lock screen artwork). */
    fun safe(url: String?): String? {
        url ?: return null
        val c = candidates(url)
        return if (c.size == 1) url else c.firstOrNull { "googleusercontent" in it || "ggpht" in it || it.endsWith("/hqdefault.jpg") } ?: url
    }

    /** Coil interceptor: loads the first candidate that exists. Skipped for covers
     * kept offline ([isOffline]), which the OfflineCovers mapper serves from disk
     * under their original URL. */
    class CoilInterceptor(private val isOffline: (String) -> Boolean) : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val data = chain.request.data as? String ?: return chain.proceed(chain.request)
            if (isOffline(data)) return chain.proceed(chain.request)
            val list = candidates(data)
            if (list.size == 1) return chain.proceed(chain.request)
            var last: ImageResult? = null
            for (candidate in list) {
                val result = chain.proceed(chain.request.newBuilder().data(candidate).build())
                if (result !is ErrorResult) return result
                last = result
            }
            return last ?: chain.proceed(chain.request)
        }
    }
}
