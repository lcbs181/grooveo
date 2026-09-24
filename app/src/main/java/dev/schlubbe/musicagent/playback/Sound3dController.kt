package dev.schlubbe.musicagent.playback

import dev.schlubbe.musicagent.playback.reverb.ConvolutionReverbAudioProcessor

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
 * Selects the current "Raumklang" preset on the shared [ConvolutionReverbAudioProcessor] -
 * see that class's kdoc for the actual DSP, and [dev.schlubbe.musicagent.playback.reverb.ReverbIrLibrary]'s
 * for where the impulse responses behind each of the 7 named presets come from.
 *
 * This used to wrap [android.media.audiofx.PresetReverb], attached to the current
 * ExoPlayer's audio session id - measured (on a real device, not the emulator, which
 * has its own null/no-op effect stubs) to have literally no audible effect at all,
 * a known issue with several OEMs' own effect-framework implementations. Unlike
 * that, [reverbProcessor] is a genuine in-process part of the audio pipeline, so
 * there's no session id to attach to and no per-device effect-implementation
 * quirk it could be silently swallowed by.
 */
class Sound3dController(private val reverbProcessor: ConvolutionReverbAudioProcessor) {
    fun applyPreset(preset: Sound3dPreset) {
        reverbProcessor.applyPreset(preset)
    }
}
