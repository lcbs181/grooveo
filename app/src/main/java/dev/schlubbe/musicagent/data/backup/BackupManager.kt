package dev.schlubbe.musicagent.data.backup

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.schlubbe.musicagent.data.remote.dto.TrackOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.remote.dto.toTrackResultDto
import dev.schlubbe.musicagent.data.repository.FollowRepository
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.data.repository.PlaylistRepository
import dev.schlubbe.musicagent.data.repository.SavedPlaylistRepository
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import dev.schlubbe.musicagent.playback.EqPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/** Real local backup/restore for the standalone app's on-device library data --
 * replaces the previous pure-UI `delay(1400)` simulation in SettingsViewModel.
 * Serializes local playlists + their tracks, likes, followed artists, saved
 * (remote) playlists, and a subset of DataStore settings into one JSON file
 * under `context.filesDir/backups/`, and can restore from the most recent one
 * by round-tripping through the *existing* repositories' own insert/upsert
 * methods -- this never touches Room/AppDatabase directly, so it can't drift
 * from whatever schema those repositories already enforce. */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistRepository: PlaylistRepository,
    private val likesRepository: LikesRepository,
    private val followRepository: FollowRepository,
    private val savedPlaylistRepository: SavedPlaylistRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val gson = Gson()

    private val backupDir: File
        get() = File(context.filesDir, "backups").apply { if (!exists()) mkdirs() }

    /** Writes a fresh backup_<timestamp>.json and returns it. */
    suspend fun createBackup(): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val playlists = playlistRepository.list().map { summary ->
                val detail = playlistRepository.get(summary.id)
                BackupPlaylistDto(
                    id = detail.id,
                    name = detail.name,
                    createdAt = detail.createdAt,
                    description = detail.description,
                    accentColorKey = detail.accentColorKey,
                    moodTags = detail.moodTags,
                    tracks = detail.tracks.map { it.track.toBackupTrackDto() },
                )
            }
            val payload = BackupPayload(
                createdAt = Instant.now().toString(),
                likes = likesRepository.refresh().map { it.track.toBackupTrackDto() },
                playlists = playlists,
                followedArtists = followRepository.refresh().map {
                    BackupFollowedArtistDto(it.source, it.sourceId, it.name, it.thumbnailUrl, it.followedAt)
                },
                savedPlaylists = savedPlaylistRepository.refresh().map {
                    BackupSavedPlaylistDto(it.source, it.sourceId, it.title, it.thumbnailUrl, it.owner, it.trackCount, it.webpageUrl, it.savedAt)
                },
                settings = BackupSettingsDto(
                    hiResAudio = settingsRepository.hiResAudio.first(),
                    dataSaverMode = settingsRepository.dataSaverMode.first(),
                    eqPreset = settingsRepository.eqPreset.first().name,
                    playerStyle = settingsRepository.playerStyle.first(),
                    autoplayRadio = settingsRepository.autoplayRadio.first(),
                    sound3dPreset = settingsRepository.sound3dPreset.first(),
                    downloadsWifiOnly = settingsRepository.downloadsWifiOnly.first(),
                    notifyNewUploads = settingsRepository.notifyNewUploads.first(),
                    showMixControls = settingsRepository.showMixControls.first(),
                    showFeatured = settingsRepository.showFeatured.first(),
                    showNewUploads = settingsRepository.showNewUploads.first(),
                    autoBackup = settingsRepository.autoBackup.first(),
                    profileName = settingsRepository.profileName.first(),
                    profileColorStyle = settingsRepository.profileColorStyle.first(),
                ),
            )
            val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
            val file = File(backupDir, "backup_$timestamp.json")
            file.writeText(gson.toJson(payload))
            file
        }
    }

    /** Most recently written local backup file, if any. */
    fun latestBackupFile(): File? =
        backupDir.listFiles { f -> f.isFile && f.name.startsWith("backup_") && f.extension == "json" }
            ?.maxByOrNull { it.lastModified() }

    /** Reads+applies the most recent local backup file via the existing
     * repositories' own methods (never writes Room directly). Existing local
     * playlists/likes/follows/saved-playlists are cleared first so the result
     * matches the backup exactly, matching the restore dialog's own copy
     * ("...werden durch den Stand der letzten Sicherung ersetzt"). */
    suspend fun restoreLatest(): Result<Unit> {
        val file = latestBackupFile()
            ?: return Result.failure(IllegalStateException("Keine lokale Sicherung gefunden"))
        return restoreFromFile(file)
    }

    suspend fun restoreFromFile(file: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = runCatching { gson.fromJson(file.readText(), BackupPayload::class.java) }.getOrNull()
                ?: error("Sicherungsdatei ist beschädigt oder ungültig")
            restorePayload(payload)
        }
    }

    private suspend fun restorePayload(payload: BackupPayload) {
        // Clear existing local state via the repositories' own delete/unlike/
        // unfollow/unsave methods -- no direct DAO/Room access.
        playlistRepository.list().forEach { playlistRepository.delete(it.id) }
        likesRepository.refresh().forEach { likesRepository.unlike(it.track.toTrackResultDto()) }
        followRepository.refresh().forEach { followRepository.unfollow(it.source, it.sourceId) }
        savedPlaylistRepository.refresh().forEach { savedPlaylistRepository.unsave(it.source, it.sourceId) }

        payload.followedArtists.forEach {
            followRepository.follow(it.source, it.sourceId, it.name, it.thumbnailUrl)
        }
        payload.savedPlaylists.forEach {
            savedPlaylistRepository.save(it.source, it.sourceId, it.title, it.thumbnailUrl, it.owner, it.trackCount, it.webpageUrl)
        }
        payload.likes.forEach { likesRepository.like(it.toTrackResultDto()) }
        payload.playlists.forEach { p ->
            val created = playlistRepository.create(p.name)
            playlistRepository.updateDetails(created.id, p.name, p.description, p.accentColorKey, p.moodTags)
            p.tracks.forEach { t -> playlistRepository.addTrack(created.id, t.toTrackResultDto()) }
        }

        val s = payload.settings
        settingsRepository.setHiResAudio(s.hiResAudio)
        settingsRepository.setDataSaverMode(s.dataSaverMode)
        runCatching { EqPreset.valueOf(s.eqPreset) }.getOrNull()?.let {
            settingsRepository.setEqPreset(it)
        }
        settingsRepository.setPlayerStyle(s.playerStyle)
        settingsRepository.setAutoplayRadio(s.autoplayRadio)
        settingsRepository.setSound3dPreset(s.sound3dPreset)
        settingsRepository.setDownloadsWifiOnly(s.downloadsWifiOnly)
        settingsRepository.setNotifyNewUploads(s.notifyNewUploads)
        settingsRepository.setShowMixControls(s.showMixControls)
        settingsRepository.setShowFeatured(s.showFeatured)
        settingsRepository.setShowNewUploads(s.showNewUploads)
        settingsRepository.setAutoBackup(s.autoBackup)
        settingsRepository.setProfileName(s.profileName)
        settingsRepository.setProfileColorStyle(s.profileColorStyle)
    }

    /** Fires the system share sheet for the most recent backup file via a
     * FileProvider content:// URI, same pattern as UpdateRepository.installApk's
     * FileProvider usage. Returns false (no-op) if no local backup exists yet. */
    fun shareLatestBackup(): Boolean {
        val file = latestBackupFile() ?: return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Sicherung teilen").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return true
    }

    private fun TrackOutDto.toBackupTrackDto() =
        BackupTrackDto(source, sourceId, title, artist, album, durationSec, thumbnailUrl, webpageUrl)

    private fun BackupTrackDto.toTrackResultDto() =
        TrackResultDto(source, sourceId, title, artist, album, durationSec, thumbnailUrl, webpageUrl, isDrmProtected = false)
}
