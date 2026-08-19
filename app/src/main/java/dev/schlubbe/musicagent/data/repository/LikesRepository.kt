package dev.schlubbe.musicagent.data.repository

import dev.schlubbe.musicagent.data.local.dao.LikeDao
import dev.schlubbe.musicagent.data.local.entity.LikeEntity
import dev.schlubbe.musicagent.data.local.mapper.nowIso
import dev.schlubbe.musicagent.data.local.mapper.toLikeOutDto
import dev.schlubbe.musicagent.data.local.mapper.toLocalTrackEntity
import dev.schlubbe.musicagent.data.remote.dto.LikeOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Same public contract as the server-backed app's LikesRepository — backed by
 * Room instead of the (removed) backend, so every caller (LibraryViewModel,
 * PlaylistDetailViewModel, HomeViewModel, PlayerViewModel, ...) needs no changes. */
@Singleton
class LikesRepository @Inject constructor(
    private val likeDao: LikeDao,
) {
    private val _likedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val likedTrackIds: StateFlow<Set<String>> = _likedTrackIds.asStateFlow()

    suspend fun refresh(): List<LikeOutDto> {
        val likes = likeDao.getAll().map { it.toLikeOutDto() }
        _likedTrackIds.value = likes.map { it.track.id }.toSet()
        return likes
    }

    suspend fun like(track: TrackResultDto) {
        val trackId = "${track.source}:${track.sourceId}"
        likeDao.insert(LikeEntity(trackId, track.toLocalTrackEntity(), nowIso()))
        _likedTrackIds.value = _likedTrackIds.value + trackId
    }

    suspend fun unlike(track: TrackResultDto) {
        val trackId = "${track.source}:${track.sourceId}"
        likeDao.deleteByTrackId(trackId)
        _likedTrackIds.value = _likedTrackIds.value - trackId
    }

    suspend fun toggle(track: TrackResultDto) {
        val id = "${track.source}:${track.sourceId}"
        if (id in _likedTrackIds.value) unlike(track) else like(track)
    }
}
