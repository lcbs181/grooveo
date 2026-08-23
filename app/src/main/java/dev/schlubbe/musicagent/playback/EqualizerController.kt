package dev.schlubbe.musicagent.playback

import android.media.audiofx.Equalizer
import android.util.Log

/** EQ presets exposed in Settings. Persisted by name via [dev.schlubbe.musicagent.data.repository.SettingsRepository].
 * [CUSTOM] has no fixed per-frequency formula of its own (see [EqualizerController.bandLevelFor]) -
 * its actual band gains come from [dev.schlubbe.musicagent.data.repository.SettingsRepository]'s
 * separately-persisted 5-value gain list (one per [dev.schlubbe.musicagent.ui.settings.EQ_BAND_SPECS]
 * reference frequency), applied via [EqualizerController.applyCustomGains]. */
enum class EqPreset {
    FLAT,
    BASS_BOOST,
    TREBLE_BOOST,
    VOCAL,
    CUSTOM,
}

/**
 * Wraps the platform [Equalizer] audio effect, attached to the current ExoPlayer's audio
 * session id (see [androidx.media3.exoplayer.ExoPlayer.getAudioSessionId]). The session id
 * can change over the player's lifetime, so [attach] is safe to call repeatedly -- it's a
 * no-op if the id hasn't actually changed.
 *
 * Presets are hand-rolled via [Equalizer.setBandLevel] based on each band's *actual* center
 * frequency rather than an assumed fixed band count/layout, since both are device-dependent
 * (most phones expose 5 bands, but this doesn't assume that).
 */
class EqualizerController {
    private var equalizer: Equalizer? = null
    private var pendingPreset: EqPreset = EqPreset.FLAT
    private var attachedSessionId: Int = 0

    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0 || audioSessionId == attachedSessionId) return
        release()
        attachedSessionId = audioSessionId
        equalizer = runCatching {
            Equalizer(0, audioSessionId).apply { enabled = true }
        }.onFailure {
            Log.w("EqualizerController", "Failed to attach Equalizer to session $audioSessionId", it)
        }.getOrNull()
        applyPreset(pendingPreset)
    }

    // The last custom gains applied (or pending, if no Equalizer is attached yet) -
    // re-sent whenever [applyPreset] is called with CUSTOM (e.g. re-attaching to a
    // new audio session on track change) so CUSTOM survives exactly like the other
    // 4 presets do, without EqualizerScreen needing to re-push it itself.
    private var pendingCustomGains: List<Float> = List(5) { 0f }

    fun applyPreset(preset: EqPreset) {
        pendingPreset = preset
        if (preset == EqPreset.CUSTOM) {
            applyCustomGains(pendingCustomGains)
            return
        }
        val eq = equalizer ?: return
        val range = runCatching { eq.bandLevelRange }.getOrNull() ?: return
        val minLevel = range[0]
        val maxLevel = range[1]
        for (band in 0 until eq.numberOfBands.toInt()) {
            val freqHz = runCatching { eq.getCenterFreq(band.toShort()) / 1000 }.getOrDefault(0)
            val level = bandLevelFor(preset, freqHz, minLevel, maxLevel)
            runCatching { eq.setBandLevel(band.toShort(), level) }
        }
    }

    /** Applies [gains] (5 values in dB, one per [dev.schlubbe.musicagent.ui.settings.EQ_BAND_SPECS]
     * reference frequency, `-12f..12f`) as the CUSTOM preset - each real device band
     * picks up the gain of whichever of the 5 reference frequencies its own center
     * frequency is closest to (log-scale nearest), the same "device-agnostic band
     * count" approach [bandLevelFor] already uses for the 4 fixed presets. A no-op
     * (beyond remembering [gains] for the next [attach]/[applyPreset]) if no
     * equalizer is attached yet or the pending preset isn't CUSTOM. */
    fun applyCustomGains(gains: List<Float>) {
        pendingCustomGains = gains
        if (pendingPreset != EqPreset.CUSTOM) return
        val eq = equalizer ?: return
        val range = runCatching { eq.bandLevelRange }.getOrNull() ?: return
        val minLevel = range[0]
        val maxLevel = range[1]
        for (band in 0 until eq.numberOfBands.toInt()) {
            val freqHz = runCatching { eq.getCenterFreq(band.toShort()) / 1000 }.getOrDefault(0)
            val nearestIndex = REFERENCE_BAND_HZ.indices.minByOrNull {
                kotlin.math.abs(kotlin.math.ln(freqHz.coerceAtLeast(1).toDouble()) - kotlin.math.ln(REFERENCE_BAND_HZ[it].toDouble()))
            } ?: 0
            val db = gains.getOrElse(nearestIndex) { 0f }.coerceIn(-12f, 12f)
            val bound = if (db >= 0) maxLevel else minLevel
            val level = ((db / 12f) * bound).toInt().toShort()
            runCatching { eq.setBandLevel(band.toShort(), level) }
        }
    }

    /** Manual per-band override, for a future "custom" preset UI. Safe no-op if no
     * equalizer is attached yet. */
    fun setBandLevel(band: Int, level: Short) {
        runCatching { equalizer?.setBandLevel(band.toShort(), level) }
    }

    fun numberOfBands(): Int = equalizer?.numberOfBands?.toInt() ?: 0

    fun bandLevelRange(): Pair<Short, Short> {
        val range = equalizer?.bandLevelRange ?: return 0.toShort() to 0.toShort()
        return range[0] to range[1]
    }

    fun release() {
        equalizer?.release()
        equalizer = null
        attachedSessionId = 0
    }

    private fun bandLevelFor(preset: EqPreset, freqHz: Int, minLevel: Short, maxLevel: Short): Short {
        fun scaled(fraction: Float): Short {
            val bound = if (fraction >= 0) maxLevel else minLevel
            return (fraction.let { if (it >= 0) it else -it } * bound).toInt().toShort()
        }
        return when (preset) {
            EqPreset.FLAT -> 0
            EqPreset.BASS_BOOST -> when {
                freqHz < 250 -> scaled(0.9f)
                freqHz < 1000 -> scaled(0.3f)
                else -> 0
            }
            EqPreset.TREBLE_BOOST -> when {
                freqHz > 4000 -> scaled(0.9f)
                freqHz > 1000 -> scaled(0.3f)
                else -> 0
            }
            EqPreset.VOCAL -> when {
                freqHz in 500..4000 -> scaled(0.6f)
                freqHz < 200 -> scaled(-0.3f)
                else -> 0
            }
            // Never actually reached - applyPreset() intercepts CUSTOM and routes to
            // applyCustomGains() instead, which has its own per-band lookup. Kept
            // here only so this `when` stays exhaustive over EqPreset.
            EqPreset.CUSTOM -> 0
        }
    }

    companion object {
        private val REFERENCE_BAND_HZ = intArrayOf(60, 230, 910, 3600, 14000)
    }
}
