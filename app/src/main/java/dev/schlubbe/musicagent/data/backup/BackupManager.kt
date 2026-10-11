package dev.schlubbe.musicagent.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
import dev.schlubbe.musicagent.playback.eq.EqCodec
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

    /**
     * Sync id of a local playlist that came from (or was matched with) a playlist of another
     * device, by local playlist id. Playlists without an entry use their own id as sync id.
     */
    private val syncIds = context.getSharedPreferences("sync_playlist_ids", Context.MODE_PRIVATE)

    private fun syncIdOf(localId: String) = syncIds.getString(localId, null) ?: localId

    private val backupDir: File
        get() = File(context.filesDir, "backups").apply { if (!exists()) mkdirs() }

    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    /**
     * Folder the user picked for backups (Storage Access Framework tree). Files there stay
     * when the app is uninstalled, unlike [backupDir] inside the app's own data.
     */
    val folder: Uri? get() = prefs.getString(FOLDER, null)?.let(Uri::parse)

    /** A readable name of [folder] ("Download/Grooveo"), or null when none is set. */
    fun folderName(): String? = folder?.let { u ->
        runCatching { DocumentsContract.getTreeDocumentId(u).substringAfter(':').ifEmpty { "Hauptordner" } }.getOrNull()
    }

    /** Keeps write access to [uri] across restarts and makes it the backup folder; null forgets it. */
    fun setFolder(uri: Uri?) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        folder?.takeIf { it != uri }?.let { old -> runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) } }
        if (uri != null) context.contentResolver.takePersistableUriPermission(uri, flags)
        prefs.edit().putString(FOLDER, uri?.toString()).apply()
    }

    /**
     * Writes a fresh backup_<timestamp>.json inside the app and, when a [folder] is set, a copy
     * there (the newest [KEEP] of each are kept). Fails when the folder copy fails, so the user
     * learns that the backup outside the app is missing.
     */
    suspend fun createBackup(): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val json = gson.toJson(buildPayload())
            val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
            val file = File(backupDir, "backup_$timestamp.json")
            file.writeText(json)
            backupDir.listFiles { f -> f.isFile && f.name.startsWith("backup_") && f.extension == "json" }
                ?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
            folder?.let { tree ->
                try {
                    writeToFolder(tree, "grooveo-backup-$timestamp.json", json)
                } catch (e: Exception) {
                    throw IllegalStateException("Sicherung im Ordner fehlgeschlagen – Ordner neu wählen (${e.message})", e)
                }
            }
            file
        }
    }

    private fun writeToFolder(tree: Uri, name: String, json: String) {
        val r = context.contentResolver
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(r, dir, "application/json", name) ?: error("Datei nicht angelegt")
        r.openOutputStream(doc, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: error("Datei nicht beschreibbar")
        // only the app's own backups are tidied up, nothing else in the folder
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val ours = ArrayList<Pair<String, String>>()
        r.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(1) ?: continue
                if (n.startsWith("grooveo-backup-") && n.endsWith(".json")) ours += c.getString(0) to n
            }
        }
        // names carry the time, so sorting by name sorts by age
        ours.sortedByDescending { it.second }.drop(KEEP).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    /** Writes the current library and settings into [uri] (a file the user created with "Speichern unter"). */
    suspend fun exportTo(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val json = gson.toJson(buildPayload())
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ?: error("Datei nicht beschreibbar")
        }
    }

    /**
     * Restores from a backup file the user picked (from this app, an older one or the PC app).
     * [merge] adds what is missing and keeps everything here; otherwise the backup replaces
     * playlists, likes, follows and settings.
     */
    suspend fun restoreFrom(uri: Uri, merge: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > SyncProtocol.MAX_BODY) error("Datei ist zu groß für eine Grooveo-Sicherung")
                }
                out.toString(Charsets.UTF_8.name())
            } ?: error("Datei lässt sich nicht öffnen")
            val payload = parse(text)
            if (merge) applySynced(payload) else restorePayload(payload)
        }
    }

    private fun parse(text: String): BackupPayload = parseBackup(gson, text)

    /** The current library and settings in the backup format (also what the PC sync sends). */
    suspend fun buildPayload(): BackupPayload {
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
                    syncId = syncIdOf(detail.id),
                )
            }
            return BackupPayload(
                createdAt = Instant.now().toString(),
                likes = likesRepository.refresh().map { it.track.toBackupTrackDto().copy(likedAt = it.createdAt) },
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
                    eqPreset = settingsRepository.eqProfile.first().legacyPresetName(),
                    eqProfile = settingsRepository.eqProfile.first(),
                    eqUserPresets = settingsRepository.eqUserPresets.first(),
                    playerStyle = settingsRepository.playerStyle.first(),
                    autoplayRadio = settingsRepository.autoplayRadio.first(),
                    contentSafetyFilter = settingsRepository.contentSafetyFilter.first(),
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
    }

    /**
     * Applies a synced library ([dev.schlubbe.musicagent.data.backup.SyncMerge] result, a superset
     * of what is here): adds what is missing, never clears anything and leaves settings alone.
     */
    suspend fun applySynced(p: BackupPayload) = withContext(Dispatchers.IO) {
        val follows = followRepository.refresh().map { it.source to it.sourceId }.toSet()
        p.followedArtists.filter { (it.source to it.sourceId) !in follows }
            .forEach { followRepository.follow(it.source, it.sourceId, it.name, it.thumbnailUrl) }
        val saved = savedPlaylistRepository.refresh().map { it.source to it.sourceId }.toSet()
        p.savedPlaylists.filter { (it.source to it.sourceId) !in saved }
            .forEach { savedPlaylistRepository.save(it.source, it.sourceId, it.title, it.thumbnailUrl, it.owner, it.trackCount, it.webpageUrl) }
        val liked = likesRepository.refresh().map { it.track.source to it.track.sourceId }.toSet()
        p.likes.filter { (it.source to it.sourceId) !in liked }.reversed().forEach { likesRepository.like(it.toTrackResultDto(), it.validLikedAt()) }
        val mine = playlistRepository.list()
        val matches = SyncMerge.matchPlaylists(mine.map { SyncMerge.Local(syncIdOf(it.id), it.name) }, p.playlists)
        p.playlists.forEachIndexed { i, pl ->
            val key = SyncMerge.syncKey(pl)
            val id = matches[i]?.let { mine[it].id } ?: playlistRepository.create(pl.name).also {
                playlistRepository.updateDetails(it.id, pl.name, pl.description, pl.accentColorKey, pl.moodTags)
            }.id
            if (syncIdOf(id) != key) syncIds.edit().putString(id, key).apply()
            val have = playlistRepository.get(id).tracks.map { it.track.source to it.track.sourceId }.toSet()
            pl.tracks.filter { (it.source to it.sourceId) !in have }.forEach { playlistRepository.addTrack(id, it.toTrackResultDto()) }
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
        runCatching { restorePayload(parse(file.readText())) }
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
        payload.likes.forEach { likesRepository.like(it.toTrackResultDto(), it.validLikedAt()) }
        syncIds.edit().clear().apply()
        payload.playlists.forEach { p ->
            val created = playlistRepository.create(p.name)
            // keeps the playlist the same one for the PC sync after a restore
            syncIds.edit().putString(created.id, SyncMerge.syncKey(p)).apply()
            playlistRepository.updateDetails(created.id, p.name, p.description, p.accentColorKey, p.moodTags)
            p.tracks.forEach { t -> playlistRepository.addTrack(created.id, t.toTrackResultDto()) }
        }

        @Suppress("SENSELESS_COMPARISON")
        val s = payload.settings ?: return
        settingsRepository.setHiResAudio(s.hiResAudio)
        settingsRepository.setDataSaverMode(s.dataSaverMode)
        @Suppress("USELESS_ELVIS")
        settingsRepository.setEqProfile(s.eqProfile?.normalized() ?: EqCodec.fromLegacy(s.eqPreset ?: "FLAT", null))
        s.eqUserPresets?.let { list -> settingsRepository.setEqUserPresets(list.filterNotNull().map { it.normalized() }) }
        // a file from another app version may lack text settings: those keep their current value
        (s.playerStyle as String?)?.let { settingsRepository.setPlayerStyle(it) }
        settingsRepository.setAutoplayRadio(s.autoplayRadio)
        settingsRepository.setContentSafetyFilter(s.contentSafetyFilter)
        (s.sound3dPreset as String?)?.let { settingsRepository.setSound3dPreset(it) }
        settingsRepository.setDownloadsWifiOnly(s.downloadsWifiOnly)
        settingsRepository.setNotifyNewUploads(s.notifyNewUploads)
        settingsRepository.setShowMixControls(s.showMixControls)
        settingsRepository.setShowFeatured(s.showFeatured)
        settingsRepository.setShowNewUploads(s.showNewUploads)
        settingsRepository.setAutoBackup(s.autoBackup)
        (s.profileName as String?)?.let { settingsRepository.setProfileName(it) }
        (s.profileColorStyle as String?)?.let { settingsRepository.setProfileColorStyle(it) }
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

    private companion object {
        const val FOLDER = "folder"
        /** Backups kept inside the app and in the folder. */
        const val KEEP = 10
    }

    private fun TrackOutDto.toBackupTrackDto() =
        BackupTrackDto(source, sourceId, title, artist, album, durationSec, thumbnailUrl, webpageUrl)

    private fun BackupTrackDto.validLikedAt() = likedAt?.takeIf { runCatching { Instant.parse(it) }.isSuccess }

    private fun BackupTrackDto.toTrackResultDto() =
        TrackResultDto(source, sourceId, title, artist, album, durationSec, thumbnailUrl, webpageUrl, isDrmProtected = false)
}

/**
 * A backup file's content, with lists a file may lack filled in (Gson would leave them null);
 * throws with a German message when it is not a backup.
 */
internal fun parseBackup(gson: Gson, text: String): BackupPayload {
    val o = runCatching { com.google.gson.JsonParser.parseString(text.trimStart('\uFEFF')) }.getOrNull()
        ?.takeIf { it.isJsonObject }?.asJsonObject
        ?: error("Das ist keine Grooveo-Sicherung (kein gültiges JSON)")
    if (!o.has("createdAt") && !o.has("settings") && !o.has("likes") && !o.has("playlists")) error("Das ist keine Grooveo-Sicherung")
    fun com.google.gson.JsonObject.list(key: String) {
        if (get(key)?.isJsonArray != true) add(key, com.google.gson.JsonArray())
    }
    listOf("likes", "playlists", "followedArtists", "savedPlaylists").forEach(o::list)
    o.getAsJsonArray("playlists").forEach { pl -> if (pl.isJsonObject) pl.asJsonObject.apply { list("moodTags"); list("tracks") } }
    return runCatching { gson.fromJson(o, BackupPayload::class.java) }.getOrNull()
        ?: error("Sicherungsdatei ist beschädigt")
}
