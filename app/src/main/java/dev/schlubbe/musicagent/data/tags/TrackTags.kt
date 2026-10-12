package dev.schlubbe.musicagent.data.tags

/**
 * Tags of a track as the likes' tag chips show them: SoundCloud's genre plus its
 * `tag_list`, which separates tags by spaces and quotes those with spaces
 * (`"deep house" techno berlin`).
 */
object TrackTags {
    /** At most this many tags are kept per track. */
    const val MAX_PER_TRACK = 15

    fun parse(tagList: String?, genre: String? = null): List<String> {
        val raw = ArrayList<String>()
        genre?.let(raw::add)
        if (!tagList.isNullOrBlank()) {
            val re = Regex("\"([^\"]*)\"|(\\S+)")
            re.findAll(tagList).forEach { m -> raw += (m.groups[1]?.value ?: m.groups[2]!!.value) }
        }
        val seen = HashSet<String>()
        return raw.asSequence()
            .map { it.trim().trim('#', ',', ';').replace(Regex("\\s+"), " ") }
            // machine tags ("soundcloud:source=web-record") and noise are not tags anyone looks for
            .filter { it.length in 2..40 && ':' !in it && '=' !in it }
            .filter { seen.add(key(it)) }
            .take(MAX_PER_TRACK)
            .toList()
    }

    /** Tags that are the same apart from case, spaces, '-' and '_' count as one. */
    fun key(tag: String): String = tag.lowercase().replace(Regex("[\\s_-]+"), "")

    /** Stored form: one tag per line. */
    fun join(tags: List<String>): String = tags.joinToString("\n")
    fun split(stored: String): List<String> = stored.split('\n').filter { it.isNotBlank() }

    /**
     * The chips over a list: the most frequent tags of [tagLists] (one list per track), each
     * named as it is spelled most often, most frequent first, at most [limit].
     */
    fun top(tagLists: Collection<List<String>>, limit: Int = 40): List<String> {
        val counts = HashMap<String, Int>()
        val spellings = HashMap<String, HashMap<String, Int>>()
        for (tags in tagLists) for (t in tags.distinctBy(::key)) {
            val k = key(t)
            counts.merge(k, 1, Int::plus)
            spellings.getOrPut(k) { HashMap() }.merge(t, 1, Int::plus)
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            // on a tie the spelling that sorts first, so "Deep House" wins over "deep-house"
            .map { e -> spellings[e.key]!!.maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key }).key }
    }

    fun has(tags: List<String>?, tag: String): Boolean = tags?.any { key(it) == key(tag) } == true
}
