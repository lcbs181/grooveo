package dev.schlubbe.musicagent.desktop.playback

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

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
) {
    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()
    val engineState get() = engine.state
    val spectrum get() = engine.spectrum

    private var unshuffled: List<TrackResultDto>? = null
    private var playJob: Job? = null
    private var preloadJob: Job? = null
    private var sleepJob: Job? = null
    private var radioJob: Job? = null
    private var consecutiveFailures = 0

    init {
        engine.listener = object : EngineListener {
            override fun onStarted(source: AudioSource, automatic: Boolean) = onEngineStarted(source, automatic)
            override fun onEnded(source: AudioSource) { scope.launch { onEngineEnded() } }
            override fun onError(source: AudioSource, message: String) { scope.launch { onEngineError(message) } }
        }
        scope.launch {
            settings.state.distinctUntilChangedBy { Triple(it.eq, it.sound3dPreset, it.crossfadeSeconds) }.collect { s ->
                engine.setEq(s.eq)
                engine.setReverb(Sound3dPreset.of(s.sound3dPreset))
                engine.crossfadeSec = s.crossfadeSeconds
            }
        }
        engine.volume = settings.current.volume
        // listening statistics: count frames that really reached the speakers
        scope.launch {
            var last = engine.playedFrames
            while (isActive) {
                delay(15_000)
                val now = engine.playedFrames
                val sec = (now - last) / 48_000
                if (sec > 0) store.addListenSeconds(sec)
                last = now
            }
        }
    }

    // ---------------- public API ----------------

    fun playQueue(tracks: List<TrackResultDto>, startIndex: Int = 0, shuffle: Boolean = false) {
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

    fun playTrack(track: TrackResultDto) = playQueue(listOf(track))

    fun addToQueue(tracks: List<TrackResultDto>) {
        if (_state.value.queue.isEmpty()) return playQueue(tracks)
        _state.update { s -> s.copy(queue = s.queue + tracks.filter { t -> s.queue.none { it.key == t.key } }) }
        unshuffled = unshuffled?.plus(tracks)
        schedulePreload()
    }

    fun playNext(track: TrackResultDto) {
        val s = _state.value
        if (s.queue.isEmpty()) return playTrack(track)
        val without = s.queue.filterIndexed { i, t -> t.key != track.key || i == s.index }
        val idx = without.indexOfFirst { it.key == s.current?.key }.coerceAtLeast(0)
        _state.update { it.copy(queue = without.toMutableList().apply { add(idx + 1, track) }, index = idx) }
        schedulePreload()
    }

    fun removeFromQueue(i: Int) {
        val s = _state.value
        if (i !in s.queue.indices) return
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

    fun moveInQueue(from: Int, to: Int) {
        val s = _state.value
        if (from !in s.queue.indices || to !in s.queue.indices || from == to) return
        val cur = s.current
        val q = s.queue.toMutableList().apply { add(to, removeAt(from)) }
        _state.update { it.copy(queue = q, index = q.indexOfFirst { t -> t.key == cur?.key }) }
        schedulePreload()
    }

    fun clearUpNext() {
        val s = _state.value
        _state.update { it.copy(queue = s.queue.take(s.index + 1)) }
        unshuffled = null
        schedulePreload()
    }

    fun skipTo(i: Int) {
        if (i !in _state.value.queue.indices) return
        _state.update { it.copy(index = i) }
        startCurrent()
    }

    fun next() {
        val s = _state.value
        val n = nextIndex(s, userAction = true) ?: return
        _state.update { it.copy(index = n) }
        startCurrent()
    }

    fun previous() {
        val s = _state.value
        if (engine.state.value.positionSec > 3 || s.index <= 0) { seek(0.0); return }
        _state.update { it.copy(index = s.index - 1) }
        startCurrent()
    }

    fun togglePlay() {
        val e = engine.state.value
        when {
            e.playing -> engine.pause()
            e.currentId != null -> engine.resume()
            _state.value.current != null -> startCurrent()
        }
    }

    fun seek(sec: Double) = engine.seek(sec)

    fun setVolume(v: Float) {
        engine.volume = v.coerceIn(0f, 1f)
        settings.update { it.copy(volume = engine.volume) }
    }

    fun toggleShuffle() {
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

    fun cycleRepeat() {
        _state.update { it.copy(repeat = RepeatMode.entries[(it.repeat.ordinal + 1) % RepeatMode.entries.size]) }
        schedulePreload()
    }

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) { _state.update { it.copy(sleepTimerEndAt = null) }; return }
        val end = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepTimerEndAt = end) }
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            engine.pause()
            _state.update { it.copy(sleepTimerEndAt = null) }
        }
    }

    /** Switches between the stream and the downloaded copy at the same position. */
    fun toggleSource() {
        val s = _state.value
        val t = s.current ?: return
        val local = localFile(t)
        if (local == null && !s.playingLocalCopy) return
        val pos = engine.state.value.positionSec
        playJob?.cancel()
        playJob = scope.launch {
            val src = if (s.playingLocalCopy) resolveStream(t, preferLocal = false) else AudioSource(t.key, local!!.path)
            engine.play(src.copy(startSec = pos))
            _state.update { it.copy(playingLocalCopy = !s.playingLocalCopy) }
        }
    }

    fun stop() {
        playJob?.cancel(); preloadJob?.cancel()
        engine.stop()
        _state.update { QueueState(repeat = it.repeat) }
    }

    // ---------------- internals ----------------

    private fun startCurrent() {
        val t = _state.value.current ?: return
        playJob?.cancel()
        preloadJob?.cancel()
        _state.update { it.copy(resolving = true, error = null) }
        playJob = scope.launch {
            try {
                val src = resolveStream(t)
                if (_state.value.current?.key != t.key) return@launch
                engine.play(src)
                _state.update { it.copy(resolving = false, playingLocalCopy = src.url.startsWith("/")) }
                consecutiveFailures = 0
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

    private fun onEngineStarted(source: AudioSource, automatic: Boolean) {
        if (automatic) {
            val s = _state.value
            val idx = nextIndex(s, userAction = false)?.takeIf { s.queue.getOrNull(it)?.key == source.id }
                ?: s.queue.indexOfFirst { it.key == source.id }
            if (idx >= 0) _state.update { it.copy(index = idx, playingLocalCopy = source.url.startsWith("/")) }
        }
        val t = _state.value.current ?: return
        store.recordPlay(t)
        engine.mixOutSec = store.current.analysis[t.key]?.mixOutMs?.div(1000.0)
        schedulePreload()
        maybeExtendWithRadio()
    }

    private fun schedulePreload() {
        preloadJob?.cancel()
        val s = _state.value
        val n = nextIndex(s, userAction = false)
        if (n == null || s.current == null) { engine.preloadNext(null); return }
        val t = s.queue[n]
        preloadJob = scope.launch {
            delay(1500) // let the current track settle; quick skips don't resolve needlessly
            val src = runCatching { resolveStream(t) }.getOrNull() ?: return@launch
            val mixIn = if (settings.current.crossfadeSeconds > 0) store.current.analysis[t.key]?.mixInMs?.div(1000.0) ?: 0.0 else 0.0
            engine.preloadNext(src.copy(startSec = mixIn))
        }
    }

    /** Appends recommendations while the last queued track plays, so playback continues gaplessly. */
    private fun maybeExtendWithRadio() {
        val s = _state.value
        if (!settings.current.autoplayRadio || s.repeat != RepeatMode.OFF || s.index < s.queue.size - 1) return
        if (radioJob?.isActive == true) return
        radioJob = scope.launch {
            val q = _state.value.queue
            val more = runCatching { recommend(q.takeLast(5), q.map { it.key }.toSet(), 8) }.getOrDefault(emptyList())
                .filterNot { t -> t.artist?.lowercase() in store.current.dislikedArtists }
            if (more.isNotEmpty()) addToQueue(more)
        }
    }

    private suspend fun onEngineEnded() {
        val s = _state.value
        val n = nextIndex(s, userAction = false)
        if (n != null) { _state.update { it.copy(index = n) }; startCurrent(); return }
        if (settings.current.autoplayRadio) {
            radioJob?.join()
            if (_state.value.queue.size > s.queue.size) { _state.update { it.copy(index = s.index + 1) }; startCurrent() }
        }
    }

    private suspend fun onEngineError(message: String) {
        _state.update { it.copy(resolving = false, error = message) }
        // skip unplayable tracks, but don't spin through a whole broken queue
        if (++consecutiveFailures < 3 && nextIndex(_state.value, userAction = true) != null) {
            delay(800)
            next()
        }
    }

    fun close() = engine.close()
}
