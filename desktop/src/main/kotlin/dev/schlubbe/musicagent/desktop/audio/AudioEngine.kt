package dev.schlubbe.musicagent.desktop.audio

import dev.schlubbe.musicagent.playback.eq.*

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class EngineState(
    val currentId: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionSec: Double = 0.0,
    val durationSec: Double? = null,
)

interface EngineListener {
    /** [source] became audible; [automatic] for gapless/crossfade hand-over. */
    fun onStarted(source: AudioSource, automatic: Boolean) {}
    /** The current source played to its end and no next source was ready. */
    fun onEnded(source: AudioSource) {}
    fun onError(source: AudioSource, message: String) {}
}

/** Engine output rate: everything is decoded and processed at 48 kHz stereo. */
const val SAMPLE_RATE = DEFAULT_SAMPLE_RATE

/**
 * Two-deck mixer. The current deck plays; the other deck holds the preloaded next
 * source, which takes over gaplessly at end of track, with an equal-power crossfade
 * (optionally starting at an analysed mix-out point), or with a [TransitionPlan]: a
 * beat-locked DJ transition with tempo sync and automated EQ, filters, fader and echo.
 *
 * Each deck runs through a [DeckChain] (time-stretch for tempo sync, [DeckFx]).
 * Post-mix stages: EQ, 3D-Sound convolution, volume, soft clip, spectrum, output.
 */
