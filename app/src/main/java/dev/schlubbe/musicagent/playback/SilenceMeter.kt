package dev.schlubbe.musicagent.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Measures how long the decoded audio has been silent, for gapless playback: a lot of
 * uploads end in seconds of dead air, which plays as a gap before the next track even
 * though ExoPlayer itself joins the tracks seamlessly.
 *
 * Sits first in the sink's processor chain (a [TeeAudioProcessor] sink, so it never
 * changes the audio), sees the raw decoded PCM before EQ dither or reverb, and counts
 * the current run of frames whose every channel stays under [THRESHOLD]
 * (about -66 dBFS). The decoder runs at most the AudioTrack buffer (≤ 0.75 s) ahead of
 * what is heard, so once [silentMs] exceeds that, what is audible right now is
 * silence too. Seeks and format changes flush the tee, which restarts the count.
 */
@UnstableApi
class SilenceMeter : TeeAudioProcessor.AudioBufferSink {
    @Volatile private var sampleRate = 0
    @Volatile private var channels = 0
    @Volatile private var encoding = C.ENCODING_INVALID
    @Volatile private var silentFrames = 0L

    /** Length of the current silent run in the decoded audio, in ms. */
    val silentMs: Long
        get() = sampleRate.takeIf { it > 0 }?.let { silentFrames * 1000 / it } ?: 0L

    fun reset() {
        silentFrames = 0
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz
        channels = channelCount
        this.encoding = encoding
        silentFrames = 0
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val ch = channels
        if (ch <= 0) return
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val frames = b.remaining() / (2 * ch)
                count(frames) { i -> abs(b.getShort(b.position() + i * 2).toInt()) <= THRESHOLD_16 }
            }
            C.ENCODING_PCM_FLOAT -> {
                val frames = b.remaining() / (4 * ch)
                count(frames) { i -> abs(b.getFloat(b.position() + i * 4)) <= THRESHOLD }
            }
            else -> silentFrames = 0
        }
    }

    /** Walks [frames] frames; [quiet] tells whether interleaved sample i is below threshold. */
    private inline fun count(frames: Int, quiet: (Int) -> Boolean) {
        val ch = channels
        var run = silentFrames
        for (f in 0 until frames) {
            var silent = true
            for (c in 0 until ch) if (!quiet(f * ch + c)) { silent = false; break }
            run = if (silent) run + 1 else 0
        }
        silentFrames = run
    }

    companion object {
        const val THRESHOLD = 0.0005f
        private const val THRESHOLD_16 = 16
    }
}
