package dev.schlubbe.musicagent.desktop.data

import dev.schlubbe.musicagent.data.backup.BackupFollowedArtistDto
import dev.schlubbe.musicagent.data.backup.BackupPayload
import dev.schlubbe.musicagent.data.backup.BackupPlaylistDto
import dev.schlubbe.musicagent.data.backup.BackupSavedPlaylistDto
import dev.schlubbe.musicagent.data.backup.BackupSettingsDto
import dev.schlubbe.musicagent.data.backup.BackupTrackDto
import dev.schlubbe.musicagent.data.backup.legacyPresetName
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Export/import in the Android app's backup JSON format (BackupModels, shared
 * source), so a backup moves between phone and desktop in both directions.
 * Restore replaces likes, playlists, follows and saved playlists, like on Android.
 */
class BackupManager(private val store: LibraryStore, private val settings: SettingsRepository, private val dir: File) {

    fun export(target: File? = null): File {
        val payload = payload()
        val file = target ?: File(dir, "backup_${STAMP.format(Instant.now())}.json")
        writeJson(file, payload)
        settings.update { it.copy(lastBackupAt = Instant.now().toString()) }
        return file
    }

    /** The current library and settings in the shared backup format. */
    fun payload(): BackupPayload {
        val d = store.current
        val s = settings.current
        return BackupPayload(
            version = 1,
            createdAt = Instant.now().toString(),
            likes = d.likes.map { it.track.toDto() },
            playlists = d.playlists.map { p ->
                BackupPlaylistDto(p.id, p.name, Instant.ofEpochMilli(p.createdAt).toString(), p.description, p.accentColorKey, p.moodTags, p.tracks.map { it.track.toDto() })
            },
            followedArtists = d.followed.map { BackupFollowedArtistDto(it.source, it.sourceId, it.name, it.thumbnailUrl, Instant.ofEpochMilli(it.followedAt).toString()) },
            savedPlaylists = d.savedPlaylists.map { BackupSavedPlaylistDto(it.source, it.sourceId, it.title, it.thumbnailUrl, it.owner, it.trackCount, it.webpageUrl, Instant.ofEpochMilli(it.savedAt).toString()) },
            settings = BackupSettingsDto(
                hiResAudio = s.hiResAudio, dataSaverMode = s.dataSaverMode, eqPreset = s.eq.legacyPresetName(), eqProfile = s.eq, eqUserPresets = s.eqUserPresets, playerStyle = s.playerStyle,
                autoplayRadio = s.autoplayRadio, contentSafetyFilter = s.contentSafetyFilter, sound3dPreset = s.sound3dPreset,
                downloadsWifiOnly = false, notifyNewUploads = s.notifyNewUploads, showMixControls = s.showMixControls,
                showFeatured = s.showFeatured, showNewUploads = s.showNewUploads, autoBackup = s.autoBackup,
                profileName = s.profileName, profileColorStyle = s.profileColorStyle,
            ),
        )
    }

