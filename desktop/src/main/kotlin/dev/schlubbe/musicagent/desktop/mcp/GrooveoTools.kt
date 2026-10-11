package dev.schlubbe.musicagent.desktop.mcp

import com.google.gson.JsonParser
import dev.schlubbe.musicagent.data.remote.dto.AlbumResultDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.desktop.AppGraph
import dev.schlubbe.musicagent.desktop.audio.BeatGrid
import dev.schlubbe.musicagent.desktop.audio.BeatTracker
import dev.schlubbe.musicagent.desktop.audio.Sound3dPreset
import dev.schlubbe.musicagent.desktop.data.FollowedArtist
import dev.schlubbe.musicagent.desktop.data.SavedPlaylist
import dev.schlubbe.musicagent.desktop.data.Settings
import dev.schlubbe.musicagent.desktop.data.TrackAnalysis
import dev.schlubbe.musicagent.desktop.data.gson
import dev.schlubbe.musicagent.desktop.data.key
import dev.schlubbe.musicagent.desktop.playback.RepeatMode
import dev.schlubbe.musicagent.playback.eq.EqProfile
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor

/**
 * The MCP tools: everything the user can do in the app, as calls an AI can make. Tracks are
 * addressed by id ("soundcloud:123", "ytmusic:abc") as returned by search, library and queue tools.
 * Results are JSON text. Changes go through the same code paths as the UI (PlayerController,
 * LibraryStore, SettingsRepository), so the window shows them at once.
 */
