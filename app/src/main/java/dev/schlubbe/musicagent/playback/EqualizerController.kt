package dev.schlubbe.musicagent.playback

import android.media.audiofx.Equalizer
import android.util.Log

/** EQ presets exposed in Settings. Persisted by name via [dev.schlubbe.musicagent.data.repository.SettingsRepository]. */
enum class EqPreset {
    FLAT,
    BASS_BOOST,
    TREBLE_BOOST,
    VOCAL,
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

    fun applyPreset(preset: EqPreset) {
        pendingPreset = preset
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
        }
    }
}
