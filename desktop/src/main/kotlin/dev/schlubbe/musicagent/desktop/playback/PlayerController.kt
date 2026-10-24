package dev.schlubbe.musicagent.desktop.playback

import dev.schlubbe.musicagent.desktop.audio.TransitionStatus
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.drop
import dev.schlubbe.musicagent.data.extract.ResolvedStream
import dev.schlubbe.musicagent.data.extract.soundcloud.SoundCloudDrmOnlyException
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import dev.schlubbe.musicagent.desktop.audio.AudioEngine
import dev.schlubbe.musicagent.desktop.audio.AudioSource
import dev.schlubbe.musicagent.desktop.audio.EngineListener
import dev.schlubbe.musicagent.desktop.audio.Sound3dPreset
import dev.schlubbe.musicagent.desktop.data.LibraryStore
import dev.schlubbe.musicagent.desktop.data.key
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import dev.schlubbe.musicagent.desktop.data.TrackAnalysis

enum class RepeatMode(val label: String) { OFF("Wiederholen: aus"), ALL("Wiederholen: alle"), ONE("Wiederholen: ein Titel") }

data class QueueState(
    val queue: List<TrackResultDto> = emptyList(),
    val index: Int = -1,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val resolving: Boolean = false,
    val error: String? = null,
    val sleepTimerEndAt: Long? = null,
    val playingLocalCopy: Boolean = false,
) {
    val current: TrackResultDto? get() = queue.getOrNull(index)
    val upNext: List<TrackResultDto> get() = if (index < 0) queue else queue.drop(index + 1)
}

/**
 * Queue, stream resolution and playback policy on top of [AudioEngine] — the
 * desktop counterpart of the Android PlayerController: shuffle/repeat, gapless
 * preloading, crossfade with analysed mix points, "Automatische Weiterempfehlung"
 * radio via the shared FeedRepository.predictNext, sleep timer, history and
 * listening statistics.
 *
 * Called from many threads (UI, MPRIS, MCP, the phone bridge, the assistant, its own
 * jobs): queue changes and the bookkeeping around them run under [lock], and engine
 * events are handled one after another on [events], never on the mixer thread.
 */
