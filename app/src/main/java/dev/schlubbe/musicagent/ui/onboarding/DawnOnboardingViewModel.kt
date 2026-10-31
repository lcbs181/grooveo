package dev.schlubbe.musicagent.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.schlubbe.musicagent.BuildConfig
import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.repository.FollowRepository
import dev.schlubbe.musicagent.data.repository.SearchRepository
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

/** One tile in the artist grid. [artist] is null while its photo is still being
 * looked up (the tile shows a placeholder with the name). */
data class ArtistPick(val name: String, val artist: ArtistResultDto?) {
    val key: String get() = artist?.let { "${it.source}:${it.sourceId}" } ?: "name:$name"
}

/** Dawn Music's first-run flow (welcome, name, genres, artists). Genres write
 * through to settings as they are tapped like the full app's onboarding; name and
 * artists are saved on [finish], where the picked artists are also followed so
 * they show up with their photos on Home right away. */
@HiltViewModel
class DawnOnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val searchRepository: SearchRepository,
    private val followRepository: FollowRepository,
) : ViewModel() {

    val name = MutableStateFlow("")

    val preferredGenres: StateFlow<Set<String>> = settingsRepository.preferredGenres
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _suggestions = MutableStateFlow<List<ArtistPick>>(emptyList())
    val suggestions: StateFlow<List<ArtistPick>> = _suggestions.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** null: not searching (grid shows suggestions); empty: no hits. */
    private val _searchResults = MutableStateFlow<List<ArtistPick>?>(null)
    val searchResults: StateFlow<List<ArtistPick>?> = _searchResults.asStateFlow()

    private val _selected = MutableStateFlow<List<ArtistPick>>(emptyList())
    val selected: StateFlow<List<ArtistPick>> = _selected.asStateFlow()

    /** Photo lookups by artist name, shared between genre changes. */
    private val resolved = mutableMapOf<String, ArtistResultDto?>()
    private val lookups = Semaphore(6)
    private var suggestionJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch { name.value = settingsRepository.profileName.first() }
    }

    private val _genreCovers = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Photo of each genre's best-known artist, shown tilted in the corner of the
     * genre tile like Spotify's tile artwork. */
    val genreCovers: StateFlow<Map<String, String>> = _genreCovers.asStateFlow()
    private var genreCoversJob: Job? = null

    /** Looks up the genre tiles' photos once. The lookups also warm [resolved],
     * so the artist step's first names already have their photos. */
    fun loadGenreCovers() {
        if (genreCoversJob != null) return
        genreCoversJob = viewModelScope.launch {
            ONBOARDING_GENRES.forEach { genre ->
                val artistName = ONBOARDING_ARTISTS[genre]?.firstOrNull() ?: return@forEach
                launch {
                    val hit = resolved[artistName] ?: lookups.withPermit { lookUp(artistName) }
                    resolved[artistName] = hit
                    hit?.thumbnailUrl?.let { _genreCovers.value = _genreCovers.value + (genre to it) }
                }
            }
        }
    }

    fun toggleGenre(genre: String) {
        viewModelScope.launch {
            val current = preferredGenres.value
            settingsRepository.setPreferredGenres(if (genre in current) current - genre else current + genre)
        }
    }

    /** Builds the grid from the picked genres (interleaved, so every genre shows
     * up near the top) and looks up each artist's photo. */
    fun loadSuggestions() {
        val genres = preferredGenres.value.ifEmpty { setOf("Pop", "Deutschrap", "Hip-Hop", "Elektronisch") }
        val lists = ONBOARDING_GENRES.filter { it in genres }.map { ONBOARDING_ARTISTS[it].orEmpty() }
        val names = buildList {
            val longest = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) lists.forEach { l -> l.getOrNull(i)?.let(::add) }
        }.distinctBy { it.lowercase() }.take(MAX_SUGGESTIONS)
        _suggestions.value = names.map { ArtistPick(it, resolved[it]) }
        suggestionJob?.cancel()
        suggestionJob = viewModelScope.launch {
            names.filterNot { it in resolved }.map { artistName ->
                async {
                    val hit = lookups.withPermit { lookUp(artistName) }
                    resolved[artistName] = hit
                    _suggestions.value = _suggestions.value.map { if (it.name == artistName) it.copy(artist = hit) else it }
                }
            }.awaitAll()
            // drop names the sources don't know rather than showing a photo-less tile
            _suggestions.value = _suggestions.value.filter { it.artist != null }
        }
    }

    private suspend fun lookUp(artistName: String): ArtistResultDto? = runCatching {
        searchRepository.searchArtists(artistName, source = "ytmusic", limit = 3)
            .firstOrNull { it.name.equals(artistName, ignoreCase = true) && hasPhoto(it) }
            ?: searchRepository.searchArtists(artistName, source = "all", limit = 3).firstOrNull { hasPhoto(it) }
    }.getOrNull()

    fun onQueryChange(text: String) {
        _query.value = text
        searchJob?.cancel()
        if (text.isBlank()) {
            _searchResults.value = null
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            val hits = runCatching { searchRepository.searchArtists(text.trim(), limit = 12) }.getOrDefault(emptyList())
            _searchResults.value = hits.filter { hasPhoto(it) }
                .distinctBy { it.name.lowercase() }
                .map { ArtistPick(it.name, it) }
        }
    }

    fun toggleArtist(pick: ArtistPick) {
        if (pick.artist == null) return
        val current = _selected.value
        _selected.value = if (current.any { it.key == pick.key }) current.filterNot { it.key == pick.key } else current + pick
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setProfileName(name.value.trim())
            val picks = _selected.value.mapNotNull { it.artist }
            val names = settingsRepository.preferredArtists.first().toMutableList()
            picks.forEach { a -> if (names.none { it.equals(a.name, ignoreCase = true) }) names += a.name }
            settingsRepository.setPreferredArtists(names)
            picks.forEach { a -> runCatching { followRepository.follow(a.source, a.sourceId, a.name, a.thumbnailUrl) } }
            settingsRepository.setLastSeenVersionCode(BuildConfig.VERSION_CODE)
            onDone()
        }
    }

    /** A real profile picture, not missing and not SoundCloud's grey default avatar. */
    private fun hasPhoto(a: ArtistResultDto): Boolean =
        a.thumbnailUrl != null && "default_avatar" !in a.thumbnailUrl

    private companion object {
        const val MAX_SUGGESTIONS = 36
    }
}

