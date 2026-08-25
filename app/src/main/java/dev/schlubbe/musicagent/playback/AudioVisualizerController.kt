package dev.schlubbe.musicagent.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import dev.schlubbe.musicagent.playback.visualizer.PcmRingBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Number of frequency bands the FFT is reduced to - the UI's Visualizer variants
 * read this many normalized (0f..1f) dB-scaled values.
 *
 * Raised 32 -> 64 -> 256 as the particle sphere's point count grew. The sphere gives
 * every point its own frequency, so the band count is what decides how many genuinely
 * distinct frequencies exist to hand out: at 64 bands a thousand points meant sixteen
 * neighbours all moving as one, which is a much coarser instrument than it looks.
 *
 * 256 is near the useful ceiling for a 2048-point transform. Bin spacing at 44.1kHz is
 * 21.5Hz, so below roughly 1kHz several adjacent bands necessarily resolve to the same
 * bin and read identical values - see rebuildBandEdges, which maps each band from its
 * own frequency rather than chaining off its neighbour precisely so that those bands
 * stay centred correctly instead of collapsing into a linear ramp. Going higher would
 * add bands without adding information. */
const val VISUALIZER_BAND_COUNT = 256

private const val TAG = "VizTap"

/** 2048 samples @44.1kHz = ~46ms window / ~21Hz per bin: fine enough resolution to
 * separate bass content (a kick's fundamental sits around 50-100Hz, i.e. only bins
 * 2-5 at a 1024-point FFT) while keeping the window short enough that the visual
 * still reads as instantaneous. */
private const val FFT_SIZE = 2048

/** Target spectra per second of *audio*. The hop between transforms is derived from
 * this and the sample rate, so the analysis grid stays even in musical time regardless
 * of how the host happens to deliver buffers. */
private const val TARGET_FRAMES_PER_SECOND = 60

/** Depth of the queue between the audio thread and the frame pump.
 *
 * Decoded PCM does not arrive smoothly: measured on device, this tap receives one
 * buffer roughly every 125ms carrying 125ms of audio, and every hop inside that buffer
 * is processed within a millisecond or two of wall-clock time. A wall-clock rate gate
 * therefore admitted exactly one spectrum per buffer and discarded the other eleven,
 * which pinned the visualizer at ~8 updates a second no matter what the analysis did.
 * Queuing the burst and releasing it a frame at a time restores an even 60Hz.
 *
 * The queue is also a feature rather than only a smoothing buffer: audio handed to the
 * sink has not been heard yet - it plays out about one buffer later - so releasing
 * frames a buffer behind the tap moves the visuals closer to what the ear is hearing,
 * not further from it. Beyond this depth the oldest frames are dropped rather than
 * allowed to accumulate lag. */
private const val MAX_QUEUED_FRAMES = 16

/** Capacity of the hand-off ring between the audio thread and the analysis thread, in
 * mono samples. 32768 is about 0.74s at 44.1kHz - comfortably more than the ~125ms
 * buffers this tap was measured to receive, so ordinary scheduling jitter never drops
 * anything, while still bounding how far behind the analysis can fall. */
private const val PCM_RING_CAPACITY = 1 shl 15

/** How long the analysis thread waits when the ring is empty. It is also unparked by
 * the producer, so this is only a backstop against a missed wake-up, not the normal
 * path. */
private const val ANALYSIS_IDLE_PARK_NANOS = 2_000_000L

/** Size of the fixed scratch arrays used to move samples across the ring. */
private const val TRANSFER_CHUNK = 2048

/** Strength and pass count of the lateral smoothing applied across bands before
 * publication - see [AudioVisualizerController.diffuseBands].
 *
 * Much gentler than the two passes at 0.18 this started with. That figure comes from
 * visualizers with a few dozen bands, where coupling a band to its neighbours spans a
 * wide slice of the spectrum and deliberately produces waves rolling across the
 * elements. At 256 bands one neighbour is 1/256th of a log decade away, so the same
 * setting no longer buys coherence - it only smears genuinely distinct frequencies
 * together, and the rolling wave it created across the sphere is exactly what the
 * design is not supposed to have. One light pass is left purely to take the edge off
 * single-band noise. */
private const val BAND_DIFFUSION = 0.15f
private const val BAND_DIFFUSION_PASSES = 1

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

/** Everything below this counts as bass. Open-ended downwards - the window runs from
 * the very lowest band the FFT resolves up to here - so no low-frequency content is
 * excluded and a deep sub hits the value just as hard as a kick fundamental.
 *
 * The ceiling sits just under the midrange rather than at the textbook 250Hz
 * bass/low-mid split: a kick's body and click extend past 250Hz, and including them
 * makes each individual kick, and the moment a drop lands, read far more obviously
 * than a window confined to the fundamental alone. */
private const val BASS_MAX_HZ = 320f

