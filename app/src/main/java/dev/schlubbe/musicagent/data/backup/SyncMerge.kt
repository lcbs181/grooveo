package dev.schlubbe.musicagent.data.backup

/**
 * Merge used by the phone <-> PC sync: a union of both libraries, so syncing never
 * loses anything on either side. Both devices end up with the same result
 * ([merge] of the two payloads). Nothing is ever deleted by a sync: an item removed
 * on one device comes back from the other. Settings stay as they are on [local].
 *
 * Playlists are matched by their sync id ([syncKey]); tracks are matched by source + sourceId.
 */
object SyncMerge {
    fun merge(local: BackupPayload, remote: BackupPayload): BackupPayload = local.copy(
        likes = unionTracks(local.likes.orEmpty(), remote.likes.orEmpty(), earliestLike = true),
        playlists = mergePlaylists(local.playlists.orEmpty(), remote.playlists.orEmpty()),
        followedArtists = (local.followedArtists.orEmpty() + remote.followedArtists.orEmpty())
            .groupBy { it.source to it.sourceId }
            .map { (_, v) -> v.minBy { it.followedAt } },
        savedPlaylists = (local.savedPlaylists.orEmpty() + remote.savedPlaylists.orEmpty())
            .groupBy { it.source to it.sourceId }
            .map { (_, v) -> v.minBy { it.savedAt } },
    )

    /** Identity of a playlist across devices: its sync id, or its id in payloads from before sync ids. */
    @Suppress("USELESS_ELVIS")
    fun syncKey(p: BackupPlaylistDto): String =
        p.syncId?.takeIf { it.isNotBlank() } ?: p.id?.takeIf { it.isNotBlank() } ?: ("name:" + normName(p.name ?: ""))

    fun normName(name: String) = name.trim().lowercase()

    /** A playlist that is already on this device: its sync key and its name. */
    data class Local(val key: String, val name: String)

    /**
     * For each of [incoming], the index in [local] of the playlist it is the same as, or null when
     * it is new here. Playlists match by sync key. Only when that finds nothing, a name is enough,
     * and only when exactly one playlist on each side has that name and the local one is not
     * already matched by its key, so two playlists with the same name (e.g. two "Neue Playlist")
     * are never folded together.
     */
    fun matchPlaylists(local: List<Local>, incoming: List<BackupPlaylistDto>): List<Int?> {
        val byKey = HashMap<String, Int>()
        local.forEachIndexed { i, l -> byKey.putIfAbsent(l.key, i) }
        val incomingKeys = incoming.map(::syncKey).toSet()
        val localNames = local.groupingBy { normName(it.name) }.eachCount()
        val incomingNames = incoming.groupingBy { normName(it.name) }.eachCount()
        return incoming.map { p ->
            byKey[syncKey(p)] ?: run {
                val n = normName(p.name)
                if (localNames[n] != 1 || incomingNames[n] != 1) return@run null
                local.indexOfFirst { normName(it.name) == n }.takeIf { local[it].key !in incomingKeys }
            }
        }
    }

    private fun unionTracks(a: List<BackupTrackDto>, b: List<BackupTrackDto>, earliestLike: Boolean = false): List<BackupTrackDto> {
        if (!earliestLike) return (a + b).distinctBy { it.source to it.sourceId }
        val dates = (a + b).filter { it.likedAt != null }.groupBy { it.source to it.sourceId }
            .mapValues { (_, v) -> v.minOf { it.likedAt!! } }
        return (a + b).distinctBy { it.source to it.sourceId }.map { t ->
            val d = dates[t.source to t.sourceId]
            if (d == null || d == t.likedAt) t else t.copy(likedAt = d)
        }
    }

    private fun mergePlaylists(a: List<BackupPlaylistDto>, b: List<BackupPlaylistDto>): List<BackupPlaylistDto> {
        val out = ArrayList<BackupPlaylistDto>()
        val index = HashMap<String, Int>()
        fun add(p: BackupPlaylistDto) {
            val key = syncKey(p)
            val at = index[key]
            if (at == null) {
                index[key] = out.size
                out += p.copy(syncId = key, tracks = p.tracks.orEmpty())
            } else out[at] = fold(out[at], p)
        }
        a.forEach(::add)
        val matches = matchPlaylists(out.map { Local(syncKey(it), it.name) }, b)
        b.forEachIndexed { i, p ->
            val at = matches[i]
            if (at != null) out[at] = fold(out[at], p) else add(p)
        }
        return out
    }

    /** [p] folded into [have]; [have] keeps its name and sync id. */
    private fun fold(have: BackupPlaylistDto, p: BackupPlaylistDto) = have.copy(
        description = have.description ?: p.description,
        accentColorKey = have.accentColorKey ?: p.accentColorKey,
        moodTags = (have.moodTags.orEmpty() + p.moodTags.orEmpty()).distinct(),
        createdAt = minOf(have.createdAt, p.createdAt),
        tracks = unionTracks(have.tracks.orEmpty(), p.tracks.orEmpty()),
    )
}
