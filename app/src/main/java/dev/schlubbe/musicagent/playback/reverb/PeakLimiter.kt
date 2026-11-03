package dev.schlubbe.musicagent.playback.reverb

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Stereo-linked look-ahead peak limiter: lets the "Hall-Anteil" slider push the
 * reverb mix louder without the sum ever going over [ceiling].
 *
 * Per sample the gain needed to keep that frame under the ceiling is held as a
 * minimum over the last [lookahead] frames, released slowly (one-pole, [releaseSeconds]),
 * then box-averaged over [lookahead] frames. The audio is delayed by lookahead - 1
 * frames, so every gain value averaged for an output frame is already at or below the
 * gain that frame needs: peaks are caught by a smooth ramp, never by clipping.
 */
class PeakLimiter(
    private val lookahead: Int = 64,
    private val ceiling: Float = 0.9f,
    private val releaseSeconds: Float = 0.15f,
) {
    private val delayLeft = FloatArray(lookahead)
    private val delayRight = FloatArray(lookahead)
    private val required = FloatArray(lookahead)
    private val released = FloatArray(lookahead)
    private var releasedSum = 0f
    private var envelope = 1f
    private var pos = 0
    private var releaseCoef = 0f

    /** True once [process] has buffered audio that [drain] has not handed back yet. */
    var holdsAudio = false
        private set

    init {
        configure(48_000)
    }

    fun configure(sampleRate: Int) {
        releaseCoef = 1f - exp(-1f / (releaseSeconds * sampleRate.coerceAtLeast(1)))
        reset()
    }

    fun reset() {
        delayLeft.fill(0f)
        delayRight.fill(0f)
        required.fill(1f)
        released.fill(1f)
        releasedSum = lookahead.toFloat()
        envelope = 1f
        pos = 0
        holdsAudio = false
    }

    /** Limits [count] frames in place; the output is the input delayed by lookahead - 1 frames. */
    fun process(left: FloatArray, right: FloatArray, count: Int) {
        holdsAudio = true
        for (i in 0 until count) {
            val l = left[i]
            val r = right[i]
            val peak = max(abs(l), abs(r))
            required[pos] = if (peak > ceiling) ceiling / peak else 1f

            var windowMin = 1f
            for (k in 0 until lookahead) if (required[k] < windowMin) windowMin = required[k]
            envelope = if (windowMin < envelope) windowMin else envelope + (windowMin - envelope) * releaseCoef

            releasedSum += envelope - released[pos]
            released[pos] = envelope
            delayLeft[pos] = l
            delayRight[pos] = r

            val out = (pos + 1) % lookahead
            val gain = releasedSum / lookahead
            left[i] = delayLeft[out] * gain
            right[i] = delayRight[out] * gain

            pos = out
            // Re-sum once per lap so float drift in the running sum can't accumulate.
            if (pos == 0) releasedSum = released.sum()
        }
    }

    /** The lookahead - 1 frames still buffered, oldest first, at the current gain; then reset. */
    fun drain(): Pair<FloatArray, FloatArray> {
        val n = lookahead - 1
        val outLeft = FloatArray(n)
        val outRight = FloatArray(n)
        val gain = minOf(releasedSum / lookahead, envelope)
        for (i in 0 until n) {
            val idx = (pos + 1 + i) % lookahead
            outLeft[i] = delayLeft[idx] * gain
            outRight[i] = delayRight[idx] * gain
        }
        reset()
        return outLeft to outRight
    }
}
