package dev.schlubbe.musicagent.podcast

/**
 * Many feeds route their episode links through measurement services first
 * (`https://dts.podtrac.com/redirect.mp3/real.host/file.mp3`): each one logs the IP
 * address and device before redirecting to the actual file. The real address is part of
 * the link, so the app goes there directly and none of them learns what was heard.
 * Prefixes can be chained; they are taken off one after another.
 */
object TrackingPrefixes {

    // host (and path start) -> number of path segments after it that belong to the prefix
    private val PREFIXES: List<Pair<String, Int>> = listOf(
        "dts.podtrac.com/redirect.mp3/" to 0,
        "dts.podtrac.com/redirect.m4a/" to 0,
        "www.podtrac.com/pts/redirect.mp3/" to 0,
        "www.podtrac.com/pts/redirect.m4a/" to 0,
        "podtrac.com/pts/redirect.mp3/" to 0,
        "chrt.fm/track/" to 1,
        "chtbl.com/track/" to 1,
        "pdst.fm/e/" to 0,
        "pdcn.co/e/" to 0,
        "op3.dev/e/" to 0,
        "op3.dev/e," to 1, // options glued on: op3.dev/e,pg=…/host/file
        "mgln.ai/e/" to 1,
        "pfx.vpixl.com/" to 1,
        "arttrk.com/p/" to 1,
        "verifi.podscribe.com/rss/p/" to 0,
        "pscrb.fm/rss/p/" to 0,
        "prfx.byspotify.com/e/" to 0,
        "tracking.swap.fm/track/" to 1,
        "claritaspod.com/measure/" to 0,
        "growth-chartable.swap.fm/" to 0,
        "track.gnmedia.net/" to 0,
    )

    fun strip(url: String): String {
        var current = url
        repeat(MAX_ROUNDS) {
            val next = stripOnce(current) ?: return current
            current = next
        }
        return current
    }

    private fun stripOnce(url: String): String? {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "https" && scheme != "http") return null
        val rest = url.substringAfter("://")
        for ((prefix, extraSegments) in PREFIXES) {
            if (!rest.startsWith(prefix, ignoreCase = true)) continue
            var target = rest.substring(prefix.length)
            repeat(extraSegments) { target = target.substringAfter('/', "") }
            if (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) {
                target = target.substringAfter("://")
            }
            val host = target.substringBefore('/')
            // the remainder must look like host/path, or this was no prefix after all
            if (!host.contains('.') || target.length <= host.length + 1) return null
            return "https://$target"
        }
        return null
    }

    private const val MAX_ROUNDS = 5
}
