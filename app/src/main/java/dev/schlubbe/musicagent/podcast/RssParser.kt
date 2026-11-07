package dev.schlubbe.musicagent.podcast

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reads a podcast RSS 2.0 feed (with the iTunes extensions) into a show and its episodes.
 * A pull parser, so no DOCTYPE or external entity is ever resolved.
 */
object RssParser {

    private const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    private const val MAX_EPISODES = 1000

    fun parse(reader: Reader, showId: String, kind: ShowKind): ShowWithEpisodes {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(reader)
        return parse(parser, showId, kind)
    }

    /** From raw bytes, so the encoding in the XML declaration (ISO-8859-1 feeds exist) is honored. */
    fun parse(input: java.io.InputStream, showId: String, kind: ShowKind): ShowWithEpisodes {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(input, null)
        return parse(parser, showId, kind)
    }

    private fun parse(parser: XmlPullParser, showId: String, kind: ShowKind): ShowWithEpisodes {

        var title: String? = null
        var author: String? = null
        var image: String? = null
        var description: String? = null
        val items = mutableListOf<RawItem>()
        var item: RawItem? = null
        var inImage = false
        var sawChannel = false

        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG) {
                when {
                    parser.name == "item" && parser.namespace.isEmpty() -> {
                        item?.let { if (items.size < MAX_EPISODES) items += it }
                        item = null
                    }
                    parser.name == "image" && parser.namespace.isEmpty() -> inImage = false
                }
                continue
            }
            if (event != XmlPullParser.START_TAG) continue
            val ns = parser.namespace
            val name = parser.name
            if (ns.isEmpty() && name == "channel") sawChannel = true
            val current = item
            if (current == null) {
                when {
                    ns.isEmpty() && name == "item" -> item = RawItem()
                    ns.isEmpty() && name == "image" -> inImage = true
                    ns.isEmpty() && name == "url" && inImage -> image = image ?: parser.text()
                    ns.isEmpty() && name == "title" && !inImage -> title = title ?: parser.text()
                    ns.isEmpty() && name == "description" -> description = description ?: parser.text()
                    ns == ITUNES && name == "author" -> author = author ?: parser.text()
                    ns == ITUNES && name == "summary" -> description = description ?: parser.text()
                    ns == ITUNES && name == "image" -> parser.getAttributeValue(null, "href")?.let { image = it }
                }
            } else {
                when {
                    ns.isEmpty() && name == "title" -> current.title = parser.text()
                    ns.isEmpty() && name == "guid" -> current.guid = parser.text()
                    ns.isEmpty() && name == "link" -> current.link = parser.text()
                    ns.isEmpty() && name == "pubDate" -> current.pubDate = parser.text()
                    ns.isEmpty() && name == "description" -> current.description = current.description ?: parser.text()
                    ns == ITUNES && name == "summary" -> current.description = current.description ?: parser.text()
                    ns == ITUNES && name == "duration" -> current.duration = parser.text()
                    ns == ITUNES && name == "image" -> current.image = parser.getAttributeValue(null, "href")
                    ns.isEmpty() && name == "enclosure" -> {
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        val url = parser.getAttributeValue(null, "url")
                        if (url != null && current.audioUrl == null && (type.isEmpty() || type.startsWith("audio") || type.startsWith("video"))) {
                            current.audioUrl = url.trim()
                        }
                    }
                }
            }
        }
        require(sawChannel) { "kein RSS-Feed" }

        val showTitle = title?.trim().takeUnless { it.isNullOrEmpty() } ?: "Podcast"
        val showImage = image?.trim()?.takeIf(::isWebUrl)?.let(::secureUrl)
        val episodes = items.mapNotNull { raw ->
            val url = raw.audioUrl?.takeIf(::isWebUrl)?.let { TrackingPrefixes.strip(secureUrl(it)) } ?: return@mapNotNull null
            PodcastEpisode(
                showId = showId,
                showTitle = showTitle,
                title = raw.title?.trim().takeUnless { it.isNullOrEmpty() } ?: "Folge",
                description = raw.description?.let(::plainText),
                audioUrl = url,
                imageUrl = raw.image?.trim()?.takeIf(::isWebUrl)?.let(::secureUrl) ?: showImage,
                durationSec = raw.duration?.let(::parseDuration),
                publishedAt = raw.pubDate?.let(::parseDate),
                link = raw.link?.trim()?.takeIf(::isWebUrl),
            )
        }.distinctBy { it.audioUrl }.sortedByDescending { it.publishedAt ?: 0L }

        return ShowWithEpisodes(
            PodcastShow(
                id = showId,
                title = showTitle,
                author = author?.trim(),
                imageUrl = showImage,
                description = description?.let(::plainText),
                kind = kind,
                episodeCount = episodes.size,
            ),
            episodes,
        )
    }

    /** "3600", "61:05" or "1:01:05". */
    fun parseDuration(value: String): Int? {
        val parts = value.trim().split(':')
        if (parts.size > 3 || parts.any { it.isEmpty() }) return null
        var seconds = 0.0
        for (part in parts) seconds = seconds * 60 + (part.toDoubleOrNull() ?: return null)
        return seconds.toInt().takeIf { it > 0 }
    }

    /** RFC 822 dates as podcasts write them, including the common sloppy variants. */
    fun parseDate(value: String): Long? {
        val text = value.trim().replace(Regex("\\s+"), " ")
        runCatching { return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        for (pattern in listOf("EEE, d MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm zzz", "d MMM yyyy HH:mm:ss Z")) {
            runCatching {
                return ZonedDateTime.parse(text, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)).toInstant().toEpochMilli()
            }
        }
        return runCatching { ZonedDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    }

    /** Show notes are HTML; the app shows them as plain text. */
    fun plainText(html: String): String? = html
        .replace(Regex("(?i)<br\\s*/?>|</p>|</li>"), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
        .takeIf { it.isNotEmpty() }

    private fun XmlPullParser.text(): String? = runCatching { nextText() }.getOrNull()

    private class RawItem {
        var title: String? = null
        var guid: String? = null
        var link: String? = null
        var pubDate: String? = null
        var description: String? = null
        var duration: String? = null
        var image: String? = null
        var audioUrl: String? = null
    }
}
