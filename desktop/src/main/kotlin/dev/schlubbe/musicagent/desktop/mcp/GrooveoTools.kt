package dev.schlubbe.musicagent.desktop.mcp

import com.google.gson.JsonParser
import dev.schlubbe.musicagent.data.remote.dto.AlbumResultDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.desktop.AppGraph
import dev.schlubbe.musicagent.desktop.audio.BeatGrid
import dev.schlubbe.musicagent.desktop.audio.Curve
import dev.schlubbe.musicagent.desktop.audio.DeckRole
import dev.schlubbe.musicagent.desktop.audio.FxChain
import dev.schlubbe.musicagent.desktop.audio.FxParam
import dev.schlubbe.musicagent.desktop.audio.FxUnit
import dev.schlubbe.musicagent.desktop.audio.FxUnitSpec
import dev.schlubbe.musicagent.desktop.audio.Lane
import dev.schlubbe.musicagent.desktop.audio.LanePoint
import dev.schlubbe.musicagent.desktop.audio.MusicalKey
import dev.schlubbe.musicagent.desktop.audio.TransitionStatus
import dev.schlubbe.musicagent.desktop.playback.PlannedTransition
import dev.schlubbe.musicagent.desktop.playback.TransitionPlanner
import dev.schlubbe.musicagent.desktop.playback.TransitionRecipe
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
import kotlinx.serialization.json.double
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

    private val _defs = ArrayList<ToolDef>()
    /** Every tool, for the MCP server and for the in-app assistant (see ai/AiAssistant). */
    val defs: List<ToolDef> get() = _defs

    init {
        registerPlayback()
        registerQueue()
        registerDiscovery()
        registerLibrary()
        registerSound()
        registerFxChain()
        registerSettings()
        registerAnalysis()
        registerDj()
    }

    fun register(server: Server) {
        for (d in _defs) {
            if (readOnly && d.mutates) continue
            server.addTool(
                name = d.name,
                description = d.description,
                inputSchema = ToolSchema(properties = d.properties, required = d.required.ifEmpty { null }),
                toolAnnotations = ToolAnnotations(readOnlyHint = !d.mutates, destructiveHint = if (d.mutates) d.destructive else null, openWorldHint = d.openWorld),
            ) { req ->
                val (ok, text) = d.call(req.arguments)
                log(McpActivity(System.currentTimeMillis(), d.name, ok, text.take(120)))
                CallToolResult(content = listOf(TextContent(text)), isError = if (!ok) true else null)
            }
        }
        registerResources(server)
        registerPrompts(server)
    }

    fun def(name: String): ToolDef? = _defs.firstOrNull { it.name == name }

    // ---------------------------------------------------------------- playback

    private fun registerPlayback() {
        tool("get_now_playing", "Current track, position, duration, play state, volume, shuffle, repeat, sleep timer and queue length.") { nowPlaying() }
        tool(
            "play_tracks", "Replace the queue with these tracks and start playing.",
            props = mapOf("track_ids" to ids("Tracks to play, in order"), "start_index" to int("Index to start at (default 0)"), "shuffle" to bool("Shuffle the queue")),
            required = listOf("track_ids"), mutates = true,
        ) { a ->
            val tracks = tracks(a.strings("track_ids"))
            ui { g.player.playQueue(tracks, a.int("start_index") ?: 0, a.bool("shuffle") ?: false) }
            "Playing ${tracks.size} tracks"
        }
        tool(
            "playback", "Play, pause, toggle, stop, next or previous.",
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
        tool("seek", "Jump to a position in the current track.", props = mapOf("seconds" to num("Position in seconds")), required = listOf("seconds"), mutates = true) { a ->
            ui { g.player.seek(a.double("seconds")!!.coerceAtLeast(0.0)) }
            "Seeked"
        }
        tool("set_volume", "Set the volume, 0.0 to 1.0 (perceptual curve).", props = mapOf("volume" to num("0.0 .. 1.0")), required = listOf("volume"), mutates = true) { a ->
            ui { g.player.setVolume(a.double("volume")!!.toFloat()) }
            "Volume ${g.settings.current.volume}"
        }
        tool("set_shuffle", "Turn shuffle on or off.", props = mapOf("enabled" to bool("")), required = listOf("enabled"), mutates = true) { a ->
            ui { if (g.player.state.value.shuffle != a.bool("enabled")) g.player.toggleShuffle() }
            "Shuffle ${g.player.state.value.shuffle}"
        }
        tool("set_repeat", "Repeat mode.", props = mapOf("mode" to enum(listOf("off", "all", "one"), "")), required = listOf("mode"), mutates = true) { a ->
            val want = RepeatMode.valueOf(a.string("mode")!!.uppercase())
            ui { repeat(RepeatMode.entries.size) { if (g.player.state.value.repeat != want) g.player.cycleRepeat() } }
            "Repeat ${g.player.state.value.repeat.name.lowercase()}"
        }
        tool("set_sleep_timer", "Pause playback after some minutes; omit minutes to cancel the timer.", props = mapOf("minutes" to int("Minutes until pause")), mutates = true) { a ->
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
            "transition" to g.player.transition.value.let { st ->
                if (st.phase == TransitionStatus.Phase.NONE) null else mapOf("phase" to st.phase.name.lowercase(), "style" to st.plan?.style, "beat" to round2(st.beat))
            },
            "queue_index" to q.index,
            "queue_length" to q.queue.size,
            "error" to q.error,
        )
    }

    // ---------------------------------------------------------------- queue

    private fun registerQueue() {
        tool("get_queue", "The whole queue; 'index' marks the current track.") {
            val q = g.player.state.value
            mapOf("index" to q.index, "tracks" to q.queue.map(::view))
        }
        tool(
            "add_to_queue", "Add tracks to the queue, at the end or right after the current track.",
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
        tool("remove_from_queue", "Remove the track at a queue index.", props = mapOf("index" to int("")), required = listOf("index"), mutates = true) { a ->
            ui { g.player.removeFromQueue(a.int("index")!!) }
            "Removed"
        }
        tool("move_in_queue", "Move a queue entry.", props = mapOf("from" to int(""), "to" to int("")), required = listOf("from", "to"), mutates = true) { a ->
            val n = g.player.state.value.queue.size
            val from = a.int("from")!!; val to = a.int("to")!!
            require(from in 0 until n && to in 0 until n) { "Index out of range (queue has $n tracks)" }
            ui { g.player.moveInQueue(from, to) }
            "Moved"
        }
        tool("skip_to", "Play the queue entry at this index.", props = mapOf("index" to int("")), required = listOf("index"), mutates = true) { a ->
            ui { g.player.skipTo(a.int("index")!!) }
            nowPlaying()
        }
        tool("clear_up_next", "Remove everything after the current track from the queue.", mutates = true, destructive = true) {
            ui { g.player.clearUpNext() }
            "Cleared"
        }
    }

    // ---------------------------------------------------------------- discovery

    private fun registerDiscovery() {
        tool(
            "search", "Search SoundCloud and YouTube Music.",
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
            "get_remote_playlist", "Tracks of a SoundCloud/YouTube playlist or album found by search.",
            props = mapOf("source" to str(""), "source_id" to str("")), required = listOf("source", "source_id"), openWorld = true,
        ) { a ->
            val d = g.search.getPlaylistDetail(a.string("source")!!, a.string("source_id")!!)
            mapOf("title" to d.title, "owner" to d.owner, "description" to d.description, "tags" to d.tags, "tracks" to d.tracks.map(::view))
        }
        tool(
            "get_artist", "Artist details with top and latest tracks.",
            props = mapOf("source" to str(""), "source_id" to str("")), required = listOf("source", "source_id"), openWorld = true,
        ) { a ->
            val d = g.search.getArtist(a.string("source")!!, a.string("source_id")!!)
            mapOf("name" to d.name, "description" to d.description, "subscribers" to d.subscriberCount, "top_tracks" to d.topTracks.map(::view), "latest_tracks" to d.latestTracks.map(::view))
        }
        tool(
            "get_trending", "Trending tracks, optionally for a genre.",
            props = mapOf("genre" to str("e.g. techno, hip hop"), "source" to enum(listOf("all", "soundcloud", "ytmusic"), ""), "limit" to int("")), openWorld = true,
        ) { a ->
            val limit = (a.int("limit") ?: 20).coerceIn(1, 50)
            (a.string("genre")?.let { g.search.getTrendingByGenre(it, limit) } ?: g.search.getTrending(limit, a.string("source") ?: "all")).map(::view)
        }
        tool("get_recommendations", "Tracks Grooveo would play next for the listener, from history and likes.", props = mapOf("limit" to int("")), openWorld = true) { a ->
            val recent = g.store.current.history.take(20).map { it.track }
            g.feed.predictNext(recent, recent.map { it.key }.toSet(), (a.int("limit") ?: 15).coerceIn(1, 50)).map(::view)
        }
        tool("get_lyrics", "Lyrics of a track (LRCLIB).", props = mapOf("track_id" to str("")), required = listOf("track_id"), openWorld = true) { a ->
            val t = track(a.string("track_id")!!)
            val l = g.lyrics.lyricsFor(t.title, t.artist, t.durationSec) ?: return@tool "No lyrics found"
            l.plain ?: l.synced?.joinToString("\n") { "[${it.timeMs / 1000}s] ${it.text}" } ?: "No lyrics found"
        }
    }

    // ---------------------------------------------------------------- library

    private fun registerLibrary() {
        tool(
            "get_library", "The listener's library.",
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
        tool("get_playlist", "A local playlist with its tracks.", props = mapOf("playlist_id" to str("")), required = listOf("playlist_id")) { a ->
            val p = playlist(a.string("playlist_id")!!)
            mapOf("id" to p.id, "name" to p.name, "description" to p.description, "mood_tags" to p.moodTags, "tracks" to p.tracks.map { view(it.track) })
        }
        tool(
            "create_playlist", "Create a local playlist, optionally with tracks.",
            props = mapOf("name" to str(""), "description" to str(""), "mood_tags" to strings("Short tags like 'chill', 'gym'"), "track_ids" to ids("")),
            required = listOf("name"), mutates = true,
        ) { a ->
            val tracks = tracks(a.strings("track_ids"))
            val p = ui { g.store.createPlaylist(a.string("name")!!, tracks) }
            ui { g.store.updatePlaylist(p.id) { it.copy(description = a.string("description") ?: it.description, moodTags = a.strings("mood_tags").ifEmpty { it.moodTags }) } }
            mapOf("playlist_id" to p.id, "track_count" to tracks.size)
        }
        tool(
            "update_playlist", "Rename a playlist or change its description and mood tags.",
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
        tool("add_to_playlist", "Append tracks to a playlist.", props = mapOf("playlist_id" to str(""), "track_ids" to ids("")), required = listOf("playlist_id", "track_ids"), mutates = true) { a ->
            playlist(a.string("playlist_id")!!)
            val tracks = tracks(a.strings("track_ids"))
            ui { g.store.addToPlaylist(a.string("playlist_id")!!, tracks) }
            "Added ${tracks.size}"
        }
        tool("remove_from_playlist", "Remove the track at an index from a playlist.", props = mapOf("playlist_id" to str(""), "index" to int("")), required = listOf("playlist_id", "index"), mutates = true) { a ->
            val p = playlist(a.string("playlist_id")!!)
            val i = a.int("index")!!
            require(i in p.tracks.indices) { "Index out of range (playlist has ${p.tracks.size} tracks)" }
            ui { g.store.removeFromPlaylist(p.id, i) }
            "Removed"
        }
        tool("move_playlist_track", "Move a track inside a playlist.", props = mapOf("playlist_id" to str(""), "from" to int(""), "to" to int("")), required = listOf("playlist_id", "from", "to"), mutates = true) { a ->
            val p = playlist(a.string("playlist_id")!!)
            val from = a.int("from")!!; val to = a.int("to")!!
            require(from in p.tracks.indices && to in p.tracks.indices) { "Index out of range" }
            ui { g.store.movePlaylistTrack(p.id, from, to) }
            "Moved"
        }
        tool("delete_playlist", "Delete a local playlist. Cannot be undone.", props = mapOf("playlist_id" to str("")), required = listOf("playlist_id"), mutates = true, destructive = true) { a ->
            val p = playlist(a.string("playlist_id")!!)
            ui { g.store.deletePlaylist(p.id) }
            "Deleted '${p.name}'"
        }
        tool("play_playlist", "Play a local playlist.", props = mapOf("playlist_id" to str(""), "shuffle" to bool("")), required = listOf("playlist_id"), mutates = true) { a ->
            val p = playlist(a.string("playlist_id")!!)
            require(p.tracks.isNotEmpty()) { "Playlist is empty" }
            ui { g.player.playQueue(p.tracks.map { it.track }, 0, a.bool("shuffle") ?: false) }
            "Playing '${p.name}'"
        }
        tool("set_liked", "Like or unlike tracks.", props = mapOf("track_ids" to ids(""), "liked" to bool("")), required = listOf("track_ids", "liked"), mutates = true) { a ->
            val want = a.bool("liked")!!
            val tracks = tracks(a.strings("track_ids"))
            ui { tracks.forEach { if (g.store.isLiked(it) != want) g.store.toggleLike(it) } }
            "${if (want) "Liked" else "Unliked"} ${tracks.size}"
        }
        tool(
            "set_following", "Follow or unfollow an artist (from search or get_artist).",
            props = mapOf("source" to str(""), "source_id" to str(""), "name" to str("Artist name"), "following" to bool("")),
            required = listOf("source", "source_id", "following"), mutates = true,
        ) { a ->
            val src = a.string("source")!!; val id = a.string("source_id")!!; val want = a.bool("following")!!
            if (g.store.isFollowing(src, id) != want) ui { g.store.toggleFollow(FollowedArtist(src, id, a.string("name") ?: id, null)) }
            if (want) "Following" else "Unfollowed"
        }
        tool(
            "set_saved_playlist", "Save or unsave a SoundCloud/YouTube playlist or album found by search.",
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
        tool("dislike_artist", "Never recommend this artist again.", props = mapOf("artist" to str("")), required = listOf("artist"), mutates = true) { a ->
            ui { g.store.dislikeArtist(a.string("artist")!!) }
            "Disliked"
        }
        tool("download_tracks", "Download tracks for offline playback.", props = mapOf("track_ids" to ids("")), required = listOf("track_ids"), mutates = true, openWorld = true) { a ->
            val tracks = tracks(a.strings("track_ids"))
            ui { g.downloads.enqueueAll(tracks) }
            "Queued ${tracks.size} downloads"
        }
        tool("remove_download", "Delete a downloaded file.", props = mapOf("track_id" to str("")), required = listOf("track_id"), mutates = true, destructive = true) { a ->
            ui { g.downloads.remove(a.string("track_id")!!) }
            "Removed"
        }
        tool("clear_history", "Clear the listening history. Cannot be undone.", mutates = true, destructive = true) {
            ui { g.store.clearHistory() }
            "Cleared"
        }
    }

    // ---------------------------------------------------------------- FX chain

    private fun registerFxChain() {
        tool("get_fx_chain", "The FX chain on the output (effects in order), the saved chains, and every effect type with its parameters.") {
            val st = g.settings.current
            mapOf(
                "active" to st.fxChain,
                "saved" to st.fxChainPresets,
                "effect_types" to FxUnitSpec.ALL.map { sp ->
                    mapOf("type" to sp.type, "description" to sp.description,
                        "params" to sp.params.map { mapOf("key" to it.key, "min" to it.min, "max" to it.max, "default" to it.default, "meaning" to it.meaning) })
                },
            )
        }
        tool(
            "set_fx_chain",
            "Puts an FX chain on the whole output: effects run in the given order, before the equalizer. Replaces the current chain. " +
                "Each effect is {type, ...its parameters} (see get_fx_chain), e.g. [{\"type\":\"highpass\",\"hz\":30},{\"type\":\"echo\",\"beats\":0.75,\"mix\":0.15}," +
                "{\"type\":\"room\",\"preset\":\"RAVE\"}]. The user is asked before it is switched on.",
            props = mapOf(
                "effects" to buildJsonObject { put("type", "array"); putJsonObject("items") { put("type", "object") }; put("description", "Effects in order") },
                "name" to str("Name for the chain"),
                "save" to bool("Also save it under its name for later"),
            ),
            required = listOf("effects"), mutates = true,
            check = { a -> FxUnitSpec.validate(FxChain("", parseEffects(a.raw("effects")))) },
        ) { a ->
            val chain = FxUnitSpec.validate(FxChain(a.string("name") ?: "KI-Kette", parseEffects(a.raw("effects"))))
            ui {
                g.settings.update { s ->
                    s.copy(fxChain = chain, fxChainPresets = if (a.bool("save") == true) s.fxChainPresets.filter { it.name != chain.name } + chain else s.fxChainPresets)
                }
            }
            mapOf("active" to chain)
        }
        tool(
            "apply_fx_preset", "Switches on a saved FX chain by name.", props = mapOf("name" to str("")), required = listOf("name"), mutates = true,
            check = { a -> require(g.settings.current.fxChainPresets.any { it.name.equals(a.string("name"), ignoreCase = true) }) { "No saved chain '${a.string("name")}'" } },
        ) { a ->
            val n = a.string("name")!!
            val chain = g.settings.current.fxChainPresets.firstOrNull { it.name.equals(n, ignoreCase = true) } ?: error("No saved chain '$n'")
            ui { g.settings.update { it.copy(fxChain = chain) } }
            mapOf("active" to chain)
        }
        tool("clear_fx_chain", "Switches the FX chain off (saved chains stay).", mutates = true) {
            ui { g.settings.update { it.copy(fxChain = null) } }
            "FX chain off"
        }
        tool("delete_fx_preset", "Deletes a saved FX chain.", props = mapOf("name" to str("")), required = listOf("name"), mutates = true, destructive = true) { a ->
            val n = a.string("name")!!
            require(g.settings.current.fxChainPresets.any { it.name == n }) { "No saved chain '$n'" }
            ui { g.settings.update { s -> s.copy(fxChainPresets = s.fxChainPresets.filter { it.name != n }) } }
            "Deleted"
        }
    }

    private fun parseEffects(raw: JsonElement?): List<FxUnit> =
        (raw as? JsonArray ?: error("effects must be a list")).map { el ->
            val o = el as? JsonObject ?: error("Each effect must be an object")
            val type = (o["type"] as? JsonPrimitive)?.content ?: error("Effect without type")
            FxUnit(
                type,
                o.filterKeys { it != "type" && it != "preset" }.mapValues { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull ?: error("$type.$k must be a number") },
                (o["preset"] as? JsonPrimitive)?.content,
            )
        }

    // ---------------------------------------------------------------- analysis

    private fun registerAnalysis() {
        tool(
            "analyze_tracks",
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
                        val stored = g.store.current.analysis[t.key]?.takeIf { !force && it.upToDate }
                        val r = stored ?: gate.withPermit { g.analyze(t) }
                        r?.let { analysisView(t.key, it) } ?: mapOf("track_id" to t.key, "error" to "Could not load or analyse this track")
                    }
                }.awaitAll()
            }
        }
        tool(
            "get_track_analysis", "Stored analysis of a track (BPM, beat grid, mix points), or 'Not analysed' - see analyze_tracks.",
            props = mapOf("track_id" to str(""), "include_beats" to bool("Also list every detected beat (ms), when the tempo is not steady; can be long")),
            required = listOf("track_id"),
        ) { a ->
            val id = a.string("track_id")!!
            g.store.current.analysis[id]?.let { analysisView(id, it, includeBeats = a.bool("include_beats") == true) } ?: "Not analysed"
        }
        tool(
            "find_tracks_by_bpm",
            "Analysed tracks whose tempo fits a range, sorted by closeness to its middle. With allow_half_double, a track at half or double " +
                "the tempo also matches (e.g. 70 BPM for 140), since those mix on the beat as well. Only tracks analysed before are searched.",
            props = mapOf(
                "min_bpm" to num(""), "max_bpm" to num(""), "allow_half_double" to bool("Default true"), "limit" to int("Default 50"),
                "camelot" to str("Only keys that mix harmonically with this Camelot code (same, ±1 hour, or relative major/minor), e.g. '8A'"),
                "compatible_with" to str("Only keys that mix harmonically with this track's key (track id)"),
            ),
            required = listOf("min_bpm", "max_bpm"),
        ) { a ->
            val keyRef = a.string("camelot") ?: a.string("compatible_with")?.let { id ->
                g.store.current.analysis[id]?.key?.camelot ?: error("Track '$id' has no key yet - analyze_tracks first")
            }
            val lo = a.double("min_bpm")!!
            val hi = a.double("max_bpm")!!
            require(lo in 1.0..hi) { "Need 0 < min_bpm <= max_bpm" }
            val factors = if (a.bool("allow_half_double") != false) listOf(1.0, 2.0, 0.5) else listOf(1.0)
            val mid = (lo + hi) / 2
            g.store.current.analysis.mapNotNull { (id, an) ->
                val bpm = an.grid?.bpm ?: return@mapNotNull null
                if (keyRef != null && (an.key?.camelot?.let { MusicalKey.camelotDistance(it, keyRef) } ?: 99) > 1) return@mapNotNull null
                val fit = factors.map { bpm * it }.filter { it in lo..hi }.minByOrNull { abs(it - mid) } ?: return@mapNotNull null
                Triple(id, an, abs(fit - mid))
            }.sortedBy { it.third }.take(a.int("limit") ?: 50).map { (id, an, _) ->
                val t = runCatching { track(id) }.getOrNull()
                mapOf("track" to (t?.let(::view) ?: mapOf("id" to id)), "bpm" to round2(an.grid!!.bpm), "key" to an.key?.name, "camelot" to an.key?.camelot)
            }
        }
    }

    // ---------------------------------------------------------------- DJ

    private fun registerDj() {
        val fxParams = FxParam.entries.map { it.key }
        tool("get_transition_styles", "How transitions work: styles, cue names, automatable deck parameters and curves, with their ranges.") {
            mapOf(
                "styles" to TransitionPlanner.STYLES,
                "out_cues" to listOf("mix_out", "outro_start", "breakdown", "drop", "build", "bar:N", "next_bar", "next_phrase"),
                "in_cues" to listOf("mix_in", "first_beat", "drop", "breakdown", "build", "bar:N"),
                "lane_params" to FxParam.entries.map { mapOf("param" to it.key, "neutral" to it.neutral, "min" to it.min, "max" to it.max, "meaning" to it.description) },
                "curves" to Curve.entries.map { it.key },
                "lanes" to "Each lane automates one parameter of the outgoing ('out') or incoming ('in') deck. Points are " +
                    "{beat, value, curve}: beat counts from the transition start in beats of the outgoing track (4 per bar); the curve " +
                    "shapes the segment ending at that point. A lane replaces the style's lane for the same deck and parameter. " +
                    "Incoming lanes that end off neutral return to neutral over one bar.",
                "tempo" to "With tempo=sync the incoming track is time-stretched (pitch kept) to the outgoing tempo and phase-locked to its " +
                    "beats; afterwards it glides back to its own tempo over release_bars, or keeps the mixed tempo with keep_tempo=true. " +
                    "Tempos more than ${(TransitionPlanner.MAX_STRETCH * 100).toInt()} % apart (after half/double) are not synced.",
                "sync_available" to g.player.canSync,
            )
        }
        val recipeProps = mapOf(
            "style" to enum(TransitionPlanner.STYLES.keys.toList(), "Default auto"),
            "bars" to int("Length in bars; default fits the outro and intro"),
            "out_cue" to str("Where the playing track starts the transition (see get_transition_styles); default mix_out"),
            "in_cue" to str("Where the next track is heard from; default mix_in"),
            "tempo" to enum(listOf("sync", "none"), "Default sync"),
            "keep_tempo" to bool("Keep the mixed tempo afterwards (for a set at one tempo)"),
            "release_bars" to int("Bars to glide back to the track's own tempo afterwards; default 8"),
            "harmonic" to bool("Transpose the next track up to 2 semitones if the keys clash"),
            "semitones" to int("Explicit transposition of the next track, -6..6"),
            "echo_beats" to num("Echo delay in beats, default 0.75"),
            "lanes" to buildJsonObject {
                put("type", "array")
                put("description", "Custom automation, see get_transition_styles")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        put("deck", enum(listOf("out", "in"), ""))
                        put("param", enum(fxParams, ""))
                        putJsonObject("points") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") { put("beat", num("")); put("value", num("")); put("curve", enum(Curve.entries.map { it.key }, "Default linear")) }
                            }
                        }
                    }
                }
            },
        )
        tool(
            "set_transition",
            "Plans how the playing track mixes into the next queued track: style, length, cue points, tempo sync, key shift and " +
                "optional custom automation of fader, 3-band EQ, filters and echo per deck. Both tracks are analysed first if needed. " +
                "Returns what was planned (start time, bars, tempos). Applies to the next transition only; call again after it ran.",
            props = recipeProps, mutates = true,
        ) { a -> plannedView(transition(recipe(a))) }
        tool(
            "transition_now",
            "Starts a transition into the next queued track at the next bar (or next 8-bar phrase): a live DJ move. Same options as set_transition.",
            props = recipeProps + ("at" to enum(listOf("next_bar", "next_phrase"), "Default next_bar")), mutates = true,
        ) { a -> plannedView(transition(recipe(a).copy(outCue = a.string("at") ?: "next_bar"))) }
        tool("get_transition", "The planned or running transition: phase (none/armed/running/releasing), beat, rates, beat-sync error and the plan.") {
            val st = g.player.transition.value
            mapOf(
                "phase" to st.phase.name.lowercase(),
                "beat" to round2(st.beat),
                "bar" to if (st.phase == TransitionStatus.Phase.NONE) null else Math.floorDiv(st.beat.toLong(), 4L) + 1,
                "length_beats" to st.plan?.lengthBeats,
                "style" to st.plan?.style,
                "incoming_rate" to round2(st.rate * 1000) / 1000,
                "sync_error_ms" to st.syncErrorMs?.let(::round2),
                "planned" to g.player.planned.value?.summary,
            )
        }
        tool("cancel_transition", "Drops the planned transition; the next track follows with the normal crossfade or gaplessly.", mutates = true) {
            ui { g.player.cancelTransition() }; "Cancelled"
        }
        tool(
            "set_deck_fx",
            "Live effect on the playing track, like turning a knob on a DJ mixer: ${FxParam.entries.joinToString { "${it.key} (${it.description})" }}. " +
                "Stays until changed or the track changes; running transitions automate these themselves.",
            props = mapOf("param" to enum(fxParams, ""), "value" to num("")), required = listOf("param", "value"), mutates = true,
            check = { a -> requireNotNull(FxParam.byKey(a.string("param").orEmpty())) { "Unknown param, use one of ${fxParams.joinToString()}" } },
        ) { a ->
            val p = FxParam.byKey(a.string("param")!!) ?: error("Unknown param")
            ui {
                g.player.setFx(p, a.double("value")!!)
                mapOf("param" to p.key, "value" to g.player.fxValue(p))
            }
        }
        tool("get_deck_fx", "Current effect settings of the playing track.") {
            ui { FxParam.entries.associate { it.key to g.player.fxValue(it) } }
        }
    }

    /**
     * Plans a transition on the UI thread, like the player's own calls. The analyses it needs
     * are made here first, off the UI thread, since decoding a track takes seconds.
     */
    private suspend fun transition(r: TransitionRecipe): PlannedTransition? {
        val q = g.player.state.value
        val next = when {
            q.queue.isEmpty() -> null
            q.repeat == RepeatMode.ONE -> q.current
            q.index + 1 < q.queue.size -> q.queue[q.index + 1]
            q.repeat != RepeatMode.OFF -> q.queue.first()
            else -> null
        }
        val analysed = next == null || listOfNotNull(q.current, next).distinctBy { it.key }.all { t ->
            g.store.current.analysis[t.key]?.upToDate == true || withContext(Dispatchers.IO) { g.analyze(t) } != null
        }
        // a track that cannot be analysed would be tried again inside; that must not block the window
        return withContext(if (analysed) Dispatchers.Main else Dispatchers.IO) { g.player.setTransition(r) }
    }

    private fun recipe(a: Args): TransitionRecipe = TransitionRecipe(
        style = a.string("style") ?: "auto",
        bars = a.int("bars"),
        outCue = a.string("out_cue"),
        inCue = a.string("in_cue"),
        tempo = a.string("tempo") ?: "sync",
        keepTempo = a.bool("keep_tempo") == true,
        releaseBars = a.int("release_bars") ?: 8,
        harmonic = a.bool("harmonic") == true,
        semitones = a.int("semitones"),
        echoBeats = a.double("echo_beats") ?: 0.75,
        lanes = (a.raw("lanes") as? JsonArray)?.map { el ->
            val o = el as? JsonObject ?: error("Each lane must be an object")
            val deck = when ((o["deck"] as? JsonPrimitive)?.content) { "out" -> DeckRole.OUT; "in" -> DeckRole.IN; else -> error("Lane deck must be 'out' or 'in'") }
            val param = (o["param"] as? JsonPrimitive)?.content?.let(FxParam::byKey) ?: error("Unknown lane param ${o["param"]}")
            val points = (o["points"] as? JsonArray ?: error("Lane needs points")).map { p ->
                when (p) {
                    is JsonArray -> LanePoint((p[0] as JsonPrimitive).double, (p[1] as JsonPrimitive).double)
                    is JsonObject -> LanePoint(
                        (p["beat"] as? JsonPrimitive)?.doubleOrNull ?: error("Point needs beat"),
                        (p["value"] as? JsonPrimitive)?.doubleOrNull ?: error("Point needs value"),
                        (p["curve"] as? JsonPrimitive)?.content?.let { Curve.byKey(it) ?: error("Unknown curve $it") } ?: Curve.LINEAR,
                    )
                    else -> error("Bad point $p")
                }
            }
            require(points.isNotEmpty()) { "Lane ${param.key} has no points" }
            Lane(deck, param, points.map { it.copy(value = it.value.coerceIn(param.min, param.max)) })
        } ?: emptyList(),
    )

    private fun plannedView(p: PlannedTransition?): Any = p?.let {
        it.summary + ("lanes" to it.plan.lanes.map { l -> mapOf("deck" to l.deck.name.lowercase(), "param" to l.param.key, "points" to l.points.map { pt -> listOf(pt.beat, pt.value, pt.curve.key) }) })
    } ?: "No beat-synced plan: a track has no beat grid (analyze_tracks), or nothing is queued next. The normal crossfade applies."

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
            "key" to a.key?.name,
            "camelot" to a.key?.camelot,
            "key_confidence" to a.key?.confidence,
            // a one-hour DJ set has hundreds of sections; its start and end are what mixing needs
            "sections" to a.structure?.sections?.let { s -> if (s.size > MAX_SECTIONS) s.take(MAX_SECTIONS / 2) + s.takeLast(MAX_SECTIONS / 2) else s }
                ?.map { mapOf("label" to it.label, "start_bar" to it.startBar, "bars" to it.bars, "start_ms" to it.startMs, "energy" to it.energy) },
            "sections_omitted" to a.structure?.sections?.size?.let { it - MAX_SECTIONS }?.takeIf { it > 0 },
            "cues" to a.structure?.cues?.let { c -> if (c.size > MAX_SECTIONS) c.take(MAX_SECTIONS / 2) + c.takeLast(MAX_SECTIONS / 2) else c }
                ?.map { mapOf("name" to it.name, "bar" to it.bar, "ms" to it.ms) },
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

    private fun registerSound() {
        tool("get_eq", "The parametric equalizer, the built-in and saved presets, and the 3D sound presets.") {
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
            "set_eq", "Change the equalizer. Pass only the fields to change; 'bands' replaces the whole band list. Use auto_preamp to avoid clipping.",
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
        tool("apply_eq_preset", "Switch to a built-in or saved EQ preset by name.", props = mapOf("name" to str("")), required = listOf("name"), mutates = true) { a ->
            val name = a.string("name")!!
            val p = (g.settings.current.eqUserPresets + EqProfile.PRESETS).firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: error("No preset '$name'")
            ui { g.settings.update { it.copy(eq = p.copy(enabled = true)) } }
            "EQ: ${p.name}"
        }
        tool("save_eq_preset", "Save the current EQ as a user preset.", props = mapOf("name" to str("")), required = listOf("name"), mutates = true) { a ->
            val name = a.string("name")!!.trim().also { require(it.isNotEmpty()) { "Name is empty" } }
            ui { g.settings.update { st -> st.copy(eqUserPresets = st.eqUserPresets.filterNot { it.name == name } + st.eq.copy(name = name)) } }
            "Saved '$name'"
        }
    }

    // ---------------------------------------------------------------- settings

    private fun registerSettings() {
        tool("get_settings", "All app settings as JSON (playback, crossfade, sources, home screen, theme, downloads, ...).") { g.settings.current }
        tool(
            "update_settings",
            "Change playback, sound and display settings. Pass only the fields to change, with names and types as in get_settings. " +
                "Only these can be changed: ${SETTABLE_SETTINGS.joinToString()}.",
            props = mapOf("settings" to obj("Partial settings object")), required = listOf("settings"), mutates = true,
            check = { a -> settingsPatch(a) },
        ) { a ->
            val patch = settingsPatch(a)
            ui {
                val tree = gson.toJsonTree(g.settings.current).asJsonObject
                patch.entrySet().forEach { (k, v) -> tree.add(k, v) }
                val next = gson.fromJson(tree, Settings::class.java).normalized().let { it.copy(fxChain = it.fxChain?.let(FxUnitSpec::validate)) }
                g.settings.update { next.copy(volume = it.volume) }
                // the engine only follows the volume through the player
                if (patch.has("volume")) g.player.setVolume(next.volume)
            }
            "Updated: ${patch.keySet().joinToString()}"
        }
    }

    /** The settings patch of update_settings; only [SETTABLE_SETTINGS], an FX chain must be valid. */
    private fun settingsPatch(a: Args): com.google.gson.JsonObject {
        val patch = runCatching { JsonParser.parseString(a.raw("settings").toString()).asJsonObject }.getOrNull()
            ?: error("settings must be an object")
        patch.entrySet().forEach { (k, v) ->
            require(k in SETTABLE_SETTINGS) { "Setting '$k' cannot be changed with update_settings. Allowed: ${SETTABLE_SETTINGS.joinToString()}" }
            if (k == "fxChain" && !v.isJsonNull) FxUnitSpec.validate(gson.fromJson(v, FxChain::class.java))
        }
        return patch
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
                        "favour keys that mix harmonically (camelot), then start it with play_tracks. Mix every track in like a DJ: " +
                        "for each next track call set_transition (style, bars, cues; vary styles to fit the music, e.g. blend for long intros, " +
                        "echo_out for big tempo jumps, filter for breakdowns), watch get_now_playing and get_transition, and use " +
                        "transition_now or set_deck_fx for live moves. Save the set as a playlist too.",
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
        name: String,
        description: String,
        props: Map<String, JsonObject> = emptyMap(),
        required: List<String> = emptyList(),
        mutates: Boolean = false,
        destructive: Boolean = false,
        openWorld: Boolean = false,
        check: ((Args) -> Unit)? = null,
        body: suspend (Args) -> Any?,
    ) {
        _defs += ToolDef(name, description, buildJsonObject { props.forEach { (k, v) -> put(k, v) } }, required, mutates, destructive, openWorld, body, check)
    }

    class Args(private val o: JsonObject?) {
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
        private const val MAX_SECTIONS = 40
        private const val ANALYZE_PARALLEL = 3
        private fun round2(v: Double) = Math.round(v * 100) / 100.0

        /**
         * What update_settings may change: playback, sound and display. Never the assistant's own
         * setup (ai*: provider, server, when to ask), paths, accounts, presets or internal state.
         */
        val SETTABLE_SETTINGS = setOf(
            "hiResAudio", "dataSaverMode", "crossfadeSeconds", "playerStyle", "autoplayRadio", "showMixControls", "showFeatured",
            "showNewUploads", "sound3dPreset", "vizVariant", "notifyNewUploads", "profileName", "profileColorStyle",
            "sourceSoundCloud", "sourceYouTube", "preferredGenres", "preferredArtists", "volume", "loudnessNormalization", "eq",
            "themeMode", "queuePanelOpen", "minimizeToTray", "djTransitions", "djStyle", "djHarmonic", "fxChain",
        )

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
