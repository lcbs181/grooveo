package dev.schlubbe.musicagent.desktop.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** Final audio output. Implementations block in [write] to pace the mixer in real time. */
interface PcmSink {
    fun write(buf: FloatArray, frames: Int)
    fun start()
    fun stop()
    fun flush()
    /** Seconds of audio written but not yet heard. */
    val latencySec: Double
    fun close()
}

/**
 * Default sound device via javax.sound (PipeWire/Pulse/ALSA on Linux). Opens it with 24 bits
 * where the device takes them, else 16 bits; 16-bit output gets TPDF dither, so quiet passages
 * and fade-outs keep their detail instead of turning into rounding noise.
 *
 * [write] blocks until the device has taken the audio, which paces the mixer in real time. A
 * device that goes away (unplugged, sound server restarted) stops blocking: then [write] sleeps
 * for what it could not write, so the mixer never runs ahead, and the default device is opened
 * again with growing pauses between attempts. [openLine] is replaceable for tests.
 */
class JavaSoundSink(
    private val bufferFrames: Int = 4096,
    private val openLine: (AudioFormat, Int) -> SourceDataLine = { f, bytes -> AudioSystem.getSourceDataLine(f).apply { open(f, bytes) } },
) : PcmSink {
    /** The open line and its bytes per sample: 3 (24 bit) or 2 (16 bit). */
    private class Output(val line: SourceDataLine, val width: Int)
    @Volatile private var out: Output? = openBest().getOrThrow().also { it.line.start() }
    private var bytes = ByteArray(0)
    private val rng = java.util.SplittableRandom()
    /** The engine wants the line running (between [start] and [stop]). */
    @Volatile private var started = true
    @Volatile private var closed = false
    /** Bumped by [stop] and [flush]: both legitimately cut a blocked write short. */
    @Volatile private var interruptions = 0
    /** Short writes in a row without a stop or flush in between (mixer thread). */
    private var shortWrites = 0
    private var reopenAt = 0L
    private var backoffMs = RETRY_MIN_MS

    private fun openBest(): Result<Output> {
        var failure: Throwable? = null
        for (bits in intArrayOf(24, 16)) {
            val format = AudioFormat(SAMPLE_RATE.toFloat(), bits, 2, true, false)
            try {
                return Result.success(Output(openLine(format, bufferFrames * 2 * bits / 8), bits / 8))
            } catch (e: Exception) {
                failure = e
            }
        }
        return Result.failure(failure ?: IllegalStateException("no audio output"))
    }

    override fun write(buf: FloatArray, frames: Int) {
        val o = out
        val width = o?.width ?: 2
        val n = frames * 2 * width
        if (bytes.size < n) bytes = ByteArray(n)
        if (width == 3) {
            for (i in 0 until frames * 2) {
                val s = (buf[i].coerceIn(-1f, 1f) * 8388607f).toInt()
                bytes[i * 3] = s.toByte()
                bytes[i * 3 + 1] = (s shr 8).toByte()
                bytes[i * 3 + 2] = (s shr 16).toByte()
            }
        } else {
            for (i in 0 until frames * 2) {
                // TPDF dither: two uniform values, +-1 LSB triangular
                val d = (rng.nextDouble() - rng.nextDouble()).toFloat()
                val s = Math.round(buf[i].coerceIn(-1f, 1f) * 32767f + d).coerceIn(-32768, 32767)
                bytes[i * 2] = s.toByte()
                bytes[i * 2 + 1] = (s shr 8).toByte()
            }
        }
        val l = o?.line
        val before = interruptions
        val written = if (l != null && l.isOpen) runCatching { l.write(bytes, 0, n) }.getOrDefault(0) else 0
        if (written >= n) { shortWrites = 0; backoffMs = RETRY_MIN_MS; return }
        // keep the real-time pace for what the device did not take
        Thread.sleep(((n - written) / (2 * width) * 1000L / SAMPLE_RATE.toLong()).coerceAtLeast(1))
        val lost = l == null || !l.isOpen || (started && before == interruptions && ++shortWrites >= LOST_AFTER)
        if (lost && !closed) reopen(l)
    }

    /** Replaces a dead line with a fresh one on the default device, at most once per backoff interval. */
    private fun reopen(old: SourceDataLine?) {
        val now = System.currentTimeMillis()
        if (now < reopenAt) return
        if (old != null) {
            java.util.logging.Logger.getLogger("JavaSoundSink").warning("audio device lost, reopening")
            out = null
            runCatching { old.close() }
        }
        shortWrites = 0
        val fresh = openBest()
            .onFailure { java.util.logging.Logger.getLogger("JavaSoundSink").warning("no audio device: $it") }
            .getOrNull()
        if (fresh != null) {
            if (started) fresh.line.start()
            out = fresh
            backoffMs = RETRY_MIN_MS
            if (closed) runCatching { fresh.line.close() }
        } else {
            reopenAt = now + backoffMs
            backoffMs = minOf(backoffMs * 2, RETRY_MAX_MS)
        }
    }

    override fun start() { started = true; out?.line?.start() }
    override fun stop() { started = false; interruptions++; out?.line?.stop() }
    override fun flush() { interruptions++; out?.line?.flush() }
    override val latencySec: Double
        get() = out?.let { o -> runCatching { (o.line.bufferSize - o.line.available()) / (2.0 * o.width) / SAMPLE_RATE }.getOrNull() } ?: 0.0
    override fun close() { closed = true; out?.line?.close() }

    companion object {
        /** Short writes in a row on an open line that mean the device is gone. */
        private const val LOST_AFTER = 3
        private const val RETRY_MIN_MS = 250L
        private const val RETRY_MAX_MS = 10_000L

        /** The default sound device, or [SilentSink] when there is none (no sound card, headless CI). */
        fun orSilent(): PcmSink = runCatching { JavaSoundSink() }.getOrElse {
            System.err.println("No audio output available, playing silently: $it")
            SilentSink()
        }
    }
}

/** Discards the audio, paced like a real device so playback still advances in real time. */
class SilentSink : PcmSink {
    override fun write(buf: FloatArray, frames: Int) = Thread.sleep((frames * 1000L / SAMPLE_RATE.toLong()).coerceAtLeast(1))
    override fun start() {}
    override fun stop() {}
    override fun flush() {}
    override val latencySec: Double get() = 0.0
    override fun close() {}
}
