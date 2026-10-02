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
 */
class ParametricEqAudioProcessor : BaseAudioProcessor() {
    @Volatile private var profile: EqProfile = EqProfile.flat()
    @Volatile private var volume = 1f
    private var eq: EqProcessor? = null
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
        eq = EqProcessor(inputAudioFormat.sampleRate.toDouble()).also {
            it.setProfile(profile)
            it.setVolume(volume)
        }
    }

    override fun onReset() {
        eq = null
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
