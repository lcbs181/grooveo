package dev.schlubbe.musicagent.data.tags

import android.util.Log
import dev.schlubbe.musicagent.data.extract.soundcloud.SoundCloudApi
import dev.schlubbe.musicagent.data.extract.soundcloud.toSoundCloudTrackResultDto
import dev.schlubbe.musicagent.data.local.dao.TrackTagsDao
import dev.schlubbe.musicagent.data.local.entity.TrackTagsEntity
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TagRepository"

/**
 * Tags per track ("source:sourceId"), for the tag chips over likes and downloads. They come
 * with SoundCloud tracks ([TrackResultDto.tags]) and are kept when a track is liked or
 * downloaded; likes from before (or from a backup) get theirs fetched once in the background
 * ([fetchMissing]). YouTube Music has no tags.
 */
@Singleton
class TagRepository @Inject constructor(
    private val dao: TrackTagsDao,
    private val soundCloud: SoundCloudApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetching = Mutex()

    /** Tags by track id; tracks without an entry are not looked up yet. */
    val tagsByTrack: StateFlow<Map<String, List<String>>> = dao.observeAll()
        .map { rows -> rows.associate { it.trackId to TrackTags.split(it.tags) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Keeps the tags [track] came with (nothing when its source sent none). */
    suspend fun remember(track: TrackResultDto) {
        val tags = track.tags ?: return
        save("${track.source}:${track.sourceId}", tags)
    }

    suspend fun save(trackId: String, tags: List<String>) {
        runCatching { dao.upsert(TrackTagsEntity(trackId, TrackTags.join(tags), System.currentTimeMillis())) }
    }

    /**
     * Looks up the tags of the SoundCloud tracks among [tracks] (pairs of source and sourceId)
     * that have none stored yet, a few at a time, in the background. A track that cannot be
     * found is stored without tags, so it is not asked for again.
     */
    fun fetchMissing(tracks: List<Pair<String, String>>) {
        scope.launch {
            fetching.withLock {
                val known = dao.knownIds().toHashSet()
                val todo = tracks.filter { (source, id) -> source == "soundcloud" && "$source:$id" !in known }.distinct()
                if (todo.isEmpty()) return@withLock
                val permits = Semaphore(3)
                todo.map { (source, id) ->
                    async {
                        permits.withPermit {
                            runCatching { fetch(id) }
                                .onSuccess { tags -> save("$source:$id", tags) }
                                .onFailure { e ->
                                    Log.w(TAG, "tags for $id: ${e.message}")
                                    // gone or private: no tags; a network error is tried again next time
                                    if (e.message?.contains("error 404") == true) save("$source:$id", emptyList())
                                }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /** A SoundCloud track's tags by its permalink path, fetched now. */
    suspend fun fetch(sourceId: String): List<String> {
        val json = soundCloud.get("resolve", mapOf("url" to "https://soundcloud.com/$sourceId"))
        return json.toSoundCloudTrackResultDto()?.tags.orEmpty()
    }
}
