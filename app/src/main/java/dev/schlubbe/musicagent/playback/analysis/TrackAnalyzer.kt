package dev.schlubbe.musicagent.playback.analysis

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    suspend fun analyze(uri: Uri): TrackAnalysisResult? = withContext(Dispatchers.Default) {
        val envelope = runCatching { decodeEnergyEnvelope(uri) }.onFailure {
            Log.w(TAG, "Failed to decode $uri for analysis", it)
        }.getOrNull() ?: return@withContext null

        if (envelope.size < MIN_BODY_MS / FRAME_MS) return@withContext null
        val peak = envelope.max()
        if (peak <= 0f) return@withContext null

        val mixInFrame = findMixInFrame(envelope, peak)
        val mixOutFrame = findMixOutFrame(envelope, peak)
        val mixInMs = mixInFrame * FRAME_MS
        val mixOutMs = mixOutFrame * FRAME_MS

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

    /** Decodes the whole track to PCM and reduces it, on the fly, to one RMS value
     * per [FRAME_MS] (multi-channel audio mixed to mono first) - never holds more
     * than a [FRAME_MS]-sized accumulator plus the (tiny) resulting envelope in
     * memory, regardless of track length. */
    private fun decodeEnergyEnvelope(uri: Uri): List<Float> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return emptyList()
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val frameSamples = (sampleRate * FRAME_MS / 1000L).toInt().coerceAtLeast(1)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return runDecodeLoop(extractor, codec, channelCount, frameSamples)
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun runDecodeLoop(
        extractor: MediaExtractor,
        codec: MediaCodec,
        channelCount: Int,
        frameSamples: Int,
    ): List<Float> {
        val envelope = mutableListOf<Float>()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var accum = 0.0
        var accumCount = 0

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex) ?: continue
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
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
        // A trailing partial frame (shorter than frameSamples) still carries real
        // signal - dropping it would bias findMixOutFrame's backward scan to start
        // one frame early on every track.
        if (accumCount > 0) envelope += sqrt(accum / accumCount).toFloat()
        return envelope
    }

    companion object {
        private const val FRAME_MS = 50L
        private const val TIMEOUT_US = 10_000L
        private const val INTRO_THRESHOLD_FRACTION = 0.22f
        private const val OUTRO_THRESHOLD_FRACTION = 0.30f
        private const val MIN_SUSTAIN_FRAMES = 6
        private const val MAX_EDGE_SKIP_MS = 15_000L
        private const val MIN_BODY_MS = 20_000L
    }
}
