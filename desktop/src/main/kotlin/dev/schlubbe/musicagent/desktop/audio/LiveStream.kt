package dev.schlubbe.musicagent.desktop.audio

import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.OutputStream
import java.nio.FloatBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import java.util.logging.Logger

/**
 * "Auf dem Handy hören": the engine's finished mix (DJ transitions, FX, EQ, limiter - all
 * before the PC's volume) as a live AAC stream for the phone.
 *
 * The phone asks for a [ticket] over the signed sync connection and then fetches the audio
 * with plain HTTP at the ticket's path; the ticket is a random 128-bit secret, valid until the
 * phone stops listening ([TICKET_IDLE_MS] without a listener) or [revokeAll]. Each listener
 * gets its own encoder, fed from the mixer through a lock-free ring ([tap] never blocks or
 * allocates). While the PC is paused the stream carries silence, so the phone keeps playing
 * and simply goes on when the PC does.
 *
 * With [Ticket.muteDevice] the PC's own output is silent while a phone listens.
 */
class LiveStream(private val engine: AudioEngine) {
    class Ticket(val token: String, val muteDevice: Boolean, @Volatile var lastSeen: Long)

    private val tickets = ConcurrentHashMap<String, Ticket>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val random = SecureRandom()

    /** Called by the mixer with each finished block (interleaved stereo, before the volume). */
    fun tap(buf: FloatArray, frames: Int) {
        for (l in listeners) l.ring.write(buf, frames * 2)
    }

    val listening: Boolean get() = listeners.isNotEmpty()

    /** A new ticket for the phone; [muteDevice] silences the PC while it listens. */
    fun ticket(muteDevice: Boolean): Ticket {
        expire()
        val b = ByteArray(16).also(random::nextBytes)
        val t = Ticket(b.joinToString("") { "%02x".format(it) }, muteDevice, System.currentTimeMillis())
        tickets[t.token] = t
        return t
    }

    fun isValid(token: String): Boolean {
        expire()
        return tickets.containsKey(token)
    }

    fun revokeAll() {
        tickets.clear()
        listeners.forEach { it.stop() }
    }

    /**
     * Streams to [out] until the phone disconnects, the ticket is revoked or the app exits.
     * Blocks the calling thread; returns false when [token] is unknown (nothing was written).
     */
    fun serve(token: String, out: OutputStream): Boolean {
        val t = tickets[token] ?: return false
        val l = Listener(t)
        listeners += l
        updateMute()
        try {
            l.run(out)
        } catch (e: Exception) {
            log.fine("live stream ended: $e")
        } finally {
            listeners -= l
            t.lastSeen = System.currentTimeMillis()
            updateMute()
        }
        return true
    }

    private fun updateMute() {
        engine.deviceMuted = listeners.any { it.ticket.muteDevice && tickets.containsKey(it.ticket.token) }
    }

    private fun expire() {
        val now = System.currentTimeMillis()
        val active = listeners.map { it.ticket.token }.toSet()
        tickets.values.removeIf { it.token !in active && now - it.lastSeen > TICKET_IDLE_MS }
    }

    private inner class Listener(val ticket: Ticket) {
        val ring = FloatRing(SAMPLE_RATE.toInt() * 2 * 4) // 4 s
        @Volatile private var stopped = false
        @Volatile private var thread: Thread? = null

        fun stop() { stopped = true; thread?.let(LockSupport::unpark) }

        fun run(out: OutputStream) {
            thread = Thread.currentThread()
            val rec = FFmpegFrameRecorder(out, 2).apply {
                format = "adts"
                audioCodec = avcodec.AV_CODEC_ID_AAC
                audioBitrate = BITRATE
                sampleRate = SAMPLE_RATE.toInt()
                start()
            }
            val chunk = FloatArray(CHUNK_FRAMES * 2)
            val buf = FloatBuffer.wrap(chunk)
            var idleSince = System.nanoTime()
            try {
                while (!stopped && tickets.containsKey(ticket.token)) {
                    val got = ring.read(chunk, chunk.size)
                    val n = if (got > 0) {
                        idleSince = System.nanoTime()
                        got
                    } else if (System.nanoTime() - idleSince > IDLE_SILENCE_NS) {
                        // the PC is paused: silence in real time keeps the phone's player going
                        idleSince = System.nanoTime()
                        chunk.fill(0f)
                        chunk.size
                    } else {
                        LockSupport.parkNanos(5_000_000)
                        continue
                    }
                    for (i in 0 until n) chunk[i] = chunk[i].coerceIn(-1f, 1f)
                    buf.clear().limit(n)
                    rec.recordSamples(SAMPLE_RATE.toInt(), 2, buf)
                    ticket.lastSeen = System.currentTimeMillis()
                }
            } finally {
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }
        }
    }

    /** Single-producer (mixer), single-consumer (encoder) float ring; a full ring drops the newest samples. */
    internal class FloatRing(capacity: Int) {
        private val data = FloatArray(capacity)
        private val written = AtomicLong(0) // total samples ever written
        private val readCount = AtomicLong(0)

        fun write(src: FloatArray, n: Int) {
            val w = written.get()
            val free = data.size - (w - readCount.get())
            if (n > free) return // the phone fell behind: drop rather than block the mixer
            for (i in 0 until n) data[((w + i) % data.size).toInt()] = src[i]
            written.set(w + n)
        }

        /** Copies up to [max] samples into [dst]; returns how many. */
        fun read(dst: FloatArray, max: Int): Int {
            val r = readCount.get()
            val n = minOf(max.toLong(), written.get() - r).toInt()
            if (n <= 0) return 0
            for (i in 0 until n) dst[i] = data[((r + i) % data.size).toInt()]
            readCount.set(r + n)
            return n
        }
    }

    companion object {
        const val BITRATE = 256_000
        const val TICKET_IDLE_MS = 10 * 60_000L
        private const val CHUNK_FRAMES = 4800 // 100 ms, also the silence block
        private const val IDLE_SILENCE_NS = 100_000_000L
        private val log = Logger.getLogger("LiveStream")
    }
}