/** Number of bands below [BASS_MAX_HZ], resolved in
 * [AudioVisualizerController.rebuildBandEdges] rather than hardcoded, because the log
 * band mapping is Nyquist-dependent - at a low sample rate the same Hz ceiling covers
 * a noticeably larger share of the bands. */
@Volatile
private var bassBandEnd = 24

/** Length of the rolling window the adaptive normalization below measures its
 * reference range over: ~2.5s at the ~60 published frames a second. Long enough to
 * span several beats (so an individual kick can't drag the reference up with it),
 * short enough that the visual re-adapts within a couple of seconds of a section
 * change - an intro giving way to a drop, or a quiet breakdown. */
private const val STAT_WINDOW = 150

/** Below this much spread between the window's quietest and loudest moment there is
 * no meaningful dynamic to normalize against (digital silence, or a perfectly steady
 * tone), and dividing by it would amplify noise into a full-scale flicker. */
private const val MIN_SPAN = 0.09f

/** Cutoff of the cascaded one-pole low-pass the kick detector listens through.
 * Three poles give -18dB/octave, so by 1kHz the snare and everything above it are
 * ~30dB down and the envelope is following the kick and bassline rather than the
 * whole mix. */
private const val KICK_LOWPASS_HZ = 150f

/** Envelope time constants for the kick detector, in seconds. The fast envelope has
 * to rise inside a kick's attack (a few milliseconds); the slow one is the reference
 * level that rise is measured against, so it averages across several beats. */
private const val KICK_FAST_ATTACK_SECONDS = 0.002f
private const val KICK_FAST_RELEASE_SECONDS = 0.080f
private const val KICK_SLOW_SECONDS = 0.35f

/** How often the detector samples its own envelope, in audio frames - ~1.5ms at
 * 44.1kHz, several steps inside a kick's attack. */
private const val KICK_STEP_FRAMES = 64

/** Rise per step, as a fraction of the slow envelope, mapped onto 0f..1f of
 * [VisualizerFrame.bassOnset]. */
private const val KICK_RISE_FLOOR = 0.05f
private const val KICK_RISE_CEILING = 0.34f

/** Minimum gap between two detected kicks - faster than any real pattern, but enough
 * that one hit's attack cannot register as several. */
private const val KICK_REFRACTORY_SECONDS = 0.11f

/** Headroom on the adaptive normalization's ceiling. Without it the reference is the
 * highest value seen in the window, so during any loud passage the current value *is*
 * the maximum for frames at a time and the output sits pinned at exactly 1.0 - the
 * effect stops responding precisely when the music is at its most energetic. Dividing
 * by a slightly wider span puts an ordinary peak near 0.85 and reserves the top of the
 * range for something genuinely bigger than anything recent. */
private const val NORMALIZE_HEADROOM = 1.18f

/** How much of a published band value comes from its absolute level rather than its
 * adaptively-normalized one. The absolute part preserves the spectrum's *shape* (a
 * bass-heavy track still looks bass-heavy), the normalized part supplies the *motion*.
 * Weighted towards motion, because shape alone is what made the old output look like a
 * static plateau with a barely visible ripple on top. */
private const val ABSOLUTE_MIX = 0.35f

/** Per-band envelope followers for the adaptive normalization, in units per second.
 * The ceiling falls slowly so a band keeps its headroom between hits; the floor rises
 * more slowly still, so a band that goes quiet is not immediately re-normalized back
 * up to full scale. */
private const val BAND_CEILING_FALL_PER_SEC = 0.35f
private const val BAND_FLOOR_RISE_PER_SEC = 0.15f

/** Decay of the onset (kick) envelope: full scale to zero in this many seconds. About
 * a fifth of a second reads as a distinct punch per kick at anything up to ~180bpm,
 * without the envelope still being half-open when the next one lands. */
private const val ONSET_DECAY_SECONDS = 0.18f

/** One published spectrum plus the scalars derived from it.
 *
 * The scalars are computed here, on the audio thread, rather than by each consumer
 * from [bands]: they need per-frame history (see [AudioVisualizerController]'s
 * adaptive normalization), which a stateless helper over the published array cannot
 * have - that is exactly why the previous `bassAmplitude(bands)` helper could only
 * ever report an absolute level, and why everything driven by it sat pinned near its
 * maximum for the whole of a loud track. */
class VisualizerFrame(
    /** Per-band levels, 0f..1f, low frequency first. */
    val bands: FloatArray,
    /** Bass energy normalized against its own recent range, so a kick swings this
     * across most of 0f..1f instead of nudging a plateau. Use for anything whose
     * *size* should follow the bass: a ring radius, a core scale, a wave height. */
    val bass: Float,
    /** Kick/transient strength, from the time-domain detector in [detectKick] rather
     * than from the spectrum: near zero during a sustained bassline however loud it is,
     * and spiking on each new attack. Use for anything that should fire *per hit*:
     * confetti bursts, ripples, flashes. */
    val bassOnset: Float,
    /** Overall loudness, half absolute and half normalized against its recent range. */
    val level: Float,
    /** Bass and overall loudness as plain absolute readings, un-normalized.
     *
     * Only for consumers that compare against their own multi-second history - the
     * drop detector, which asks "is there less bass now than over the last six
     * seconds". Feeding it the normalized values instead would have it comparing one
     * adaptive measurement against another, and a build-up (where bass is deliberately
     * stripped out) would normalize straight back up to full scale and hide the very
     * thing being looked for. */
    val bassAbsolute: Float,
    val levelAbsolute: Float,
)

