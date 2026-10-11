package dev.schlubbe.musicagent.desktop.ai

import dev.schlubbe.musicagent.data.backup.SyncProtocol
import dev.schlubbe.musicagent.data.backup.SyncTransport
import dev.schlubbe.musicagent.data.sync.RemoteAi
import dev.schlubbe.musicagent.desktop.AppGraph
import dev.schlubbe.musicagent.desktop.data.SyncServer
import dev.schlubbe.musicagent.desktop.data.gson
import dev.schlubbe.musicagent.desktop.data.track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The phone's "KI" tab against a real sync server: sealed requests, the shared conversation, MCP tool calls. */
class PhoneBridgeTest {
    private val dir = Files.createTempDirectory("grooveo-phone-ai").toFile()
    private val graph = AppGraph(dir)
    private var provider = ScriptedProvider()
    private val ai = AiAssistant(graph, File(dir, "key"), providerOverride = { provider })
    private val server = SyncServer(graph.backup, File(dir, "sync-test.json"), port = 0, discoveryPort = 0)
        .apply { remote = PhoneBridge(graph) { ai }; start() }
    private val keys = SyncProtocol.keys(server.key)
    private val peer get() = SyncTransport.Peer("127.0.0.1", server.boundPortOrDefault, 0)

    @AfterTest fun tearDown() { server.stop(); ai.close(); graph.close() }

    private fun call(path: String, body: String, k: SyncProtocol.Keys = keys): SyncTransport.Answer =
        SyncTransport.call(peer, k, path, body.toByteArray(), System.currentTimeMillis() + 30_000)

    private fun ai(r: RemoteAi.Request): RemoteAi.State {
        val a = call(SyncProtocol.AI_PATH, gson.toJson(r))
        assertEquals(200, a.status)
        return gson.fromJson(a.json!!.decodeToString(), RemoteAi.State::class.java)
    }

    private fun mcp(method: String, params: String = "{}"): JsonObject {
        val a = call(SyncProtocol.MCP_PATH, """{"jsonrpc":"2.0","id":7,"method":"$method","params":$params}""")
        assertEquals(200, a.status)
        return Json.parseToJsonElement(a.json!!.decodeToString()).jsonObject
    }

    /** Long-polls like the phone until [cond] holds. */
    private fun pollUntil(cond: (RemoteAi.State) -> Boolean): RemoteAi.State {
        var s = ai(RemoteAi.Request(RemoteAi.Op.STATE))
        val end = System.currentTimeMillis() + 10_000
        while (!cond(s)) {
            check(System.currentTimeMillis() < end) { "timeout: $s" }
            s = ai(RemoteAi.Request(RemoteAi.Op.STATE, since = s.rev))
        }
        return s
    }

    @Test fun `the phone drives the PC's assistant and answers its questions`() {
        graph.store.toggleLike(track("a"))
        val id = graph.store.createPlaylist("Alt").id
        provider = ScriptedProvider(listOf("delete_playlist" to """{"playlist_id":"$id"}"""))
        ai(RemoteAi.Request(RemoteAi.Op.SEND, text = "Lösch die Playlist Alt"))
        val asked = pollUntil { s -> s.items.any { it.type == "confirm" && it.answer == null } }
        val confirm = asked.items.last { it.type == "confirm" }
        assertEquals("DELETE", confirm.kind)
        assertEquals(1, graph.store.current.playlists.size, "nothing deleted before the answer")
        // the PC's panel shows the same conversation
        assertTrue(this.ai.state.value.items.any { it is AiItem.User && it.text == "Lösch die Playlist Alt" })
        ai(RemoteAi.Request(RemoteAi.Op.ANSWER, id = confirm.id, allow = true))
        val done = pollUntil { s -> !s.running && s.items.lastOrNull()?.type == "reply" }
        assertEquals(0, graph.store.current.playlists.size)
        assertEquals("Fertig.", done.items.last().text)
        assertTrue(done.items.any { it.type == "action" && it.status == "ok" && it.label == "Playlist gelöscht" })
        assertTrue(done.canUndo)
        ai(RemoteAi.Request(RemoteAi.Op.UNDO))
        pollUntil { s -> s.items.any { it.type == "notice" && "Rückgängig" in it.text.orEmpty() } }
        assertEquals(listOf("Alt"), graph.store.current.playlists.map { it.name })
    }

    @Test fun `a state request waits for a change and an unchanged one comes back after the timeout`() {
        val first = ai(RemoteAi.Request(RemoteAi.Op.STATE))
        Thread { Thread.sleep(300); this.ai.send("hallo") }.start()
        val t0 = System.currentTimeMillis()
        val next = ai(RemoteAi.Request(RemoteAi.Op.STATE, since = first.rev))
        assertNotEquals(first.rev, next.rev)
        assertTrue(System.currentTimeMillis() - t0 in 250..5_000)
        assertTrue(next.items.any { it.type == "user" && it.text == "hallo" })
    }

    @Test fun `MCP over the sync connection lists and calls tools, deleting ones are left out`() {
        assertEquals("grooveo-pc", mcp("initialize")["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val names = mcp("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("get_now_playing" in names && "playback" in names)
        assertFalse("delete_playlist" in names)
        val now = mcp("tools/call", """{"name":"get_now_playing","arguments":{}}""")["result"]!!.jsonObject
        assertFalse(now["isError"]!!.jsonPrimitive.boolean)
        assertTrue("\"playing\"" in now["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val id = graph.store.createPlaylist("Bleibt").id
        val del = mcp("tools/call", """{"name":"delete_playlist","arguments":{"playlist_id":"$id"}}""")["result"]!!.jsonObject
        assertTrue(del["isError"]!!.jsonPrimitive.boolean)
        assertEquals(1, graph.store.current.playlists.size)
    }

    @Test fun `switched off on the PC, or with the wrong key, the phone gets nothing`() {
        assertEquals(401, call(SyncProtocol.AI_PATH, gson.toJson(RemoteAi.Request(RemoteAi.Op.STATE)), SyncProtocol.keys(SyncProtocol.newKey())).status)
        graph.settings.update { it.copy(aiFromPhone = false) }
        val s = ai(RemoteAi.Request(RemoteAi.Op.SEND, text = "hallo"))
        assertFalse(s.enabled)
        assertTrue(this.ai.state.value.items.isEmpty())
        assertTrue("error" in mcp("tools/list"))
    }
}
