package dev.schlubbe.musicagent.data.repository

import dev.schlubbe.musicagent.data.local.dao.FollowedArtistDao
import dev.schlubbe.musicagent.data.local.entity.FollowedArtistEntity
import dev.schlubbe.musicagent.data.local.mapper.nowIso
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** On-device artist follows — same local-only pattern as LikesRepository/
 * PlaylistRepository, since there's no server-side follow graph in this
 * backend-less app. Backs the Artist page's follow/unfollow button and Home's
 * "Neu von Künstlern" shelf. */
@Singleton
class FollowRepository @Inject constructor(
    private val followedArtistDao: FollowedArtistDao,
) {
    private val _followedArtists = MutableStateFlow<List<FollowedArtistEntity>>(emptyList())
    val followedArtists: StateFlow<List<FollowedArtistEntity>> = _followedArtists.asStateFlow()

    private val _followedIds = MutableStateFlow<Set<String>>(emptySet())
    val followedIds: StateFlow<Set<String>> = _followedIds.asStateFlow()

    suspend fun refresh(): List<FollowedArtistEntity> {
        val all = followedArtistDao.getAll()
        _followedArtists.value = all
        _followedIds.value = all.map { "${it.source}:${it.sourceId}" }.toSet()
        return all
    }

    suspend fun follow(source: String, sourceId: String, name: String, thumbnailUrl: String?) {
        followedArtistDao.insert(FollowedArtistEntity(source, sourceId, name, thumbnailUrl, nowIso()))
        refresh()
    }

    suspend fun unfollow(source: String, sourceId: String) {
        followedArtistDao.delete(source, sourceId)
        refresh()
    }

    suspend fun toggle(source: String, sourceId: String, name: String, thumbnailUrl: String?) {
        if ("$source:$sourceId" in _followedIds.value) unfollow(source, sourceId) else follow(source, sourceId, name, thumbnailUrl)
    }
}
