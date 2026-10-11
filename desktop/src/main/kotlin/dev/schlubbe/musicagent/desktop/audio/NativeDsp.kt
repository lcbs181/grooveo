package dev.schlubbe.musicagent.desktop.audio

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * libgrooveo_native (desktop/native): aubio beat tracking and Signalsmith Stretch,
 * built per OS by Gradle (buildNative) and called through JNA.
 */
@Suppress("FunctionName")
internal interface NativeDspLib : Library {
    fun grooveo_beats_new(samplerate: Int, win: Int, hop: Int): Pointer?
    fun grooveo_beats_process(handle: Pointer, samples: FloatArray, n: Int, beats: DoubleArray, maxBeats: Int): Int
    fun grooveo_beats_bpm(handle: Pointer): Float
    fun grooveo_beats_confidence(handle: Pointer): Float
    fun grooveo_beats_free(handle: Pointer)

    fun grooveo_stretch_new(channels: Int, sampleRate: Float): Pointer?
    fun grooveo_stretch_input_latency(handle: Pointer): Int
    fun grooveo_stretch_output_latency(handle: Pointer): Int
    fun grooveo_stretch_set_transpose(handle: Pointer, semitones: Float)
    fun grooveo_stretch_process(handle: Pointer, input: FloatArray, inFrames: Int, output: FloatArray, outFrames: Int)
    fun grooveo_stretch_reset(handle: Pointer)
    fun grooveo_stretch_free(handle: Pointer)
}

internal object NativeDsp {
    /**
     * Packaged app: jpackage's app resources dir (compose.application.resources.dir).
     * Tests: grooveo.native.dir, set by Gradle to the freshly built library.
     */
    val lib: NativeDspLib? by lazy {
        listOfNotNull(System.getProperty("grooveo.native.dir"), System.getProperty("compose.application.resources.dir"))
            .forEach { NativeLibrary.addSearchPath("grooveo_native", it) }
        runCatching { Native.load("grooveo_native", NativeDspLib::class.java) }
            .onFailure { java.util.logging.Logger.getLogger("NativeDsp").warning("native DSP unavailable (no BPM, no tempo sync): ${it.message}") }
            .getOrNull()
    }
}

/**
 * Time-stretcher for one deck (interleaved stereo, 48 kHz): plays [rate] source
 * frames per output frame with the pitch kept, optionally transposed. The source
 * position of the output lags the input by [latencyFrames] (in source frames, at the
 * current rate), which the deck chain takes into account for beat sync.
 */
class TimeStretcher : AutoCloseable {
    private val lib = NativeDsp.lib ?: error("libgrooveo_native not loaded")
    private var handle: Pointer? = lib.grooveo_stretch_new(2, SAMPLE_RATE.toFloat()) ?: error("stretch init failed")
    val inputLatency = lib.grooveo_stretch_input_latency(handle!!)
    val outputLatency = lib.grooveo_stretch_output_latency(handle!!)

    /** Source frames between the newest input and the output being produced, at [rate]. */
    fun latencyFrames(rate: Double): Double = inputLatency + rate * outputLatency

    var semitones: Float = 0f
        set(v) { if (v != field) { field = v; handle?.let { lib.grooveo_stretch_set_transpose(it, v) } } }

    fun process(input: FloatArray, inFrames: Int, output: FloatArray, outFrames: Int) {
        val h = handle ?: return
        lib.grooveo_stretch_process(h, input, inFrames, output, outFrames)
    }

    fun reset() { handle?.let { lib.grooveo_stretch_reset(it) } }

    override fun close() {
        handle?.let { lib.grooveo_stretch_free(it) }
        handle = null
    }

    companion object {
        val available: Boolean get() = NativeDsp.lib != null
    }
}
