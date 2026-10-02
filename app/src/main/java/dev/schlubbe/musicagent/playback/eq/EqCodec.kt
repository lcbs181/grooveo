package dev.schlubbe.musicagent.playback.eq

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * JSON persistence for [EqProfile] (DataStore strings, backups) plus the migration
 * from the old platform-Equalizer settings (a preset name and five custom gains).
 */
object EqCodec {
    private val gson = Gson()
    private val listType = object : TypeToken<List<EqProfile>>() {}.type

    fun encode(p: EqProfile): String = gson.toJson(p)

    /** Gson leaves fields missing from older JSON at null/0; [EqProfile.normalized] repairs them. */
    fun decode(json: String?): EqProfile? =
        json?.let { runCatching { gson.fromJson(it, EqProfile::class.java)?.normalized() }.getOrNull() }

    fun encodeList(list: List<EqProfile>): String = gson.toJson(list)

    fun decodeList(json: String?): List<EqProfile> =
        json?.let { runCatching { gson.fromJson<List<EqProfile>>(it, listType)?.filterNotNull()?.map { p -> p.normalized() } }.getOrNull() }
            ?: emptyList()

    /** Reference frequencies of the old five-slider custom EQ. */
    private val LEGACY_BANDS = listOf(60.0, 230.0, 910.0, 3600.0, 14000.0)

    /**
     * Maps the old settings (`EqPreset` name and the CUSTOM preset's five gains) to a
     * parametric profile: the fixed presets become their parametric counterparts, the
     * custom gains become shelf/peak bands at the old reference frequencies.
     */
    fun fromLegacy(preset: String?, customGains: List<Float>?): EqProfile {
        fun named(n: String) = EqProfile.PRESETS.first { it.name == n }
        return when (preset) {
            "BASS_BOOST" -> named("Bass-Boost")
            "TREBLE_BOOST" -> named("Höhen-Boost")
            "VOCAL" -> named("Vocal")
            "CUSTOM" -> {
                val g = customGains?.takeIf { it.size == LEGACY_BANDS.size } ?: return EqProfile.flat()
                EqProfile(
                    name = "Eigen",
                    bands = LEGACY_BANDS.mapIndexed { i, f ->
                        val type = when (i) {
                            0 -> FilterType.LOW_SHELF
                            LEGACY_BANDS.lastIndex -> FilterType.HIGH_SHELF
                            else -> FilterType.PEAK
                        }
                        EqBand(type, f, g[i].toDouble(), if (type == FilterType.PEAK) 1.0 else 0.7).clamped()
                    },
                ).withAutoPreamp()
            }
            else -> EqProfile.flat()
        }
    }
}
