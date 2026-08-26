package dev.schlubbe.musicagent.data.backup

/** JSON shape written by [BackupManager]. Kept as its own file so the schema is
 * easy to read/diff independently of the read/write logic. [BackupPayload.version]
 * exists purely for future-compatibility -- [BackupManager] doesn't yet branch on
 * it, but a later schema change can check it before attempting to parse an older
 * backup file. */
data class BackupPayload(
    val version: Int = CURRENT_VERSION,
    val createdAt: String, // ISO-8601
    val likes: List<BackupTrackDto> = emptyList(),
    val playlists: List<BackupPlaylistDto> = emptyList(),
    val followedArtists: List<BackupFollowedArtistDto> = emptyList(),
    val savedPlaylists: List<BackupSavedPlaylistDto> = emptyList(),
    val settings: BackupSettingsDto,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** Mirrors [dev.schlubbe.musicagent.data.local.entity.LocalTrackEntity] /
 * [dev.schlubbe.musicagent.data.remote.dto.TrackResultDto] minus the
 * DRM flag, which is a live SoundCloud-resolution signal that doesn't make
 * sense to freeze into a backup. */
data class BackupTrackDto(
    val source: String,
    val sourceId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationSec: Int?,
    val thumbnailUrl: String?,
    val webpageUrl: String,
)

data class BackupPlaylistDto(
    val id: String,
    val name: String,
    val createdAt: String,
    val description: String?,
    val accentColorKey: String?,
    val moodTags: List<String>,
    val tracks: List<BackupTrackDto>,
)

data class BackupFollowedArtistDto(
    val source: String,
    val sourceId: String,
    val name: String,
    val thumbnailUrl: String?,
    val followedAt: String,
)

data class BackupSavedPlaylistDto(
    val source: String,
    val sourceId: String,
    val title: String,
    val thumbnailUrl: String?,
    val owner: String?,
    val trackCount: Int?,
    val webpageUrl: String,
    val savedAt: String,
)

/** Personalization/playback settings worth restoring across a device or a wipe.
 * Deliberately excludes [dev.schlubbe.musicagent.data.repository.SettingsRepository]'s
 * backendBaseUrl/apiKey fields: this file is user-shareable via the "Teilen" share
 * sheet, and a self-hosted backend URL plus its API key is exactly the kind of thing
 * that shouldn't ride along in a file the user might hand to someone else. */
data class BackupSettingsDto(
    val hiResAudio: Boolean,
    val dataSaverMode: Boolean,
    val eqPreset: String,
    val playerStyle: String,
    val autoplayRadio: Boolean,
    val contentSafetyFilter: Boolean = true,
    val sound3dPreset: String,
    val downloadsWifiOnly: Boolean,
    val notifyNewUploads: Boolean,
    val showMixControls: Boolean,
    val showFeatured: Boolean,
    val showNewUploads: Boolean,
    val autoBackup: Boolean,
    val profileName: String,
    val profileColorStyle: String,
)
