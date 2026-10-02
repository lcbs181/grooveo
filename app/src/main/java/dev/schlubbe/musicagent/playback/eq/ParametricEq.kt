package dev.schlubbe.musicagent.playback.eq

import java.util.Locale
import kotlin.math.PI
import kotlin.math.pow

/**
 * Parametric equalizer model. Filters are analog-matched biquads ([MatchedDesign],
 * after Vicanek) run in double precision by [EqProcessor]; the curve shown in the
 * UI is computed from the very same coefficients, so what you see is what you hear.
 */
enum class FilterType(val apo: String, val label: String, val hasGain: Boolean, val hasSlope: Boolean = false) {
    PEAK("PK", "Glocke", true),
    LOW_SHELF("LSC", "Low-Shelf", true),
    HIGH_SHELF("HSC", "High-Shelf", true),
    LOW_PASS("LPQ", "Tiefpass", false, hasSlope = true),
    HIGH_PASS("HPQ", "Hochpass", false, hasSlope = true),
    NOTCH("NO", "Kerbfilter", false),
}

data class EqBand(
    val type: FilterType = FilterType.PEAK,
    val freq: Double = 1000.0,
    val gainDb: Double = 0.0,
    val q: Double = 1.0,
    val enabled: Boolean = true,
    /** High/low-pass steepness in 12 dB/oct steps (1..4 = 12..48 dB/oct, Butterworth). */
    val slope: Int = 1,
) {
    fun clamped() = copy(
        freq = freq.coerceIn(MIN_FREQ, MAX_FREQ),
        gainDb = gainDb.coerceIn(-MAX_GAIN, MAX_GAIN),
        q = q.coerceIn(MIN_Q, MAX_Q),
        slope = slope.coerceIn(1, 4),
    )

    /** Biquad sections realising this band (several for steep high/low-pass). */
    fun sections(fs: Double = DEFAULT_SAMPLE_RATE): List<Biquad> {
        if (!enabled) return emptyList()
        return if (type.hasSlope && slope > 1) {
            // Butterworth cascade; the user's Q scales the resonance of the last section
            MatchedDesign.butterworthQs(slope).mapIndexed { i, bq ->
                MatchedDesign.design(type, freq, 0.0, if (i == slope - 1) bq * q / 0.7071 else bq, fs)
            }
        } else listOf(MatchedDesign.design(type, freq, gainDb, q, fs))
    }

    fun responseDb(f: Double, fs: Double = DEFAULT_SAMPLE_RATE): Double = sections(fs).sumOf { it.responseDb(2 * PI * f / fs) }

    companion object {
        const val MIN_FREQ = 20.0
        const val MAX_FREQ = 20000.0
        const val MAX_GAIN = 15.0
        const val MIN_Q = 0.1
        const val MAX_Q = 12.0
    }
}

/** Rate used when a caller has no device rate (graphs, presets, desktop engine). */
const val DEFAULT_SAMPLE_RATE = 48000.0

