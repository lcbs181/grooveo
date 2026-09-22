package dev.schlubbe.musicagent.data.repository

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.schlubbe.musicagent.data.extract.di.ExtractionHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val TAG = "LyricsRepository"
private const val LRCLIB_BASE = "https://lrclib.net"

// LRCLIB asks callers to identify themselves with a descriptive User-Agent rather
// than a registered API key - there is no key at all for this API.
private const val USER_AGENT = "Grooveo (https://github.com/lcbs181/grooveo)"

/** One time-stamped line of synced lyrics, [timeMs] relative to track start. */
data class LyricLine(val timeMs: Long, val text: String)

/** [synced] is present only when LRCLIB returned a parseable `syncedLyrics` LRC
 * blob with at least one timed line; [plain] is the plain-text fallback. Both can
 * be non-null (LRCLIB usually returns both together) - callers should prefer
 * [synced] when it's there. */
data class Lyrics(val synced: List<LyricLine>?, val plain: String?)

/** Strips common track-title decorations ("(feat. X)", "[Official Video]",
 * "(Remastered 2009)", " - Live") before querying LRCLIB, since those rarely
 * appear in LRCLIB's own metadata and can make an otherwise-exact match miss. */
private val BRACKET_DECORATION = Regex(
    """[\(\[][^()\[\]]*?\b(feat\.?|ft\.?|featuring|official|video|lyrics?|remaster(ed)?|live|audio|explicit|clean|hd|mv|visualizer|remix|edit|mix)\b[^()\[\]]*?[\)\]]""",
    RegexOption.IGNORE_CASE,
)
private val DASH_DECORATION = Regex(
    """\s+-\s+(remaster(ed)?(\s+\d{4})?|live(\s+at\s+.+)?|official\s+video|lyrics?(\s+video)?|explicit|clean|mono|stereo|radio\s+edit|extended(\s+mix)?)\s*$""",
    RegexOption.IGNORE_CASE,
)
private val EXTRA_SPACES = Regex("""\s{2,}""")

private fun cleanTitle(title: String): String =
    title.replace(BRACKET_DECORATION, "")
        .replace(DASH_DECORATION, "")
        .replace(EXTRA_SPACES, " ")
        .trim()

// Matches one or more leading `[mm:ss]`/`[mm:ss.xx]`/`[mm:ss.xxx]` tags on an LRC
// line. Metadata tags like `[ar:Artist]`/`[ti:Title]` never match (the part
// before ':' isn't purely digits), so they're naturally skipped rather than
// needing an explicit denylist.
private val LRC_TIMESTAMP = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

/** Parses an LRC blob into timed lines, sorted by time. A line can carry more
 * than one timestamp tag (repeated lyrics reusing the same text) - each tag
 * produces its own [LyricLine]. Lines with no timestamp (metadata tags) or empty
 * text after the tags are dropped. */
private fun parseLrc(lrc: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    lrc.lineSequence().forEach { rawLine ->
        val matches = LRC_TIMESTAMP.findAll(rawLine).toList()
        if (matches.isEmpty()) return@forEach
        val text = rawLine.substring(matches.last().range.last + 1).trim()
        if (text.isEmpty()) return@forEach
        matches.forEach { match ->
            val minutes = match.groupValues[1].toInt()
            val seconds = match.groupValues[2].toInt()
            val fraction = match.groupValues[3]
            val millis = when (fraction.length) {
                0 -> 0
                1 -> fraction.toInt() * 100
                2 -> fraction.toInt() * 10
                else -> fraction.take(3).toInt()
            }
            lines += LyricLine(minutes * 60_000L + seconds * 1000L + millis, text)
        }
    }
    return lines.sortedBy { it.timeMs }
}

