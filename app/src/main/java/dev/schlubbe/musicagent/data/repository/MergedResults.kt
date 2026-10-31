package dev.schlubbe.musicagent.data.repository

import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto

/** How Dawn Music merges the two sources into one list: a song
 * or an artist appears once, YouTube Music first, SoundCloud only for what
 * YouTube Music does not have. The full app keeps both sources side by side. */
object MergedResults {

    /** Uploader decorations that do not make a different recording. "(Live)" or
     * "(Remix)" do, so only these are ignored when comparing titles. */
    private val NOISE = Regex(
        "[(\\[][^)\\]]*(official|video|audio|lyrics?|visuali[sz]er|hd|4k|clip)[^)\\]]*[)\\]]",
        RegexOption.IGNORE_CASE,
    )

    internal fun trackKey(t: TrackResultDto): String {
        val title = NOISE.replace(t.title, "").lowercase().filter { it.isLetterOrDigit() }
        val artist = t.artist.orEmpty().lowercase()
            .replace(Regex("\\s*-\\s*topic$"), "")
            .split(",", "&", " x ", " feat", " ft.")
            .first().filter { it.isLetterOrDigit() }
        return "$title|$artist"
    }

    fun tracks(soundCloud: List<TrackResultDto>, youTube: List<TrackResultDto>): List<TrackResultDto> {
        val seen = HashSet<String>()
        return (youTube + soundCloud).filter { seen.add(trackKey(it)) }
    }

    /** YouTube Music artists first; a SoundCloud account only when YouTube Music
     * has no artist of that name and it is either a real audience (1,000+
     * followers) or exactly what was searched for - which keeps the fan and
     * namesake accounts ("DJ Kraftklub", 6 followers) out. */
    fun artists(query: String, soundCloud: List<ArtistResultDto>, youTube: List<ArtistResultDto>): List<ArtistResultDto> {
        val names = youTube.map { it.name.lowercase().trim() }.toMutableSet()
        val extra = soundCloud.filter { a ->
            val name = a.name.lowercase().trim()
            name !in names &&
                ((followers(a.subscriberCount) ?: 0) >= 1000 || name == query.lowercase().trim()) &&
                names.add(name)
        }
        return youTube + extra
    }

    /** "17.4K" / "1.2M" / "436" as written by the search clients. */
    internal fun followers(text: String?): Long? {
        val t = text?.trim()?.replace(",", ".") ?: return null
        val number = t.dropLastWhile { it.isLetter() }.toDoubleOrNull() ?: return null
        return when (t.lastOrNull()?.uppercaseChar()) {
            'K' -> (number * 1_000).toLong()
            'M' -> (number * 1_000_000).toLong()
            'B' -> (number * 1_000_000_000).toLong()
            else -> number.toLong()
        }
    }
}