/** Well-known artists per onboarding genre - the artist step's starting grid. */
val ONBOARDING_ARTISTS: Map<String, List<String>> = mapOf(
    "Pop" to listOf("Taylor Swift", "Billie Eilish", "Dua Lipa", "The Weeknd", "Ed Sheeran", "Sabrina Carpenter", "Harry Styles", "Olivia Rodrigo"),
    "Hip-Hop" to listOf("Drake", "Kendrick Lamar", "Travis Scott", "Kanye West", "Future", "J. Cole"),
    "Rap" to listOf("Eminem", "Central Cee", "21 Savage", "Doja Cat", "Post Malone", "Lil Baby"),
    "Deutschrap" to listOf("Apache 207", "Luciano", "RAF Camora", "Shirin David", "Bonez MC", "Cro", "Ufo361", "Sido"),
    "R&B" to listOf("SZA", "Bruno Mars", "Frank Ocean", "Beyoncé", "Rihanna", "Daniel Caesar"),
    "Rock" to listOf("Linkin Park", "Imagine Dragons", "Arctic Monkeys", "Queen", "Red Hot Chili Peppers", "Foo Fighters"),
    "Indie" to listOf("Lana Del Rey", "Tame Impala", "The 1975", "Clairo", "Phoebe Bridgers", "Mac DeMarco"),
    "Elektronisch" to listOf("Fred again..", "Calvin Harris", "David Guetta", "Avicii", "Kygo", "Martin Garrix"),
    "House" to listOf("Fisher", "Purple Disco Machine", "Robin Schulz", "Meduza", "Disclosure", "Chris Lake"),
    "Techno" to listOf("Charlotte de Witte", "Amelie Lens", "Boris Brejcha", "Paul Kalkbrenner", "Peggy Gou", "ANNA"),
    "Drum & Bass" to listOf("Sub Focus", "Chase & Status", "Pendulum", "Wilkinson", "Netsky", "Hedex"),
    "Lo-Fi" to listOf("Nujabes", "Jinsang", "Idealism", "Kupla", "j'san", "Potsu"),
    "Chill" to listOf("Cigarettes After Sex", "Khruangbin", "Men I Trust", "Rhye", "Novo Amor", "Jeremy Zucker"),
    "Jazz" to listOf("Norah Jones", "Miles Davis", "John Coltrane", "Ella Fitzgerald", "Chet Baker", "Gregory Porter"),
    "Klassik" to listOf("Ludovico Einaudi", "Hans Zimmer", "Max Richter", "Wolfgang Amadeus Mozart", "Ludwig van Beethoven", "Johann Sebastian Bach"),
    "Metal" to listOf("Metallica", "Rammstein", "Slipknot", "Bring Me The Horizon", "Iron Maiden", "System Of A Down"),
    "Latin" to listOf("Bad Bunny", "Shakira", "KAROL G", "Rosalía", "J Balvin", "Peso Pluma"),
    "K-Pop" to listOf("BTS", "BLACKPINK", "Stray Kids", "NewJeans", "TWICE", "SEVENTEEN"),
    "Afrobeats" to listOf("Burna Boy", "Rema", "Wizkid", "Tems", "Davido", "Ayra Starr"),
    "Schlager" to listOf("Helene Fischer", "Andrea Berg", "Roland Kaiser", "Vanessa Mai", "Beatrice Egli", "Florian Silbereisen"),
)
