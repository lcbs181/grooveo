package dev.schlubbe.musicagent.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Number of frequency bands the FFT is reduced to - the UI's Visualizer variants
 * read this many normalized (0f..1f) dB-scaled values. Bumped from 32 to 64 so the
 * particle sphere has enough distinct bands for individual points to visibly react
 * to *different* frequencies rather than a handful of points sharing each band. */
const val VISUALIZER_BAND_COUNT = 64

/** 2048 samples @44.1kHz = ~46ms window / ~21Hz per bin: fine enough resolution to
 * separate bass content (a kick's fundamental sits around 50-100Hz, i.e. only bins
 * 2-5 at a 1024-point FFT) while keeping the window short enough that the visual
 * still reads as instantaneous. */
private const val FFT_SIZE = 2048

/** Minimum newly-accumulated samples before the FFT may run again (~11ms at 44.1kHz).
 * In practice [MIN_PUBLISH_INTERVAL_NANOS] is the binding constraint - this only stops
 * a run of very small audio buffers from each triggering their own transform. */
private const val FFT_HOP = 512

/** Minimum wall-clock gap between published spectra (~60/second). */
private const val MIN_PUBLISH_INTERVAL_NANOS = 16_000_000L

/** How long without a fresh spectrum counts as "the audio tap has gone quiet" - see
 * [AudioVisualizerController.hasGoneStale]. Comfortably longer than the ~16ms publish
 * interval so ordinary scheduling jitter never trips it. */
private const val STALE_THRESHOLD_NANOS = 300_000_000L

/** Log-spaced band range. Below ~35Hz is mostly inaudible rumble/DC offset, above
 * ~16kHz carries almost no musical energy, so spending bands there would waste
 * visual resolution on nothing. */
private const val BAND_LOW_HZ = 35f
private const val BAND_HIGH_HZ = 16_000f

/** dB window mapped onto each band's 0f..1f output. A full-scale sine in a single
 * bin normalizes to ~0dB (see [magnitudeToNormalizedDb]), but real music spreads its
 * energy across bins and rarely drives any single band above roughly -15dB, so that
 * is the top of the useful range rather than 0. Getting these slightly wrong only
 * shifts how "hot" the visual reads (everything is clamped to 0f..1f either way), it
 * cannot break the visualization. */
private const val MIN_DB = -75f
private const val MAX_DB = -15f

/** Per-band smoothing: jump most of the way up immediately so a transient (kick,
 * snare) actually punches, then fall back slowly so the eye can follow the decay.
 * Without the asymmetry the bands either look jittery (no smoothing) or mushy
 * (symmetric smoothing). Applied per published frame. */
private const val ATTACK = 0.6f
private const val DECAY = 0.16f

/** How many of the lowest bands get averaged into the scalar "bass" value used by
 * the Orb/Pulse variants. With [VISUALIZER_BAND_COUNT] log-spaced bands from
 * [BAND_LOW_HZ], the first 10 cover roughly 35-160Hz - kick and bassline territory. */
private const val BASS_BAND_COUNT = 10

/** Averages the lowest [BASS_BAND_COUNT] bands into a single scalar - use this
 * instead of a full-spectrum average wherever a visualizer's scale or motion is
 * meant to track kick-drum/bassline hits specifically rather than overall loudness
 * (which smears bass, mids, and treble together into a smooth drift). */
fun bassAmplitude(bands: FloatArray): Float {
    if (bands.isEmpty()) return 0f
    val count = BASS_BAND_COUNT.coerceAtMost(bands.size)
    var sum = 0f
    for (i in 0 until count) sum += bands[i]
    return (sum / count).coerceIn(0f, 1f)
}

/** Mean of every band - overall perceived loudness, for effects that should react to
 * "the track is loud right now" rather than to bass specifically. */
fun overallAmplitude(bands: FloatArray): Float {
    if (bands.isEmpty()) return 0f
    var sum = 0f
    for (b in bands) sum += b
    return (sum / bands.size).coerceIn(0f, 1f)
}

