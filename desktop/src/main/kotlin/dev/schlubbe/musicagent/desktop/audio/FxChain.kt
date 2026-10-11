package dev.schlubbe.musicagent.desktop.audio

import kotlin.math.sqrt

/** One effect in an [FxChain]: a [type] from [FxUnitSpec.ALL] with its numeric [params] (and a 3D-Sound [preset] for "room"). */
data class FxUnit(val type: String, val params: Map<String, Double> = emptyMap(), val preset: String? = null)

/** Effects applied to the whole output, in order, before the equalizer. */
data class FxChain(val name: String = "", val units: List<FxUnit> = emptyList())

/** What a unit type is and which parameters it takes (with range and default). */
data class FxUnitSpec(val type: String, val label: String, val description: String, val params: List<Param>) {
    data class Param(val key: String, val min: Double, val max: Double, val default: Double, val meaning: String)

    companion object {
        val ALL = listOf(
            FxUnitSpec("highpass", "Hochpass", "Removes everything below the cutoff", listOf(Param("hz", 20.0, 2000.0, 30.0, "cutoff in Hz"))),
            FxUnitSpec("lowpass", "Tiefpass", "Removes everything above the cutoff", listOf(Param("hz", 200.0, 20_000.0, 12_000.0, "cutoff in Hz"))),
            FxUnitSpec("eq", "3-Band-EQ", "DJ isolator: bass below 200 Hz, mids, highs above 2.5 kHz", listOf(
                Param("low", EQ_KILL_DB, 6.0, 0.0, "dB, -40 = kill"), Param("mid", EQ_KILL_DB, 6.0, 0.0, "dB"), Param("high", EQ_KILL_DB, 6.0, 0.0, "dB"))),
            FxUnitSpec("echo", "Echo", "Tempo-synced echo (follows the playing track's beat)", listOf(
                Param("beats", 0.125, 4.0, 0.75, "delay in beats"), Param("feedback", 0.0, 0.9, 0.4, "repeats"), Param("mix", 0.0, 1.0, 0.2, "send level"))),
            FxUnitSpec("width", "Stereobreite", "0 = mono, 1 = unchanged, 2 = extra wide", listOf(Param("amount", 0.0, 2.0, 1.0, "stereo width"))),
            FxUnitSpec("room", "Raum", "Convolution room from the 3D-Sound presets; set \"preset\" to one of ${Sound3dPreset.entries.filter { it.asset != null }.joinToString { it.name }}", emptyList()),
        )

        fun of(type: String) = ALL.firstOrNull { it.type == type }

        /** Checks [chain]: known types, known parameters, values clamped into range, room presets valid. */
        fun validate(chain: FxChain): FxChain {
            require(chain.units.size <= 12) { "At most 12 effects in a chain" }
            return chain.copy(units = chain.units.map { u ->
                val spec = of(u.type) ?: error("Unknown effect '${u.type}', use one of ${ALL.joinToString { it.type }}")
                u.params.keys.firstOrNull { k -> spec.params.none { it.key == k } }?.let { error("${u.type} has no parameter '$it'") }
                if (u.type == "room") {
                    val p = Sound3dPreset.entries.firstOrNull { it.name.equals(u.preset, ignoreCase = true) && it.asset != null }
                        ?: error("room needs a preset: ${Sound3dPreset.entries.filter { it.asset != null }.joinToString { it.name }}")
                    u.copy(preset = p.name, params = emptyMap())
                } else u.copy(params = spec.params.associate { p -> p.key to (u.params[p.key] ?: p.default).coerceIn(p.min, p.max) })
            })
        }
    }
}

/**
 * The running [FxChain] on the engine's output. A new chain is built off the audio
 * thread and crossfaded in over one block, so switching never clicks.
 */
class MasterFx {
    private interface Stage { fun process(buf: FloatArray, frames: Int) }

    @Volatile private var stages: List<Stage> = emptyList()
    @Volatile private var pending: List<Stage>? = null
    /** Beat length of the playing track, for the echo. */
    @Volatile var beatSec: Double = 0.5
    private val tmp = FloatArray(AudioEngine.BLOCK * 2)

    fun set(chain: FxChain?) {
        pending = (chain?.units ?: emptyList()).mapNotNull { stageFor(it) }
    }

    val active: Boolean get() = stages.isNotEmpty() || pending != null

    fun process(buf: FloatArray, frames: Int) {
        val next = pending
        if (next == null) { for (s in stages) s.process(buf, frames); return }
        System.arraycopy(buf, 0, tmp, 0, frames * 2)
        for (s in stages) s.process(buf, frames)
        for (s in next) s.process(tmp, frames)
        for (i in 0 until frames) {
            val t = (i + 1f) / frames
            buf[2 * i] = buf[2 * i] * (1 - t) + tmp[2 * i] * t
            buf[2 * i + 1] = buf[2 * i + 1] * (1 - t) + tmp[2 * i + 1] * t
        }
        stages = next
        pending = null
    }

    private fun stageFor(u: FxUnit): Stage? {
        val p = u.params
        return when (u.type) {
            "highpass", "lowpass" -> {
                val f = SweepFilter(highpass = u.type == "highpass").apply { jumpTo(p["hz"] ?: 1000.0) }
                object : Stage { override fun process(buf: FloatArray, frames: Int) = f.process(buf, 0, frames) }
            }
            "eq" -> {
                val iso = Isolator().apply { setGainsDb(p["low"] ?: 0.0, p["mid"] ?: 0.0, p["high"] ?: 0.0); settle() }
                object : Stage { override fun process(buf: FloatArray, frames: Int) = iso.process(buf, 0, frames) }
            }
            "echo" -> {
                val e = Echo().apply { set(p["mix"] ?: 0.2, p["feedback"] ?: 0.4) }
                val beats = p["beats"] ?: 0.75
                object : Stage {
                    override fun process(buf: FloatArray, frames: Int) {
                        e.delayFrames = (beats * beatSec * SAMPLE_RATE).toInt()
                        e.process(buf, buf, 0, frames)
                    }
                }
            }
            "width" -> {
                val w = (p["amount"] ?: 1.0).toFloat()
                object : Stage {
                    override fun process(buf: FloatArray, frames: Int) {
                        for (i in 0 until frames) {
                            val m = (buf[2 * i] + buf[2 * i + 1]) / 2
                            val s = (buf[2 * i] - buf[2 * i + 1]) / 2 * w
                            buf[2 * i] = m + s; buf[2 * i + 1] = m - s
                        }
                    }
                }
            }
            "room" -> {
                val r = ReverbStage().apply { setPreset(Sound3dPreset.of(u.preset)) }
                object : Stage { override fun process(buf: FloatArray, frames: Int) = r.process(buf) }
            }
            else -> null
        }
    }

    companion object {
        /** RMS of [buf] (both channels), for tests. */
        fun rms(buf: FloatArray, from: Int, to: Int) = sqrt((from until to).sumOf { buf[it].toDouble() * buf[it] } / (to - from))
    }
}
