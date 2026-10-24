package dev.schlubbe.musicagent.data.backup

/**
 * Merge used by the phone <-> PC sync: a union of both libraries, minus what either side
 * removed ([SyncTombstone]s, which travel along). An item added again after its removal
 * stays. Both devices end up with the same result ([merge] of the two payloads). Settings
 * stay as they are on [local].
 *
 * Playlists are matched by their sync id ([syncKey]); tracks are matched by source + sourceId.
 */
object SyncMerge {
    /** Removals older than this are forgotten; a device not synced for so long may bring items back. */
    const val TOMBSTONE_KEEP_MS = 180L * 24 * 3600_000

    fun merge(local: BackupPayload, remote: BackupPayload, now: Long = System.currentTimeMillis()): BackupPayload {
        val tombs = mergeTombstones(local.deleted.orEmpty() + remote.deleted.orEmpty(), now)
        val gone = Tombstones(tombs)
        // each side's copy is checked on its own: a copy added after the removal survives it
        fun BackupPayload.kept() = copy(
            likes = likes.orEmpty().filterNot { gone.removed(SyncTombstone.LIKE, "${it.source}:${it.sourceId}", it.likedAt) },
            playlists = playlists.orEmpty()
                .filterNot { gone.removed(SyncTombstone.PLAYLIST, syncKey(it), it.createdAt) }
                .map { p ->
                    val key = syncKey(p)
                    p.copy(tracks = p.tracks.orEmpty().filterNot { t ->
                        gone.removed(SyncTombstone.PLAYLIST_TRACK, SyncTombstone.playlistTrack(key, t.source, t.sourceId), t.addedAt)
                    })
                },
            followedArtists = followedArtists.orEmpty().filterNot { gone.removed(SyncTombstone.FOLLOW, "${it.source}:${it.sourceId}", it.followedAt) },
            savedPlaylists = savedPlaylists.orEmpty().filterNot { gone.removed(SyncTombstone.SAVED, "${it.source}:${it.sourceId}", it.savedAt) },
        )
        val l = local.kept()
        val r = remote.kept()
        return l.copy(
            likes = unionTracks(l.likes, r.likes, earliestLike = true),
            playlists = mergePlaylists(l.playlists, r.playlists),
            followedArtists = (l.followedArtists + r.followedArtists)
                .groupBy { it.source to it.sourceId }
                .map { (_, v) -> v.minBy { it.followedAt } },
            savedPlaylists = (l.savedPlaylists + r.savedPlaylists)
                .groupBy { it.source to it.sourceId }
                .map { (_, v) -> v.minBy { it.savedAt } },
            deleted = tombs,
        )
    }

    /** The newest removal per item, without the ones older than [TOMBSTONE_KEEP_MS]. */
    fun mergeTombstones(all: List<SyncTombstone>, now: Long = System.currentTimeMillis()): List<SyncTombstone> =
        all.filter { now - it.deletedAt < TOMBSTONE_KEEP_MS }
            .groupBy { it.kind to it.key }
            .map { (_, v) -> v.maxBy { it.deletedAt } }

    /** Looks up removals: [removed] is true when an item added at [addedAt] (ISO-8601, or
     * null when unknown) was removed afterwards. */
    class Tombstones(list: List<SyncTombstone>) {
        private val at = list.associate { (it.kind to it.key) to it.deletedAt }
        fun removed(kind: String, key: String, addedAt: String?): Boolean {
            val deletedAt = at[kind to key] ?: return false
            val added = addedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
            return deletedAt >= added
        }
        fun removed(kind: String, key: String, addedAtMs: Long): Boolean = (at[kind to key] ?: return false) >= addedAtMs
    }

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
        // in a playlist the newest add counts: a track added again after a removal stays
        if (!earliestLike) return (a + b).groupBy { it.source to it.sourceId }.map { (_, v) -> v.maxBy { it.addedAt.orEmpty() } }
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
