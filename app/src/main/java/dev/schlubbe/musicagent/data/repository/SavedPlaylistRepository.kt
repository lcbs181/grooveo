package dev.schlubbe.musicagent.data.repository

import dev.schlubbe.musicagent.data.local.dao.SavedPlaylistDao
import dev.schlubbe.musicagent.data.local.entity.SavedPlaylistEntity
import dev.schlubbe.musicagent.data.local.mapper.nowIso
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** On-device "liked" public playlists/albums (from Search) - same local-only
 * pattern as [FollowRepository], which this mirrors field-for-field. Backs the
 * save/unsave toggle on the remote playlist browse screen and the "Gespeichert"
 * badge merged into Library's Playlists tab alongside locally-created playlists. */
@Singleton
class SavedPlaylistRepository @Inject constructor(
    private val savedPlaylistDao: SavedPlaylistDao,
) {
    private val _savedPlaylists = MutableStateFlow<List<SavedPlaylistEntity>>(emptyList())
    val savedPlaylists: StateFlow<List<SavedPlaylistEntity>> = _savedPlaylists.asStateFlow()

    private val _savedIds = MutableStateFlow<Set<String>>(emptySet())
    val savedIds: StateFlow<Set<String>> = _savedIds.asStateFlow()

    suspend fun refresh(): List<SavedPlaylistEntity> {
        val all = savedPlaylistDao.getAll()
        _savedPlaylists.value = all
        _savedIds.value = all.map { "${it.source}:${it.sourceId}" }.toSet()
        return all
    }

    suspend fun save(
        source: String,
        sourceId: String,
        title: String,
        thumbnailUrl: String?,
        owner: String?,
        trackCount: Int?,
        webpageUrl: String,
    ) {
        savedPlaylistDao.insert(
            SavedPlaylistEntity(source, sourceId, title, thumbnailUrl, owner, trackCount, webpageUrl, nowIso()),
        )
        refresh()
    }

    suspend fun unsave(source: String, sourceId: String) {
        savedPlaylistDao.delete(source, sourceId)
        refresh()
    }

    suspend fun toggle(
        source: String,
        sourceId: String,
        title: String,
        thumbnailUrl: String?,
        owner: String?,
        trackCount: Int?,
        webpageUrl: String,
    ) {
        if ("$source:$sourceId" in _savedIds.value) {
            unsave(source, sourceId)
        } else {
            save(source, sourceId, title, thumbnailUrl, owner, trackCount, webpageUrl)
        }
    }
}
