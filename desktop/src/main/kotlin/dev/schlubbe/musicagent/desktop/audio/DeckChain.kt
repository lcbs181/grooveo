package dev.schlubbe.musicagent.desktop.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One deck with its tempo stage and effects, as the mixer sees it.
 *
 * Tempo modes:
 *  - [Mode.DRY]: the deck's audio as is (normal playback, no extra latency).
 *  - [Mode.STRETCH]: through a [TimeStretcher] at [rate] source frames per output
 *    frame (beat sync). Chosen when a track is loaded for a synced transition.
 *  - [Mode.DELAYED]: back at the original tempo after a sync: dry audio, delayed by
 *    the stretcher's latency so the switch neither skips nor repeats audio.
 *
 * [outputSourceSec] is the source position of the next frame this chain outputs, with
 * the stretcher's latency taken out; beat sync and transitions are timed on it.
 */
class DeckChain(val deck: Deck) {
    enum class Mode { DRY, STRETCH, DELAYED }

    val fx = DeckFx()
    var mode = Mode.DRY
        private set
    /** Current playback rate (source frames per output frame). */
    var rate = 1.0
        private set
    /** Rate the chain glides to; [glidePerFrame] sets how fast. */
    private var targetRate = 1.0
    private var glidePerFrame = 0.0
    /** Pitch shift in semitones while stretching (harmonic mixing). */
    var semitones = 0f
        set(v) { field = v; stretcher?.semitones = v }

    private var stretcher: TimeStretcher? = null
    private var delay: FloatArray = FloatArray(0)
    private var delayFrames = 0
    private var delayWrite = 0
    private var delayFill = 0
    /** Fractional source frames owed to the stretcher from earlier blocks. */
    private var inCarry = 0.0
    private val inBuf = FloatArray(BLOCK_IN * 2)
    private val outTmp = FloatArray(BLOCK_IN * 2)
    /** Remaining frames of the STRETCH -> DELAYED crossfade. */
    private var switchLeft = 0
    /** The deck ended while stretching: the stretcher's last output, played out before [ended]. */
    private var tail = FloatArray(0)
    private var tailPos = 0
    private var tailLeft = 0
    private var flushed = false

    val source: AudioSource? get() = deck.source
    val ended: Boolean get() = deck.ended && when (mode) {
        Mode.DRY -> true
        Mode.STRETCH -> flushed && tailLeft == 0
        Mode.DELAYED -> delayFill == 0
    }

    val outputSourceSec: Double
        get() = deck.positionSec - when (mode) {
            Mode.DRY -> 0.0
            Mode.STRETCH -> stretcher!!.latencyFrames(rate) / SAMPLE_RATE
            Mode.DELAYED -> delayFill / SAMPLE_RATE
        }

    /** Source seconds of latency a deck loaded for stretching needs before its cue (see [prime]). */
    fun stretchLatencySec(rate: Double): Double = (stretcher ?: TimeStretcher().also { stretcher = it }).latencyFrames(rate) / SAMPLE_RATE

    /** Back to plain playback (new track, seek, stop). */
    fun resetTempo() {
        mode = Mode.DRY
        rate = 1.0; targetRate = 1.0; glidePerFrame = 0.0
        inCarry = 0.0; switchLeft = 0; delayFill = 0
        tailPos = 0; tailLeft = 0; flushed = false
        semitones = 0f
        stretcher?.reset()
    }

    /** Switches to stretching at [rate]. Only valid before the loaded track is heard. */
    fun startStretch(rate: Double, semitones: Float) {
        resetTempo()
        val s = stretcher ?: TimeStretcher().also { stretcher = it }
        s.reset()
        this.semitones = semitones
        s.semitones = semitones
        this.rate = rate; targetRate = rate
        mode = Mode.STRETCH
        // room for the flush at track end even at the slowest rate
        val tailFrames = (s.latencyFrames(MIN_RATE) / MIN_RATE).roundToInt() + 1
        if (tail.size < tailFrames * 2) tail = FloatArray(tailFrames * 2)
    }

    /** Glides the rate to [target] over [frames] output frames (0 = at once). */
    fun glideTo(target: Double, frames: Double) {
        targetRate = target
        glidePerFrame = if (frames <= 0) Double.MAX_VALUE else abs(target - rate) / frames
    }

    /** Sets the rate for the next block directly (sync correction). */
    fun setRate(r: Double) { rate = r; targetRate = r; glidePerFrame = 0.0 }

    val gliding: Boolean get() = rate != targetRate

    /**
     * Runs the stretcher with its output discarded until [outputSourceSec] reaches
     * [cueSec], so a synced track starts exactly on its cue. Returns false while the
     * deck has not decoded enough audio yet (call again on the next block).
     */
    fun prime(cueSec: Double, read: (FloatArray, Int) -> Int): Boolean {
        if (mode != Mode.STRETCH) return true
        val s = stretcher ?: return true
        while (true) {
            val missing = ((cueSec - outputSourceSec) * SAMPLE_RATE).roundToInt()
            if (missing <= 0) return true
            val want = minOf(missing, BLOCK_IN)
            if (deck.available() < want && !deck.ended) return false
            val got = read(inBuf, want)
            if (got <= 0) return deck.ended
            val out = maxOf(1, (got / rate).roundToInt())
            s.process(inBuf, got, outTmp, minOf(out, BLOCK_IN))
            pushDelay(inBuf, got)
        }
    }

