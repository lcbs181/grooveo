package dev.schlubbe.musicagent.desktop.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.schlubbe.musicagent.desktop.AppGraph
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

    @Test fun `read-only mode offers no tool that changes anything`() {
        mcp.setReadOnly(true)
        connect()
        val tools = toolNames()
        assertTrue("get_library" in tools && "get_now_playing" in tools)
        assertTrue(listOf("create_playlist", "play_tracks", "update_settings", "delete_playlist", "set_eq").none { it in tools }, tools.toString())
    }
}