class AudioEngine(
    deckFactory: (String) -> Deck,
    private val sink: PcmSink,
) {
    private val chains = arrayOf(DeckChain(deckFactory("a")), DeckChain(deckFactory("b")))
    /** "Lautstärke angleichen", one per deck so both tracks of a crossfade are levelled on their own. */
    private val norms = arrayOf(LoudnessNormalizer(SAMPLE_RATE), LoudnessNormalizer(SAMPLE_RATE))
    /** Stored loudness per track id; measurements are written back when a deck loads the next track. */
    @Volatile var loudnessCache: LoudnessCache? = null
    /** Turns loudness normalisation on or off (glides, no jump). */
    var normalize: Boolean
        get() = norms[0].enabled
        set(v) { norms.forEach { it.enabled = v } }
    @Volatile private var cur = 0
    private val ctl = Executors.newSingleThreadExecutor { r -> Thread(r, "engine-ctl").apply { isDaemon = true } }
    private val lock = Any()

    @Volatile private var playing = false
    @Volatile private var epoch = 0
    @Volatile private var next: AudioSource? = null
    @Volatile private var running = true

    // ---- transition state (mixer thread; changed by ctl under [lock])
    /** Plan for the transition to [next], null = gapless or plain crossfade. */
    @Volatile private var plan: TransitionPlan? = null
    @Volatile private var phase = TransitionStatus.Phase.NONE
    /** The incoming stretcher reached its cue. */
    @Volatile private var primed = false
    /** Plan being played (also a plain crossfade, as a time-based plan). */
    private var active: TransitionPlan? = null
    /** Output frames since the transition started, and the beat length in output frames. */
    private var tFrames = 0L
    private var beatFrames = 1.0
    private var syncBaseRate = 1.0
    private var syncErrMs: Double? = null
    /** A preload that arrived while a transition was using the other deck. */
    @Volatile private var pendingPreload: Pair<AudioSource?, TransitionPlan?>? = null

    @Volatile var crossfadeSec: Int = 0
    /** Mix-out point (seconds) of the current track from "Übergänge analysieren"; null = fade over the last [crossfadeSec]. */
    @Volatile var mixOutSec: Double? = null
    @Volatile var volume: Float = 0.8f
    @Volatile var listener: EngineListener = object : EngineListener {}

    val reverb = ReverbStage()
    /** "FX-Kette": effects on the whole output, before the equalizer. */
    val masterFx = MasterFx()
    private val equalizer = EqProcessor()
    val spectrum = SpectrumAnalyzer()
    /** Frames actually sent to the output while playing (for listening statistics). */
    @Volatile var playedFrames = 0L
        private set

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()
    private val _transition = MutableStateFlow(TransitionStatus())
    val transition: StateFlow<TransitionStatus> = _transition.asStateFlow()

    private val mixer = Thread(::mixLoop, "engine-mixer").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }

    private val current get() = chains[cur]
    private val other get() = chains[1 - cur]

    /** Tempo stretching is available (native library loaded). */
    val canSync: Boolean get() = TimeStretcher.available

    fun play(source: AudioSource) = ctl.execute {
        synchronized(lock) {
            epoch++
            next = null
            plan = null
            active = null
            phase = TransitionStatus.Phase.NONE
            pendingPreload = null
            mixOutSec = null
        }
        other.deck.stop()
        chains.forEach { it.resetTempo(); it.fx.reset(clearEcho = true) }
        startLoudness(cur, source)
        current.deck.load(source)
        spectrum.reset()
        sink.flush()
        playing = true
        sink.start()
        publish(buffering = true)
        publishTransition()
        listener.onStarted(source, automatic = false)
    }

    /**
     * Prepares [source] on the idle deck so it can follow gaplessly, crossfaded, or with
     * [plan]. Null clears it. While a transition uses the idle deck, the request waits.
     */
    fun preloadNext(source: AudioSource?, plan: TransitionPlan? = null) = ctl.execute {
        if (phase == TransitionStatus.Phase.RUNNING) { pendingPreload = source to plan; return@execute }
        val p = plan?.takeIf { source != null && it.nextId == source.id }
        if (source == next && (source == null || other.source?.id == source.id) && p == this.plan) return@execute
        loadNext(source, p)
    }

    /** Sets or replaces the plan for the already preloaded next track (null = plain behaviour). */
    fun setTransition(plan: TransitionPlan?) = ctl.execute {
        val n = next
        if (phase == TransitionStatus.Phase.RUNNING) return@execute
        if (n == null) return@execute
        if (plan != null && plan.nextId != n.id) return@execute
        loadNext(n, plan)
    }

    private fun loadNext(source: AudioSource?, p: TransitionPlan?) {
        val o = other
        synchronized(lock) {
            next = source
            plan = p
            primed = false
            phase = if (p != null) TransitionStatus.Phase.ARMED else if (phase == TransitionStatus.Phase.ARMED) TransitionStatus.Phase.NONE else phase
        }
        o.resetTempo()
        o.fx.reset(clearEcho = false)
        if (source == null) { o.deck.stop(); publishTransition(); return }
        startLoudness(1 - cur, source)
        if (p == null) {
            o.deck.load(source)
        } else if (p.sync && canSync) {
            val rate = syncRate(p)
            o.startStretch(rate, p.semitones)
            // start early by the stretcher's latency; prime() runs it up to the cue
            o.deck.load(source.copy(startSec = (p.inCueSec - o.stretchLatencySec(rate) - 0.05).coerceAtLeast(0.0)))
        } else {
            o.deck.load(source.copy(startSec = p.inCueSec))
            primed = true
        }
        publishTransition()
    }

    /** Incoming rate that makes its beats as long as the outgoing deck's beats sound now. */
    private fun syncRate(p: TransitionPlan): Double = (p.inBeatSec / (p.outBeatSec / current.rate)).coerceIn(0.5, 2.0)

    fun cancelTransition() = setTransition(null)

    fun pause() {
        playing = false
        sink.stop()
        publish()
    }

    fun resume() {
        if (current.source == null) return
        playing = true
        sink.start()
        publish()
    }

    fun seek(sec: Double) = ctl.execute {
        synchronized(lock) {
            epoch++
            if (phase == TransitionStatus.Phase.RUNNING) finishTransition()
        }
        val c = current
        c.resetTempo()
        c.deck.seek(sec.coerceAtLeast(0.0))
        sink.flush()
        publish(buffering = true)
    }

    fun stop() = ctl.execute {
        playing = false
        synchronized(lock) { epoch++; next = null; plan = null; active = null; phase = TransitionStatus.Phase.NONE; pendingPreload = null }
        chains.forEach { it.deck.stop(); it.resetTempo(); it.fx.reset(clearEcho = true) }
        sink.flush()
        spectrum.reset()
        _state.value = EngineState()
        publishTransition()
    }

    /** Live control of the playing deck's effects (outside transitions, which automate them). */
    fun setFx(param: FxParam, value: Double) { current.fx.set(param, value) }
    fun fxValue(param: FxParam): Double = current.fx.get(param)

    fun setEq(profile: EqProfile) = equalizer.setProfile(profile)
    fun setReverb(preset: Sound3dPreset) = reverb.setPreset(preset)

    /** Ends the running transition: the incoming deck becomes the current one. Holds [lock]. */
    private fun finishTransition() {
        val old = current
        val p = active
        cur = 1 - cur
        active = null
        next = null
        plan = null
        mixOutSec = null
        syncErrMs = null
        val c = current
        phase = if (p != null && p.sync && p.releaseBeats != null && c.mode == DeckChain.Mode.STRETCH && c.rate != 1.0) {
            c.glideTo(1.0, p.releaseBeats * p.inBeatSec / ((c.rate + 1) / 2) * SAMPLE_RATE)
            TransitionStatus.Phase.RELEASING
        } else TransitionStatus.Phase.NONE
        // the outgoing deck's echo keeps ringing; its fader and filters reset with the next load
        ctl.execute {
            old.deck.stop()
            old.resetTempo()
            pendingPreload?.let { (s, pl) -> pendingPreload = null; loadNext(s, pl) }
        }
    }

    private fun publish(buffering: Boolean = _state.value.buffering) {
        val d = if (phase == TransitionStatus.Phase.RUNNING) other else current
        _state.value = EngineState(
            currentId = d.source?.id,
            playing = playing,
            buffering = buffering,
            positionSec = (d.outputSourceSec - if (playing) sink.latencySec else 0.0).coerceAtLeast(d.source?.startSec ?: 0.0).coerceAtLeast(0.0),
            durationSec = d.deck.durationSec,
        )
    }

    private fun publishTransition() {
        val beat = when (phase) {
            TransitionStatus.Phase.RUNNING -> tFrames / beatFrames
            TransitionStatus.Phase.ARMED -> plan?.let { (current.outputSourceSec - it.outStartSec) / it.outBeatSec } ?: 0.0
            else -> 0.0
        }
        val rate = when (phase) {
            TransitionStatus.Phase.RUNNING -> other.rate
            TransitionStatus.Phase.ARMED -> plan?.takeIf { it.sync && canSync }?.let { syncRate(it) } ?: 1.0
            else -> current.rate
        }
        _transition.value = TransitionStatus(phase, active ?: plan, beat, rate, syncErrMs)
    }

    /** Stores what deck [i] measured for its previous track and primes it for [source]. */
    private fun startLoudness(i: Int, source: AudioSource) {
        val cache = loudnessCache
        norms[i].trackId?.let { id -> norms[i].measuredLufs()?.let { cache?.put(id, it) } }
        norms[i].startTrack(source.id, cache?.get(source.id))
        cache?.save()
    }

    /** Fills [buf] from [deck] until [frames] frames, deck end, error, pause or epoch change. */
    private fun readFull(deck: Deck, buf: FloatArray, frames: Int, myEpoch: Int): Int {
        var got = 0
        var waitedMs = 0
        while (got < frames && running) {
            val n = deck.read(scratch, minOf(frames - got, scratch.size / 2))
            System.arraycopy(scratch, 0, buf, got * 2, n * 2)
            got += n
            if (got >= frames || deck.ended || deck.error != null || !playing || epoch != myEpoch || deck.source == null) break
            Thread.sleep(2)
            waitedMs += 2
            if (waitedMs == 150) publish(buffering = true)
        }
        // clear the flag set by play()/seek() too, not only after a stall: audio that
        // arrives within 150 ms otherwise left the play button spinning while playing
        if (got > 0 && (waitedMs >= 150 || _state.value.buffering)) publish(buffering = false)
        if (got > 0) norms[if (deck === chains[0].deck) 0 else 1].process(buf, got)
        return got
    }

    /** Non-blocking read for priming: only what is decoded already. */
    private fun readAvailable(deck: Deck, buf: FloatArray, frames: Int): Int {
        val n = deck.read(scratch, minOf(frames, scratch.size / 2))
        System.arraycopy(scratch, 0, buf, 0, n * 2)
        if (n > 0) norms[if (deck === chains[0].deck) 0 else 1].process(buf, n)
        return n
    }

    private val scratch = FloatArray(DeckChain.BLOCK_IN * 2)
    private val bufA = FloatArray(BLOCK * 2)
    private val bufB = FloatArray(BLOCK * 2)
    private val mix = FloatArray(BLOCK * 2)
    private var publishCounter = 0

    private fun mixLoop() {
        while (running) {
            try {
                if (!playing || current.source == null) { Thread.sleep(10); continue }
                val myEpoch = epoch
                val startAt = maybeStartTransition(myEpoch)
                val c = current
                val o = other
                bufA.fill(0f)
                mix.fill(0f)
                val n = c.render(bufA, 0, BLOCK) { b, k -> readFull(c.deck, b, k, myEpoch) }
                if (epoch != myEpoch || !playing) continue
                val p = active
                if (p != null && phase == TransitionStatus.Phase.RUNNING) {
                    val from = startAt.coerceIn(0, BLOCK)
                    syncIncoming(p)
                    bufB.fill(0f)
                    o.render(bufB, from, BLOCK - from) { b, k -> readFull(o.deck, b, k, myEpoch) }
                    // frames before the start belong to the outgoing deck only
                    applyLanes(p, DeckRole.OUT, c, bufA, 0, BLOCK, -from.toLong())
                    applyLanes(p, DeckRole.IN, o, bufB, from, BLOCK - from, -from.toLong())
                    for (i in 0 until BLOCK * 2) mix[i] += bufA[i] + bufB[i]
                    tFrames += BLOCK - from
                    if (tFrames / beatFrames >= p.endBeat || (c.ended && tFrames / beatFrames >= p.lengthBeats)) {
                        synchronized(lock) { if (epoch == myEpoch) finishTransition() }
                        spectrum.reset()
                        publishTransition()
                    } else if (publishCounter % 8 == 0) publishTransition()
                } else if (n < BLOCK && (c.ended || c.deck.error != null)) {
                    endOfTrack(c, n, myEpoch)
                    continue
                } else {
                    c.fx.process(bufA, mix, 0, BLOCK)
                    for (i in 0 until BLOCK * 2) mix[i] += bufA[i]
                    o.fx.processTail(mix, BLOCK)
                    if (phase == TransitionStatus.Phase.RELEASING && !c.gliding) {
                        phase = TransitionStatus.Phase.NONE
                        publishTransition()
                    } else if (phase != TransitionStatus.Phase.NONE && publishCounter % 8 == 0) publishTransition()
                }
                output(mix)
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                java.util.logging.Logger.getLogger("AudioEngine").warning("mixer: $e")
            }
        }
    }

    /** Sets the lane values of [role]'s deck every 64 frames and runs its effects into [mix]. */
    private fun applyLanes(p: TransitionPlan, role: DeckRole, chain: DeckChain, buf: FloatArray, from: Int, frames: Int, frameOffset: Long) {
        val lanes = p.lanes.filter { it.deck == role }
        var i = from
        while (i < from + frames) {
            val len = minOf(64, from + frames - i)
            val beat = (tFrames + frameOffset + i) / beatFrames
            for (l in lanes) chain.fx.set(l.param, l.valueAt(beat.coerceAtLeast(0.0)))
            chain.fx.process(buf, mix, i, len)
            i += len
        }
    }

    /** Beat sync: steers the incoming deck's rate so its beats stay on the outgoing deck's beats. */
    private fun syncIncoming(p: TransitionPlan) {
        val o = other
        if (!p.sync || o.mode != DeckChain.Mode.STRETCH) return
        // the incoming deck's next frame is heard at this beat of the transition
        val expected = p.inCueSec + tFrames / beatFrames * p.inBeatSec
        val err = o.outputSourceSec - expected
        syncErrMs = err * 1000
        // correct the error over about two beats, never more than 3 % off the synced tempo
        val tau = 2 * beatFrames / SAMPLE_RATE
        o.setRate((syncBaseRate - err / tau).coerceIn(syncBaseRate * 0.97, syncBaseRate * 1.03))
    }

    /**
     * Starts a transition when its time has come. Returns the frame offset in this
     * block where the incoming deck starts (0 when it starts with the block).
     */
    private fun maybeStartTransition(myEpoch: Int): Int {
        if (phase == TransitionStatus.Phase.RUNNING) return 0
        val c = current
        val o = other
        val nxt = next ?: return 0
        if (o.source?.id != nxt.id || o.deck.error != null) return 0
        val p = plan
        if (p != null) {
            if (!primed) primed = o.prime(p.inCueSec) { b, k -> readAvailable(o.deck, b, k) }
            val framesToStart = ((p.outStartSec - c.outputSourceSec) / c.rate * SAMPLE_RATE).toLong()
            if (framesToStart >= BLOCK) return 0
            if (!primed || framesToStart < 0) {
                // the next track is not ready yet (or the plan came too late): one bar later, if the outgoing track lasts
                val later = p.copy(outStartSec = p.outStartSec + 4 * p.outBeatSec)
                val dur = c.deck.durationSec
                synchronized(lock) { if (epoch == myEpoch) plan = if (dur == null || later.outStartSec + later.lengthBeats * later.outBeatSec < dur) later else null }
                if (plan == null) phase = TransitionStatus.Phase.NONE
                publishTransition()
                return 0
            }
            begin(p, if (p.sync && canSync) syncRate(p) else 1.0)
            return framesToStart.coerceAtLeast(0).toInt()
        }
        // plain crossfade over the last crossfadeSec seconds (or from the analysed mix-out point)
        val xf = crossfadeSec
        if (xf <= 0) return 0
        val dur = c.deck.durationSec ?: return 0
        val pos = c.outputSourceSec
        val start = (mixOutSec ?: (dur - xf)).coerceAtMost(dur - 1.0)
        if (pos < start) return 0
        val len = (dur - pos).coerceIn(1.0, maxOf(xf.toDouble(), 1.0))
        begin(
            TransitionPlan(
                nxt.id, pos, 1.0, o.outputSourceSec, 1.0, len,
                listOf(
                    Lane(DeckRole.OUT, FxParam.VOLUME, listOf(LanePoint(0.0, 1.0), LanePoint(len, 0.0, Curve.EASE_IN))),
                    Lane(DeckRole.IN, FxParam.VOLUME, listOf(LanePoint(0.0, 0.0), LanePoint(len, 1.0, Curve.EASE_OUT))),
                ),
                sync = false, releaseBeats = null, style = "crossfade",
            ),
            1.0,
        )
        return 0
    }

    private fun begin(p: TransitionPlan, inRate: Double) {
        val c = current
        val o = other
        synchronized(lock) {
            active = p
            plan = null
            phase = TransitionStatus.Phase.RUNNING
            tFrames = 0
            beatFrames = p.outBeatSec / c.rate * SAMPLE_RATE
            syncBaseRate = inRate
            // the outgoing tempo holds still during the transition (stops a running release)
            c.setRate(c.rate)
        }
        if (o.mode == DeckChain.Mode.STRETCH) o.setRate(inRate)
        val echo = (p.echoBeats * beatFrames).toInt().coerceIn(1, Echo.MAX_FRAMES - 1)
        c.fx.echo.delayFrames = echo
        o.fx.echo.delayFrames = echo
        // the incoming deck's effects start where its lanes start, not at neutral
        p.lanes.filter { it.deck == DeckRole.IN }.forEach { o.fx.set(it.param, it.valueAt(0.0)) }
        listener.onStarted(o.source!!, automatic = true)
        publishTransition()
    }

    /**
     * [c] ran out after [n] frames of this block. Hands over gaplessly to the
     * preloaded deck (filling the rest of the block from it) or stops and reports.
     */
    private fun endOfTrack(c: DeckChain, n: Int, myEpoch: Int) {
        val src = c.source ?: return
        val err = c.deck.error
        val o = other
        val nxt = next
        var switched = false
        synchronized(lock) {
            if (epoch != myEpoch) return
            if (err == null && nxt != null && o.source?.id == nxt.id && o.deck.error == null) {
                cur = 1 - cur
                next = null
                plan = null
                mixOutSec = null
                // a deck loaded for a synced transition that never started: back to its own tempo
                phase = if (o.mode == DeckChain.Mode.STRETCH && o.rate != 1.0) {
                    o.glideTo(1.0, 8 * SAMPLE_RATE); TransitionStatus.Phase.RELEASING
                } else TransitionStatus.Phase.NONE
                switched = true
            } else {
                playing = false
            }
        }
        if (switched) {
            ctl.execute { c.deck.stop(); c.resetTempo() }
            spectrum.reset()
            listener.onStarted(nxt!!, automatic = true)
            publishTransition()
            val rest = o.render(bufA, n, BLOCK - n) { b, k -> readFull(o.deck, b, k, myEpoch) }
            if (n + rest < BLOCK) bufA.fill(0f, (n + rest) * 2, BLOCK * 2)
            o.fx.process(bufA, mix, 0, BLOCK)
            for (i in 0 until BLOCK * 2) mix[i] += bufA[i]
            output(mix)
            return
        }
        if (n > 0) {
            c.fx.process(bufA, mix, 0, BLOCK)
            for (i in 0 until BLOCK * 2) mix[i] += bufA[i]
            output(mix)
        }
        publish()
        if (err != null) listener.onError(src, err) else listener.onEnded(src)
    }

    private fun output(buf: FloatArray) {
        norms.forEach { it.makeupDb = equalizer.makeupDb }
        equalizer.forceLimiter = norms.any { it.currentGainDb > 0.05 }
        equalizer.setVolume(volume)
        masterFx.process(buf, BLOCK)
        equalizer.process(buf, BLOCK)
        reverb.process(buf)
        val v = volume.coerceIn(0f, 1f)
        val g = v * v * v // perceptual curve
        for (i in buf.indices) buf[i] = softClip(buf[i] * g)
        spectrum.push(buf, BLOCK)
        sink.write(buf, BLOCK)
        playedFrames += BLOCK
        if (++publishCounter % 4 == 0) publish()
    }

    /** Currently audible track's deck (for a running transition, the incoming one). */
    val activeSource: AudioSource? get() = if (phase == TransitionStatus.Phase.RUNNING) other.source else current.source

    /** Playback rate of the deck the listener follows (1 = native tempo). */
    val currentRate: Double get() = if (phase == TransitionStatus.Phase.RUNNING) other.rate else current.rate

    fun close() {
        norms.forEach { n -> n.trackId?.let { id -> n.measuredLufs()?.let { loudnessCache?.put(id, it) } } }
        loudnessCache?.save()
        running = false
        mixer.interrupt()
        ctl.shutdownNow()
        ctl.awaitTermination(1, TimeUnit.SECONDS)
        chains.forEach { it.deck.close(); it.close() }
        sink.close()
    }

    companion object {
        const val BLOCK = ReverbStage.BLOCK
    }
}
