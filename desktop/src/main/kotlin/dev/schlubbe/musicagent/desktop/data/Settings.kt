package dev.schlubbe.musicagent.desktop.data

import dev.schlubbe.musicagent.playback.eq.EqProfile

/**
 * All desktop settings. Field defaults mirror the Android app's SettingsRepository.
 * Gson bypasses constructors, so [normalized] repairs fields missing in older files.
 */
data class Settings(
    val hiResAudio: Boolean = false,
    val dataSaverMode: Boolean = false,
    val crossfadeSeconds: Int = 0,
    val playerStyle: String = "waveform",
    val autoplayRadio: Boolean = true,
    val contentSafetyFilter: Boolean = true,
    val showMixControls: Boolean = true,
    val showFeatured: Boolean = true,
    val showNewUploads: Boolean = true,
    val sound3dPreset: String = "DISABLED",
    val vizVariant: String = "particles",
    val notifyNewUploads: Boolean = false,
    val autoBackup: Boolean = false,
    val lastBackupAt: String? = null,
    val profileName: String = "",
    val profileColorStyle: String = "auto",
    val sourceSoundCloud: Boolean = true,
    val sourceYouTube: Boolean = true,
    val preferredGenres: List<String> = emptyList(),
    val preferredArtists: List<String> = emptyList(),
    val onboardingDone: Boolean = false,
    val lastSeenVersion: String = "",
    val homeScPromoDismissed: Boolean = false,
    val soundCloudClientId: String = "",
    val volume: Float = 0.8f,
    val eq: EqProfile = EqProfile.flat(),
    val eqUserPresets: List<EqProfile> = emptyList(),
    val downloadDir: String = "",
    /** "system" | "light" | "dark" */
    val themeMode: String = "system",
    val queuePanelOpen: Boolean = true,
    val minimizeToTray: Boolean = true,
) {
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    fun normalized(): Settings {
        val d = Settings()
        return copy(
            playerStyle = playerStyle ?: d.playerStyle,
            sound3dPreset = sound3dPreset ?: d.sound3dPreset,
            vizVariant = vizVariant ?: d.vizVariant,
            profileName = profileName ?: d.profileName,
            profileColorStyle = profileColorStyle ?: d.profileColorStyle,
            preferredGenres = preferredGenres ?: emptyList(),
            preferredArtists = preferredArtists ?: emptyList(),
            lastSeenVersion = lastSeenVersion ?: "",
            soundCloudClientId = soundCloudClientId ?: "",
            eq = eq?.normalized() ?: EqProfile.flat(),
            eqUserPresets = eqUserPresets?.map { it.normalized() } ?: emptyList(),
            downloadDir = downloadDir ?: "",
            themeMode = themeMode ?: "system",
            crossfadeSeconds = crossfadeSeconds.coerceIn(0, 12),
            volume = if (volume.isNaN()) d.volume else volume.coerceIn(0f, 1f),
        )
    }

    /** "all" | "soundcloud" | "ytmusic", like the Android app's enabledSource. */
    val enabledSource: String
        get() = when {
            sourceSoundCloud && !sourceYouTube -> "soundcloud"
            sourceYouTube && !sourceSoundCloud -> "ytmusic"
            else -> "all"
        }
}
