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

/** Default sound device via javax.sound (PipeWire/Pulse/ALSA on Linux). */
class JavaSoundSink(bufferFrames: Int = 4096) : PcmSink {
    private val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 2, true, false)
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(format).apply {
        open(format, bufferFrames * 4)
        start()
    }
    private var bytes = ByteArray(0)

    override fun write(buf: FloatArray, frames: Int) {
        val n = frames * 4
        if (bytes.size < n) bytes = ByteArray(n)
        for (i in 0 until frames * 2) {
            val s = (buf[i].coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[i * 2] = s.toByte()
            bytes[i * 2 + 1] = (s shr 8).toByte()
        }
        line.write(bytes, 0, n)
    }

    override fun start() = line.start()
    override fun stop() = line.stop()
    override fun flush() = line.flush()
    override val latencySec: Double get() = (line.bufferSize - line.available()) / 4 / SAMPLE_RATE
    override fun close() = line.close()
}