class GrooveoTools(
    private val g: AppGraph,
    private val readOnly: Boolean = false,
    private val log: (McpActivity) -> Unit = {},
) {
    /** Every track the AI has seen, so later calls can refer to it by id. */
    private val known = ConcurrentHashMap<String, TrackResultDto>()
    private val knownPlaylists = ConcurrentHashMap<String, SavedPlaylist>()

    fun register(server: Server) {
        registerPlayback(server)
        registerQueue(server)
        registerDiscovery(server)
        registerLibrary(server)
        registerSound(server)
        registerSettings(server)
        registerAnalysis(server)
        registerResources(server)
        registerPrompts(server)
    }

    // ---------------------------------------------------------------- playback

    private fun registerPlayback(s: Server) {
        tool(s, "get_now_playing", "Current track, position, duration, play state, volume, shuffle, repeat, sleep timer and queue length.") { nowPlaying() }
        tool(
            s, "play_tracks", "Replace the queue with these tracks and start playing.",
            props = mapOf("track_ids" to ids("Tracks to play, in order"), "start_index" to int("Index to start at (default 0)"), "shuffle" to bool("Shuffle the queue")),
            required = listOf("track_ids"), mutates = true,
        ) { a ->
            val tracks = tracks(a.strings("track_ids"))
            ui { g.player.playQueue(tracks, a.int("start_index") ?: 0, a.bool("shuffle") ?: false) }
            "Playing ${tracks.size} tracks"
        }
        tool(
            s, "playback", "Play, pause, toggle, stop, next or previous.",
            props = mapOf("action" to enum(listOf("play", "pause", "toggle", "stop", "next", "previous"), "What to do")),
            required = listOf("action"), mutates = true,
        ) { a ->
            val playing = g.player.engineState.value.playing
            ui {
                when (a.string("action")) {
                    "play" -> if (!playing) g.player.togglePlay()
                    "pause" -> if (playing) g.player.togglePlay()
                    "toggle" -> g.player.togglePlay()
                    "stop" -> g.player.stop()
                    "next" -> g.player.next()
                    "previous" -> g.player.previous()
                    else -> error("Unknown action")
                }
            }
            nowPlaying()
        }
        tool(s, "seek", "Jump to a position in the current track.", props = mapOf("seconds" to num("Position in seconds")), required = listOf("seconds"), mutates = true) { a ->
            ui { g.player.seek(a.double("seconds")!!.coerceAtLeast(0.0)) }
            "Seeked"
        }
        tool(s, "set_volume", "Set the volume, 0.0 to 1.0 (perceptual curve).", props = mapOf("volume" to num("0.0 .. 1.0")), required = listOf("volume"), mutates = true) { a ->
            ui { g.player.setVolume(a.double("volume")!!.toFloat()) }
            "Volume ${g.settings.current.volume}"
        }
        tool(s, "set_shuffle", "Turn shuffle on or off.", props = mapOf("enabled" to bool("")), required = listOf("enabled"), mutates = true) { a ->
            ui { if (g.player.state.value.shuffle != a.bool("enabled")) g.player.toggleShuffle() }
            "Shuffle ${g.player.state.value.shuffle}"
        }
        tool(s, "set_repeat", "Repeat mode.", props = mapOf("mode" to enum(listOf("off", "all", "one"), "")), required = listOf("mode"), mutates = true) { a ->
            val want = RepeatMode.valueOf(a.string("mode")!!.uppercase())
            ui { repeat(RepeatMode.entries.size) { if (g.player.state.value.repeat != want) g.player.cycleRepeat() } }
            "Repeat ${g.player.state.value.repeat.name.lowercase()}"
        }
        tool(s, "set_sleep_timer", "Pause playback after some minutes; omit minutes to cancel the timer.", props = mapOf("minutes" to int("Minutes until pause")), mutates = true) { a ->
            ui { g.player.setSleepTimer(a.int("minutes")?.coerceIn(1, 24 * 60)) }
            if (a.int("minutes") == null) "Sleep timer off" else "Sleep timer ${a.int("minutes")} min"
        }
    }

    private fun nowPlaying(): Map<String, Any?> {
        val q = g.player.state.value
        val e = g.player.engineState.value
        return mapOf(
            "track" to q.current?.let(::view),
            "playing" to e.playing,
            "buffering" to (e.buffering || q.resolving),
            "position_sec" to e.positionSec,
            "duration_sec" to e.durationSec,
            "volume" to g.settings.current.volume,
            "shuffle" to q.shuffle,
            "repeat" to q.repeat.name.lowercase(),
            "sleep_timer_ends_at" to q.sleepTimerEndAt?.let { java.time.Instant.ofEpochMilli(it).toString() },
            "beat" to q.current?.let { t -> g.store.current.analysis[t.key]?.grid?.let { beatPosition(it, e.positionSec * 1000) } },
            "queue_index" to q.index,
            "queue_length" to q.queue.size,
            "error" to q.error,
        )
    }

    // ---------------------------------------------------------------- queue

    private fun registerQueue(s: Server) {
        tool(s, "get_queue", "The whole queue; 'index' marks the current track.") {
            val q = g.player.state.value
            mapOf("index" to q.index, "tracks" to q.queue.map(::view))
        }
        tool(
            s, "add_to_queue", "Add tracks to the queue, at the end or right after the current track.",
            props = mapOf("track_ids" to ids(""), "position" to enum(listOf("end", "next"), "Default end")),
            required = listOf("track_ids"), mutates = true,
        ) { a ->
            val tracks = tracks(a.strings("track_ids"))
            ui {
                if (a.string("position") == "next") tracks.asReversed().forEach(g.player::playNext)
                else g.player.addToQueue(tracks)
            }
            "Added ${tracks.size}"
        }
        tool(s, "remove_from_queue", "Remove the track at a queue index.", props = mapOf("index" to int("")), required = listOf("index"), mutates = true) { a ->
            ui { g.player.removeFromQueue(a.int("index")!!) }
            "Removed"
        }
        tool(s, "move_in_queue", "Move a queue entry.", props = mapOf("from" to int(""), "to" to int("")), required = listOf("from", "to"), mutates = true) { a ->
            val n = g.player.state.value.queue.size
            val from = a.int("from")!!; val to = a.int("to")!!
            require(from in 0 until n && to in 0 until n) { "Index out of range (queue has $n tracks)" }
            ui { g.player.moveInQueue(from, to) }
            "Moved"
        }
        tool(s, "skip_to", "Play the queue entry at this index.", props = mapOf("index" to int("")), required = listOf("index"), mutates = true) { a ->
            ui { g.player.skipTo(a.int("index")!!) }
            nowPlaying()
        }
        tool(s, "clear_up_next", "Remove everything after the current track from the queue.", mutates = true, destructive = true) {
            ui { g.player.clearUpNext() }
            "Cleared"
        }
    }

    // ---------------------------------------------------------------- discovery

    private fun registerDiscovery(s: Server) {
        tool(
            s, "search", "Search SoundCloud and YouTube Music.",
            props = mapOf(
                "query" to str(""),
                "type" to enum(listOf("tracks", "artists", "playlists", "albums"), "Default tracks"),
                "source" to enum(listOf("all", "soundcloud", "ytmusic"), "Default all"),
                "limit" to int("1..50, default 20"),
            ),
            required = listOf("query"), openWorld = true,
        ) { a ->
            val q = a.string("query")!!
            val src = a.string("source") ?: "all"
            val limit = (a.int("limit") ?: 20).coerceIn(1, 50)
            when (a.string("type") ?: "tracks") {
                "artists" -> g.search.searchArtists(q, src, limit).map { mapOf("source" to it.source, "source_id" to it.sourceId, "name" to it.name, "subscribers" to it.subscriberCount) }
                "playlists" -> g.search.searchPlaylists(q, src, limit).map(::rememberPlaylist)
                "albums" -> g.search.searchAlbums(q, src, limit).map(::rememberAlbum)
                else -> g.search.search(q, src, limit).map(::view)
            }
        }
        tool(
            s, "get_remote_playlist", "Tracks of a SoundCloud/YouTube playlist or album found by search.",
            props = mapOf("source" to str(""), "source_id" to str("")), required = listOf("source", "source_id"), openWorld = true,
        ) { a ->
            val d = g.search.getPlaylistDetail(a.string("source")!!, a.string("source_id")!!)
            mapOf("title" to d.title, "owner" to d.owner, "description" to d.description, "tags" to d.tags, "tracks" to d.tracks.map(::view))
        }
        tool(
            s, "get_artist", "Artist details with top and latest tracks.",
            props = mapOf("source" to str(""), "source_id" to str("")), required = listOf("source", "source_id"), openWorld = true,
        ) { a ->
            val d = g.search.getArtist(a.string("source")!!, a.string("source_id")!!)
            mapOf("name" to d.name, "description" to d.description, "subscribers" to d.subscriberCount, "top_tracks" to d.topTracks.map(::view), "latest_tracks" to d.latestTracks.map(::view))
        }
        tool(
            s, "get_trending", "Trending tracks, optionally for a genre.",
            props = mapOf("genre" to str("e.g. techno, hip hop"), "source" to enum(listOf("all", "soundcloud", "ytmusic"), ""), "limit" to int("")), openWorld = true,
        ) { a ->
            val limit = (a.int("limit") ?: 20).coerceIn(1, 50)
            (a.string("genre")?.let { g.search.getTrendingByGenre(it, limit) } ?: g.search.getTrending(limit, a.string("source") ?: "all")).map(::view)
        }
        tool(s, "get_recommendations", "Tracks Grooveo would play next for the listener, from history and likes.", props = mapOf("limit" to int("")), openWorld = true) { a ->
            val recent = g.store.current.history.take(20).map { it.track }
            g.feed.predictNext(recent, recent.map { it.key }.toSet(), (a.int("limit") ?: 15).coerceIn(1, 50)).map(::view)
        }
        tool(s, "get_lyrics", "Lyrics of a track (LRCLIB).", props = mapOf("track_id" to str("")), required = listOf("track_id"), openWorld = true) { a ->
            val t = track(a.string("track_id")!!)
            val l = g.lyrics.lyricsFor(t.title, t.artist, t.durationSec) ?: return@tool "No lyrics found"
            l.plain ?: l.synced?.joinToString("\n") { "[${it.timeMs / 1000}s] ${it.text}" } ?: "No lyrics found"
        }
    }

    // ---------------------------------------------------------------- library

    private fun registerLibrary(s: Server) {
        tool(
            s, "get_library", "The listener's library.",
            props = mapOf("section" to enum(listOf("likes", "playlists", "saved_playlists", "followed_artists", "history", "downloads", "disliked_artists"), "")),
            required = listOf("section"),
        ) { a ->
            val d = g.store.current
            when (a.string("section")) {
                "likes" -> d.likes.map { view(it.track) }
                "playlists" -> d.playlists.map { mapOf("id" to it.id, "name" to it.name, "description" to it.description, "mood_tags" to it.moodTags, "track_count" to it.tracks.size) }
                "saved_playlists" -> d.savedPlaylists.map { mapOf("source" to it.source, "source_id" to it.sourceId, "title" to it.title, "owner" to it.owner, "is_album" to it.isAlbum) }
                "followed_artists" -> d.followed.map { mapOf("source" to it.source, "source_id" to it.sourceId, "name" to it.name) }
                "history" -> d.history.take(100).map { view(it.track) + ("played_at" to java.time.Instant.ofEpochMilli(it.playedAt).toString()) }
                "downloads" -> d.downloads.map { view(it.track) + mapOf("state" to it.state.name.lowercase(), "progress_pct" to it.progressPct) }
                "disliked_artists" -> d.dislikedArtists
                else -> error("Unknown section")
            }
        }
        tool(s, "get_playlist", "A local playlist with its tracks.", props = mapOf("playlist_id" to str("")), required = listOf("playlist_id")) { a ->
            val p = playlist(a.string("playlist_id")!!)
            mapOf("id" to p.id, "name" to p.name, "description" to p.description, "mood_tags" to p.moodTags, "tracks" to p.tracks.map { view(it.track) })
        }
        tool(
            s, "create_playlist", "Create a local playlist, optionally with tracks.",
            props = mapOf("name" to str(""), "description" to str(""), "mood_tags" to strings("Short tags like 'chill', 'gym'"), "track_ids" to ids("")),
            required = listOf("name"), mutates = true,
        ) { a ->
            val tracks = tracks(a.strings("track_ids"))
            val p = ui { g.store.createPlaylist(a.string("name")!!, tracks) }
            ui { g.store.updatePlaylist(p.id) { it.copy(description = a.string("description") ?: it.description, moodTags = a.strings("mood_tags").ifEmpty { it.moodTags }) } }
            mapOf("playlist_id" to p.id, "track_count" to tracks.size)
        }
        tool(
            s, "update_playlist", "Rename a playlist or change its description and mood tags.",
            props = mapOf("playlist_id" to str(""), "name" to str(""), "description" to str(""), "mood_tags" to strings("")),
            required = listOf("playlist_id"), mutates = true,
        ) { a ->
            playlist(a.string("playlist_id")!!)
            ui {
                g.store.updatePlaylist(a.string("playlist_id")!!) {
                    it.copy(
                        name = a.string("name")?.takeIf(String::isNotBlank) ?: it.name,
                        description = a.string("description") ?: it.description,
                        moodTags = if (a.has("mood_tags")) a.strings("mood_tags") else it.moodTags,
                    )
                }
            }
            "Updated"
        }
        tool(s, "add_to_playlist", "Append tracks to a playlist.", props = mapOf("playlist_id" to str(""), "track_ids" to ids("")), required = listOf("playlist_id", "track_ids"), mutates = true) { a ->
            playlist(a.string("playlist_id")!!)
            val tracks = tracks(a.strings("track_ids"))
            ui { g.store.addToPlaylist(a.string("playlist_id")!!, tracks) }
            "Added ${tracks.size}"
        }
        tool(s, "remove_from_playlist", "Remove the track at an index from a playlist.", props = mapOf("playlist_id" to str(""), "index" to int("")), required = listOf("playlist_id", "index"), mutates = true) { a ->
            val p = playlist(a.string("playlist_id")!!)
            val i = a.int("index")!!
            require(i in p.tracks.indices) { "Index out of range (playlist has ${p.tracks.size} tracks)" }
            ui { g.store.removeFromPlaylist(p.id, i) }
            "Removed"
        }
        tool(s, "move_playlist_track", "Move a track inside a playlist.", props = mapOf("playlist_id" to str(""), "from" to int(""), "to" to int("")), required = listOf("playlist_id", "from", "to"), mutates = true) { a ->
            val p = playlist(a.string("playlist_id")!!)
            val from = a.int("from")!!; val to = a.int("to")!!
            require(from in p.tracks.indices && to in p.tracks.indices) { "Index out of range" }
            ui { g.store.movePlaylistTrack(p.id, from, to) }
            "Moved"
        }
        tool(s, "delete_playlist", "Delete a local playlist. Cannot be undone.", props = mapOf("playlist_id" to str("")), required = listOf("playlist_id"), mutates = true, destructive = true) { a ->
            val p = playlist(a.string("playlist_id")!!)
            ui { g.store.deletePlaylist(p.id) }
            "Deleted '${p.name}'"
        }
        tool(s, "play_playlist", "Play a local playlist.", props = mapOf("playlist_id" to str(""), "shuffle" to bool("")), required = listOf("playlist_id"), mutates = true) { a ->
            val p = playlist(a.string("playlist_id")!!)
            require(p.tracks.isNotEmpty()) { "Playlist is empty" }
            ui { g.player.playQueue(p.tracks.map { it.track }, 0, a.bool("shuffle") ?: false) }
            "Playing '${p.name}'"
        }
        tool(s, "set_liked", "Like or unlike tracks.", props = mapOf("track_ids" to ids(""), "liked" to bool("")), required = listOf("track_ids", "liked"), mutates = true) { a ->
            val want = a.bool("liked")!!
            val tracks = tracks(a.strings("track_ids"))
            ui { tracks.forEach { if (g.store.isLiked(it) != want) g.store.toggleLike(it) } }
            "${if (want) "Liked" else "Unliked"} ${tracks.size}"
        }
        tool(
            s, "set_following", "Follow or unfollow an artist (from search or get_artist).",
            props = mapOf("source" to str(""), "source_id" to str(""), "name" to str("Artist name"), "following" to bool("")),
            required = listOf("source", "source_id", "following"), mutates = true,
        ) { a ->
            val src = a.string("source")!!; val id = a.string("source_id")!!; val want = a.bool("following")!!
            if (g.store.isFollowing(src, id) != want) ui { g.store.toggleFollow(FollowedArtist(src, id, a.string("name") ?: id, null)) }
            if (want) "Following" else "Unfollowed"
        }
        tool(
            s, "set_saved_playlist", "Save or unsave a SoundCloud/YouTube playlist or album found by search.",
            props = mapOf("source" to str(""), "source_id" to str(""), "saved" to bool("")), required = listOf("source", "source_id", "saved"), mutates = true,
        ) { a ->
            val src = a.string("source")!!; val id = a.string("source_id")!!; val want = a.bool("saved")!!
            if (g.store.isSaved(src, id) != want) {
                val p = knownPlaylists["$src:$id"] ?: g.store.current.savedPlaylists.firstOrNull { it.source == src && it.sourceId == id }
                    ?: error("Unknown playlist - find it with search first")
                ui { g.store.toggleSaved(p) }
            }
            if (want) "Saved" else "Unsaved"
        }
        tool(s, "dislike_artist", "Never recommend this artist again.", props = mapOf("artist" to str("")), required = listOf("artist"), mutates = true) { a ->
            ui { g.store.dislikeArtist(a.string("artist")!!) }
            "Disliked"
        }
        tool(s, "download_tracks", "Download tracks for offline playback.", props = mapOf("track_ids" to ids("")), required = listOf("track_ids"), mutates = true, openWorld = true) { a ->
            val tracks = tracks(a.strings("track_ids"))
            ui { g.downloads.enqueueAll(tracks) }
            "Queued ${tracks.size} downloads"
        }
        tool(s, "remove_download", "Delete a downloaded file.", props = mapOf("track_id" to str("")), required = listOf("track_id"), mutates = true, destructive = true) { a ->
            ui { g.downloads.remove(a.string("track_id")!!) }
            "Removed"
        }
        tool(s, "clear_history", "Clear the listening history. Cannot be undone.", mutates = true, destructive = true) {
            ui { g.store.clearHistory() }
            "Cleared"
        }
    }

    // ---------------------------------------------------------------- analysis

    private fun registerAnalysis(s: Server) {
        tool(
            s, "analyze_tracks",
            "Analyse tracks: tempo (BPM), beat grid with bars (4/4), and mix-in/mix-out points (where the music starts and ends, " +
                "skipping silent intros/outros). Decodes each whole track, so it takes a few seconds per track. " +
                "Results are stored; already analysed tracks are returned from the store unless force=true.",
            props = mapOf("track_ids" to ids("At most $MAX_ANALYZE per call"), "force" to bool("Analyse again even if already analysed")),
            required = listOf("track_ids"), openWorld = true,
        ) { a ->
            val ids = a.strings("track_ids").distinct()
            require(ids.size <= MAX_ANALYZE) { "At most $MAX_ANALYZE tracks per call" }
            val tracks = tracks(ids)
            val force = a.bool("force") == true
            val gate = Semaphore(ANALYZE_PARALLEL)
            coroutineScope {
                tracks.map { t ->
                    async(Dispatchers.IO) {
                        val stored = g.store.current.analysis[t.key]?.takeIf { !force && it.grid != null }
                        val r = stored ?: gate.withPermit { g.analyze(t) }
                        r?.let { analysisView(t.key, it) } ?: mapOf("track_id" to t.key, "error" to "Could not load or analyse this track")
                    }
                }.awaitAll()
            }
        }
        tool(
            s, "get_track_analysis", "Stored analysis of a track (BPM, beat grid, mix points), or 'Not analysed' - see analyze_tracks.",
            props = mapOf("track_id" to str(""), "include_beats" to bool("Also list every detected beat (ms), when the tempo is not steady; can be long")),
            required = listOf("track_id"),
        ) { a ->
            val id = a.string("track_id")!!
            g.store.current.analysis[id]?.let { analysisView(id, it, includeBeats = a.bool("include_beats") == true) } ?: "Not analysed"
        }
        tool(
            s, "find_tracks_by_bpm",
            "Analysed tracks whose tempo fits a range, sorted by closeness to its middle. With allow_half_double, a track at half or double " +
                "the tempo also matches (e.g. 70 BPM for 140), since those mix on the beat as well. Only tracks analysed before are searched.",
            props = mapOf("min_bpm" to num(""), "max_bpm" to num(""), "allow_half_double" to bool("Default true"), "limit" to int("Default 50")),
            required = listOf("min_bpm", "max_bpm"),
        ) { a ->
            val lo = a.double("min_bpm")!!
            val hi = a.double("max_bpm")!!
            require(lo in 1.0..hi) { "Need 0 < min_bpm <= max_bpm" }
            val factors = if (a.bool("allow_half_double") != false) listOf(1.0, 2.0, 0.5) else listOf(1.0)
            val mid = (lo + hi) / 2
            g.store.current.analysis.mapNotNull { (id, an) ->
                val bpm = an.grid?.bpm ?: return@mapNotNull null
                val fit = factors.map { bpm * it }.filter { it in lo..hi }.minByOrNull { abs(it - mid) } ?: return@mapNotNull null
                Triple(id, an, abs(fit - mid))
            }.sortedBy { it.third }.take(a.int("limit") ?: 50).map { (id, an, _) ->
                val t = runCatching { track(id) }.getOrNull()
                mapOf("track" to (t?.let(::view) ?: mapOf("id" to id)), "bpm" to round2(an.grid!!.bpm))
            }
        }
    }

    /** MCP resources: the same data as the read tools, for clients that browse resources. */
    private fun registerResources(s: Server) {
        fun json(uri: String, value: Any?) = ReadResourceResult(listOf(TextResourceContents(gson.toJson(value), uri, "application/json")))
        s.addResource("grooveo://now-playing", "Now playing", "Current track, position, beat/bar position and player state.", "application/json") { req ->
            json(req.uri, nowPlaying())
        }
        s.addResource("grooveo://queue", "Queue", "The play queue.", "application/json") { req ->
            val q = g.player.state.value
            json(req.uri, mapOf("index" to q.index, "tracks" to q.queue.map(::view)))
        }
        s.addResourceTemplate(
            "grooveo://track/{track_id}/analysis", "Track analysis",
            "BPM, beat grid, bars and mix points of a track, e.g. grooveo://track/ytmusic:abc/analysis. Percent-encode '/' in the " +
                "track id (soundcloud:artist%2Ftitle). Analyse first with analyze_tracks.",
            "application/json",
        ) { req, vars ->
            val id = vars["track_id"].orEmpty()
            json(req.uri, g.store.current.analysis[id]?.let { analysisView(id, it, includeBeats = true) } ?: mapOf("track_id" to id, "analysed" to false))
        }
    }

    private fun analysisView(id: String, a: TrackAnalysis, includeBeats: Boolean = false): Map<String, Any?> {
        val grid = a.grid
        return mapOf(
            "track_id" to id,
            "mix_in_ms" to a.mixInMs,
            "mix_out_ms" to a.mixOutMs,
            "bpm" to grid?.let { round2(it.bpm) },
            "beat_ms" to grid?.let { round2(it.beatMs) },
            "beats_per_bar" to grid?.let { 4 },
            "first_beat_ms" to grid?.let { round2(it.firstBeatMs) },
            "first_downbeat_ms" to grid?.let { round2(it.firstBeatMs + it.downbeatOffset * it.beatMs) },
            "tempo_confidence" to grid?.let { round2(it.confidence.toDouble()) },
            "grid_stability" to grid?.let { round2(it.stability) },
            "beats_ms" to grid?.beatsMs?.takeIf { includeBeats },
            "note" to when {
                grid == null -> "No steady beat found (or analysed by an older version - analyze_tracks with force=true)"
                grid.stability < BeatTracker.KEEP_BEATS_BELOW ->
                    "Not every part follows the grid (breakdowns without drums, or tempo changes); the grid fits the steady parts. " +
                        "Detected beats: get_track_analysis with include_beats=true"
                else -> "Beat n is at first_beat_ms + n*beat_ms; bars start at first_downbeat_ms + k*4*beat_ms (downbeat is an estimate)"
            },
            "analysed_at" to java.time.Instant.ofEpochMilli(a.analyzedAt).toString(),
        )
    }

    /** Where [ms] falls on the grid: 1-based bar and beat in bar, like a DJ counts. */
    private fun beatPosition(grid: BeatGrid, ms: Double): Map<String, Any> {
        val downbeat = grid.firstBeatMs + grid.downbeatOffset * grid.beatMs
        val beats = floor((ms - downbeat) / grid.beatMs).toLong()
        return mapOf(
            "bpm" to round2(grid.bpm),
            "bar" to Math.floorDiv(beats, 4L) + 1,
            "beat_in_bar" to Math.floorMod(beats, 4L) + 1,
            "next_beat_ms" to round2(downbeat + (beats + 1) * grid.beatMs),
            "next_bar_ms" to round2(downbeat + (Math.floorDiv(beats, 4L) + 1) * 4 * grid.beatMs),
        )
    }

    // ---------------------------------------------------------------- sound

    private fun registerSound(s: Server) {
        tool(s, "get_eq", "The parametric equalizer, the built-in and saved presets, and the 3D sound presets.") {
            mapOf(
                "eq" to g.settings.current.eq,
                "loudness_normalization" to (g.settings.current.loudnessNormalization != false),
                "presets" to EqProfile.PRESETS.map { it.name },
                "user_presets" to g.settings.current.eqUserPresets.map { it.name },
                "sound3d_preset" to g.settings.current.sound3dPreset,
                "sound3d_presets" to Sound3dPreset.entries.map { mapOf("name" to it.name, "description" to it.description) },
                "notes" to "bands: type PEAK/LOW_SHELF/HIGH_SHELF/LOW_PASS/HIGH_PASS/NOTCH, freq 20-20000 Hz, gainDb -15..15, q 0.1..12, " +
                    "slope 1-4 (pass filters, x12 dB/oct), max ${EqProfile.MAX_BANDS} bands. preampDb -24..12; " +
                    "bassEnhance 0..1, bassEnhanceFreq 40-160 Hz, dynamicBass 0..${EqProfile.MAX_DYNAMIC_BASS_DB} dB, tameHarsh 0..1.",
            )
        }
        tool(
            s, "set_eq", "Change the equalizer. Pass only the fields to change; 'bands' replaces the whole band list. Use auto_preamp to avoid clipping.",
            props = mapOf("eq" to obj("Partial EqProfile, see get_eq"), "auto_preamp" to bool("Set the preamp so the curve never boosts above 0 dB")),
            required = listOf("eq"), mutates = true,
        ) { a ->
            val merged = gson.toJsonTree(g.settings.current.eq).asJsonObject
            JsonParser.parseString(a.raw("eq").toString()).asJsonObject.entrySet().forEach { (k, v) ->
                require(merged.has(k) || k == "name") { "Unknown EQ field '$k'" }
                merged.add(k, v)
            }
            var eq = gson.fromJson(merged, EqProfile::class.java).normalized()
            if (a.bool("auto_preamp") == true) eq = eq.withAutoPreamp()
            ui { g.settings.update { it.copy(eq = eq) } }
            eq
        }
        tool(s, "apply_eq_preset", "Switch to a built-in or saved EQ preset by name.", props = mapOf("name" to str("")), required = listOf("name"), mutates = true) { a ->
            val name = a.string("name")!!
            val p = (g.settings.current.eqUserPresets + EqProfile.PRESETS).firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: error("No preset '$name'")
            ui { g.settings.update { it.copy(eq = p.copy(enabled = true)) } }
            "EQ: ${p.name}"
        }
        tool(s, "save_eq_preset", "Save the current EQ as a user preset.", props = mapOf("name" to str("")), required = listOf("name"), mutates = true) { a ->
            val name = a.string("name")!!.trim().also { require(it.isNotEmpty()) { "Name is empty" } }
            ui { g.settings.update { st -> st.copy(eqUserPresets = st.eqUserPresets.filterNot { it.name == name } + st.eq.copy(name = name)) } }
            "Saved '$name'"
        }
    }

    // ---------------------------------------------------------------- settings

    private fun registerSettings(s: Server) {
        tool(s, "get_settings", "All app settings as JSON (playback, crossfade, sources, home screen, theme, downloads, ...).") { g.settings.current }
        tool(
            s, "update_settings", "Change settings. Pass only the fields to change, with names and types as in get_settings.",
            props = mapOf("settings" to obj("Partial settings object")), required = listOf("settings"), mutates = true,
        ) { a ->
            val tree = gson.toJsonTree(g.settings.current).asJsonObject
            val patch = JsonParser.parseString(a.raw("settings").toString()).asJsonObject
            patch.entrySet().forEach { (k, v) ->
                require(tree.has(k) || k in NULLABLE_SETTINGS) { "Unknown setting '$k'" }
                tree.add(k, v)
            }
            val next = gson.fromJson(tree, Settings::class.java).let { it.copy(eq = it.eq.normalized(), crossfadeSeconds = it.crossfadeSeconds.coerceIn(0, 12)) }
            ui { g.settings.update { next } }
            "Updated: ${patch.keySet().joinToString()}"
        }
    }

    // ---------------------------------------------------------------- prompts

    private fun registerPrompts(s: Server) {
        s.addPrompt(
            "dj_set", "Plan and start a DJ set in Grooveo.",
            listOf(PromptArgument("mood", "Mood or occasion", required = true), PromptArgument("minutes", "Length in minutes")),
        ) { req ->
            val mood = req.arguments?.get("mood").orEmpty()
            val minutes = req.arguments?.get("minutes") ?: "60"
            GetPromptResult(
                messages = listOf(PromptMessage(Role.User, TextContent(
                    "Be my DJ in Grooveo for about $minutes minutes: $mood. Look at my likes and history (get_library) for taste, " +
                        "find tracks with search/get_trending/get_recommendations, analyse them (analyze_tracks) and order them by energy " +
                        "and tempo so the set builds and releases without big BPM jumps (find_tracks_by_bpm helps), " +
                        "set a fitting crossfade (update_settings crossfadeSeconds) and EQ, then start it with play_tracks. " +
                        "Save the set as a playlist too.",
                ))),
            )
        }
        s.addPrompt(
            "build_playlist", "Build a playlist from a description.",
            listOf(PromptArgument("description", "What the playlist should be", required = true)),
        ) { req ->
            GetPromptResult(
                messages = listOf(PromptMessage(Role.User, TextContent(
                    "Create a Grooveo playlist: ${req.arguments?.get("description").orEmpty()}. Use my likes and history for taste, " +
                        "search for fitting tracks, avoid disliked artists, give it a name, description and mood tags (create_playlist).",
                ))),
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun view(t: TrackResultDto): Map<String, Any?> {
        known[t.key] = t
        return mapOf("id" to t.key, "title" to t.title, "artist" to t.artist, "album" to t.album, "duration_sec" to t.durationSec, "genre" to t.genre)
    }

    private fun rememberPlaylist(p: PlaylistResultDto): Map<String, Any?> {
        knownPlaylists["${p.source}:${p.sourceId}"] = SavedPlaylist(p.source, p.sourceId, p.title, p.thumbnailUrl, p.owner, p.trackCount, p.webpageUrl)
        return mapOf("source" to p.source, "source_id" to p.sourceId, "title" to p.title, "owner" to p.owner, "track_count" to p.trackCount)
    }

    private fun rememberAlbum(p: AlbumResultDto): Map<String, Any?> {
        knownPlaylists["${p.source}:${p.sourceId}"] = SavedPlaylist(p.source, p.sourceId, p.title, p.thumbnailUrl, p.artist, null, p.webpageUrl, isAlbum = true)
        return mapOf("source" to p.source, "source_id" to p.sourceId, "title" to p.title, "artist" to p.artist, "year" to p.year)
    }

    /** A track by id: from earlier results, the queue or the library. */
    private fun track(id: String): TrackResultDto {
        known[id]?.let { return it }
        val d = g.store.current
        val t = g.player.state.value.queue.firstOrNull { it.key == id }
            ?: d.likes.firstOrNull { it.track.key == id }?.track
            ?: d.playlists.firstNotNullOfOrNull { p -> p.tracks.firstOrNull { it.track.key == id }?.track }
            ?: d.history.firstOrNull { it.track.key == id }?.track
            ?: d.downloads.firstOrNull { it.track.key == id }?.track
            ?: error("Unknown track '$id' - use an id from search, get_library, get_queue or get_playlist")
        known[id] = t
        return t
    }

    private fun tracks(ids: List<String>): List<TrackResultDto> = ids.map(::track).also { require(it.isNotEmpty()) { "No tracks given" } }

    private fun playlist(id: String) = g.store.current.playlists.firstOrNull { it.id == id } ?: error("Unknown playlist '$id' - see get_library section=playlists")

    /** Runs on the UI thread, like a click would. */
    private suspend fun <T> ui(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private fun tool(
        s: Server,
        name: String,
        description: String,
        props: Map<String, JsonObject> = emptyMap(),
        required: List<String> = emptyList(),
        mutates: Boolean = false,
        destructive: Boolean = false,
        openWorld: Boolean = false,
        body: suspend (Args) -> Any?,
    ) {
        if (readOnly && mutates) return
        s.addTool(
            name = name,
            description = description,
            inputSchema = ToolSchema(properties = buildJsonObject { props.forEach { (k, v) -> put(k, v) } }, required = required.ifEmpty { null }),
            toolAnnotations = ToolAnnotations(readOnlyHint = !mutates, destructiveHint = if (mutates) destructive else null, openWorldHint = openWorld),
        ) { req ->
            val args = Args(req.arguments)
            val result = runCatching {
                required.forEach { require(args.has(it)) { "Missing argument '$it'" } }
                body(args)
            }
            val text = result.fold(
                onSuccess = { if (it is String) it else gson.toJson(it) },
                onFailure = { it.message ?: it.javaClass.simpleName },
            )
            log(McpActivity(System.currentTimeMillis(), name, result.isSuccess, text.take(120)))
            CallToolResult(content = listOf(TextContent(text)), isError = if (result.isFailure) true else null)
        }
    }

    internal class Args(private val o: JsonObject?) {
        fun has(n: String) = o?.get(n)?.let { it !is kotlinx.serialization.json.JsonNull } == true
        fun raw(n: String): JsonElement? = o?.get(n)
        fun string(n: String): String? = (raw(n) as? JsonPrimitive)?.content
        fun int(n: String): Int? = (raw(n) as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
        fun double(n: String): Double? = (raw(n) as? JsonPrimitive)?.doubleOrNull
        fun bool(n: String): Boolean? = (raw(n) as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }
        fun strings(n: String): List<String> = when (val v = raw(n)) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> listOf(v.content)
            else -> emptyList()
        }
    }

    companion object {
        private const val MAX_ANALYZE = 25
        private const val ANALYZE_PARALLEL = 3
        private fun round2(v: Double) = Math.round(v * 100) / 100.0

        /** Settings fields whose default is null, so get_settings may not list them. */
        private val NULLABLE_SETTINGS = setOf("lastBackupAt", "loudnessNormalization")

        const val INSTRUCTIONS =
            "Grooveo is a music player (SoundCloud + YouTube Music) running on the user's PC. Tracks are referred to by id " +
                "('soundcloud:123', 'ytmusic:abc') as returned by search, get_library, get_queue and get_playlist. " +
                "Look at get_library (likes, history) to learn the listener's taste before choosing music. " +
                "analyze_tracks gives tempo, beat grid and mix points for planning transitions. " +
                "Changes appear in the app window at once. Avoid destructive tools unless the user asked for them."

        private fun str(d: String) = schema("string", d)
        private fun int(d: String) = schema("integer", d)
        private fun num(d: String) = schema("number", d)
        private fun bool(d: String) = schema("boolean", d)
        private fun obj(d: String) = schema("object", d)
        private fun schema(type: String, d: String) = buildJsonObject { put("type", type); if (d.isNotEmpty()) put("description", d) }
        private fun strings(d: String) = buildJsonObject {
            put("type", "array"); putJsonObject("items") { put("type", "string") }
            if (d.isNotEmpty()) put("description", d)
        }
        private fun ids(d: String) = strings(listOf("Track ids like 'soundcloud:123'", d).filter(String::isNotEmpty).joinToString(". "))
        private fun enum(values: List<String>, d: String) = buildJsonObject {
            put("type", "string"); putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
            if (d.isNotEmpty()) put("description", d)
        }
    }
}