/** Fetches lyrics from LRCLIB (https://lrclib.net) - free, no API key. Tries the
 * exact-match `/api/get` endpoint first (cleaned title, then the raw title if
 * that finds nothing), then falls back to `/api/search` and picks the closest
 * duration match. Every outcome (including "nothing found") is cached in memory
 * per track key so re-opening the lyrics sheet, or a track looping back around,
 * doesn't re-hit the network. */
@Singleton
class LyricsRepository @Inject constructor(
    @ExtractionHttpClient private val client: OkHttpClient,
) {
    private val cache = mutableMapOf<String, Lyrics?>()
    private val cacheMutex = Mutex()

    suspend fun lyricsFor(title: String, artist: String?, durationSec: Int?): Lyrics? {
        val key = "$title\u0000${artist.orEmpty()}\u0000${durationSec ?: -1}"
        cacheMutex.withLock {
            if (cache.containsKey(key)) return cache.getValue(key)
        }
        val result = runCatching { fetchLyrics(title, artist.orEmpty(), durationSec) }
            .onFailure { Log.w(TAG, "lyricsFor failed for '$title'", it) }
            .getOrNull()
        cacheMutex.withLock { cache[key] = result }
        return result
    }

    private suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Lyrics? {
        val cleaned = cleanTitle(title)
        val candidateTitles = listOfNotNull(cleaned, title.takeIf { it != cleaned })

        candidateTitles.forEach { candidate ->
            getExact(candidate, artist, durationSec)?.let { return it }
        }
        candidateTitles.forEach { candidate ->
            searchFallback(candidate, artist, durationSec)?.let { return it }
        }
        return null
    }

    private suspend fun getExact(title: String, artist: String, durationSec: Int?): Lyrics? =
        withContext(Dispatchers.IO) {
            val urlBuilder = "$LRCLIB_BASE/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", artist)
            durationSec?.let { urlBuilder.addQueryParameter("duration", it.toString()) }
            val request = Request.Builder().url(urlBuilder.build()).header("User-Agent", USER_AGENT).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return@use null
                toLyrics(obj)
            }
        }

    private suspend fun searchFallback(title: String, artist: String, durationSec: Int?): Lyrics? =
        withContext(Dispatchers.IO) {
            val urlBuilder = "$LRCLIB_BASE/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", artist)
            val request = Request.Builder().url(urlBuilder.build()).header("User-Agent", USER_AGENT).build()

            val results = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                runCatching { JsonParser.parseString(body).asJsonArray }.getOrNull()
            } ?: return@withContext null

            results.mapNotNull { it.takeIf { el -> el.isJsonObject }?.asJsonObject }
                // Within ~10s of the known duration - close enough to be confident it's
                // the same recording, since LRCLIB has no other reliable identifier to
                // match on here. Kept unfiltered (any duration) when we don't know the
                // track's own duration.
                .filter { obj ->
                    val candidateDuration = obj.get("duration")?.takeIf { !it.isJsonNull }?.asInt
                    durationSec == null || (candidateDuration != null && abs(candidateDuration - durationSec) <= 10)
                }
                .sortedBy { obj ->
                    val candidateDuration = obj.get("duration")?.takeIf { !it.isJsonNull }?.asInt
                    if (durationSec != null && candidateDuration != null) abs(candidateDuration - durationSec) else 0
                }
                .firstNotNullOfOrNull { obj -> toLyrics(obj) }
        }

    /** Null for an instrumental track / one with neither field populated - callers
     * treat that the same as "not found" rather than an empty [Lyrics]. */
    private fun toLyrics(obj: JsonObject): Lyrics? {
        val syncedRaw = obj.get("syncedLyrics")?.takeIf { !it.isJsonNull }?.asString
        val plainRaw = obj.get("plainLyrics")?.takeIf { !it.isJsonNull }?.asString
        val synced = syncedRaw?.let { parseLrc(it) }?.takeIf { it.isNotEmpty() }
        val plain = plainRaw?.takeIf { it.isNotBlank() }
        return if (synced == null && plain == null) null else Lyrics(synced = synced, plain = plain)
    }
}
