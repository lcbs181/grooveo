package dev.schlubbe.musicagent.desktop.audio

import dev.schlubbe.musicagent.playback.reverb.PartitionedConvolver
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** "3D-Sound" room presets; labels, descriptions and wet mix match the Android app. */
enum class Sound3dPreset(val label: String, val description: String, val asset: String?, val wetMix: Float) {
    DISABLED("Deaktiviert", "Kein Raumklang-Effekt", null, 0f),
    KINO("Kino", "Breiter, kinoartiger Hall", "kino.pcm", 0.22f),
    HEIMKINO("Heimkino", "Dezenter Raumklang für zuhause", "heimkino.pcm", 0.18f),
    KONZERT("Konzert", "Weiter Konzertsaal-Hall", "konzert.pcm", 0.26f),
    RAVE("Rave", "Enger, druckvoller Club-Hall", "rave.pcm", 0.22f),
    STUDIO("Studio", "Trocken, fast kein Hall", "studio.pcm", 0.12f),
    KIRCHE("Kirche", "Langer, hallender Kirchenraum", "kirche.pcm", 0.3f),
    ;

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: DISABLED
    }
}

/**
 * Convolution reverb with the real impulse responses bundled with the Android app
 * (app/src/main/assets/reverb, 22.05 kHz stereo s16), using the shared
 * [PartitionedConvolver]. Processes blocks of exactly [BLOCK] frames.
 */
class ReverbStage {
    private class Engine(val left: PartitionedConvolver, val right: PartitionedConvolver, val wet: Float) {
        val dry = 1f - wet * 0.5f
    }

    @Volatile private var engine: Engine? = null
    private val inL = FloatArray(BLOCK)
    private val inR = FloatArray(BLOCK)
    private val wetL = FloatArray(BLOCK)
    private val wetR = FloatArray(BLOCK)

    fun setPreset(preset: Sound3dPreset) {
        engine = loadIr(preset)?.let { (l, r) ->
            Engine(PartitionedConvolver(l, BLOCK), PartitionedConvolver(r, BLOCK), preset.wetMix)
        }
    }

    /** In-place on interleaved stereo [buf] of [BLOCK] frames. */
    fun process(buf: FloatArray) {
        val e = engine ?: return
        for (i in 0 until BLOCK) { inL[i] = buf[2 * i]; inR[i] = buf[2 * i + 1] }
        e.left.process(inL, wetL)
        e.right.process(inR, wetR)
        for (i in 0 until BLOCK) {
            buf[2 * i] = inL[i] * e.dry + wetL[i] * e.wet
            buf[2 * i + 1] = inR[i] * e.dry + wetR[i] * e.wet
        }
    }

    companion object {
        const val BLOCK = 1024
        private const val IR_RATE = 22050

        fun loadIr(preset: Sound3dPreset, targetRate: Int = SAMPLE_RATE.toInt()): Pair<FloatArray, FloatArray>? {
            val asset = preset.asset ?: return null
            val bytes = ReverbStage::class.java.getResourceAsStream("/reverb/$asset")?.use { it.readBytes() } ?: return null
            val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val frames = shorts.remaining() / 2
            val l = FloatArray(frames) { shorts.get(it * 2) / 32768f }
            val r = FloatArray(frames) { shorts.get(it * 2 + 1) / 32768f }
            val rl = resample(l, targetRate)
            val rr = resample(r, targetRate)
            var energy = 0.0
            rl.forEach { energy += it * it }; rr.forEach { energy += it * it }
            if (energy > 0) {
                val s = (1.0 / sqrt(energy / 2)).toFloat()
                for (i in rl.indices) rl[i] *= s
                for (i in rr.indices) rr[i] *= s
            }
            return rl to rr
        }

        private fun resample(input: FloatArray, targetRate: Int): FloatArray {
            val ratio = targetRate.toDouble() / IR_RATE
            return FloatArray((input.size * ratio).toInt().coerceAtLeast(1)) { i ->
                val src = i / ratio
                val i0 = src.toInt().coerceIn(0, input.size - 1)
                val i1 = (i0 + 1).coerceAtMost(input.size - 1)
                val f = (src - i0).toFloat()
                input[i0] * (1 - f) + input[i1] * f
            }
        }
    }
}

/** Linear below 0.8, smooth knee above (same curve as the Android reverb processor). */
fun softClip(x: Float): Float {
    val a = abs(x)
    if (a <= 0.8f) return x
    val over = a - 0.8f
    val shaped = 0.8f + 0.2f * (over / (over + 0.2f))
    return if (x < 0) -shaped else shaped
}