data class EqProfile(
    val name: String = "Flach",
    val enabled: Boolean = true,
    val preampDb: Double = 0.0,
    val bands: List<EqBand> = DEFAULT_LAYOUT,
    val limiter: Boolean = true,
    /** 24 dB/oct high-pass at 20 Hz: removes inaudible rumble that eats headroom and smears bass. */
    val subsonic: Boolean = true,
    /** Psychoacoustic bass (harmonics of the sub-bass), 0..1. Makes bass audible on small speakers. */
    val bassEnhance: Double = 0.0,
    val bassEnhanceFreq: Double = 90.0,
    /** Equal-loudness compensation: more bass/treble at low volume (ISO 226). */
    val loudness: Boolean = false,
    /** "Dynamischer Bass": maximum lift in dB (0 = off) for mixes thinner than [DynamicEqBand.BASS_TARGET_DB]. */
    val dynamicBass: Double = 0.0,
    /** "Schärfe zähmen": 0..1, scales the maximum dip of the 2.5-8 kHz band. */
    val tameHarsh: Double = 0.0,
) {
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    fun normalized(): EqProfile = copy(
        name = name ?: "Eigen",
        bands = (bands ?: DEFAULT_LAYOUT).filterNotNull()
            .map { (if (it.type == null) it.copy(type = FilterType.PEAK) else it).let { b -> if (b.slope == 0) b.copy(slope = 1) else b }.clamped() }
            .take(MAX_BANDS),
        preampDb = preampDb.coerceIn(-24.0, 12.0),
        bassEnhance = if (bassEnhance.isNaN()) 0.0 else bassEnhance.coerceIn(0.0, 1.0),
        bassEnhanceFreq = if (bassEnhanceFreq < 40.0) 90.0 else bassEnhanceFreq.coerceAtMost(160.0),
        dynamicBass = if (dynamicBass.isNaN()) 0.0 else dynamicBass.coerceIn(0.0, MAX_DYNAMIC_BASS_DB),
        tameHarsh = if (tameHarsh.isNaN()) 0.0 else tameHarsh.coerceIn(0.0, 1.0),
    )

    /** Combined static response (dB) of all bands plus preamp (excludes loudness/enhancer). */
    fun responseDb(f: Double): Double =
        if (!enabled) 0.0 else preampDb + bands.sumOf { it.responseDb(f) } + if (subsonic) SUBSONIC.responseDb(f) else 0.0

    /** Highest boost of the curve, used for the auto-preamp suggestion. */
    fun peakBoostDb(): Double = logFrequencies(256).maxOf { responseDb(it) - preampDb }

    fun withAutoPreamp(): EqProfile = copy(preampDb = -(peakBoostDb().coerceAtLeast(0.0)))

    /** Equalizer APO / AutoEQ "ParametricEQ.txt" format (steep passes become cascades). */
    fun toApoText(): String = buildString {
        appendLine("Preamp: ${fmt(preampDb)} dB")
        var n = 1
        bands.forEach { b ->
            val qs = if (b.type.hasSlope && b.slope > 1) MatchedDesign.butterworthQs(b.slope) else listOf(b.q)
            qs.forEach { q ->
                append("Filter ${n++}: ${if (b.enabled) "ON" else "OFF"} ${b.type.apo} Fc ${fmt(b.freq)} Hz")
                if (b.type.hasGain) append(" Gain ${fmt(b.gainDb)} dB")
                appendLine(" Q ${fmt(q)}")
            }
        }
    }

    companion object {
        const val MAX_BANDS = 16
        const val MAX_DYNAMIC_BASS_DB = 9.0
        val SUBSONIC = EqBand(FilterType.HIGH_PASS, 20.0, 0.0, 0.7071, slope = 2)

        val DEFAULT_LAYOUT = listOf(
            EqBand(FilterType.LOW_SHELF, 105.0, 0.0, 0.7),
            EqBand(FilterType.PEAK, 60.0, 0.0, 1.2),
            EqBand(FilterType.PEAK, 250.0, 0.0, 1.0),
            EqBand(FilterType.PEAK, 1000.0, 0.0, 1.0),
            EqBand(FilterType.PEAK, 3000.0, 0.0, 1.0),
            EqBand(FilterType.PEAK, 6500.0, 0.0, 1.5),
            EqBand(FilterType.HIGH_SHELF, 10000.0, 0.0, 0.7),
        )

        fun flat() = EqProfile()

        private fun layout(
            name: String, vararg gains: Double, enhance: Double = 0.0, loudness: Boolean = false,
            dynamicBass: Double = 0.0, tameHarsh: Double = 0.0,
        ) = EqProfile(
            name = name,
            bands = DEFAULT_LAYOUT.mapIndexed { i, b -> b.copy(gainDb = gains[i]) },
            bassEnhance = enhance,
            loudness = loudness,
            dynamicBass = dynamicBass,
            tameHarsh = tameHarsh,
        ).withAutoPreamp()

        /**
         * Built-in presets (layout: Low-Shelf 105 Hz · Sub 60 Hz · Mud 250 Hz · 1k · 3k · 6.5k · High-Shelf 10k).
         * Bass presets lift the shelf + sub peak but cut the 250 Hz "mud" region, which is
         * what makes bass sound full instead of boomy.
         */
        val PRESETS: List<EqProfile> = listOf(
            flat(),
            layout("Sattes Fundament", 5.0, 2.5, -2.0, 0.0, 0.0, 0.0, 0.5, enhance = 0.25),
            // only dynamic stages: lifts thin mixes, leaves full ones alone, tames harsh peaks
            layout("Adaptiv", 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, enhance = 0.15, dynamicBass = 6.0, tameHarsh = 0.6),
            layout("Bass-Boost", 7.0, 4.0, -2.5, -0.5, 0.0, 0.0, 0.0, enhance = 0.35),
            layout("Sub-Bass", 3.0, 7.0, -1.5, 0.0, 0.0, 0.0, 0.0, enhance = 0.15),
            layout("Höhen-Boost", 0.0, 0.0, -1.0, 0.0, 2.0, 3.0, 6.0),
            layout("Vocal", -2.0, -2.0, -1.0, 2.0, 3.5, 1.0, -1.0),
            layout("Loudness", 4.0, 2.0, -1.0, -1.0, 0.0, 1.5, 4.0, loudness = true),
            layout("Elektronik", 5.0, 4.0, -2.0, 0.0, 1.0, 2.0, 3.0, enhance = 0.3),
            layout("Rock", 3.0, 1.5, -2.0, -1.0, 2.0, 3.0, 2.0),
            layout("Akustik", 1.5, 0.0, -0.5, 1.0, 1.5, 1.5, 2.0),
            layout("Sprache", -6.0, -4.0, -1.0, 3.0, 3.0, 0.0, -3.0),
            layout("Weniger Bass", -5.0, -4.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        )

        /** Parses Equalizer APO / AutoEQ text; unknown lines are ignored. */
        fun parseApo(text: String, name: String = "Import"): EqProfile {
            var preamp = 0.0
            val bands = mutableListOf<EqBand>()
            val num = "(-?[0-9]+(?:[.,][0-9]+)?)"
            val preampRe = Regex("""^\s*Preamp:\s*$num\s*dB""", RegexOption.IGNORE_CASE)
            val filterRe = Regex("""^\s*Filter\s*\d*:\s*(ON|OFF)\s+([A-Z]+)\s+Fc\s+$num\s*Hz(?:\s+Gain\s+$num\s*dB)?(?:\s+Q\s+$num)?""", RegexOption.IGNORE_CASE)
            fun d(s: String) = s.replace(',', '.').toDouble()
            text.lineSequence().forEach { line ->
                preampRe.find(line)?.let { preamp = d(it.groupValues[1]) }
                filterRe.find(line)?.let { m ->
                    val g = m.groupValues
                    val type = when (g[2].uppercase()) {
                        "PK", "PEQ", "MODAL" -> FilterType.PEAK
                        "LS", "LSC", "LSQ", "LOWSHELF" -> FilterType.LOW_SHELF
                        "HS", "HSC", "HSQ", "HIGHSHELF" -> FilterType.HIGH_SHELF
                        "LP", "LPQ" -> FilterType.LOW_PASS
                        "HP", "HPQ" -> FilterType.HIGH_PASS
                        "NO" -> FilterType.NOTCH
                        else -> return@let
                    }
                    val defaultQ = if (type == FilterType.LOW_SHELF || type == FilterType.HIGH_SHELF) 0.7 else 0.707
                    bands += EqBand(
                        type = type,
                        freq = d(g[3]),
                        gainDb = g[4].takeIf { it.isNotEmpty() }?.let(::d) ?: 0.0,
                        q = g[5].takeIf { it.isNotEmpty() }?.let(::d) ?: defaultQ,
                        enabled = g[1].equals("ON", ignoreCase = true),
                    ).clamped()
                }
            }
            require(bands.isNotEmpty()) { "Keine Filter gefunden" }
            return EqProfile(name = name, preampDb = preamp, bands = bands.take(MAX_BANDS)).normalized()
        }

        fun logFrequencies(n: Int, from: Double = 20.0, to: Double = 20000.0): List<Double> =
            List(n) { i -> from * (to / from).pow(i / (n - 1.0)) }
    }
}

private fun fmt(v: Double): String = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
