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

    private class Engine(val left: PartitionedConvolver, val right: PartitionedConvolver, val wetMix: Float) {
        // Keeps overall loudness roughly level when a preset is engaged instead of
        // stacking the wet tail on top of full-level dry signal.
        val dryMix = 1f - wetMix * 0.5f
    }

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

    override fun queueInput(inputBuffer: ByteBuffer) = androidx.tracing.trace("Reverb.queueInput") { queueInputTraced(inputBuffer) }

    private fun queueInputTraced(inputBuffer: ByteBuffer) {
        val frameCount = inputBuffer.remaining() / BYTES_PER_FRAME
        if (frameCount == 0) return
        val preset = currentPreset
        if (preset == Sound3dPreset.DISABLED || enginesByPreset[preset] == null) {
            passThrough(inputBuffer)
            return
        }
        ensurePendingCapacity(pendingCount + frameCount)

        // Absolute-indexed get/put on the buffers ExoPlayer itself hands in/out,
        // rather than asShortBuffer() - that allocates a brand new view object on
        // every single call (dozens of times a second, for the entire lifetime of
        // playback, whether or not a preset is even engaged). Harmless in isolation,
        // but this runs on the real-time audio thread, and this device was already
        // under real memory pressure - any avoidable per-call allocation there is
        // exactly what risks a GC pause landing mid-buffer and showing up as a
        // stutter, independent of how cheap the DSP itself is.
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val inBase = inputBuffer.position()
        for (i in 0 until frameCount) {
            val frameBase = inBase + i * BYTES_PER_FRAME
            pendingLeft[pendingCount + i] = inputBuffer.getShort(frameBase) / 32768f
            pendingRight[pendingCount + i] = inputBuffer.getShort(frameBase + 2) / 32768f
        }
        pendingCount += frameCount
        inputBuffer.position(inputBuffer.limit())

        val processableFrames = (pendingCount / blockSize) * blockSize
        if (processableFrames == 0) return

        val engine = if (currentPreset == Sound3dPreset.DISABLED) null else enginesByPreset[currentPreset]
        val outputBuffer = replaceOutputBuffer(processableFrames * BYTES_PER_FRAME)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        var offset = 0
        while (offset < processableFrames) {
            System.arraycopy(pendingLeft, offset, dryBlockLeft, 0, blockSize)
            System.arraycopy(pendingRight, offset, dryBlockRight, 0, blockSize)
            if (engine != null) {
                engine.left.process(dryBlockLeft, wetBlockLeft)
                engine.right.process(dryBlockRight, wetBlockRight)
            }
            for (i in 0 until blockSize) {
                val frameBase = (offset + i) * BYTES_PER_FRAME
                val l = if (engine != null) softClip(dryBlockLeft[i] * engine.dryMix + wetBlockLeft[i] * engine.wetMix) else dryBlockLeft[i]
                val r = if (engine != null) softClip(dryBlockRight[i] * engine.dryMix + wetBlockRight[i] * engine.wetMix) else dryBlockRight[i]
                outputBuffer.putShort(frameBase, (l * 32767f).toInt().toShort())
                outputBuffer.putShort(frameBase + 2, (r * 32767f).toInt().toShort())
            }
            offset += blockSize
        }

        // Absolute puts leave position/limit untouched, and replaceOutputBuffer()
        // hands back a clear()ed, possibly larger reused buffer - without this the
        // sink played everything up to capacity, stale bytes included (the crackle).
        outputBuffer.position(0)
        outputBuffer.limit(processableFrames * BYTES_PER_FRAME)

        val remaining = pendingCount - processableFrames
        System.arraycopy(pendingLeft, processableFrames, pendingLeft, 0, remaining)
        System.arraycopy(pendingRight, processableFrames, pendingRight, 0, remaining)
        pendingCount = remaining
    }

    /** Off (or engine not built yet): straight copy, no block re-chunking and no
     * added latency - plus whatever dry samples were still queued from before the
     * preset was switched off, so nothing is dropped at the switch. */
    private fun passThrough(inputBuffer: ByteBuffer) {
        val inBytes = inputBuffer.remaining()
        val out = replaceOutputBuffer(pendingCount * BYTES_PER_FRAME + inBytes)
        out.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until pendingCount) {
            out.putShort((pendingLeft[i] * 32767f).toInt().toShort())
            out.putShort((pendingRight[i] * 32767f).toInt().toShort())
        }
        pendingCount = 0
        out.put(inputBuffer)
        out.flip()
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
        Sound3dPreset.STUDIO -> 0.12f
        Sound3dPreset.HEIMKINO -> 0.18f
        Sound3dPreset.RAVE -> 0.22f
        Sound3dPreset.KINO -> 0.22f
        Sound3dPreset.KONZERT -> 0.26f
        Sound3dPreset.KIRCHE -> 0.3f
    }

    // Linear below 0.8, smooth knee above - residual overs from a loud transient
    // bend instead of hard-clipping into audible crackle.
    private fun softClip(x: Float): Float {
        val a = kotlin.math.abs(x)
        if (a <= 0.8f) return x
        val over = a - 0.8f
        val shaped = 0.8f + 0.2f * (over / (over + 0.2f))
        return if (x < 0) -shaped else shaped
    }

    companion object {
        private const val BYTES_PER_FRAME = 4 // 2 channels * 16-bit
    }
}
