package dev.schlubbe.musicagent.data.repository

import dev.schlubbe.musicagent.data.remote.dto.LikeOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackOutDto
import dev.schlubbe.musicagent.desktop.data.LibraryStore
import dev.schlubbe.musicagent.desktop.data.key
import java.time.Instant

/** Desktop counterpart of the Android LikesRepository (API used by the shared FeedRepository). */
class LikesRepository(private val store: LibraryStore) {
    suspend fun refresh(): List<LikeOutDto> = store.current.likes.map { l ->
        val t = l.track
        LikeOutDto(
            track = TrackOutDto(t.key, t.source, t.sourceId, t.title, t.artist, t.album, t.durationSec, t.thumbnailUrl, t.webpageUrl),
            createdAt = Instant.ofEpochMilli(l.createdAt).toString(),
        )
    }
}
