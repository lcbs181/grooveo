package dev.schlubbe.musicagent.playback.reverb

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import dev.schlubbe.musicagent.playback.Sound3dPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Real, in-process convolution reverb for "Raumklang" (see [ReverbIrLibrary]'s kdoc
 * for why this exists instead of [android.media.audiofx.PresetReverb] - measured to
 * have no audible effect at all on this device/OS combination, a known issue with
 * several OEMs' own effect-framework implementations).
 *
 * Spliced into [dev.schlubbe.musicagent.playback.PlaybackService]'s
 * [androidx.media3.exoplayer.audio.DefaultAudioSink] processor chain, right
 * alongside the visualizer's TeeAudioProcessor - a genuine part of the audio
 * pipeline, not a platform effect bolted onto a session id, so it works identically
 * on every device regardless of the OEM's own effect implementation.
 *
 * Always reports itself active (stays in the graph at all times, 16-bit stereo PCM
 * only - the one real format this app's audio sink normally runs; anything else
 * passes through with [android.media.audiofx.PresetReverb]'s own old "silently no
 * effect" fallback rather than failing the whole pipeline). [applyPreset] switches
 * instantly with no reconfigure - see its own kdoc for why.
 */
class ConvolutionReverbAudioProcessor(private val context: Context) : BaseAudioProcessor() {

    private class Engine(val left: PartitionedConvolver, val right: PartitionedConvolver, val wetMix: Float)

    private val blockSize = PartitionedConvolver.DEFAULT_BLOCK_SIZE

    // Built off the audio thread (see applyPreset) and cached per preset for as long
    // as the sample rate doesn't change - FFT-ing every impulse-response partition
    // up front is real (if sub-100ms) work that has no business running on the audio
    // thread's next queueInput() the instant a preset changes. ConcurrentHashMap
    // because this is written from processorScope's background thread and read from
    // the audio thread.
    private val enginesByPreset = ConcurrentHashMap<Sound3dPreset, Engine>()
    private val processorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var currentPreset: Sound3dPreset = Sound3dPreset.DISABLED
    @Volatile private var sampleRate: Int = 0

    // Per-channel FIFO of samples queued but not yet enough for a whole
    // PartitionedConvolver.process() block - ExoPlayer hands this arbitrarily-sized
    // buffers, never conveniently aligned to blockSize.
    private var pendingLeft = FloatArray(blockSize * 4)
    private var pendingRight = FloatArray(blockSize * 4)
    private var pendingCount = 0

    private val dryBlockLeft = FloatArray(blockSize)
    private val dryBlockRight = FloatArray(blockSize)
    private val wetBlockLeft = FloatArray(blockSize)
    private val wetBlockRight = FloatArray(blockSize)

    /** Called from [dev.schlubbe.musicagent.playback.Sound3dController], itself
     * reacting to a settings change - pre-builds the new preset's engine on a
     * background dispatcher (so the actual audio thread never blocks on it) before
     * switching, unless the pipeline hasn't been configured yet (nothing has played
     * yet this session), in which case it just switches immediately and the first
     * [queueInput] after playback starts builds it - a one-time exception rather
     * than one this class tries to special-case away. */
    fun applyPreset(preset: Sound3dPreset) {
        if (preset == Sound3dPreset.DISABLED || sampleRate <= 0) {
            currentPreset = preset
            return
        }
        processorScope.launch {
            buildEngine(preset, sampleRate)
            currentPreset = preset
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.sampleRate != sampleRate) {
            // A genuinely different sample rate invalidates every cached engine (an
            // impulse response built for 44.1kHz is the wrong pitch/speed of decay
            // convolved against 48kHz audio) - rebuilt lazily as each preset is next
            // selected, same as a completely fresh app process.
            enginesByPreset.clear()
            sampleRate = inputAudioFormat.sampleRate
        }
        pendingCount = 0
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameCount = inputBuffer.remaining() / BYTES_PER_FRAME
        if (frameCount == 0) return
        ensurePendingCapacity(pendingCount + frameCount)

        val shorts = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until frameCount) {
            pendingLeft[pendingCount + i] = shorts.get(i * 2) / 32768f
            pendingRight[pendingCount + i] = shorts.get(i * 2 + 1) / 32768f
        }
        pendingCount += frameCount
        inputBuffer.position(inputBuffer.limit())

        val processableFrames = (pendingCount / blockSize) * blockSize
        if (processableFrames == 0) return

        val engine = if (currentPreset == Sound3dPreset.DISABLED) null else enginesByPreset[currentPreset]
        val outputBuffer = replaceOutputBuffer(processableFrames * BYTES_PER_FRAME)
        val outShorts = outputBuffer.asShortBuffer()

        var offset = 0
        while (offset < processableFrames) {
            System.arraycopy(pendingLeft, offset, dryBlockLeft, 0, blockSize)
            System.arraycopy(pendingRight, offset, dryBlockRight, 0, blockSize)
            if (engine != null) {
                engine.left.process(dryBlockLeft, wetBlockLeft)
                engine.right.process(dryBlockRight, wetBlockRight)
            }
            for (i in 0 until blockSize) {
                val frame = offset + i
                val l = if (engine != null) (dryBlockLeft[i] + wetBlockLeft[i] * engine.wetMix).coerceIn(-1f, 1f) else dryBlockLeft[i]
                val r = if (engine != null) (dryBlockRight[i] + wetBlockRight[i] * engine.wetMix).coerceIn(-1f, 1f) else dryBlockRight[i]
                outShorts.put(frame * 2, (l * 32767f).toInt().toShort())
                outShorts.put(frame * 2 + 1, (r * 32767f).toInt().toShort())
            }
            offset += blockSize
        }

        val remaining = pendingCount - processableFrames
        System.arraycopy(pendingLeft, processableFrames, pendingLeft, 0, remaining)
        System.arraycopy(pendingRight, processableFrames, pendingRight, 0, remaining)
        pendingCount = remaining
    }

    override fun onFlush() {
        // A seek/track-change shouldn't let a fade's tail leak into unrelated audio,
        // but the engine (and its FFT'd impulse response) is expensive to rebuild -
        // only its history is cleared, not the cached engines themselves.
        enginesByPreset.values.forEach { it.left.reset(); it.right.reset() }
        pendingCount = 0
    }

    override fun onReset() {
        enginesByPreset.clear()
        pendingCount = 0
        sampleRate = 0
    }

    private fun ensurePendingCapacity(minSize: Int) {
        if (pendingLeft.size >= minSize) return
        var newSize = pendingLeft.size
        while (newSize < minSize) newSize *= 2
        pendingLeft = pendingLeft.copyOf(newSize)
        pendingRight = pendingRight.copyOf(newSize)
    }

    private fun buildEngine(preset: Sound3dPreset, sampleRateAtBuildTime: Int) {
        if (enginesByPreset.containsKey(preset)) return
        val (irLeft, irRight) = ReverbIrLibrary.load(context, preset, sampleRateAtBuildTime) ?: return
        enginesByPreset[preset] = Engine(
            left = PartitionedConvolver(irLeft, blockSize),
            right = PartitionedConvolver(irRight, blockSize),
            wetMix = wetMixFor(preset),
        )
    }

    private fun wetMixFor(preset: Sound3dPreset): Float = when (preset) {
        Sound3dPreset.DISABLED -> 0f
        Sound3dPreset.STUDIO -> 0.35f
        Sound3dPreset.HEIMKINO -> 0.55f
        Sound3dPreset.RAVE -> 0.7f
        Sound3dPreset.KINO -> 0.65f
        Sound3dPreset.KONZERT -> 0.75f
        Sound3dPreset.KIRCHE -> 0.8f
    }

    companion object {
        private const val BYTES_PER_FRAME = 4 // 2 channels * 16-bit
    }
}