/**
 * Computes real-time frequency-band levels from the audio this app is actually
 * playing, by tapping ExoPlayer's own decoded PCM through Media3's
 * [TeeAudioProcessor] (see PlaybackService, which installs it into the audio sink)
 * and running an FFT over it.
 *
 * **Why not `android.media.audiofx.Visualizer`:** that is what this class used to
 * wrap, and it never worked. The platform Visualizer effect requires the
 * `RECORD_AUDIO` permission for *any* session, not just the global output mix -
 * Android's own documentation is explicit that "to protect privacy of certain audio
 * data (e.g. voice mail), the use of the visualizer requires the
 * android.permission.RECORD_AUDIO permission". This app never requested it (asking a
 * music player for microphone access to draw an overlay is a bad trade), so
 * constructing the effect always threw, the failure was swallowed by a `runCatching`,
 * the bands stayed all-zero forever, and the UI silently fell back to a decorative
 * pseudo-random loop. The result looked animated but had nothing to do with the
 * audio - the bug this rewrite fixes.
 *
 * Tapping the audio pipeline instead needs no permission at all: this is our own
 * decoded output buffer on its way to the device, not a recording of anything.
 *
 * [handleBuffer] runs on ExoPlayer's audio thread, so everything here is
 * preallocated and allocation-free apart from the one small array published per
 * frame (a fresh copy each time, since consumers read it concurrently and must never
 * observe a half-overwritten spectrum).
 */
@UnstableApi
class AudioVisualizerController : TeeAudioProcessor.AudioBufferSink {

    private val _bands = MutableStateFlow(FloatArray(VISUALIZER_BAND_COUNT))
    val bands: StateFlow<FloatArray> = _bands.asStateFlow()

    // Ring buffer of the most recent mono samples, normalized to -1f..1f.
    private val ring = FloatArray(FFT_SIZE)
    private var ringWrite = 0
    private var samplesSinceFft = 0
    private var totalSamples = 0L

    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val smoothed = FloatArray(VISUALIZER_BAND_COUNT)
    private val bandStart = IntArray(VISUALIZER_BAND_COUNT)
    private val bandEnd = IntArray(VISUALIZER_BAND_COUNT)

