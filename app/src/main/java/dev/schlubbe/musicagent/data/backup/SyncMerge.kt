package dev.schlubbe.musicagent.data.backup

/**
 * Merge used by the phone <-> PC sync: a union of both libraries, so syncing never
 * loses anything on either side. Both devices end up with the same result
 * ([merge] of the two payloads). Nothing is ever deleted by a sync: an item removed
 * on one device comes back from the other. Settings stay as they are on [local].
 *
 * Playlists are matched by name (their ids differ per device); tracks are matched
 * by source + sourceId.
 */
object SyncMerge {
    fun merge(local: BackupPayload, remote: BackupPayload): BackupPayload = local.copy(
        likes = unionTracks(local.likes.orEmpty(), remote.likes.orEmpty()),
        playlists = mergePlaylists(local.playlists.orEmpty(), remote.playlists.orEmpty()),
        followedArtists = (local.followedArtists.orEmpty() + remote.followedArtists.orEmpty())
            .groupBy { it.source to it.sourceId }
            .map { (_, v) -> v.minBy { it.followedAt } },
        savedPlaylists = (local.savedPlaylists.orEmpty() + remote.savedPlaylists.orEmpty())
            .groupBy { it.source to it.sourceId }
            .map { (_, v) -> v.minBy { it.savedAt } },
    )

    private fun unionTracks(a: List<BackupTrackDto>, b: List<BackupTrackDto>) =
        (a + b).distinctBy { it.source to it.sourceId }

    private fun mergePlaylists(a: List<BackupPlaylistDto>, b: List<BackupPlaylistDto>): List<BackupPlaylistDto> {
        val out = LinkedHashMap<String, BackupPlaylistDto>()
        for (p in a + b) {
            val key = p.name.trim().lowercase()
            val have = out[key]
            out[key] = if (have == null) p.copy(tracks = p.tracks.orEmpty()) else have.copy(
                description = have.description ?: p.description,
                accentColorKey = have.accentColorKey ?: p.accentColorKey,
                moodTags = (have.moodTags.orEmpty() + p.moodTags.orEmpty()).distinct(),
                createdAt = minOf(have.createdAt, p.createdAt),
                tracks = unionTracks(have.tracks.orEmpty(), p.tracks.orEmpty()),
            )
        }
        return out.values.toList()
    }
}
