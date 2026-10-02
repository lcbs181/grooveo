package dev.schlubbe.musicagent.desktop.ui.screens

import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto

/** Result tabs of the search page. */
enum class SearchType(val label: String) { TRACKS("Titel"), ARTISTS("Künstler"), PLAYLISTS("Playlists"), ALBUMS("Alben") }

/** Source filter; [key] is the SearchRepository source string. */
enum class SearchSource(val key: String, val label: String) { ALL("all", "Alle Quellen"), SOUNDCLOUD("soundcloud", "SoundCloud"), YTMUSIC("ytmusic", "YouTube Music") }

/** Source filter options given which sources are enabled in the settings. */
fun availableSources(soundCloud: Boolean, youTube: Boolean): List<SearchSource> = when {
    soundCloud && youTube -> SearchSource.entries
    soundCloud -> listOf(SearchSource.SOUNDCLOUD)
    youTube -> listOf(SearchSource.YTMUSIC)
    else -> SearchSource.entries
}

data class SearchKey(val query: String, val source: SearchSource, val type: SearchType) {
    companion object {
        fun of(query: String, source: SearchSource, type: SearchType) = SearchKey(query.trim().lowercase(), source, type)
    }
}

/** Small LRU cache for search results (per query, source and tab). */
class SearchCache(private val max: Int = 32) {
    private val map = object : LinkedHashMap<SearchKey, List<Any>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SearchKey, List<Any>>?) = size > max
    }

    @Synchronized operator fun get(k: SearchKey): List<Any>? = map[k]
    @Synchronized operator fun set(k: SearchKey, v: List<Any>) { map[k] = v }
    @Synchronized fun clear() = map.clear()
    @Synchronized fun size() = map.size
}

/** Whether to show the suggestion dropdown. */
fun shouldShowSuggestions(text: String, submitted: String?, focused: Boolean, suggestions: List<String>): Boolean =
    focused && text.isNotBlank() && suggestions.isNotEmpty() && text.trim() != submitted?.trim()

/** Keyboard navigation through the suggestion list: -1 = the text field itself. */
fun moveSelection(current: Int, delta: Int, size: Int): Int = if (size == 0) -1 else (current + delta).coerceIn(-1, size - 1)

/** Top result: the first playable (non-DRM) track. */
fun topTrack(tracks: List<TrackResultDto>): TrackResultDto? = tracks.firstOrNull { !it.isDrmProtected } ?: tracks.firstOrNull()
