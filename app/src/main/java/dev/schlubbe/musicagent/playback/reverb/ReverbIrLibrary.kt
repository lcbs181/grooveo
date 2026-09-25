package dev.schlubbe.musicagent.playback.reverb

import android.content.Context
import dev.schlubbe.musicagent.playback.Sound3dPreset
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads the bundled, real impulse response for each named "Raumklang" space and
 * resamples it to whatever rate the live audio pipeline is actually running at -
 * see [ConvolutionReverbAudioProcessor]'s kdoc for why this exists at all instead of
 * [android.media.audiofx.PresetReverb] (measured to have no audible effect on this
 * app's test device/OS combination).
 *
 * All six are real recordings, not synthesized - sourced from Freesound.org, every
 * one released CC0 (public domain) by its recordist. No CC0 impulse response of an
 * actual cinema/theatre or nightclub turned up despite a real search, so Kino and
 * Rave use the closest available real-space stand-in instead (noted below); every
 * other name is a direct match for what it's called:
 * - Kirche: "IR_Cathedral_5m_Stereo.wav" by Nox_Sound
 * - Konzert: "IR Liverpool Philharmonic Hall.wav" by johnnyguitar01
 * - Kino: "The Knights Hall IR.wav" by johnnyguitar01 - a grand hall, not a cinema
 * - Rave: "4.1s Long Parking Garage IR Reverb" by djericmark - a dense,
 *   hard-surfaced concrete space, not an actual club
 * - Heimkino: "Living Room Impulse" by Fission9
 * - Studio: "STUDIO A (COLLEGE RECORDING STUDIO) IR REVERB" by djericmark
 *
 * Bundled under app/src/main/assets/reverb/ as raw interleaved 16-bit stereo PCM at
 * [BUNDLED_SAMPLE_RATE] rather than the original WAV/MP3 files - no header to parse,
 * no codec involved to read them back, and a fraction of the size of the originals
 * (each trimmed to where its tail actually decays into the noise floor, peak
 * -normalized, and downsampled - a reverb tail carries little content that high
 * anyway, and the source was already a lossy encode).
 */
object ReverbIrLibrary {
    private const val BUNDLED_SAMPLE_RATE = 22050

    private val ASSET_NAMES = mapOf(
        Sound3dPreset.KIRCHE to "kirche.pcm",
        Sound3dPreset.KONZERT to "konzert.pcm",
        Sound3dPreset.KINO to "kino.pcm",
        Sound3dPreset.RAVE to "rave.pcm",
        Sound3dPreset.HEIMKINO to "heimkino.pcm",
        Sound3dPreset.STUDIO to "studio.pcm",
    )

    /** (left, right) impulse response arrays resampled to [targetSampleRate] - null
     * only for [Sound3dPreset.DISABLED], which has no impulse response at all. */
    fun load(context: Context, preset: Sound3dPreset, targetSampleRate: Int): Pair<FloatArray, FloatArray>? {
        val assetName = ASSET_NAMES[preset] ?: return null
        val bytes = context.assets.open("reverb/$assetName").use { it.readBytes() }
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frameCount = shorts.remaining() / 2
        val left = FloatArray(frameCount)
        val right = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            left[i] = shorts.get(i * 2) / 32768f
            right[i] = shorts.get(i * 2 + 1) / 32768f
        }
        val l = if (targetSampleRate == BUNDLED_SAMPLE_RATE) left else resample(left, targetSampleRate)
        val r = if (targetSampleRate == BUNDLED_SAMPLE_RATE) right else resample(right, targetSampleRate)
        normalizeEnergy(l, r)
        return l to r
    }

    /** Scales both channels so the impulse response has unit energy (sum of squares
     * averaged over the two channels = 1). The assets are only peak-normalized, and
     * a dense multi-second tail convolved at peak level came out several times
     * louder than the dry signal and clipped - wet gain in the processor is only
     * meaningful relative to a unit-energy IR. */
    private fun normalizeEnergy(left: FloatArray, right: FloatArray) {
        var energy = 0.0
        for (v in left) energy += v * v
        for (v in right) energy += v * v
        if (energy <= 0.0) return
        val scale = (1.0 / kotlin.math.sqrt(energy / 2)).toFloat()
        for (i in left.indices) left[i] *= scale
        for (i in right.indices) right[i] *= scale
    }

    /** Plain linear-interpolation resampler - entirely adequate here: the source is
     * already a lossy encode at [BUNDLED_SAMPLE_RATE], so a sharper (and much more
     * expensive) resampling filter would just be polishing noise. */
    private fun resample(input: FloatArray, targetSampleRate: Int): FloatArray {
        val ratio = targetSampleRate.toDouble() / BUNDLED_SAMPLE_RATE
        val outLength = (input.size * ratio).toInt().coerceAtLeast(1)
        val output = FloatArray(outLength)
        for (i in 0 until outLength) {
            val srcPos = i / ratio
            val idx0 = srcPos.toInt().coerceIn(0, input.size - 1)
            val idx1 = (idx0 + 1).coerceAtMost(input.size - 1)
            val frac = (srcPos - idx0).toFloat()
            output[i] = input[idx0] * (1 - frac) + input[idx1] * frac
        }
        return output
    }
}