    // Hann window - tapers each end of the sample block to zero so a partial cycle at
    // the block boundary doesn't smear energy across every bin (spectral leakage),
    // which would otherwise leave the whole spectrum looking uniformly lit.
    private val window = FloatArray(FFT_SIZE) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat()
    }

    // Twiddle factors, precomputed once. Indexed with a per-stage stride rather than
    // multiplied forward step-by-step, which would accumulate float error across the
    // 11 stages of a 2048-point transform.
    private val cosTable = FloatArray(FFT_SIZE / 2) { i -> cos(2.0 * PI * i / FFT_SIZE).toFloat() }
    private val sinTable = FloatArray(FFT_SIZE / 2) { i -> sin(2.0 * PI * i / FFT_SIZE).toFloat() }

    private var channelCount = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var lastPublishNanos = 0L

    @Volatile
    private var pendingReset = false

    /** Wall-clock nanos of the last published spectrum, for the staleness watchdog -
     * see [hasGoneStale]. Volatile: written on the audio thread, read from the
     * service's watchdog coroutine on the main thread. */
    @Volatile
    private var lastDataNanos = 0L

    init {
        // [flush] is guaranteed to run before any [handleBuffer] (TeeAudioProcessor
        // only forwards input once the processor is active, and flushes it with the
        // format first), but seeding a sane default anyway means a hypothetical
        // missed flush degrades to a slightly-wrong band mapping instead of leaving
        // every band pinned at zero - i.e. a visibly-working visualizer rather than a
        // silently dead one, which is the exact failure this class just got rewritten
        // to eliminate.
        rebuildBandEdges(44_100)
    }

    /** Called by [TeeAudioProcessor] whenever the audio format changes or the pipeline
     * is flushed (track change, seek). Recomputes the band-to-bin mapping for the new
     * sample rate and drops any partially-accumulated block. */
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.channelCount = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        rebuildBandEdges(sampleRateHz)
        ring.fill(0f)
        ringWrite = 0
        samplesSinceFft = 0
        totalSamples = 0L
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        applyPendingResetIfNeeded()
        val pcm = buffer.order(ByteOrder.LITTLE_ENDIAN)
        val channels = channelCount
        when (encoding) {
            C.ENCODING_PCM_16BIT -> while (pcm.remaining() >= 2 * channels) {
                var sum = 0f
                for (c in 0 until channels) sum += pcm.short.toFloat() / 32_768f
                push(sum / channels)
            }
            C.ENCODING_PCM_FLOAT -> while (pcm.remaining() >= 4 * channels) {
                var sum = 0f
                for (c in 0 until channels) sum += pcm.float
                push(sum / channels)
            }
            // Defensive only: Media3 runs ToInt16PcmAudioProcessor ahead of the
            // configured chain on the integer path, and the float path bypasses the
            // chain entirely, so in practice the tee only ever sees 16-bit here.
            C.ENCODING_PCM_32BIT -> while (pcm.remaining() >= 4 * channels) {
                var sum = 0f
                for (c in 0 until channels) sum += pcm.int.toFloat() / Int.MAX_VALUE.toFloat()
                push(sum / channels)
            }
            // Any other encoding (compressed passthrough, 24-bit packed, ...) isn't
            // something we can read sample-accurately here. Leaving the bands at
            // their last value is better than publishing garbage; the visual simply
            // stops reacting rather than showing something invented.
            else -> return
        }

        if (samplesSinceFft >= FFT_HOP && totalSamples >= FFT_SIZE) {
            val now = System.nanoTime()
            if (now - lastPublishNanos >= MIN_PUBLISH_INTERVAL_NANOS) {
                lastPublishNanos = now
                lastDataNanos = now
                samplesSinceFft = 0
                analyzeAndPublish()
            }
        }
    }

    /** Zeroes the spectrum - called when playback stops so the overlay settles to its
     * rest state instead of freezing on whatever the last live frame happened to be.
     *
     * Callable from any thread (the pause hook runs on the app's main thread, the
     * buffers arrive on ExoPlayer's playback thread). Rather than clearing the shared
     * ring/smoothing state from here - which has no happens-before edge to the audio
     * thread, so the clear could simply never be observed and bands would decay from
     * their stale pre-pause values on resume - it publishes the zeroed spectrum
     * immediately and hands the buffer clearing to the audio thread itself via a
     * volatile flag. */
    fun reset() {
        pendingReset = true
        _bands.value = FloatArray(VISUALIZER_BAND_COUNT)
    }

    /** True when playback is supposedly running but no spectrum has been produced for
     * a while, i.e. PCM has stopped reaching this sink even though nothing paused.
     *
     * That is reachable in a few ways this class cannot detect from the inside:
     * compressed passthrough or audio offload (no PCM in the chain at all), an
     * encoding [handleBuffer] can't decode, a stalled decoder - and notably when the
     * hi-res "float output" setting is on *and* the content decodes to >16-bit PCM,
     * because Media3's DefaultAudioSink only splices the configured audio-processor
     * chain (and therefore our TeeAudioProcessor) into its **integer** output path.
     * Without this check the overlay would freeze mid-pose on the last live frame,
     * which reads as a rendering glitch; the caller uses it to publish a rest state
     * instead, so "no data" looks like no data. */
    fun hasGoneStale(): Boolean =
        lastDataNanos != 0L && System.nanoTime() - lastDataNanos > STALE_THRESHOLD_NANOS

    private fun applyPendingResetIfNeeded() {
        if (!pendingReset) return
        pendingReset = false
        smoothed.fill(0f)
        ring.fill(0f)
        ringWrite = 0
        samplesSinceFft = 0
        totalSamples = 0L
    }

    private fun push(sample: Float) {
        ring[ringWrite] = sample
        // FFT_SIZE is a power of two, so the mask is equivalent to `% FFT_SIZE` and
        // saves an integer division per sample in the hottest loop in the app.
        ringWrite = (ringWrite + 1) and (FFT_SIZE - 1)
        samplesSinceFft++
        totalSamples++
    }

    private fun analyzeAndPublish() {
        // Copy oldest-to-newest out of the ring, applying the window as we go.
        for (i in 0 until FFT_SIZE) {
            re[i] = ring[(ringWrite + i) % FFT_SIZE] * window[i]
            im[i] = 0f
        }
        fft()

        val out = FloatArray(VISUALIZER_BAND_COUNT)
        for (b in 0 until VISUALIZER_BAND_COUNT) {
            var peak = 0f
            for (bin in bandStart[b] until bandEnd[b]) {
                val magnitude = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                if (magnitude > peak) peak = magnitude
            }
            val target = magnitudeToNormalizedDb(peak)
            // Fast attack, slow decay - see ATTACK/DECAY.
            val previous = smoothed[b]
            smoothed[b] = if (target > previous) {
                previous + (target - previous) * ATTACK
            } else {
                previous + (target - previous) * DECAY
            }
            out[b] = smoothed[b]
        }
        _bands.value = out
    }

    /** Peak-bin magnitude -> 0f..1f over the [MIN_DB]..[MAX_DB] window. The
     * `4 / FFT_SIZE` factor undoes the transform's length scaling and the Hann
     * window's 0.5 coherent gain, so a full-scale sine lands near 0dB. */
    private fun magnitudeToNormalizedDb(magnitude: Float): Float {
        val normalized = magnitude * 4f / FFT_SIZE
        // The isFinite() half matters: a single NaN sample (reachable on the float
        // path) would otherwise produce log10(NaN) = NaN, and since Kotlin's coerceIn
        // passes NaN straight through (every comparison against NaN is false) the
        // smoothing below would latch that band at NaN permanently, poisoning
        // everything downstream that scales a radius or an alpha by it.
        if (!normalized.isFinite() || normalized <= 1e-7f) return 0f
        val db = 20f * log10(normalized)
        return ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
    }

    /** Maps each band onto the run of FFT bins covering its own log-spaced frequency
     * slice of [BAND_LOW_HZ]..the usable top of the spectrum, so bass gets the fine
     * resolution musical variation actually lives in while treble is pooled coarsely.
     * Bin 0 (DC) is skipped - it carries any constant offset, not audible content.
     *
     * Each band's *own* lower edge is derived from its own frequency rather than
     * chained off the previous band's end. Chaining looked tidier (guaranteed
     * non-overlapping runs) but silently defeated the log spacing: wherever a band's
     * log-width was narrower than one bin, the `end = start + 1` guard forced it onto
     * the next consecutive bin, and at 44.1kHz that made the lowest 31 of 64 bands a
     * plain linear bin-per-band ramp - half the display showing sub-700Hz content,
     * and band 0 centred on 21.5Hz rather than the [BAND_LOW_HZ] it advertises. The
     * trade-off of computing edges independently is that the lowest bands now share
     * bins (below ~215Hz at 44.1kHz there is less than one bin per band), so several
     * neighbours read near-identical values - but they are at least centred on the
     * right frequencies, which is what the visuals index by.
     *
     * The top of the range follows Nyquist rather than being a fixed constant: at a
     * low sample rate a fixed 16kHz ceiling put every band above Nyquist onto the
     * single top bin, which for band-limited content is permanently silent (14 of 64
     * bands dead at 8kHz, 7 at 16kHz). */
    private fun rebuildBandEdges(sampleRateHz: Int) {
        val nyquistBin = FFT_SIZE / 2
        val binHz = sampleRateHz.toFloat() / FFT_SIZE
        val highHz = BAND_HIGH_HZ.coerceAtMost(sampleRateHz * 0.45f)
        val ratio = (highHz / BAND_LOW_HZ).coerceAtLeast(1.0001f)
        for (b in 0 until VISUALIZER_BAND_COUNT) {
            val lowerHz = BAND_LOW_HZ * ratio.pow(b.toFloat() / VISUALIZER_BAND_COUNT)
            val upperHz = BAND_LOW_HZ * ratio.pow((b + 1f) / VISUALIZER_BAND_COUNT)
            val start = (lowerHz / binHz).toInt().coerceIn(1, nyquistBin - 1)
            val end = (upperHz / binHz).toInt().coerceIn(start + 1, nyquistBin)
            bandStart[b] = start
            bandEnd[b] = end
        }
    }

    /** In-place iterative radix-2 Cooley-Tukey FFT over [re]/[im]. */
    private fun fft() {
        val n = FFT_SIZE
        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var swap = re[i]; re[i] = re[j]; re[j] = swap
                swap = im[i]; im[i] = im[j]; im[j] = swap
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val stride = n / len
            var base = 0
            while (base < n) {
                for (k in 0 until half) {
                    val twiddle = k * stride
                    val wr = cosTable[twiddle]
                    val wi = -sinTable[twiddle]
                    val i0 = base + k
                    val i1 = i0 + half
                    val vRe = re[i1] * wr - im[i1] * wi
                    val vIm = re[i1] * wi + im[i1] * wr
                    re[i1] = re[i0] - vRe
                    im[i1] = im[i0] - vIm
                    re[i0] += vRe
                    im[i0] += vIm
                }
                base += len
            }
            len = len shl 1
        }
    }
}