class PlayerController(
    private val engine: AudioEngine,
    /** Stream resolution (StreamResolverRegistry.resolveWithFallback in the app). */
    private val resolveRemote: suspend (TrackResultDto) -> ResolvedStream,
    /** Radio recommendations (FeedRepository.predictNext in the app). */
    private val recommend: suspend (recent: List<TrackResultDto>, exclude: Set<String>, limit: Int) -> List<TrackResultDto>,
    private val store: LibraryStore,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** "Übergänge analysieren" for one track, storing the result (AppGraph.analyze in the app). */
    private val analyze: suspend (TrackResultDto) -> TrackAnalysis? = { null },
) {
    /** Guards the queue, the jobs and the counters below; held only briefly, never across a suspension. */
    private val lock = Any()
    /** Engine callbacks, handled in the order the engine sent them. */
    private val events = Dispatchers.Default.limitedParallelism(1)
    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()
    val engineState get() = engine.state
    val spectrum get() = engine.spectrum

    private var unshuffled: List<TrackResultDto>? = null
    private var playJob: Job? = null
    private var preloadJob: Job? = null
    /** Bumped for every preload or transition request: a slower, older one must not write its result. */
    private val preloadGen = AtomicLong()
    /** Current and next track keys the running (or finished) preload is for. */
    @Volatile private var preloadTarget: Pair<String?, String>? = null
    private var sleepJob: Job? = null
    private var radioJob: Job? = null
    private var consecutiveFailures = 0
    /** Stream retries for the current track (reset when a track starts), see [onPlaybackError]. */
    private var streamRetries = 0
    @Volatile private var retrying = false
    /** Transition wishes for the transition into a track (by track key), e.g. from the AI. */
    private val recipes = ConcurrentHashMap<String, TransitionRecipe>()
    private val _planned = MutableStateFlow<PlannedTransition?>(null)
    /** The transition planned into the next track, if any. */
    val planned: StateFlow<PlannedTransition?> = _planned.asStateFlow()
    private val _planNote = MutableStateFlow<String?>(null)
    /** Why no transition is planned into the next track (DJ mode on), or that it is still being prepared. */
    val planNote: StateFlow<String?> = _planNote.asStateFlow()
    val transition get() = engine.transition

    init {
        engine.listener = object : EngineListener {
            // the mixer thread only hands these over: library writes and planning must not hold up the audio
            override fun onStarted(source: AudioSource, automatic: Boolean) { scope.launch(events) { synchronized(lock) { onEngineStarted(source, automatic) } } }
            override fun onEnded(source: AudioSource) { scope.launch(events) { onEngineEnded() } }
            override fun onError(source: AudioSource, message: String) { scope.launch(events) { onPlaybackError(source, message) } }
        }
        scope.launch {
            settings.state.distinctUntilChangedBy { listOf(it.eq, it.sound3dPreset, it.crossfadeSeconds, it.loudnessNormalization, it.fxChain, it.djTransitions) }.collect { s ->
                engine.masterFx.set(s.fxChain)
                engine.setEq(s.eq)
                engine.setReverb(Sound3dPreset.of(s.sound3dPreset))
                // DJ mode falls back to a crossfade where it cannot plan (no beat found), never to a gap
                engine.crossfadeSec = if (s.djTransitions && s.crossfadeSeconds <= 0) DJ_FALLBACK_CROSSFADE_SEC else s.crossfadeSeconds
                engine.normalize = s.loudnessNormalization != false
            }
        }
        // a changed DJ setting applies to the very next track, not the one after it
        scope.launch {
            settings.state.map { listOf(it.djTransitions, it.djStyle, it.djHarmonic, it.crossfadeSeconds) }.distinctUntilChanged().drop(1).collect {
                val phase = engine.transition.value.phase
                if (phase == TransitionStatus.Phase.NONE || phase == TransitionStatus.Phase.ARMED) schedulePreload(force = true)
            }
        }
        engine.volume = settings.current.volume
        // the volume also changes without setVolume (undo, update_settings)
        scope.launch {
            settings.state.map { it.volume }.distinctUntilChanged().collect { engine.volume = it.coerceIn(0f, 1f) }
        }
        // listening statistics: count frames that really reached the speakers
        scope.launch {
            var last = engine.playedFrames
            while (isActive) {
                delay(15_000)
                val sec = (engine.playedFrames - last) / 48_000
                if (sec > 0) store.addListenSeconds(sec)
                last += sec * 48_000 // the remainder counts towards the next round
            }
        }
    }

    // ---------------- public API ----------------

    fun playQueue(tracks: List<TrackResultDto>, startIndex: Int = 0, shuffle: Boolean = false) {
        synchronized(lock) {
            if (tracks.isEmpty()) return
            var list = tracks.distinctBy { it.key }
            var start = startIndex.coerceIn(0, list.size - 1)
            unshuffled = null
            if (shuffle) {
                unshuffled = list
                val first = if (startIndex in tracks.indices) list[start] else list.random()
                list = listOf(first) + (list - first).shuffled()
                start = 0
            }
            _state.update { it.copy(queue = list, index = start, shuffle = shuffle, error = null) }
            startCurrent()
        }
    }

    fun playTrack(track: TrackResultDto) = playQueue(listOf(track))

    fun addToQueue(tracks: List<TrackResultDto>) {
        synchronized(lock) {
            if (_state.value.queue.isEmpty()) return playQueue(tracks)
            _state.update { s -> s.copy(queue = s.queue + tracks.filter { t -> s.queue.none { it.key == t.key } }) }
            unshuffled = unshuffled?.plus(tracks)
            schedulePreload()
        }
    }

    fun playNext(track: TrackResultDto) {
        synchronized(lock) {
            val s = _state.value
            if (s.queue.isEmpty()) return playTrack(track)
            val without = s.queue.filterIndexed { i, t -> t.key != track.key || i == s.index }
            val idx = without.indexOfFirst { it.key == s.current?.key }.coerceAtLeast(0)
            _state.update { it.copy(queue = without.toMutableList().apply { add(idx + 1, track) }, index = idx) }
            // with shuffle on, the order to go back to gets it right after the current track too
            unshuffled = unshuffled?.let { u ->
                val rest = u.filter { it.key != track.key }
                val at = rest.indexOfFirst { it.key == s.current?.key }
                rest.toMutableList().apply { add(at + 1, track) }
            }
            schedulePreload()
        }
    }

    fun removeFromQueue(i: Int) {
        synchronized(lock) {
            val s = _state.value
            if (i !in s.queue.indices) return
            // gone for good, also from the order shuffle returns to
            unshuffled = unshuffled?.filter { it.key != s.queue[i].key }
            if (i == s.index) {
                val q = s.queue.filterIndexed { j, _ -> j != i }
                if (q.isEmpty()) { stop(); return }
                _state.update { it.copy(queue = q, index = i.coerceAtMost(q.size - 1)) }
                startCurrent()
                return
            }
            _state.update { it.copy(queue = s.queue.filterIndexed { j, _ -> j != i }, index = if (i < s.index) s.index - 1 else s.index) }
            schedulePreload()
        }
    }

    fun moveInQueue(from: Int, to: Int) {
        synchronized(lock) {
            val s = _state.value
            if (from !in s.queue.indices || to !in s.queue.indices || from == to) return
            val cur = s.current
            val q = s.queue.toMutableList().apply { add(to, removeAt(from)) }
            _state.update { it.copy(queue = q, index = q.indexOfFirst { t -> t.key == cur?.key }) }
            schedulePreload()
        }
    }

    fun clearUpNext() {
        synchronized(lock) {
            val s = _state.value
            _state.update { it.copy(queue = s.queue.take(s.index + 1)) }
            unshuffled = null
            schedulePreload()
        }
    }

    fun skipTo(i: Int) {
        synchronized(lock) {
            if (i !in _state.value.queue.indices) return
            _state.update { it.copy(index = i) }
            startCurrent()
        }
    }

    fun next() {
        synchronized(lock) {
            val s = _state.value
            val n = nextIndex(s, userAction = true) ?: return
            _state.update { it.copy(index = n) }
            startCurrent()
        }
    }

    fun previous() {
        synchronized(lock) {
            val s = _state.value
            // a track that came in at its mix-in point counts from there, else "back" could never go back
            val from = engine.activeSource?.takeIf { it.id == s.current?.key }?.startSec ?: 0.0
            if (engine.state.value.positionSec - from > 3 || s.index <= 0) { seek(0.0); return }
            _state.update { it.copy(index = s.index - 1) }
            startCurrent()
        }
    }

    fun togglePlay() {
        synchronized(lock) {
            val e = engine.state.value
            when {
                e.playing -> engine.pause()
                // the queue played to its end: play the last track again instead of resuming a finished deck
                e.currentId != null && e.durationSec != null && e.positionSec >= e.durationSec - 0.5 -> startCurrent()
                e.currentId != null -> engine.resume()
                _state.value.current != null -> startCurrent()
            }
        }
    }

    fun seek(sec: Double) = engine.seek(sec)

    /**
     * Sets how to mix into the next track ([recipe] null = back to the default) and
     * plans it right away. Returns the plan, or null when the next track or either
     * analysis is missing a beat grid (then the plain crossfade applies).
     */
    suspend fun setTransition(recipe: TransitionRecipe?): PlannedTransition? {
        val (t, curKey) = synchronized(lock) {
            val s = _state.value
            val n = nextIndex(s, userAction = false) ?: error("Nothing queued after the current track")
            preloadJob?.cancel()
            preloadGen.incrementAndGet() // an older preload still running must not overwrite this
            preloadTarget = null
            s.queue[n] to s.current?.key
        }
        if (recipe == null) recipes.remove(t.key)
        val src = resolveStream(t)
        // the recipe is kept only once it plans (or cannot be planned yet for lack of a grid)
        val planned = if (recipe == null) planInto(t) else planInto(t, recipe, force = true).also { recipes[t.key] = recipe }
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (!stillNext(t, curKey)) error("The queue changed while planning the transition")
            _planned.value = planned
            engine.preloadNext(if (planned != null) src else src.copy(startSec = mixInOf(t)), planned?.plan)
            preloadTarget = curKey to t.key
        }
        return planned
    }

    /** [t] is still the track after [curKey]: nothing was skipped or reordered meanwhile. */
    private fun stillNext(t: TrackResultDto, curKey: String?): Boolean {
        val s = _state.value
        return s.current?.key == curKey && nextIndex(s, userAction = false)?.let { s.queue[it].key } == t.key
    }

    /** Stops an armed transition: the next track follows with the plain crossfade or gaplessly. */
    fun cancelTransition() {
        synchronized(lock) {
            _state.value.let { s -> nextIndex(s, false)?.let { recipes.remove(s.queue[it].key) } }
            _planned.value = null
            engine.cancelTransition()
        }
    }

    /** Live effect on the playing track (DJ EQ, filters, fader, echo). */
    fun setFx(param: dev.schlubbe.musicagent.desktop.audio.FxParam, value: Double) = engine.setFx(param, value)
    fun fxValue(param: dev.schlubbe.musicagent.desktop.audio.FxParam) = engine.fxValue(param)
    val canSync: Boolean get() = engine.canSync

    private fun mixInOf(t: TrackResultDto) =
        if (settings.current.crossfadeSeconds > 0) store.current.analysis[t.key]?.mixInMs?.div(1000.0) ?: 0.0 else 0.0

    /**
     * Plans the transition from the current track into [t]: with a recipe set for [t],
     * or with the "DJ-Übergänge" setting. Missing analyses are made first.
     */
    private suspend fun planInto(t: TrackResultDto, wish: TransitionRecipe? = null, force: Boolean = false): PlannedTransition? {
        val cur = _state.value.current ?: return null
        val s = settings.current
        val xf = s.crossfadeSeconds.takeIf { it > 0 }?.toDouble()
        val recipe = (wish ?: recipes[t.key] ?: if (s.djTransitions) TransitionRecipe(style = s.djStyle, harmonic = s.djHarmonic) else { _planNote.value = null; return null })
            .let { if (it.crossfadeSec == null && xf != null) it.copy(crossfadeSec = xf) else it }
        suspend fun analysisOf(x: TrackResultDto): TrackAnalysis? {
            store.current.analysis[x.key]?.takeIf { it.upToDate }?.let { return it }
            _planNote.value = "Analysiere „${x.title}“ …"
            return analyze(x)
        }
        val a = analysisOf(cur) ?: run { _planNote.value = "„${cur.title}“ ließ sich nicht analysieren – Überblendung"; return null }
        val b = analysisOf(t) ?: run { _planNote.value = "„${t.title}“ ließ sich nicht analysieren – Überblendung"; return null }
        val e = engine.state.value
        return runCatching {
            TransitionPlanner.plan(a, b, t.key, recipe, e.positionSec, e.durationSec, engine.canSync)
        }.onFailure { if (force) throw it }.getOrNull().also { p ->
            _planNote.value = when {
                p != null -> null
                a.grid == null || b.grid == null -> "Kein Beat erkannt – Überblendung statt DJ-Übergang"
                else -> "Kein Platz mehr vor dem Ende – Überblendung"
            }
        }
    }

    fun setVolume(v: Float) {
        val vol = v.coerceIn(0f, 1f)
        engine.volume = vol
        settings.update { it.copy(volume = vol) }
    }

    fun toggleShuffle() {
        synchronized(lock) {
            val s = _state.value
            val cur = s.current
            if (!s.shuffle) {
                unshuffled = s.queue
                val up = s.upNext.shuffled()
                _state.update { it.copy(queue = s.queue.take(s.index + 1) + up, shuffle = true) }
            } else {
                val orig = unshuffled ?: s.queue
                val idx = orig.indexOfFirst { it.key == cur?.key }
                _state.update { it.copy(queue = orig, index = if (idx >= 0) idx else s.index, shuffle = false) }
                unshuffled = null
            }
            schedulePreload()
        }
    }

    fun cycleRepeat() {
        synchronized(lock) {
            _state.update { it.copy(repeat = RepeatMode.entries[(it.repeat.ordinal + 1) % RepeatMode.entries.size]) }
            schedulePreload()
        }
    }

    fun setSleepTimer(minutes: Int?) {
        synchronized(lock) {
            sleepJob?.cancel()
            if (minutes == null) { _state.update { it.copy(sleepTimerEndAt = null) }; return }
            val end = System.currentTimeMillis() + minutes * 60_000L
            _state.update { it.copy(sleepTimerEndAt = end) }
            sleepJob = scope.launch {
                delay((minutes * 60_000L - SLEEP_FADE_MS).coerceAtLeast(0))
                // fade out instead of stopping mid-phrase; the volume setting itself stays
                val from = engine.volume
                val steps = 80
                try {
                    for (k in 1..steps) {
                        delay(SLEEP_FADE_MS / steps)
                        engine.volume = from * (1f - k.toFloat() / steps)
                    }
                    engine.pause()
                } finally {
                    engine.volume = settings.current.volume.coerceIn(0f, 1f)
                }
                _state.update { it.copy(sleepTimerEndAt = null) }
            }
        }
    }

    /** Switches between the stream and the downloaded copy at the same position. */
    fun toggleSource() {
        synchronized(lock) {
            val s = _state.value
            val t = s.current ?: return
            val local = localFile(t)
            if (local == null && !s.playingLocalCopy) return
            val pos = engine.state.value.positionSec
            playJob?.cancel()
            playJob = scope.launch {
                val src = if (s.playingLocalCopy) resolveStream(t, preferLocal = false) else AudioSource(t.key, local!!.path)
                synchronized(lock) {
                    if (_state.value.current?.key != t.key) return@launch
                    engine.play(src.copy(startSec = pos))
                    _state.update { it.copy(playingLocalCopy = !s.playingLocalCopy) }
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            playJob?.cancel(); preloadJob?.cancel()
            preloadGen.incrementAndGet()
            preloadTarget = null
            engine.stop()
            _state.update { QueueState(repeat = it.repeat) }
        }
    }

    // ---------------- internals ----------------

    private fun startCurrent() {
        val t = _state.value.current ?: return
        playJob?.cancel()
        preloadJob?.cancel()
        preloadGen.incrementAndGet()
        preloadTarget = null
        _state.update { it.copy(resolving = true, error = null) }
        playJob = scope.launch {
            try {
                val src = resolveStream(t)
                synchronized(lock) {
                    if (_state.value.current?.key != t.key) return@launch
                    engine.play(src)
                    _state.update { it.copy(resolving = false, playingLocalCopy = "://" !in src.url) }
                    consecutiveFailures = 0
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onEngineError(if (e is SoundCloudDrmOnlyException) "Titel nicht verfügbar" else (e.message ?: "Titel konnte nicht geladen werden"))
            }
        }
    }

    private fun localFile(t: TrackResultDto): File? =
        store.download(t.key)?.takeIf { it.state == DownloadState.COMPLETED }?.filePath?.let(::File)?.takeIf { it.isFile }

    suspend fun resolveStream(t: TrackResultDto, preferLocal: Boolean = true): AudioSource {
        if (preferLocal) localFile(t)?.let { return AudioSource(t.key, it.path) }
        if (settings.current.dataSaverMode) error("Datensparmodus: nur heruntergeladene Titel")
        val r = resolveRemote(t)
        return AudioSource(t.key, r.url, r.httpHeaders)
    }

    private fun nextIndex(s: QueueState, userAction: Boolean): Int? = when {
        s.queue.isEmpty() -> null
        s.repeat == RepeatMode.ONE && !userAction -> s.index
        s.index + 1 < s.queue.size -> s.index + 1
        s.repeat != RepeatMode.OFF -> 0
        else -> null
    }

    /** The play under way, for the history: track, start time and [AudioEngine.playedFrames] then. */
    private var play: Triple<TrackResultDto, Long, Long>? = null

    /** Records how the play under way ended: [natural] when the engine moved on by itself. */
    private fun endPlay(natural: Boolean) {
        val (t, startedAt, frames) = play ?: return
        play = null
        store.recordPlayEnd(t, startedAt, (engine.playedFrames - frames) * 1000 / 48_000, natural)
    }

    private fun onEngineStarted(source: AudioSource, automatic: Boolean) {
        // a retry's restart is no fresh start: the count only resets for a new track
        val restart = retrying
        if (retrying) retrying = false else streamRetries = 0
        if (automatic) {
            val s = _state.value
            val idx = nextIndex(s, userAction = false)?.takeIf { s.queue.getOrNull(it)?.key == source.id }
                ?: s.queue.indexOfFirst { it.key == source.id }
            if (idx >= 0) _state.update { it.copy(index = idx, playingLocalCopy = "://" !in source.url) }
        }
        val t = _state.value.current ?: return
        if (automatic) { recipes.remove(t.key); _planned.value = null; _planNote.value = null }
        store.recordPlay(t)
        if (!restart || play?.first?.key != t.key) {
            endPlay(natural = automatic)
            play = Triple(t, System.currentTimeMillis(), engine.playedFrames)
        }
        engine.mixOutSec = store.current.analysis[t.key]?.mixOutMs?.div(1000.0)
        // the echo keeps to this track's beat; without a grid, 120 BPM rather than the last track's
        engine.masterFx.trackBeatSec = store.current.analysis[t.key]?.grid?.beatMs?.div(1000) ?: 0.5
        schedulePreload(force = true) // the engine used up its preload, even when the next track repeats
        maybeExtendWithRadio()
    }

    /**
     * Resolves, analyses and plans the next track. A queue edit that leaves the next
     * track as it was keeps the preload (and an analysis still running) unless [force].
     */
    private fun schedulePreload(force: Boolean = false) {
        val s = _state.value
        val n = nextIndex(s, userAction = false)
        val curKey = s.current?.key
        val target = n?.let { curKey to s.queue[it].key }
        if (!force && target != null && target == preloadTarget) return
        preloadJob?.cancel()
        val gen = preloadGen.incrementAndGet()
        preloadTarget = target
        if (n == null || s.current == null) { engine.preloadNext(null); return }
        val t = s.queue[n]
        preloadJob = scope.launch {
            delay(1500) // let the current track settle; quick skips don't resolve needlessly
            val src = try { resolveStream(t) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (gen == preloadGen.get()) preloadTarget = null // the next queue edit tries again
                return@launch
            }
            // the analysis may take long; a skip meanwhile cancels this job and makes it stale
            val planned = try { planInto(t) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            ensureActive()
            synchronized(lock) {
                if (gen != preloadGen.get() || !stillNext(t, curKey)) return@launch
                _planned.value = planned
                engine.preloadNext(if (planned != null) src else src.copy(startSec = mixInOf(t)), planned?.plan)
            }
        }
    }

    /** Appends recommendations while the last queued track plays, so playback continues gaplessly. */
    private fun maybeExtendWithRadio() {
        val s = _state.value
        if (!settings.current.autoplayRadio || s.repeat != RepeatMode.OFF || s.index < s.queue.size - 1) return
        if (radioJob?.isActive == true) return
        radioJob = scope.launch {
            val st = _state.value
            // predictNext weighs its first seed most: the track playing now, then the ones before it
            val seeds = st.queue.take(st.index + 1).takeLast(5).asReversed()
            val exclude = st.queue.map { it.key }.toSet()
            // a failed or empty lookup (network drop, source hiccup) is retried before playback runs dry
            for (wait in RADIO_RETRY_MS) {
                delay(wait)
                val more = runCatching { recommend(seeds, exclude, 8) }.getOrDefault(emptyList())
                    .filterNot { t -> t.artist?.lowercase() in store.current.dislikedArtists }
                if (more.isNotEmpty()) { addToQueue(djOrder(more)); return@launch }
            }
        }
    }

    /**
     * With DJ transitions on, the radio batch is ordered so tempo and key flow from track
     * to track. The first few are analysed for that while the current track plays;
     * the rest keep the recommender's order.
     */
    private suspend fun djOrder(batch: List<TrackResultDto>): List<TrackResultDto> {
        if (!settings.current.djTransitions) return batch
        fun known(t: TrackResultDto) = store.current.analysis[t.key]?.takeIf { it.upToDate }
        val fresh = HashMap<String, TrackAnalysis>()
        for (t in batch.take(DJ_ORDER_ANALYSE)) {
            if (known(t) != null) continue
            withTimeoutOrNull(DJ_ORDER_TIMEOUT_MS) { runCatching { analyze(t) }.getOrNull() }?.let { fresh[t.key] = it }
        }
        val from = _state.value.queue.lastOrNull()?.let(::known)
        return TransitionScore.order(from, batch) { fresh[it.key] ?: known(it) }
    }

    private suspend fun onEngineEnded() {
        val s = synchronized(lock) {
            val s = _state.value
            val n = nextIndex(s, userAction = false)
            if (n != null) { _state.update { it.copy(index = n) }; startCurrent(); return }
            s
        }
        if (settings.current.autoplayRadio) {
            synchronized(lock) { radioJob }?.join()
            synchronized(lock) {
                if (_state.value.queue.size > s.queue.size) { _state.update { it.copy(index = s.index + 1) }; startCurrent() }
            }
        }
    }

    /**
     * A stream that breaks mid-track (network drop, expired signed URL - the preloaded
     * next URL can be hours old) is retried like the Android app does (two retries
     * with backoff), but with a freshly resolved URL, resuming at the same position.
     */
    private suspend fun onPlaybackError(source: AudioSource, message: String) {
        val (t, retry) = synchronized(lock) {
            val t = _state.value.current
            if (t == null || t.key != source.id || streamRetries >= MAX_STREAM_RETRIES) null to 0 else t to ++streamRetries
        }
        if (t == null) return onEngineError(message)
        val pos = engine.state.value.positionSec
        delay(500L * retry)
        if (_state.value.current?.key != t.key) return
        val src = runCatching { resolveStream(t) }.getOrElse { return onEngineError(message) }
        synchronized(lock) {
            if (_state.value.current?.key == t.key) {
                retrying = true
                engine.play(src.copy(startSec = pos))
            }
        }
    }

    private suspend fun onEngineError(message: String) {
        val skip = synchronized(lock) {
            _state.update { it.copy(resolving = false, error = message) }
            // skip unplayable tracks, but don't spin through a whole broken queue
            ++consecutiveFailures < 3 && nextIndex(_state.value, userAction = true) != null
        }
        if (skip) {
            delay(800)
            next()
        }
    }

    fun close() = engine.close()

    private companion object {
        /** Waits before each radio lookup: at once, then backing off over about 20 s. */
        val RADIO_RETRY_MS = longArrayOf(0, 2_000, 5_000, 15_000)
        /** Radio tracks analysed up front to order the batch for DJ transitions. */
        const val DJ_ORDER_ANALYSE = 4
        const val DJ_ORDER_TIMEOUT_MS = 25_000L
        const val MAX_STREAM_RETRIES = 2
        const val DJ_FALLBACK_CROSSFADE_SEC = 6
        const val SLEEP_FADE_MS = 8_000L
    }
}
