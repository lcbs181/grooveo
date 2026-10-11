package dev.schlubbe.musicagent.desktop.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.schlubbe.musicagent.desktop.AppGraph
import dev.schlubbe.musicagent.desktop.audio.BeatGrid
import dev.schlubbe.musicagent.desktop.data.TrackAnalysis
import dev.schlubbe.musicagent.desktop.data.track
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Talks plain MCP JSON-RPC over Streamable HTTP to the server inside the app. */
class McpServiceTest {
    private val dir = Files.createTempDirectory("grooveo-mcp").toFile()
    private val graph = AppGraph(dir)
    private val mcp = McpService(graph, java.io.File(dir, "mcp-test.json"), port = 0).apply { start() }
    private val http = HttpClient.newHttpClient()
    private var session: String? = null
    private var nextId = 1

    @AfterTest fun tearDown() { mcp.stop(); graph.close() }

    private fun post(body: String, token: String? = mcp.token): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI(mcp.url)).POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
        token?.let { b.header("Authorization", "Bearer $it") }
        session?.let { b.header("Mcp-Session-Id", it) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun rpc(method: String, params: String = "{}"): JsonObject {
        val r = post("""{"jsonrpc":"2.0","id":${nextId++},"method":"$method","params":$params}""")
        assertEquals(200, r.statusCode(), r.body())
        r.headers().firstValue("Mcp-Session-Id").ifPresent { session = it }
        val json = if (r.body().trimStart().startsWith("{")) r.body()
        else r.body().lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.last { it.startsWith("{") }
        return JsonParser.parseString(json).asJsonObject
    }

    private fun connect() {
        session = null
        rpc("initialize", """{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""")
        post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
    }

    private fun toolNames() = rpc("tools/list").getAsJsonObject("result").getAsJsonArray("tools").map { it.asJsonObject["name"].asString }.toSet()

    private fun call(name: String, args: String): Pair<Boolean, String> {
        val r = rpc("tools/call", """{"name":"$name","arguments":$args}""").getAsJsonObject("result")
        return (r["isError"]?.asBoolean != true) to r.getAsJsonArray("content")[0].asJsonObject["text"].asString
    }

    @Test fun `requests without the token are refused`() {
        assertEquals(401, post("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", token = null).statusCode())
        assertEquals(401, post("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", token = "wrong").statusCode())
    }

    @Test fun `ai creates a playlist and changes settings through the tools`() {
        graph.store.toggleLike(track("a")); graph.store.toggleLike(track("b"))
        connect()
        val tools = toolNames()
        assertTrue(listOf("play_tracks", "create_playlist", "set_eq", "update_settings", "search").all { it in tools }, tools.toString())

        val (ok, text) = call("create_playlist", """{"name":"Abendrunde","track_ids":["soundcloud:a","soundcloud:b"],"mood_tags":["chill"]}""")
        assertTrue(ok, text)
        val p = graph.store.current.playlists.single()
        assertEquals("Abendrunde", p.name); assertEquals(listOf("a", "b"), p.tracks.map { it.track.sourceId }); assertEquals(listOf("chill"), p.moodTags)

        assertTrue(call("update_settings", """{"settings":{"crossfadeSeconds":6,"themeMode":"dark"}}""").first)
        assertEquals(6, graph.settings.current.crossfadeSeconds); assertEquals("dark", graph.settings.current.themeMode)

        val (bad, msg) = call("update_settings", """{"settings":{"noSuchSetting":1}}""")
        assertFalse(bad); assertTrue("noSuchSetting" in msg)

        assertTrue(call("set_eq", """{"eq":{"preampDb":-3.0,"bassEnhance":0.4}}""").first)
        assertEquals(-3.0, graph.settings.current.eq.preampDb); assertEquals(0.4, graph.settings.current.eq.bassEnhance)

        assertFalse(call("add_to_playlist", """{"playlist_id":"${p.id}","track_ids":["soundcloud:unknown"]}""").first)
        assertTrue(mcp.activity.value.any { it.tool == "create_playlist" && it.ok })
    }

    @Test fun `analysis is readable as tools and track resources`() {
        graph.store.toggleLike(track("a")); graph.store.toggleLike(track("b")); graph.store.toggleLike(track("c"))
        graph.store.putAnalysis("soundcloud:a", TrackAnalysis(2_000, 180_000, grid = BeatGrid(128.0, 100.0, 1, 0.5f, 0.98)))
        graph.store.putAnalysis("soundcloud:b", TrackAnalysis(1_000, 200_000, grid = BeatGrid(64.5, 50.0, 0, 0.5f, 0.97)))
        graph.store.putAnalysis("soundcloud:c", TrackAnalysis(1_000, 200_000, grid = BeatGrid(150.0, 50.0, 0, 0.5f, 0.97)))
        connect()
        assertTrue("analyze_tracks" in toolNames())

        val (ok, text) = call("get_track_analysis", """{"track_id":"soundcloud:a"}""")
        assertTrue(ok, text)
        val a = JsonParser.parseString(text).asJsonObject
        assertEquals(128.0, a["bpm"].asDouble)
        assertEquals(468.75, a["beat_ms"].asDouble)
        assertEquals(568.75, a["first_downbeat_ms"].asDouble)
        assertEquals(2_000, a["mix_in_ms"].asLong)

        // 64.5 BPM counts as 129 (double time), 150 is out of range
        val found = JsonParser.parseString(call("find_tracks_by_bpm", """{"min_bpm":125,"max_bpm":131}""").second).asJsonArray
        assertEquals(listOf("soundcloud:a", "soundcloud:b"), found.map { it.asJsonObject["track"].asJsonObject["id"].asString })

        val templates = rpc("resources/templates/list").getAsJsonObject("result").getAsJsonArray("resourceTemplates")
        assertTrue(templates.any { it.asJsonObject["uriTemplate"].asString == "grooveo://track/{track_id}/analysis" })
        val read = rpc("resources/read", """{"uri":"grooveo://track/soundcloud:a/analysis"}""")
            .getAsJsonObject("result").getAsJsonArray("contents")[0].asJsonObject
        assertEquals(128.0, JsonParser.parseString(read["text"].asString).asJsonObject["bpm"].asDouble)
        val now = rpc("resources/read", """{"uri":"grooveo://now-playing"}""").getAsJsonObject("result").getAsJsonArray("contents")[0].asJsonObject
        assertTrue("playing" in now["text"].asString)
    }

    @Test fun `dj tools describe themselves and need a queued track`() {
        connect()
        val tools = toolNames()
        assertTrue(listOf("set_transition", "transition_now", "get_transition", "cancel_transition", "set_deck_fx", "get_transition_styles").all { it in tools })
        val styles = JsonParser.parseString(call("get_transition_styles", "{}").second).asJsonObject
        assertTrue(styles.getAsJsonObject("styles").has("bass_swap"))
        val (ok, msg) = call("set_transition", """{"style":"blend"}""")
        assertFalse(ok); assertTrue("Nothing queued" in msg, msg)
        assertTrue(call("set_deck_fx", """{"param":"lowpass","value":800}""").first)
        assertEquals(800.0, graph.player.fxValue(dev.schlubbe.musicagent.desktop.audio.FxParam.LOWPASS))
        assertFalse(call("set_deck_fx", """{"param":"nope","value":1}""").first)
    }

    @Test fun `read-only mode offers no tool that changes anything`() {
        mcp.setReadOnly(true)
        connect()
        val tools = toolNames()
        assertTrue("get_library" in tools && "get_now_playing" in tools)
        assertTrue(listOf("create_playlist", "play_tracks", "update_settings", "delete_playlist", "set_eq").none { it in tools }, tools.toString())
    }
}