val EMPTY_VISUALIZER_FRAME = VisualizerFrame(FloatArray(VISUALIZER_BAND_COUNT), 0f, 0f, 0f, 0f, 0f)

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

    private val _frames = MutableStateFlow(EMPTY_VISUALIZER_FRAME)

    /** The frame the UI should draw right now. Advanced by [pumpNextFrame] once per
     * display frame rather than written directly from the audio thread, which delivers
     * in bursts - see [MAX_QUEUED_FRAMES]. */
    val frames: StateFlow<VisualizerFrame> = _frames.asStateFlow()

    // Hand-off ring between the audio thread (producer) and the pump (consumer). A
    // plain array plus a lock rather than a concurrent collection: the critical section
    // is a couple of field writes, and this allocates nothing per frame.
    private val queue = arrayOfNulls<VisualizerFrame>(MAX_QUEUED_FRAMES)
    private var queueHead = 0
    private var queueCount = 0
    private val queueLock = Any()

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

    // --- adaptive normalization state -------------------------------------------
    // Per-band envelope followers bracketing each band's own recent range.
    private val bandCeiling = FloatArray(VISUALIZER_BAND_COUNT)
    private val bandFloor = FloatArray(VISUALIZER_BAND_COUNT) { 1f }
    // Rolling histories the bass/flux/level references are measured over. A window
    // min/max, rather than the cheaper envelope follower used per band, because these
    // three drive the effects the eye reads as "on the beat": a follower's floor
    // either creeps up fast enough to swallow the gap between two kicks or too slowly
    // to catch up when a section changes, and there is no setting that does neither.
    private val bassHistory = FloatArray(STAT_WINDOW)
    private val levelHistory = FloatArray(STAT_WINDOW)
    private var historyWrite = 0
    private var historyCount = 0
    private var onsetEnvelope = 0f
    private var warnedAboutEncoding = false

    // --- time-domain kick detector ------------------------------------------
    // Deliberately not derived from the FFT. The transform runs over a 2048-sample
    // window - ~46ms at 44.1kHz - while a kick's attack is roughly 10ms, so the
    // window smears the transient across itself and what reaches the spectrum is a
    // broad bump rather than an edge. Every spectral formulation tried against real
    // material (bass-band flux, high-frequency-weighted flux, flux against a lagged
    // spectrum, each z-scored against its own distribution) reported the beat late,
    // weakly, or not at all, because the information is not present in a 46ms window
    // to begin with. Running on the sample stream gives the detector the time
    // resolution the problem actually needs, for a handful of flops per sample.
    private var lowPass1 = 0f
    private var lowPass2 = 0f
    private var lowPass3 = 0f
    private var lowPassCoeff = 0.021f
    private var kickFastEnvelope = 0f
    private var kickSlowEnvelope = 0f
    private var kickPreviousFast = 0f
    private var kickAttackCoeff = 0.011f
    private var kickReleaseCoeff = 0.00028f
    private var kickSlowCoeff = 6.5e-5f
    private var kickStepCountdown = KICK_STEP_FRAMES
    private var samplesSinceKick = Int.MAX_VALUE
    private var kickRefractoryFrames = 4851
    private var hopSamples = 735
    private val diffusionScratch = FloatArray(VISUALIZER_BAND_COUNT)

    /** Strongest kick seen since the last publish, consumed by [analyzeAndPublish].
     * Kicks are detected at ~1.5ms resolution but published at ~16ms, so without this
     * the hit that mattered would usually fall between two published frames. */
    private var pendingKick = 0f
    private var bassSmoothed = 0f
    private var levelSmoothed = 0f

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

    // --- audio thread -> analysis thread hand-off ----------------------------
    // The transform used to run inside handleBuffer, i.e. on ExoPlayer's audio output
    // thread (confirmed on device: the same TID as AudioTrack). A 2048-point FFT plus a
    // 256-band reduction plus a per-frame allocation on the one thread with a hard
    // deadline is a glitch risk for no benefit - none of that work needs to be there.
    // Now the audio thread only decodes to mono float and copies into this ring.
    private val pcmRing = PcmRingBuffer(PCM_RING_CAPACITY)
    private val decodeScratch = FloatArray(TRANSFER_CHUNK)
    private val analysisScratch = FloatArray(TRANSFER_CHUNK)

    @Volatile
    private var analysisRunning = true

    /** Bumped by [flush] so the analysis thread knows to re-derive everything that
     * depends on the stream format. The rebuild itself must happen on the analysis
     * thread, because it rewrites the band tables and filter coefficients that thread
     * is actively reading. */
    @Volatile
    private var formatEpoch = 0

    @Volatile
    private var sampleRate = 44_100f

    @Volatile
    private var channelCount = 2

    @Volatile
    private var encoding = C.ENCODING_PCM_16BIT

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
        rebuildKickCoefficients(44_100)
    }

    private val analysisThread = Thread({ runAnalysisLoop() }, "VisualizerAnalysis").apply {
        isDaemon = true
        // Above normal, but nowhere near the audio thread's priority: this must keep up
        // with real time to look right, and must never compete with playback to sound
        // right.
        priority = Thread.NORM_PRIORITY + 1
    }

    // Separate init block, after the property above: Kotlin runs initializers in
    // declaration order, so starting the thread from the first init block would read it
    // before it exists.
    init {
        analysisThread.start()
    }

    /** Drains the ring and runs the whole analysis chain. Owns every field the analysis
     * touches, so nothing here needs synchronization beyond the ring itself and the
     * volatile format/reset flags. */
    private fun runAnalysisLoop() {
        var appliedEpoch = formatEpoch
        applyFormat()
        while (analysisRunning) {
            val epoch = formatEpoch
            if (epoch != appliedEpoch) {
                appliedEpoch = epoch
                applyFormat()
            }
            val count = pcmRing.read(analysisScratch, TRANSFER_CHUNK)
            if (count == 0) {
                LockSupport.parkNanos(ANALYSIS_IDLE_PARK_NANOS)
                continue
            }
            applyPendingResetIfNeeded()
            for (i in 0 until count) push(analysisScratch[i])
        }
    }

    /** Re-derives everything that depends on the stream format, on the analysis thread. */
    private fun applyFormat() {
        val rate = sampleRate.toInt()
        rebuildBandEdges(rate)
        rebuildKickCoefficients(rate)
        pcmRing.clear()
        ring.fill(0f)
        ringWrite = 0
        samplesSinceFft = 0
        totalSamples = 0L
        clearAdaptiveState()
    }

    /** Stops the analysis thread. Call from the owner's teardown. */
    fun release() {
        analysisRunning = false
        LockSupport.unpark(analysisThread)
    }

    /** Called by [TeeAudioProcessor] whenever the audio format changes or the pipeline
     * is flushed (track change, seek). Recomputes the band-to-bin mapping for the new
     * sample rate and drops any partially-accumulated block. */
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.sampleRate = sampleRateHz.coerceAtLeast(8_000).toFloat()
        this.channelCount = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        clearQueue()
        // The actual rebuild is deferred to the analysis thread - see applyFormat. It
        // rewrites the band tables and the filter coefficients, which that thread reads
        // continuously, so doing it here would be a data race.
        formatEpoch++
        LockSupport.unpark(analysisThread)
    }

    /**
     * Runs on ExoPlayer's audio output thread. Decodes to mono float and hands the
     * samples to the analysis thread - and does nothing else. No transform, no
     * allocation, no lock, no wait.
     */
    override fun handleBuffer(buffer: ByteBuffer) {
        val pcm = buffer.order(ByteOrder.LITTLE_ENDIAN)
        val channels = channelCount
        var pending = 0
        when (encoding) {
            C.ENCODING_PCM_16BIT -> while (pcm.remaining() >= 2 * channels) {
                var sum = 0f
                for (c in 0 until channels) sum += pcm.short.toFloat() / 32_768f
                decodeScratch[pending++] = sum / channels
                if (pending == TRANSFER_CHUNK) {
                    pcmRing.write(decodeScratch, pending)
                    pending = 0
                }
            }
            C.ENCODING_PCM_FLOAT -> while (pcm.remaining() >= 4 * channels) {
                var sum = 0f
                for (c in 0 until channels) sum += pcm.float
                decodeScratch[pending++] = sum / channels
                if (pending == TRANSFER_CHUNK) {
                    pcmRing.write(decodeScratch, pending)
                    pending = 0
                }
            }
            // Defensive only: Media3 runs ToInt16PcmAudioProcessor ahead of the
            // configured chain on the integer path, and the float path bypasses the
            // chain entirely, so in practice the tee only ever sees 16-bit here.
            C.ENCODING_PCM_32BIT -> while (pcm.remaining() >= 4 * channels) {
                var sum = 0f
                for (c in 0 until channels) sum += pcm.int.toFloat() / Int.MAX_VALUE.toFloat()
                decodeScratch[pending++] = sum / channels
                if (pending == TRANSFER_CHUNK) {
                    pcmRing.write(decodeScratch, pending)
                    pending = 0
                }
            }
            // Any other encoding (compressed passthrough, 24-bit packed, ...) isn't
            // something we can read sample-accurately here. Leaving the bands at
            // their last value is better than publishing garbage; the visual simply
            // stops reacting rather than showing something invented.
            else -> {
                // One-shot: this runs on the audio thread, and an encoding we cannot
                // read would otherwise log on every buffer for the whole track.
                if (!warnedAboutEncoding) {
                    warnedAboutEncoding = true
                    Log.w(TAG, "unsupported PCM encoding $encoding - visualizer tap idle")
                }
                return
            }
        }
        if (pending > 0) pcmRing.write(decodeScratch, pending)
        LockSupport.unpark(analysisThread)
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
        clearQueue()
        // Also clears the staleness clock. Without this hasGoneStale() stays true from
        // the first pause until the next buffer arrives, so the service's watchdog
        // re-reset the controller four times a second for as long as playback was
        // stopped.
        lastDataNanos = 0L
        _frames.value = EMPTY_VISUALIZER_FRAME
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
        // Also drop buffered PCM. The ring can hold most of a second of audio from
        // before the pause, and analysing it on resume would show the listener a
        // spectrum of sound that already played. clear() is consumer-side, which is
        // exactly where this runs.
        pcmRing.clear()
        smoothed.fill(0f)
        ring.fill(0f)
        ringWrite = 0
        samplesSinceFft = 0
        totalSamples = 0L
        clearAdaptiveState()
    }

    /** Drops the normalization history. Without this a track change would leave the new
     * track being measured against the previous one's dynamic range for the first
     * couple of seconds - a quiet track following a loud one would open pinned at zero,
     * and the reverse would open saturated. */
    private fun clearAdaptiveState() {
        bandCeiling.fill(0f)
        bandFloor.fill(1f)
        bassHistory.fill(0f)
        levelHistory.fill(0f)
        historyWrite = 0
        historyCount = 0
        onsetEnvelope = 0f
        pendingKick = 0f
        lowPass1 = 0f
        lowPass2 = 0f
        lowPass3 = 0f
        kickFastEnvelope = 0f
        kickSlowEnvelope = 0f
        kickPreviousFast = 0f
        kickStepCountdown = KICK_STEP_FRAMES
        samplesSinceKick = Int.MAX_VALUE
        bassSmoothed = 0f
        levelSmoothed = 0f
    }

    /** Advances the kick detector by one sample.
     *
     * Low-passes to isolate the kick and bassline, rectifies, and tracks two envelopes
     * of the result: a fast one that follows the attack, and a slow one that is the
     * recent average level. What triggers a kick is the fast envelope's *rise* over
     * ~1.5ms relative to the slow one - not its level.
     *
     * That distinction is the whole point. A level test cannot separate a kick from
     * the sustained sub-bass underneath it, because on this material they occupy the
     * same frequencies and the sub is frequently the louder of the two - which is why
     * every level-based attempt sprayed continuously instead of on the beat. A rise
     * test is blind to sustained content by construction: something that is not
     * changing has no rise, however loud it is. */
    private fun detectKick(sample: Float) {
        lowPass1 += (sample - lowPass1) * lowPassCoeff
        lowPass2 += (lowPass1 - lowPass2) * lowPassCoeff
        lowPass3 += (lowPass2 - lowPass3) * lowPassCoeff
        val rectified = if (lowPass3 < 0f) -lowPass3 else lowPass3

        kickFastEnvelope += if (rectified > kickFastEnvelope) {
            (rectified - kickFastEnvelope) * kickAttackCoeff
        } else {
            (rectified - kickFastEnvelope) * kickReleaseCoeff
        }
        kickSlowEnvelope += (rectified - kickSlowEnvelope) * kickSlowCoeff

        if (samplesSinceKick < Int.MAX_VALUE) samplesSinceKick++
        if (--kickStepCountdown > 0) return
        kickStepCountdown = KICK_STEP_FRAMES

        val rise = kickFastEnvelope - kickPreviousFast
        kickPreviousFast = kickFastEnvelope
        // The floor on the slow envelope stops near-silence dividing one rounding
        // error by another and reporting it as an enormous transient.
        if (rise <= 0f || kickSlowEnvelope < 1e-5f) return
        if (samplesSinceKick <= kickRefractoryFrames) return

        val normalizedRise = rise / kickSlowEnvelope
        if (normalizedRise < KICK_RISE_FLOOR) return
        val strength =
            ((normalizedRise - KICK_RISE_FLOOR) / (KICK_RISE_CEILING - KICK_RISE_FLOOR))
                .coerceIn(0f, 1f)
        if (strength > pendingKick) pendingKick = strength
        samplesSinceKick = 0
    }

    /** Publishes a spectrum if enough new audio has accumulated since the last one.
     *
     * Called per sample, from inside the decode loop, rather than once per
     * [handleBuffer]. That placement is the whole point: the check used to sit after
     * the loop, so however much audio a buffer carried, at most one transform ran for
     * it. The publish rate was therefore the audio sink's callback rate, and
     * [MIN_PUBLISH_INTERVAL_NANOS] could only ever lower it. Measured on device that
     * came out at **7.6 frames a second** - one 46ms analysis window every 131ms, so
     * roughly two thirds of the audio was never looked at at all, and any kick landing
     * in a gap was invisible no matter how good the detector was. Every time constant
     * in this file is written against the ~60Hz this restores. */
    private fun publishIfDue() {
        if (samplesSinceFft < hopSamples || totalSamples < FFT_SIZE) return
        // dt is the hop's own duration in audio time, not elapsed wall-clock time.
        // Every envelope and rolling window in this class is a statement about the music
        // ("fall back over 350ms"), so measuring them against how fast buffers happen to
        // arrive would scale them by however far ahead the host is running - and inside
        // a burst, wall-clock dt is near zero, which would freeze every follower solid
        // for the whole burst and then jump.
        val dt = samplesSinceFft.toFloat() / sampleRate
        lastDataNanos = System.nanoTime()
        samplesSinceFft = 0
        analyzeAndPublish(dt)
    }

    /** Advances [frames] by exactly one queued spectrum. Called once per display frame.
     *
     * Releasing precisely one is the point. Producer and consumer run at the same
     * average rate (60 spectra per second of audio, one per display frame), but the
     * producer delivers them in bursts of about eight as each decoded buffer arrives,
     * so a standing backlog of a few frames is exactly what absorbs that burstiness.
     * Draining "down to the newest" instead - which this did at first - discards most
     * of every burst and then starves until the next one, which collapses the effective
     * update rate back to roughly the buffer rate and looks like a stutter. Overflow is
     * handled where it belongs, by the ring dropping its oldest entry once genuinely
     * full; when the queue has run dry the last frame simply stands. */
    fun pumpNextFrame() {
        val next = synchronized(queueLock) {
            if (queueCount == 0) return
            val frame = queue[queueHead]
            queue[queueHead] = null
            queueHead = (queueHead + 1) % MAX_QUEUED_FRAMES
            queueCount--
            frame
        }
        if (next != null) _frames.value = next
    }

    private fun enqueue(frame: VisualizerFrame) {
        synchronized(queueLock) {
            if (queueCount == MAX_QUEUED_FRAMES) {
                queueHead = (queueHead + 1) % MAX_QUEUED_FRAMES
                queueCount--
            }
            queue[(queueHead + queueCount) % MAX_QUEUED_FRAMES] = frame
            queueCount++
        }
    }

    private fun clearQueue() {
        synchronized(queueLock) {
            for (i in queue.indices) queue[i] = null
            queueHead = 0
            queueCount = 0
        }
    }

    private fun push(sample: Float) {
        detectKick(sample)
        ring[ringWrite] = sample
        // FFT_SIZE is a power of two, so the mask is equivalent to `% FFT_SIZE` and
        // saves an integer division per sample in the hottest loop in the app.
        ringWrite = (ringWrite + 1) and (FFT_SIZE - 1)
        samplesSinceFft++
        totalSamples++
        publishIfDue()
    }

    /**
     * Runs the transform over the buffered window and publishes one [VisualizerFrame],
     * [dt] seconds after the previous one.
     *
     * The per-band output is deliberately **not** the raw dB level. Measured on a real
     * track (Martin Garrix & Zedd - Follow), the absolute bass level through the entire
     * drop stayed between 0.61 and 0.77 - a 15% ripple riding a 68% plateau. Every
     * effect scaled by that value therefore sat pinned near its maximum and moved
     * imperceptibly, which is what made the visualizer read as disconnected from the
     * music even though the tap was working perfectly: the only *visible* motion left
     * was the timer-driven animation layer, and that has no relationship to the audio
     * at all. The cause is dB itself - the [MIN_DB]..[MAX_DB] window spans 60dB, so a
     * kick landing 6dB above a sustained sub can only ever move the value by 0.1.
     *
     * So each value is renormalized against that band's *own* recent range before
     * publication, which is standard practice for music visualizers, and the same 15%
     * ripple becomes a full-scale swing. The two things the visuals need are then
     * separated explicitly, because they are genuinely different measurements:
     *
     *  - **level** ([VisualizerFrame.bass]): how much bass there is right now, relative
     *    to the last few seconds. Drives sizes.
     *  - **onset** ([VisualizerFrame.bassOnset]): spectral flux, the summed *positive*
     *    change across the bass bands. A sustained bassline, however loud, produces
     *    almost none; a new attack produces a spike. Drives per-hit events. This is the
     *    part a level measurement fundamentally cannot provide, and its absence is why
     *    the confetti sprayed continuously instead of firing on the kicks.
     */
    private fun analyzeAndPublish(dt: Float) {
        // Copy oldest-to-newest out of the ring, applying the window as we go.
        for (i in 0 until FFT_SIZE) {
            re[i] = ring[(ringWrite + i) % FFT_SIZE] * window[i]
            im[i] = 0f
        }
        fft()

        val out = FloatArray(VISUALIZER_BAND_COUNT)
        val bassEnd = bassBandEnd.coerceIn(1, VISUALIZER_BAND_COUNT)
        var bassSum = 0f
        var levelSum = 0f

        for (b in 0 until VISUALIZER_BAND_COUNT) {
            var peak = 0f
            for (bin in bandStart[b] until bandEnd[b]) {
                val magnitude = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                if (magnitude > peak) peak = magnitude
            }
            // The band level is kept unclamped for the two sums below: on a mastered
            // track the bass bands sit at or above the top of the dB window through every
            // loud section, and a saturated input leaves the adaptive normalization
            // nothing to normalize.
            val raw = magnitudeToUnclampedDb(peak)
            val target = raw.coerceIn(0f, 1f)

            if (b < bassEnd) bassSum += raw
            levelSum += raw

            // Fast attack, slow decay - see ATTACK/DECAY.
            val previous = smoothed[b]
            val value = if (target > previous) {
                previous + (target - previous) * ATTACK
            } else {
                previous + (target - previous) * DECAY
            }
            smoothed[b] = value

            bandCeiling[b] = if (value > bandCeiling[b]) {
                value
            } else {
                (bandCeiling[b] - BAND_CEILING_FALL_PER_SEC * dt).coerceAtLeast(0f)
            }
            bandFloor[b] = if (value < bandFloor[b]) {
                value
            } else {
                (bandFloor[b] + BAND_FLOOR_RISE_PER_SEC * dt).coerceAtMost(bandCeiling[b])
            }
            val span = (bandCeiling[b] - bandFloor[b]).coerceAtLeast(MIN_SPAN) * NORMALIZE_HEADROOM
            val dynamic = ((value - bandFloor[b]) / span).coerceIn(0f, 1f)
            out[b] = ABSOLUTE_MIX * value + (1f - ABSOLUTE_MIX) * dynamic
        }

        val bassRaw = bassSum / bassEnd
        val levelRaw = levelSum / VISUALIZER_BAND_COUNT

        bassHistory[historyWrite] = bassRaw
        levelHistory[historyWrite] = levelRaw
        historyWrite = (historyWrite + 1) % STAT_WINDOW
        if (historyCount < STAT_WINDOW) historyCount++

        // Smoothed with the same fast-attack/slow-decay asymmetry the bands use. The
        // normalization step re-introduces frame-to-frame jitter that the pre-normalized
        // smoothing cannot see - it divides by a span that is itself moving - so without
        // this the scalars jump around between neighbouring frames and every size driven
        // by them visibly flickers.
        val bass = smoothScalar(normalizeAgainstWindow(bassRaw, bassHistory), bassSmoothed)
            .also { bassSmoothed = it }
        // Overall loudness keeps half its absolute meaning: unlike bass, consumers use
        // it as "how loud is this passage", and a fully normalized version would report
        // a quiet intro and a drop as equally loud.
        val level = smoothScalar(
            0.5f * levelRaw + 0.5f * normalizeAgainstWindow(levelRaw, levelHistory),
            levelSmoothed,
        ).also { levelSmoothed = it }

        // Onset comes from the time-domain detector - see detectKick - held with an
        // instant attack and a short decay so a hit landing between two published
        // frames is still visible for the ~12 frames it should read as one.
        val onsetTarget = pendingKick
        pendingKick = 0f
        onsetEnvelope = if (onsetTarget > onsetEnvelope) {
            onsetTarget
        } else {
            (onsetEnvelope - dt / ONSET_DECAY_SECONDS).coerceAtLeast(0f)
        }

        diffuseBands(out)
        enqueue(
            VisualizerFrame(
                bands = out,
                bass = bass,
                bassOnset = onsetEnvelope,
                level = level.coerceIn(0f, 1f),
                bassAbsolute = bassRaw,
                levelAbsolute = levelRaw,
            ),
        )

    }

    /** Smooths energy sideways between neighbouring bands, in place.
     *
     * Without this, adjacent bands move completely independently, and anything that
     * maps elements to bands one-to-one - the particle sphere most of all - renders as
     * uncorrelated per-element jitter: the classic "looks like television static"
     * failure. Real instruments occupy several neighbouring bands at once, so a small
     * amount of lateral coupling is closer to the truth as well as far more legible;
     * what the eye then reads is a wave travelling across the elements rather than
     * noise. Two passes of a discrete Laplacian at [BAND_DIFFUSION] is the amount
     *good music visualizers converge on. */
    private fun diffuseBands(values: FloatArray) {
        if (values.size < 3) return
        repeat(BAND_DIFFUSION_PASSES) {
            diffusionScratch[0] = values[0]
            diffusionScratch[values.lastIndex] = values[values.lastIndex]
            for (i in 1 until values.lastIndex) {
                diffusionScratch[i] =
                    values[i] + BAND_DIFFUSION * (values[i - 1] + values[i + 1] - 2f * values[i])
            }
            diffusionScratch.copyInto(values)
        }
    }

    /** Fast-attack, slow-decay smoothing of a published scalar - see [ATTACK]/[DECAY]. */
    private fun smoothScalar(target: Float, previous: Float): Float =
        if (target > previous) {
            previous + (target - previous) * ATTACK
        } else {
            previous + (target - previous) * DECAY
        }

    /** Position of [value] within the range [history] has covered recently, as 0f..1f.
     * Returns 0f when that range is too narrow to be meaningful (see [MIN_SPAN]), so
     * silence and steady tones rest rather than flickering at full scale. */
    private fun normalizeAgainstWindow(
        value: Float,
        history: FloatArray,
    ): Float {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (i in 0 until historyCount) {
            val v = history[i]
            if (v < min) min = v
            if (v > max) max = v
        }
        if (historyCount == 0) return 0f
        val low = min
        // The divisor is clamped rather than the result being zeroed below a threshold.
        // Both halves of that matter. Zeroing produced a discontinuity: whenever the
        // window's span drifted around the threshold the output snapped between nothing
        // and full scale from one frame to the next, which is visible as the size of
        // everything driven by it flickering. Clamping also bounds the gain - an
        // unclamped 1/span multiplies the analysis noise of a nearly-steady passage up
        // to full scale, which is the same flicker arriving by a second route.
        val span = (max - low).coerceAtLeast(MIN_SPAN) * NORMALIZE_HEADROOM
        return ((value - low) / span).coerceIn(0f, 1f)
    }

    /** Peak-bin magnitude -> 0f..1f over the [MIN_DB]..[MAX_DB] window. The
     * `4 / FFT_SIZE` factor undoes the transform's length scaling and the Hann
     * window's 0.5 coherent gain, so a full-scale sine lands near 0dB. */
    private fun magnitudeToUnclampedDb(magnitude: Float): Float {
        val normalized = magnitude * 4f / FFT_SIZE
        // The isFinite() half matters: a single NaN sample (reachable on the float
        // path) would otherwise produce log10(NaN) = NaN, and since Kotlin's coerceIn
        // passes NaN straight through (every comparison against NaN is false) the
        // smoothing below would latch that band at NaN permanently, poisoning
        // everything downstream that scales a radius or an alpha by it.
        if (!normalized.isFinite() || normalized <= 1e-7f) return 0f
        val db = 20f * log10(normalized)
        // Floored but not capped: callers that need a 0f..1f display value clamp it
        // themselves, while the flux and the adaptive normalization need to see how far
        // past the top of the window a band actually went.
        return ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceAtLeast(0f)
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
    /** Converts the detector's time constants into per-sample coefficients for the
     * current sample rate. A one-pole follower that moves a fraction `c` of the way to
     * its input each sample reaches 63% of a step in `seconds * sampleRate` samples,
     * so `c = 1 - exp(-1 / (seconds * sampleRate))`. */
    private fun rebuildKickCoefficients(sampleRateHz: Int) {
        val rate = sampleRateHz.coerceAtLeast(8_000).toFloat()
        hopSamples = (rate / TARGET_FRAMES_PER_SECOND).toInt().coerceAtLeast(64)
        lowPassCoeff = onePoleCoefficient(1f / (2f * PI.toFloat() * KICK_LOWPASS_HZ), rate)
        kickAttackCoeff = onePoleCoefficient(KICK_FAST_ATTACK_SECONDS, rate)
        kickReleaseCoeff = onePoleCoefficient(KICK_FAST_RELEASE_SECONDS, rate)
        kickSlowCoeff = onePoleCoefficient(KICK_SLOW_SECONDS, rate)
        kickRefractoryFrames = (KICK_REFRACTORY_SECONDS * rate).toInt().coerceAtLeast(1)
    }

    private fun onePoleCoefficient(seconds: Float, sampleRateHz: Float): Float =
        (1.0 - exp(-1.0 / (seconds.toDouble() * sampleRateHz))).toFloat().coerceIn(1e-6f, 1f)

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
        // Resolve the bass ceiling against this same mapping, so bassAmplitude always
        // averages the bands that really sit below BASS_MAX_HZ at the current sample
        // rate instead of a fixed guess. There is no lower bound: the window starts at
        // band 0 so the deepest sub content counts too.
        bassBandEnd = (bandIndexForHz(BASS_MAX_HZ, ratio) + 1).coerceAtMost(VISUALIZER_BAND_COUNT)
    }

    /** Index of the log-spaced band whose range contains [hz]. */
    private fun bandIndexForHz(hz: Float, ratio: Float): Int {
        if (hz <= BAND_LOW_HZ) return 0
        val fraction = ln(hz / BAND_LOW_HZ) / ln(ratio)
        return (fraction * VISUALIZER_BAND_COUNT).toInt().coerceIn(0, VISUALIZER_BAND_COUNT - 1)
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