    /**
     * Renders [frames] output frames into [out] from offset [from]. [read] pulls source
     * frames from the deck (waiting briefly for data like the mixer always did).
     * Returns the frames rendered; fewer means the deck ran dry or ended.
     */
    fun render(out: FloatArray, from: Int, frames: Int, read: (FloatArray, Int) -> Int): Int = when (mode) {
        Mode.DRY -> readInto(out, from, frames, read)
        Mode.STRETCH -> renderStretch(out, from, frames, read)
        Mode.DELAYED -> renderDelayed(out, from, frames, read)
    }

    private fun readInto(out: FloatArray, from: Int, frames: Int, read: (FloatArray, Int) -> Int): Int {
        var done = 0
        while (done < frames) {
            val n = read(inBuf, minOf(frames - done, BLOCK_IN))
            if (n <= 0) break
            System.arraycopy(inBuf, 0, out, (from + done) * 2, n * 2)
            done += n
        }
        return done
    }

    private fun renderStretch(out: FloatArray, from: Int, frames: Int, read: (FloatArray, Int) -> Int): Int {
        val s = stretcher!!
        var done = 0
        while (done < frames) {
            if (flushed) {
                val k = minOf(frames - done, tailLeft)
                System.arraycopy(tail, tailPos * 2, out, (from + done) * 2, k * 2)
                tailPos += k; tailLeft -= k; done += k
                break
            }
            val n = minOf(frames - done, 256)
            // rate glide (tempo release) in small steps
            if (rate != targetRate) {
                val step = glidePerFrame * n
                rate = if (abs(targetRate - rate) <= step) targetRate else rate + step * Math.signum(targetRate - rate)
            }
            val needExact = n * rate + inCarry
            val need = needExact.toInt()
            val got = if (need > 0) read(inBuf, minOf(need, BLOCK_IN)) else 0
            val produce: Int
            if (got == need) {
                inCarry = needExact - need
                produce = n
            } else {
                // the deck ran dry or ended: stretch what arrived
                inCarry = 0.0
                produce = (got / rate).toInt()
                if (produce == 0) { if (flushAtEnd(s)) continue else break }
            }
            s.process(inBuf, got, outTmp, produce)
            System.arraycopy(outTmp, 0, out, (from + done) * 2, produce * 2)
            pushDelay(inBuf, got)
            done += produce
            if (produce < n) { if (flushAtEnd(s)) continue else break }
        }
        if (switchPending()) beginSwitch()
        return done
    }

    /** At deck end, takes the output still inside the stretcher (its latency) as the tail. */
    private fun flushAtEnd(s: TimeStretcher): Boolean {
        if (!deck.ended) return false
        val n = (s.latencyFrames(rate) / rate).roundToInt()
        if (tail.size < n * 2) tail = FloatArray(n * 2)
        s.flush(tail, n, rate)
        tailPos = 0; tailLeft = n; flushed = true
        return true
    }

    /** Back at the native tempo without pitch shift: hand over to the delayed dry path. */
    private fun switchPending() = !flushed && rate == 1.0 && targetRate == 1.0 && semitones == 0f && switchLeft == 0 &&
        delay.isNotEmpty() && delayFill >= delayFrames

    private fun beginSwitch() {
        mode = Mode.DELAYED
        switchLeft = SWITCH_FRAMES
    }

    private fun renderDelayed(out: FloatArray, from: Int, frames: Int, read: (FloatArray, Int) -> Int): Int {
        var done = 0
        while (done < frames) {
            val n = minOf(frames - done, BLOCK_IN)
            val got = read(inBuf, n)
            val xf = switchLeft > 0
            if (xf) stretcher!!.process(inBuf, got, outTmp, got) // stretched copy to crossfade from
            // the delay line outputs exactly what the stretcher outputs, one latency later
            var produced = 0
            for (i in 0 until got) {
                val r = (delayWrite - delayFill + delay.size / 2) % (delay.size / 2)
                var l = delay[2 * r]
                var rr = delay[2 * r + 1]
                delay[2 * delayWrite] = inBuf[2 * i]; delay[2 * delayWrite + 1] = inBuf[2 * i + 1]
                delayWrite = (delayWrite + 1) % (delay.size / 2)
                if (xf && switchLeft > 0) {
                    val t = 1f - switchLeft.toFloat() / SWITCH_FRAMES
                    l = l * t + outTmp[2 * i] * (1 - t); rr = rr * t + outTmp[2 * i + 1] * (1 - t)
                    switchLeft--
                }
                out[(from + done + i) * 2] = l; out[(from + done + i) * 2 + 1] = rr
                produced++
            }
            done += produced
            if (got < n) {
                // deck ended: drain what is still in the delay line
                if (deck.ended) {
                    while (done < frames && delayFill > 0) {
                        val r = (delayWrite - delayFill + delay.size / 2) % (delay.size / 2)
                        out[(from + done) * 2] = delay[2 * r]; out[(from + done) * 2 + 1] = delay[2 * r + 1]
                        delayFill--; done++
                    }
                }
                break
            }
        }
        return done
    }

    /** Keeps the newest [delayFrames] source frames for the switch back to dry. */
    private fun pushDelay(buf: FloatArray, n: Int) {
        val s = stretcher ?: return
        if (delay.isEmpty()) {
            delayFrames = s.latencyFrames(1.0).roundToInt()
            delay = FloatArray((delayFrames + 1) * 2)
        }
        val cap = delay.size / 2
        for (i in 0 until n) {
            delay[2 * delayWrite] = buf[2 * i]; delay[2 * delayWrite + 1] = buf[2 * i + 1]
            delayWrite = (delayWrite + 1) % cap
        }
        delayFill = minOf(delayFrames, delayFill + n)
    }

    fun close() {
        stretcher?.close()
        stretcher = null
    }

    companion object {
        const val BLOCK_IN = 4096
        const val SWITCH_FRAMES = 960 // 20 ms
        private const val MIN_RATE = 0.45
    }
}
