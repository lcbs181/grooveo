package dev.schlubbe.musicagent.playback.analysis

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "TrackAnalyzer"

/** Where to fade out of / start playing from a track for the "smart" crossfade - see
 * [dev.schlubbe.musicagent.data.local.entity.TrackAnalysisEntity]'s kdoc for the
 * feature this backs. */
data class TrackAnalysisResult(val mixOutMs: Long, val mixInMs: Long)

/**
 * Decodes a downloaded track's actual audio once (via [MediaExtractor]/[MediaCodec],
 * the same primitives ExoPlayer itself is built on - no extra codec dependency) and
 * picks two positions from its energy envelope, the same "mark a jump point instead
 * of mixing blind" idea djay's Automix uses:
 *
 * - [TrackAnalysisResult.mixInMs]: skip past a quiet/ambient intro to where the
 *   track's body actually starts, so an incoming track doesn't make the listener
 *   wait through several seconds of near-silence on every single transition.
 * - [TrackAnalysisResult.mixOutMs]: fade out before the outro/silence tail instead of
 *   blindly using the raw file's last N seconds, which on a lot of real uploads *is*
 *   that dead air.
 *
 * Deliberately does not attempt BPM/beat-grid detection or tempo-matching - this
 * only reads simple frame energy (RMS), which is robust across arbitrary source
 * material (podcasts-length mixes, live recordings, anything without a steady beat),
 * unlike a beat tracker that can lock onto the wrong tempo and make things worse.
 * Runs in [TrackAnalysisWorker], never on the playback path - a full decode of a
 * multi-minute track takes real (if sub-second-per-second) CPU time.
 */
