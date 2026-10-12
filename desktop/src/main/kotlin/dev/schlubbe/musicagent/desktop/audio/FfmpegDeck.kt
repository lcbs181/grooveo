package dev.schlubbe.musicagent.desktop.audio

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegLogCallback
import java.nio.FloatBuffer
import java.util.logging.Logger

/**
 * A [Deck] decoding with FFmpeg (libavformat/libavcodec via JavaCV) on its own
 * thread: HTTP(S), HLS, Opus/WebM, AAC/M4A, MP3, FLAC, local files. Output is
 * resampled by FFmpeg to 48 kHz 32-bit float stereo and queued in a [PcmRing]; the full ring
 * blocks the decoder, so it decodes only as fast as the mixer consumes.
 */
class FfmpegDeck(private val name: String) : Deck {
    private val log = Logger.getLogger("FfmpegDeck-$name")
    private val ring = PcmRing(BYTES_PER_FRAME * SAMPLE_RATE.toInt() / 2)
    private val scratch = ByteArray(BYTES_PER_FRAME * 4096)
    private var worker: Thread? = null
    @Volatile private var session = 0

    @Volatile private var decoderDone = false
    @Volatile private var pendingSeek: Double? = null
    @Volatile override var error: String? = null
        private set
    @Volatile override var source: AudioSource? = null
        private set
    @Volatile override var durationSec: Double? = null
        private set
    @Volatile private var basePosSec = 0.0
    @Volatile private var framesRead = 0L

    override val positionSec: Double get() = basePosSec + framesRead / SAMPLE_RATE
    override val ended: Boolean get() = source != null && decoderDone && pendingSeek == null && ring.size() < BYTES_PER_FRAME

    @Synchronized
    override fun load(source: AudioSource) {
        stopWorker()
        this.source = source
        error = null
        decoderDone = false
        durationSec = null
        pendingSeek = null
        basePosSec = source.startSec
        framesRead = 0
        ring.clear()
        val mySession = ++session
        worker = Thread({ decode(source, mySession) }, "decode-$name").apply { isDaemon = true; start() }
    }

    private fun decode(src: AudioSource, mySession: Int) {
        var grabber: FFmpegFrameGrabber? = null
        try {
            grabber = FFmpegFrameGrabber(src.url).apply {
                sampleRate = SAMPLE_RATE.toInt()
                audioChannels = 2
                // float, as the codecs decode: no 16-bit rounding before EQ and normalisation,
                // and decoder overs above 0 dBFS survive until normalisation turns them down
                sampleFormat = avutil.AV_SAMPLE_FMT_FLT
                if (src.url.startsWith("http")) {
                    setOption("reconnect", "1")
                    setOption("reconnect_streamed", "1")
                    setOption("reconnect_on_network_error", "1")
                    setOption("reconnect_delay_max", "5")
                    setOption("rw_timeout", "20000000")
                    if (src.headers.isNotEmpty()) setOption("headers", src.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" })
                    src.headers["User-Agent"]?.let { setOption("user_agent", it) }
                }
                start()
            }
            grabber.lengthInTime.takeIf { it > 0 }?.let { durationSec = it / 1_000_000.0 }
            if (src.startSec > 0) grabber.setAudioTimestamp((src.startSec * 1_000_000).toLong())
            var bytes = ByteArray(0)
            while (session == mySession) {
                val gen = ring.generation
                pendingSeek?.let { target ->
                    grabber.setAudioTimestamp((target * 1_000_000).toLong())
                    if (pendingSeek == target) pendingSeek = null
                    continue
                }
                val frame = grabber.grabSamples()
                if (frame == null) {
                    decoderDone = true
                    // stay alive for a possible seek back
                    while (session == mySession && pendingSeek == null) Thread.sleep(20)
                    if (session == mySession) decoderDone = false
                    continue
                }
                val fb = frame.samples?.firstOrNull() as? FloatBuffer ?: continue
                val n = fb.remaining()
                if (bytes.size < n * 4) bytes = ByteArray(n * 4)
                for (i in 0 until n) {
                    val v = java.lang.Float.floatToRawIntBits(fb.get(fb.position() + i))
                    bytes[i * 4] = v.toByte()
                    bytes[i * 4 + 1] = (v shr 8).toByte()
                    bytes[i * 4 + 2] = (v shr 16).toByte()
                    bytes[i * 4 + 3] = (v shr 24).toByte()
                }
                ring.write(bytes, n * 4, gen)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (session == mySession) {
                log.warning("decode failed for ${src.id}: $e")
                error = e.message?.takeIf { it.isNotBlank() } ?: "Wiedergabefehler"
                decoderDone = true
            }
        } finally {
            runCatching { grabber?.close() }
        }
    }

    override fun seek(sec: Double) {
        if (source == null) return
        pendingSeek = sec
        decoderDone = false
        ring.clear()
        basePosSec = sec
        framesRead = 0
    }

    private fun stopWorker() {
        session++
        ring.clear()
        worker?.let { it.interrupt(); it.join(500) }
        worker = null
    }

    @Synchronized
    override fun stop() {
        stopWorker()
        source = null
        decoderDone = false
        durationSec = null
        basePosSec = 0.0
        framesRead = 0
    }

    override fun read(out: FloatArray, frames: Int): Int {
        val want = minOf(frames, out.size / 2, scratch.size / BYTES_PER_FRAME)
        val got = ring.read(scratch, want * BYTES_PER_FRAME) / BYTES_PER_FRAME
        for (i in 0 until got * 2) {
            val b = i * 4
            val bits = (scratch[b].toInt() and 0xff) or ((scratch[b + 1].toInt() and 0xff) shl 8) or
                ((scratch[b + 2].toInt() and 0xff) shl 16) or (scratch[b + 3].toInt() shl 24)
            out[i] = java.lang.Float.intBitsToFloat(bits)
        }
        framesRead += got
        return got
    }

    override fun available(): Int = ring.size() / BYTES_PER_FRAME

    override fun close() = stop()

    companion object {
        const val BYTES_PER_FRAME = 8 // stereo float

        init {
            avutil.av_log_set_level(avutil.AV_LOG_ERROR)
            runCatching { FFmpegLogCallback.setLevel(avutil.AV_LOG_ERROR) }
        }
    }
}
