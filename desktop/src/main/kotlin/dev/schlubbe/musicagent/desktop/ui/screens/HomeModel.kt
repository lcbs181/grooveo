package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.repository.FeedResult
import dev.schlubbe.musicagent.desktop.data.key
import dev.schlubbe.musicagent.desktop.ui.AppUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Load state of one independently loaded Home shelf. */
sealed interface HomeLoad<out T> {
    data object Loading : HomeLoad<Nothing>
    data class Ready<T>(val value: T) : HomeLoad<T>
    data class Failed(val message: String) : HomeLoad<Nothing>
}

val <T> HomeLoad<T>.valueOrNull: T? get() = (this as? HomeLoad.Ready<T>)?.value

/**
 * Network-backed Home state. Lives as long as the app (kept per [AppUi]) so
 * returning to Home shows the last result instantly instead of re-fetching;
 * stale data (> [STALE_MS]) is refreshed in the background on re-entry.
 * Each shelf loads in its own coroutine — one failing shelf never breaks the page.
 */
class HomeModel(private val ui: AppUi) {
    private val g = ui.graph
    val logic = HomeLogic(RepoHomeSources(g.search, g.feed, g.settings))

    /** Raw trending pool; featured = literal top, charts = daily rotated window. */
    var trending by mutableStateOf<HomeLoad<List<TrackResultDto>>>(HomeLoad.Loading); private set
    var feed by mutableStateOf<HomeLoad<FeedResult>>(HomeLoad.Loading); private set
    var mixes by mutableStateOf<HomeLoad<List<HomeMix>>>(HomeLoad.Loading); private set
    var genreTracks by mutableStateOf<HomeLoad<List<TrackResultDto>>>(HomeLoad.Loading); private set
    var selectedGenre by mutableStateOf<String?>(null); private set
    var mixLoading by mutableStateOf(false); private set
    var refreshing by mutableStateOf(false); private set
    private var lastLoad = 0L
    private var genreJob: Job? = null
    private var jobs: List<Job> = emptyList()

    val featured: List<TrackResultDto> get() = trending.valueOrNull?.take(HomeLogic.FEATURED_CHART_COUNT).orEmpty()
    val charts: List<TrackResultDto>
        get() = trending.valueOrNull?.let { HomeLogic.rotateForDay(LocalDate.now(), it, HomeLogic.CHART_DISPLAY_COUNT) }.orEmpty()

    /** "Empfehlung des Tages": stable per day, prefers the personal feed over charts. */
    val dailyPick: TrackResultDto?
        get() = (feed.valueOrNull?.items?.map { it.track }.orEmpty().ifEmpty { charts }).takeIf { it.isNotEmpty() }
            ?.let { HomeLogic.pickForDay(LocalDate.now(), it) }

    fun dailyPickQueue(pick: TrackResultDto): List<TrackResultDto> {
        val f = feed.valueOrNull?.items?.map { it.track }.orEmpty()
        return if (f.any { it.key == pick.key }) f else charts
    }

    fun onEnter() {
        if (lastLoad == 0L || System.currentTimeMillis() - lastLoad > STALE_MS) refresh()
    }

    fun refresh() {
        lastLoad = System.currentTimeMillis()
        jobs.forEach { it.cancel() }
        val s = g.settings.current
        val genres = s.preferredGenres.toSet()
        val artists = s.preferredArtists
        refreshing = true
        jobs = listOf(
            load({ trending = it }, keep = trending) { g.search.getTrending(limit = HomeLogic.CHART_OVERFETCH_COUNT, source = "ytmusic") },
            load({ feed = it }, keep = feed) { g.feed.getFeed(preferredGenres = genres, preferredArtists = artists) },
            load({ mixes = it }, keep = mixes) { logic.mixCards(genres, artists) },
        )
        val chips = HomeLogic.genreChips(s.preferredGenres)
        selectGenre(selectedGenre?.takeIf { sel -> chips.any { it.equals(sel, true) } } ?: chips.first(), force = true)
        ui.scope.launch { jobs.forEach { it.join() }; refreshing = false }
    }

    fun selectGenre(genre: String, force: Boolean = false) {
        if (!force && genre == selectedGenre && genreTracks is HomeLoad.Ready) return
        selectedGenre = genre
        genreJob?.cancel()
        genreJob = load({ if (selectedGenre == genre) genreTracks = it }, keep = HomeLoad.Loading) { g.search.getTrendingByGenre(genre, limit = 8) }
    }

    /** Keeps showing the previous value while reloading (no skeleton flash on refresh). */
    private fun <T> load(set: (HomeLoad<T>) -> Unit, keep: HomeLoad<T>, block: suspend () -> T): Job {
        if (keep !is HomeLoad.Ready) set(HomeLoad.Loading)
        return ui.scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block() } }
            r.onSuccess { set(HomeLoad.Ready(it)) }
                .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) set(HomeLoad.Failed(e.message ?: e.javaClass.simpleName)) }
        }
    }

    fun startMood(mood: HomeMood) {
        if (mixLoading) return
        mixLoading = true
        ui.scope.launch {
            val fallback = charts + feed.valueOrNull?.items?.map { it.track }.orEmpty()
            val q = withContext(Dispatchers.IO) { logic.moodQueue(mood, fallback) }
            mixLoading = false
            if (q.isNotEmpty()) g.player.playQueue(q, 0) else ui.toast("Für diese Stimmung wurde nichts gefunden")
        }
    }

    fun startStation(artist: HomeArtist) {
        ui.toast("Sender „${artist.name}“ wird gestartet …")
        ui.scope.launch {
            val q = withContext(Dispatchers.IO) { logic.stationQueue(artist) }
            if (q.isNotEmpty()) g.player.playQueue(q, 0) else ui.toast("Sender konnte nicht gestartet werden")
        }
    }

    companion object {
        const val STALE_MS = 10 * 60_000L
        private val models = java.util.WeakHashMap<AppUi, HomeModel>()
        fun of(ui: AppUi): HomeModel = synchronized(models) { models.getOrPut(ui) { HomeModel(ui) } }
    }
}
