package dev.schlubbe.musicagent.desktop.audio

import dev.schlubbe.musicagent.playback.eq.*

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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

/**
 * Two-deck mixer. The current deck plays; the other deck holds the preloaded next
 * source, which takes over gaplessly at end of track or with an equal-power
 * crossfade (optionally starting at an analysed mix-out point). Post-mix stages:
 * 3D-Sound convolution, volume, soft clip, spectrum analysis, output.
 */
/** Engine output rate: everything is decoded and processed at 48 kHz stereo. */
const val SAMPLE_RATE = DEFAULT_SAMPLE_RATE

class AudioEngine(
    deckFactory: (String) -> Deck,
    private val sink: PcmSink,
) {
    private val decks = arrayOf(deckFactory("a"), deckFactory("b"))
    @Volatile private var cur = 0
    private val ctl = Executors.newSingleThreadExecutor { r -> Thread(r, "engine-ctl").apply { isDaemon = true } }
    private val lock = Any()

    @Volatile private var playing = false
    @Volatile private var epoch = 0
    @Volatile private var next: AudioSource? = null
    @Volatile private var fadeTotal = 0
    @Volatile private var fadeDone = 0
    @Volatile private var running = true

    @Volatile var crossfadeSec: Int = 0
    /** Mix-out point (seconds) of the current track from "Übergänge analysieren"; null = fade over the last [crossfadeSec]. */
    @Volatile var mixOutSec: Double? = null
    @Volatile var volume: Float = 0.8f
    @Volatile var listener: EngineListener = object : EngineListener {}

    val reverb = ReverbStage()
    private val equalizer = EqProcessor()
    val spectrum = SpectrumAnalyzer()
    /** Frames actually sent to the output while playing (for listening statistics). */
    @Volatile var playedFrames = 0L
        private set

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val mixer = Thread(::mixLoop, "engine-mixer").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }

    private val current get() = decks[cur]
    private val other get() = decks[1 - cur]

    fun play(source: AudioSource) = ctl.execute {
        synchronized(lock) {
            epoch++
            fadeTotal = 0
            next = null
            mixOutSec = null
        }
        other.stop()
        current.load(source)
        spectrum.reset()
        sink.flush()
        playing = true
        sink.start()
        publish(buffering = true)
        listener.onStarted(source, automatic = false)
    }

    /** Prepares [source] on the idle deck so it can follow gaplessly. Null clears it. */
    fun preloadNext(source: AudioSource?) = ctl.execute {
        if (source == next && (source == null || other.source == source)) return@execute
        if (fadeTotal > 0) return@execute // the idle deck is busy fading out
        next = source
        if (source == null) other.stop() else other.load(source)
    }

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
            if (fadeTotal > 0) finishFade()
        }
        current.seek(sec.coerceAtLeast(0.0))
        sink.flush()
        publish(buffering = true)
    }

    fun stop() = ctl.execute {
        playing = false
        synchronized(lock) { epoch++; next = null; fadeTotal = 0 }
        decks.forEach { it.stop() }
        sink.flush()
        spectrum.reset()
        _state.value = EngineState()
    }

    fun setEq(profile: EqProfile) = equalizer.setProfile(profile)
    fun setReverb(preset: Sound3dPreset) = reverb.setPreset(preset)

    private fun finishFade() {
        val old = current
        cur = 1 - cur
        fadeTotal = 0
        fadeDone = 0
        next = null
        mixOutSec = null
        ctl.execute { old.stop() }
    }

    private fun publish(buffering: Boolean = _state.value.buffering) {
        val d = if (fadeTotal > 0) other else current
        _state.value = EngineState(
            currentId = d.source?.id,
            playing = playing,
            buffering = buffering,
            positionSec = (d.positionSec - if (playing) sink.latencySec else 0.0).coerceAtLeast(d.source?.startSec ?: 0.0).coerceAtLeast(0.0),
            durationSec = d.durationSec,
        )
    }

    /** Fills [buf] from [deck] until [frames] frames, deck end, error, pause or epoch change. */
    private fun readFull(deck: Deck, buf: FloatArray, frames: Int, myEpoch: Int): Int {
        var got = 0
        var waitedMs = 0
        while (got < frames && running) {
            val n = deck.read(scratch, frames - got)
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
        return got
    }

    private val scratch = FloatArray(BLOCK * 2)
    private val bufA = FloatArray(BLOCK * 2)
    private val bufB = FloatArray(BLOCK * 2)
    private var publishCounter = 0

    private fun mixLoop() {
        while (running) {
            try {
                if (!playing || current.source == null) { Thread.sleep(10); continue }
                val myEpoch = epoch
                maybeStartFade()
                val c = current
                val o = other
                bufA.fill(0f)
                val n = readFull(c, bufA, BLOCK, myEpoch)
                if (epoch != myEpoch || !playing) continue
                if (fadeTotal > 0) {
                    bufB.fill(0f)
                    readFull(o, bufB, BLOCK, myEpoch)
                    for (i in 0 until BLOCK) {
                        val t = ((fadeDone + i).toFloat() / fadeTotal).coerceIn(0f, 1f)
                        val gOut = cos(t * PI / 2).toFloat()
                        val gIn = sin(t * PI / 2).toFloat()
                        bufA[2 * i] = bufA[2 * i] * gOut + bufB[2 * i] * gIn
                        bufA[2 * i + 1] = bufA[2 * i + 1] * gOut + bufB[2 * i + 1] * gIn
                    }
                    fadeDone += BLOCK
                    if (fadeDone >= fadeTotal || c.ended) synchronized(lock) { if (epoch == myEpoch) finishFade() }
                } else if (n < BLOCK && (c.ended || c.error != null)) {
                    endOfTrack(c, n, myEpoch)
                    continue
                }
                output(bufA)
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                java.util.logging.Logger.getLogger("AudioEngine").warning("mixer: $e")
            }
        }
    }

    /**
     * [c] ran out after [n] frames of this block. Hands over gaplessly to the
     * preloaded deck (filling the rest of the block from it) or stops and reports.
     */
    private fun endOfTrack(c: Deck, n: Int, myEpoch: Int) {
        val src = c.source ?: return
        val err = c.error
        val o = other
        val nxt = next
        var switched = false
        synchronized(lock) {
            if (epoch != myEpoch) return
            if (err == null && nxt != null && o.source == nxt && o.error == null) {
                cur = 1 - cur
                next = null
                mixOutSec = null
                switched = true
            } else {
                playing = false
            }
        }
        if (switched) {
            ctl.execute { c.stop() }
            spectrum.reset()
            listener.onStarted(nxt!!, automatic = true)
            val rest = readFull(o, bufB, BLOCK - n, myEpoch)
            System.arraycopy(bufB, 0, bufA, n * 2, rest * 2)
            output(bufA)
            return
        }
        if (n > 0) output(bufA)
        publish()
        if (err != null) listener.onError(src, err) else listener.onEnded(src)
    }

    private fun maybeStartFade() {
        val xf = crossfadeSec
        if (xf <= 0 || fadeTotal > 0) return
        val c = current
        val nxt = next ?: return
        val o = other
        val dur = c.durationSec ?: return
        if (o.source != nxt || o.error != null) return
        val pos = c.positionSec
        val start = (mixOutSec ?: (dur - xf)).coerceAtMost(dur - 1.0)
        if (pos < start) return
        val len = (dur - pos).coerceIn(1.0, maxOf(xf.toDouble(), 1.0))
        synchronized(lock) {
            fadeDone = 0
            fadeTotal = (len * SAMPLE_RATE).toInt()
        }
        listener.onStarted(nxt, automatic = true)
    }

    private fun output(buf: FloatArray) {
        equalizer.setVolume(volume)
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

    /** Currently audible track's deck position (for crossfade, the incoming deck). */
    val activeSource: AudioSource? get() = if (fadeTotal > 0) other.source else current.source

    fun close() {
        running = false
        mixer.interrupt()
        ctl.shutdownNow()
        ctl.awaitTermination(1, TimeUnit.SECONDS)
        decks.forEach { it.close() }
        sink.close()
    }

    companion object {
        const val BLOCK = ReverbStage.BLOCK
    }
}
