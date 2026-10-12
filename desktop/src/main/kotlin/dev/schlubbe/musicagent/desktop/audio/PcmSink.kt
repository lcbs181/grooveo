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
 */
class JavaSoundSink(bufferFrames: Int = 4096) : PcmSink {
    private val line: SourceDataLine
    /** Bytes per sample: 3 (24 bit) or 2 (16 bit). */
    private val width: Int

    init {
        var opened: Pair<SourceDataLine, Int>? = null
        var failure: Exception? = null
        for (bits in intArrayOf(24, 16)) {
            val format = AudioFormat(SAMPLE_RATE.toFloat(), bits, 2, true, false)
            try {
                val l = AudioSystem.getSourceDataLine(format)
                l.open(format, bufferFrames * 2 * bits / 8)
                opened = l to bits / 8
                break
            } catch (e: Exception) {
                failure = e
            }
        }
        val (l, w) = opened ?: throw failure ?: IllegalStateException("no audio output")
        line = l.apply { start() }
        width = w
    }

    private var bytes = ByteArray(0)
    private val rng = java.util.SplittableRandom()

    override fun write(buf: FloatArray, frames: Int) {
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
        line.write(bytes, 0, n)
    }

    override fun start() = line.start()
    override fun stop() = line.stop()
    override fun flush() = line.flush()
    override val latencySec: Double get() = (line.bufferSize - line.available()) / (2.0 * width) / SAMPLE_RATE
    override fun close() = line.close()

    companion object {
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
