package dev.schlubbe.musicagent.podcast

/**
 * Puts episodes in listening order. Publication date alone is not enough: series parts
 * often go out at the same minute ("Teil 1/2" and "Teil 2/2" on one day), and audiobook
 * feeds have no usable dates at all. Ties are broken by the part number in the title
 * (or the source's own episode number), then by the position in the source.
 */
object EpisodeOrder {

    private val PART_PATTERNS = listOf(
        // the running number first: "(1/2) – Folge 75" belongs between 74 and 76
        Regex("""(?i)\b(?:folge|teil|kapitel|episode|fall|band|case|ep\.?|nr\.?)\s*(\d{1,4})\b"""),
        Regex("""\((\d{1,4})\s*/\s*\d{1,4}\)"""),
        Regex("""(?i)\b(\d{1,4})\s*(?:von|of)\s*\d{1,4}\b"""),
        Regex("""\b(\d{1,4})\s*/\s*\d{1,4}\b"""),
        Regex("""#\s*(\d{1,4})\b"""),
    )

    private val OF_TOTAL = Regex("""\((\d{1,4})\s*/\s*(\d{1,4})\)""")

    /** One work in parts: (nearly) every title says "(n/M)" with the same M, as ARD
     * writes its audiobook readings; ARD sometimes mixes in a stray episode of another
     * reading, so 80 % is enough. Those are heard from part 1 on. */
    fun isOneWorkInParts(episodes: List<PodcastEpisode>): Boolean {
        if (episodes.size < 2) return false
        val totals = episodes.mapNotNull { OF_TOTAL.find(it.title)?.groupValues?.get(2) }
        val common = totals.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return false
        return common.value >= 2 && common.value >= episodes.size * 0.8
    }

    /** A numbered series: (nearly) every episode carries a number ("Folge 74"). */
    fun isNumbered(episodes: List<PodcastEpisode>, numbers: List<Int?> = emptyList()): Boolean {
        if (episodes.size < 2) return false
        val numbered = episodes.indices.count { numbers.getOrNull(it) != null || partNumber(episodes[it].title) != null }
        return numbered >= episodes.size * 0.8
    }

    /**
     * Whether a show is listened from episode 1 on (shown oldest first): audiobooks, one
     * work in parts, feeds that say so (itunes:type serial), and numbered Hörspiel series.
     * Talk and news podcasts stay newest first, numbered or not.
     */
    fun isSerial(kind: ShowKind, feedSaysSerial: Boolean, chronological: List<PodcastEpisode>, numbers: List<Int?> = emptyList()): Boolean =
        feedSaysSerial || kind == ShowKind.HOERBUCH || isOneWorkInParts(chronological) ||
            (kind == ShowKind.HOERSPIEL && isNumbered(chronological, numbers))

    /** The part or episode number written in a title, if any. */
    fun partNumber(title: String): Int? =
        PART_PATTERNS.firstNotNullOfOrNull { it.find(title)?.groupValues?.get(1)?.toIntOrNull() }

    /**
     * [episodes] in the order the source lists them; [sourceNewestFirst] says which way
     * that is (feeds usually list the newest first, serial feeds chapter 1 first).
     * [numbers] optionally gives the source's own episode numbers by index.
     * Returns the episodes oldest/first part first.
     */
    fun chronological(
        episodes: List<PodcastEpisode>,
        sourceNewestFirst: Boolean,
        numbers: List<Int?> = emptyList(),
    ): List<PodcastEpisode> {
        val keyed = episodes.mapIndexed { index, episode ->
            Keyed(
                episode,
                episode.publishedAt,
                numbers.getOrNull(index) ?: partNumber(episode.title),
                if (sourceNewestFirst) -index else index,
            )
        }
        val byDate = keyed.sortedWith(
            compareBy<Keyed, Long?>(nullsFirst()) { it.date }
                .thenBy(nullsLast()) { it.part }
                .thenBy { it.position },
        )
        // Parts released together are often stamped a minute apart in the wrong order
        // (ARD: "(1/2)" at 6:01, "(2/2)" at 6:00). Within a burst, the numbers decide.
        val out = ArrayList<Keyed>(byDate.size)
        var run = ArrayList<Keyed>()
        fun flush() {
            out += if (run.size > 1 && run.all { it.part != null }) run.sortedBy { it.part } else run
            run = ArrayList()
        }
        for (k in byDate) {
            val last = run.lastOrNull()
            if (last != null && (k.date == null || last.date == null || k.date - last.date > BURST_MS)) flush()
            run += k
        }
        flush()
        return out.map { it.episode }
    }

    private const val BURST_MS = 3 * 60 * 60 * 1000L

    private class Keyed(val episode: PodcastEpisode, val date: Long?, val part: Int?, val position: Int)
}