@Singleton
class TrackAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Returns null if the track couldn't be decoded, or if its energy profile didn't
     * yield a usable body (too short, or entirely one dynamic level throughout) -
     * callers treat that exactly like "not analyzed yet": fall back to
     * [dev.schlubbe.musicagent.playback.CrossfadeController]'s plain fixed-duration
     * fade. */
    suspend fun analyze(uri: Uri): TrackAnalysisResult? = withContext(analysisDispatcher) {
        val w = runCatching { decodeWindows(uri) }.onFailure {
            Log.w(TAG, "Failed to decode $uri for analysis", it)
        }.getOrNull() ?: return@withContext null

        if (w.totalMs < MIN_BODY_MS || w.intro.isEmpty() || w.outro.isEmpty()) return@withContext null
        if (w.peak <= 0f) return@withContext null

        val mixInMs = w.introStartMs + findMixInFrame(w.intro, w.peak) * FRAME_MS
        val mixOutMs = w.outroStartMs + findMixOutFrame(w.outro, w.peak) * FRAME_MS

        if (mixOutMs - mixInMs < MIN_BODY_MS) return@withContext null
        TrackAnalysisResult(mixOutMs = mixOutMs, mixInMs = mixInMs)
    }

    /** Walks forward from the start; the point [MIN_SUSTAIN_FRAMES] frames *before*
     * energy first sustains above [INTRO_THRESHOLD_FRACTION] of the track's peak is
     * where the body actually begins (the subtraction keeps the very first bit of
     * the attack rather than starting a beat late). Capped at [MAX_EDGE_SKIP_MS] so a
     * deliberately slow-building intro on a real track is never skipped past
     * entirely. */
    private fun findMixInFrame(envelope: List<Float>, peak: Float): Int {
        val threshold = peak * INTRO_THRESHOLD_FRACTION
        val cap = min(envelope.size, (MAX_EDGE_SKIP_MS / FRAME_MS).toInt())
        var run = 0
        for (i in 0 until cap) {
            if (envelope[i] >= threshold) {
                run++
                if (run >= MIN_SUSTAIN_FRAMES) return (i - MIN_SUSTAIN_FRAMES + 1).coerceAtLeast(0)
            } else {
                run = 0
            }
        }
        return 0
    }

    /** Mirror of [findMixInFrame] from the end: the point [MIN_SUSTAIN_FRAMES] frames
     * *after* energy last sustains above [OUTRO_THRESHOLD_FRACTION] of peak is where
     * the outro/decay begins. A higher threshold than the intro's - this wants "still
     * clearly in full swing", not just "louder than near-silence", so it doesn't fade
     * out during a mid-track breakdown. */
    private fun findMixOutFrame(envelope: List<Float>, peak: Float): Int {
        val threshold = peak * OUTRO_THRESHOLD_FRACTION
        val cap = min(envelope.size, (MAX_EDGE_SKIP_MS / FRAME_MS).toInt())
        val lastIndex = envelope.size - 1
        var run = 0
        for (i in lastIndex downTo (lastIndex - cap + 1).coerceAtLeast(0)) {
            if (envelope[i] >= threshold) {
                run++
                if (run >= MIN_SUSTAIN_FRAMES) return (i + MIN_SUSTAIN_FRAMES - 1).coerceAtMost(lastIndex)
            } else {
                run = 0
            }
        }
        return lastIndex
    }

    // One background-priority thread for every analysis: "Übergänge analysieren" on
    // hundreds of downloads queues hundreds of workers, and running their decodes in
    // parallel at normal priority competed with playback for CPU.
    private val analysisDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "TrackAnalysis")
    }.asCoroutineDispatcher()

    private class Windows(
        val intro: List<Float>,
        val introStartMs: Long,
        val outro: List<Float>,
        val outroStartMs: Long,
        val peak: Float,
        val totalMs: Long,
    )

    /** Only the start and end of a track decide its mix points, so a long track is
     * decoded in three short windows - start, middle (a peak reference for the
     * thresholds) and end - instead of end to end. Analysis cost stays roughly
     * constant per track; a 2-hour DJ set used to decode for minutes. Short tracks
     * are decoded whole. Envelope is one RMS value per [FRAME_MS], mixed to mono. */
    private fun decodeWindows(uri: Uri): Windows? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
            val frameSamples = (sampleRate * FRAME_MS / 1000L).toInt().coerceAtLeast(1)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val edgeUs = EDGE_WINDOW_MS * 1000L
                if (durationUs <= 0 || durationUs < 3 * edgeUs) {
                    val (start, env) = decodeRange(extractor, codec, channelCount, frameSamples, 0L, Long.MAX_VALUE)
                    val total = if (durationUs > 0) durationUs / 1000 else env.size * FRAME_MS
                    return Windows(env, start, env, start, env.maxOrNull() ?: 0f, total)
                }
                val (introStart, intro) = decodeRange(extractor, codec, channelCount, frameSamples, 0L, edgeUs)
                val mid = durationUs / 2
                val (_, middle) = decodeRange(extractor, codec, channelCount, frameSamples, mid - edgeUs / 2, mid + edgeUs / 2)
                val (outroStart, outro) = decodeRange(extractor, codec, channelCount, frameSamples, durationUs - edgeUs, Long.MAX_VALUE)
                val peak = maxOf(intro.maxOrNull() ?: 0f, middle.maxOrNull() ?: 0f, outro.maxOrNull() ?: 0f)
                return Windows(intro, introStart, outro, outroStart, peak, durationUs / 1000)
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /** Decodes from [startUs] (snapped back to the previous sync sample) until
     * [endUs] or end of stream. Returns the actual start time in ms and the envelope. */
    private fun decodeRange(
        extractor: MediaExtractor,
        codec: MediaCodec,
        channelCount: Int,
        frameSamples: Int,
        startUs: Long,
        endUs: Long,
    ): Pair<Long, List<Float>> {
        extractor.seekTo(startUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        val envelope = mutableListOf<Float>()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var firstPtsUs = -1L
        var accum = 0.0
        var accumCount = 0

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    val sampleSize = if (inputBuffer == null) -1 else extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0 || extractor.sampleTime > endUs) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    if (firstPtsUs < 0) firstPtsUs = bufferInfo.presentationTimeUs
                    val shorts = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    var i = 0
                    val total = shorts.remaining()
                    while (i + channelCount <= total) {
                        var sum = 0
                        for (c in 0 until channelCount) sum += shorts.get(i + c)
                        val mono = sum / channelCount
                        accum += mono.toDouble() * mono.toDouble()
                        accumCount++
                        if (accumCount >= frameSamples) {
                            envelope += sqrt(accum / accumCount).toFloat()
                            accum = 0.0
                            accumCount = 0
                        }
                        i += channelCount
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
            }
        }
        if (accumCount > 0) envelope += sqrt(accum / accumCount).toFloat()
        return (firstPtsUs.coerceAtLeast(0L) / 1000) to envelope
    }

    companion object {
        private const val FRAME_MS = 50L
        private const val TIMEOUT_US = 10_000L
        private const val INTRO_THRESHOLD_FRACTION = 0.22f
        private const val OUTRO_THRESHOLD_FRACTION = 0.30f
        private const val MIN_SUSTAIN_FRAMES = 6
        private const val MAX_EDGE_SKIP_MS = 15_000L
        private const val MIN_BODY_MS = 20_000L
        private const val EDGE_WINDOW_MS = 30_000L
    }
}
