package dev.schlubbe.musicagent.playback.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The parametric equalizer as an in-process Media3 audio processor (the same
 * [EqProcessor] DSP the desktop app runs: matched biquads, subsonic filter, bass
 * enhancer, loudness compensation, look-ahead limiter).
 *
 * Replaces the old [android.media.audiofx.Equalizer] effect, whose band count,
 * frequencies and ranges were device-dependent (usually 5 fixed bands, +-15 dB) and
 * which had to be re-attached on every audio session change. Running in the sink's
 * processor chain makes the EQ behave identically on every device.
 *
 * Handles 16-bit and float PCM, mono or stereo, at any sample rate (filters are
 * redesigned for the actual rate in [onConfigure]).
 *
 * Runs the [LoudnessNormalizer] first ("Lautstärke angleichen"), independent of
 * whether the EQ itself is on; [startTrack] tells it which track is playing.
 */
class ParametricEqAudioProcessor : BaseAudioProcessor() {
    @Volatile private var profile: EqProfile = EqProfile.flat()
    @Volatile private var volume = 1f
    private var eq: EqProcessor? = null
    @Volatile private var normalize = false
    @Volatile private var track: Pair<String, Double?>? = null
    // kept across flushes (seeks) of the same sample rate, so a seek neither
    // restarts the measurement nor jumps the gain
    private var normalizer: LoudnessNormalizer? = null
    private var normalizerRate = 0
    private var work = FloatArray(0)

    /** Safe from any thread; takes effect with the next buffer (smoothly, no clicks). */
    fun setProfile(p: EqProfile) {
        profile = p
        eq?.setProfile(p)
    }

    /** Listening level 0..1 on the engine's cubic curve, for loudness compensation. */
    fun setVolume(v: Float) {
        volume = v
        eq?.setVolume(v)
    }

    /** Turns loudness normalisation on or off (glides, no jump). */
    fun setNormalization(enabled: Boolean) {
        normalize = enabled
        normalizer?.enabled = enabled
    }

    /** A new track starts playing: [id] is "source:sourceId", [knownLufs] its stored loudness. */
    fun startTrack(id: String, knownLufs: Double?) {
        track = id to knownLufs
        normalizer?.startTrack(id, knownLufs)
    }

    /** The current track's measured loudness, once enough of it was heard to store it. */
    fun measured(): Pair<String, Double>? {
        val n = normalizer ?: return null
        val id = n.trackId ?: return null
        return n.measuredLufs()?.let { id to it }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if ((enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount !in 1..2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        // fresh filter/limiter state after a seek or format change, so no tail of the
        // previous position leaks into the new one
        val rate = inputAudioFormat.sampleRate
        eq = EqProcessor(rate.toDouble()).also {
            it.setProfile(profile)
            it.setVolume(volume)
        }
        if (normalizer == null || normalizerRate != rate) {
            normalizerRate = rate
            normalizer = LoudnessNormalizer(rate.toDouble()).also { n ->
                n.enabled = normalize
                track?.let { (id, k) -> n.startTrack(id, k) }
            }
        }
    }

    override fun onReset() {
        eq = null
        normalizer = null
        normalizerRate = 0
        work = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val p = eq ?: return passThrough(inputBuffer)
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val channels = inputAudioFormat.channelCount
        val sampleBytes = if (float) 4 else 2
        val frames = bytes / (sampleBytes * channels)
        if (work.size < frames * 2) work = FloatArray(frames * 2)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val base = inputBuffer.position()
        for (i in 0 until frames) {
            for (ch in 0 until 2) {
                val idx = base + (i * channels + ch.coerceAtMost(channels - 1)) * sampleBytes
                work[2 * i + ch] = if (float) inputBuffer.getFloat(idx) else inputBuffer.getShort(idx) / 32768f
            }
        }
        inputBuffer.position(inputBuffer.limit())
        normalizer?.let { n ->
            n.process(work, frames)
            p.forceLimiter = n.currentGainDb > 0.05
        }
        p.process(work, frames)
        val out = replaceOutputBuffer(frames * channels * sampleBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            for (ch in 0 until channels) {
                val v = work[2 * i + ch]
                if (float) out.putFloat(v) else out.putShort(toPcm16(v))
            }
        }
        out.flip()
    }

    private fun passThrough(inputBuffer: ByteBuffer) {
        replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
    }

    companion object {
        fun toPcm16(v: Float): Short = (v * 32768f).toInt().coerceIn(-32768, 32767).toShort()
    }
}
