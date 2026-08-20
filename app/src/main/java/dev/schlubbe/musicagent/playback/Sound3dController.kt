package dev.schlubbe.musicagent.playback

import android.media.audiofx.PresetReverb
import android.util.Log

/** The 7 "Raumklang" presets from Einstellungen > 3D-Sound (see the design
 * handoff's 3D-Sound section) - persisted by name via SettingsRepository. */
enum class Sound3dPreset(val label: String, val description: String) {
    DISABLED("Deaktiviert", "Kein Raumklang-Effekt"),
    KINO("Kino", "Breiter, kinoartiger Hall"),
    HEIMKINO("Heimkino", "Dezenter Raumklang für zuhause"),
    KONZERT("Konzert", "Weiter Konzertsaal-Hall"),
    RAVE("Rave", "Enger, druckvoller Club-Hall"),
    STUDIO("Studio", "Trocken, fast kein Hall"),
    KIRCHE("Kirche", "Langer, hallender Kirchenraum"),
}

/**
 * Wraps the platform [PresetReverb] audio effect, attached to the current
 * ExoPlayer's audio session id — same attach-is-idempotent pattern as
 * [EqualizerController]. This is a real, built-in Android DSP effect (not a
 * custom convolution engine with impulse-response files, which would be a much
 * larger separate undertaking) - [PresetReverb]'s own fixed presets are mapped
 * to the 7 named spaces below by approximate room-size/character match.
 */
class Sound3dController {
    private var reverb: PresetReverb? = null
    private var pendingPreset: Sound3dPreset = Sound3dPreset.DISABLED
    private var attachedSessionId: Int = 0

    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0 || audioSessionId == attachedSessionId) return
        release()
        attachedSessionId = audioSessionId
        reverb = runCatching {
            // Priority 0: lowest, so this never steals the effect slot from a
            // system-level effect (e.g. a accessibility service) - matches
            // EqualizerController's own priority convention.
            PresetReverb(0, audioSessionId)
        }.onFailure {
            Log.w("Sound3dController", "Failed to attach PresetReverb to session $audioSessionId", it)
        }.getOrNull()
        applyPreset(pendingPreset)
    }

    fun applyPreset(preset: Sound3dPreset) {
        pendingPreset = preset
        val fx = reverb ?: return
        runCatching {
            if (preset == Sound3dPreset.DISABLED) {
                fx.enabled = false
                return@runCatching
            }
            fx.preset = presetReverbConstantFor(preset)
            fx.enabled = true
        }
    }

    fun release() {
        reverb?.release()
        reverb = null
        attachedSessionId = 0
    }

    // PresetReverb exposes exactly 6 non-off presets - one-to-one with our 6
    // named spaces, ordered smallest/driest to largest/longest decay.
    private fun presetReverbConstantFor(preset: Sound3dPreset): Short = when (preset) {
        Sound3dPreset.DISABLED -> PresetReverb.PRESET_NONE
        Sound3dPreset.STUDIO -> PresetReverb.PRESET_SMALLROOM
        Sound3dPreset.HEIMKINO -> PresetReverb.PRESET_MEDIUMROOM
        Sound3dPreset.RAVE -> PresetReverb.PRESET_LARGEROOM
        Sound3dPreset.KONZERT -> PresetReverb.PRESET_MEDIUMHALL
        Sound3dPreset.KINO -> PresetReverb.PRESET_LARGEHALL
        // Plate reverb has no literal "room size" - a smooth, dense, long decay
        // originally modeled on physical studio plate reverbs - the closest
        // available match to a cathedral's long, smooth tail.
        Sound3dPreset.KIRCHE -> PresetReverb.PRESET_PLATE
    }
}