    /**
     * Applies a synced library ([SyncMerge] result, a superset of what is here): adds what is
     * missing and keeps existing playlist ids, covers and settings.
     */
    fun applySynced(p: BackupPayload) {
        fun ts(iso: String?) = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
        store.mutate { d ->
            val known = d.likes.map { it.track.source to it.track.sourceId }.toSet()
            val newLikes = p.likes.orEmpty().filter { (it.source to it.sourceId) !in known }
                .mapIndexed { i, t -> LikedTrack(t.toTrack(), System.currentTimeMillis() - i) }
            val byName = d.playlists.associateBy { it.name.trim().lowercase() }
            val merged = d.playlists.map { mine ->
                val theirs = p.playlists.orEmpty().firstOrNull { it.name.trim().lowercase() == mine.name.trim().lowercase() } ?: return@map mine
                val have = mine.tracks.map { it.track.source to it.track.sourceId }.toSet()
                mine.copy(tracks = mine.tracks + theirs.tracks.orEmpty().filter { (it.source to it.sourceId) !in have }
                    .map { PlaylistTrack(it.toTrack(), System.currentTimeMillis()) })
            }
            val created = p.playlists.orEmpty().filter { it.name.trim().lowercase() !in byName }.map { b ->
                Playlist(name = b.name, createdAt = ts(b.createdAt), description = b.description, accentColorKey = b.accentColorKey,
                    moodTags = b.moodTags.orEmpty(), tracks = b.tracks.orEmpty().map { PlaylistTrack(it.toTrack(), System.currentTimeMillis()) })
            }
            val fKnown = d.followed.map { it.source to it.sourceId }.toSet()
            val sKnown = d.savedPlaylists.map { it.source to it.sourceId }.toSet()
            d.copy(
                likes = newLikes + d.likes,
                playlists = merged + created,
                followed = d.followed + p.followedArtists.orEmpty().filter { (it.source to it.sourceId) !in fKnown }
                    .map { FollowedArtist(it.source, it.sourceId, it.name, it.thumbnailUrl, ts(it.followedAt)) },
                savedPlaylists = d.savedPlaylists + p.savedPlaylists.orEmpty().filter { (it.source to it.sourceId) !in sKnown }
                    .map { SavedPlaylist(it.source, it.sourceId, it.title, it.thumbnailUrl, it.owner, it.trackCount, it.webpageUrl, savedAt = ts(it.savedAt)) },
            )
        }
    }

    fun import(file: File) {
        val p = readJson<BackupPayload>(file, BackupPayload::class.java) ?: error("Keine gültige Grooveo-Sicherung")
        @Suppress("SENSELESS_COMPARISON")
        require(p.likes != null || p.playlists != null) { "Keine gültige Grooveo-Sicherung" }
        fun ts(iso: String?) = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
        store.mutate { d ->
            d.copy(
                likes = p.likes.orEmpty().mapIndexed { i, t -> LikedTrack(t.toTrack(), System.currentTimeMillis() - i) },
                playlists = p.playlists.orEmpty().map { b ->
                    Playlist(name = b.name, createdAt = ts(b.createdAt), description = b.description, accentColorKey = b.accentColorKey,
                        moodTags = b.moodTags.orEmpty(), tracks = b.tracks.orEmpty().map { PlaylistTrack(it.toTrack(), System.currentTimeMillis()) })
                },
                followed = p.followedArtists.orEmpty().map { FollowedArtist(it.source, it.sourceId, it.name, it.thumbnailUrl, ts(it.followedAt)) },
                savedPlaylists = p.savedPlaylists.orEmpty().map { SavedPlaylist(it.source, it.sourceId, it.title, it.thumbnailUrl, it.owner, it.trackCount, it.webpageUrl, savedAt = ts(it.savedAt)) },
            )
        }
        p.settings?.let { b ->
            settings.update {
                it.copy(
                    dataSaverMode = b.dataSaverMode, autoplayRadio = b.autoplayRadio, contentSafetyFilter = b.contentSafetyFilter,
                    sound3dPreset = b.sound3dPreset ?: it.sound3dPreset, playerStyle = b.playerStyle ?: it.playerStyle,
                    showMixControls = b.showMixControls, showFeatured = b.showFeatured, showNewUploads = b.showNewUploads,
                    profileName = b.profileName ?: it.profileName, profileColorStyle = b.profileColorStyle ?: it.profileColorStyle,
                    eq = b.eqProfile?.normalized() ?: it.eq,
                    eqUserPresets = b.eqUserPresets?.filterNotNull()?.map { p -> p.normalized() } ?: it.eqUserPresets,
                )
            }
        }
    }

    fun listBackups(): List<File> = dir.listFiles { f -> f.name.startsWith("backup_") && f.extension == "json" }?.sortedDescending().orEmpty()

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)
        private fun TrackResultDto.toDto() = BackupTrackDto(source, sourceId, title, artist, album, durationSec, thumbnailUrl, webpageUrl)
        private fun BackupTrackDto.toTrack() = TrackResultDto(source, sourceId, title, artist, album, durationSec, thumbnailUrl, webpageUrl ?: "")
    }
}
