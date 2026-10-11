package dev.schlubbe.musicagent.desktop.ai

import dev.schlubbe.musicagent.desktop.AppGraph
import dev.schlubbe.musicagent.desktop.audio.FxChain
import dev.schlubbe.musicagent.desktop.audio.FxUnit
import dev.schlubbe.musicagent.desktop.audio.MasterFx
import dev.schlubbe.musicagent.desktop.audio.SAMPLE_RATE
import dev.schlubbe.musicagent.desktop.data.track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Answers with scripted tool calls; after the script, with a short text. Records what it was sent. */
class ScriptedProvider(vararg steps: List<Pair<String, String>>) : LlmProvider {
    private val script = ArrayDeque(steps.toList())
    val seen = CopyOnWriteArrayList<List<Msg>>()
    override val label = "Test"
    private var n = 0
    override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, onText: (String) -> Unit): Msg {
        seen += messages.toList()
        val step = script.removeFirstOrNull() ?: run { onText("Fertig."); return Msg(Msg.Role.ASSISTANT, listOf(Block.Text("Fertig."))) }
        return Msg(Msg.Role.ASSISTANT, step.map { (name, args) -> Block.ToolUse("t${n++}", name, Json.parseToJsonElement(args).jsonObject) })
    }
}

class AiAssistantTest {
    private val dir = Files.createTempDirectory("grooveo-ai").toFile()
    private val graph = AppGraph(dir)
    private var provider = ScriptedProvider()
    private val ai = AiAssistant(graph, File(dir, "key"), providerOverride = { provider })

    @AfterTest fun tearDown() { ai.close(); graph.close() }

    private fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) { check(System.currentTimeMillis() < end) { "timeout: ${ai.state.value.items}" }; Thread.sleep(10) }
    }
    /** The turn has run: something after the user's message, and not running any more. */
    private fun idle() = waitFor { ai.state.value.let { !it.running && it.items.isNotEmpty() && it.items.last() !is AiItem.User } }
    private fun pendingConfirm() = ai.state.value.items.filterIsInstance<AiItem.Confirm>().lastOrNull { it.answer == null }
    private fun lastResultFor(call: String): Block.ToolResult? =
        provider.seen.last().flatMap { it.blocks }.filterIsInstance<Block.ToolResult>().lastOrNull { it.id == call }

    @Test fun `runs tools, shows actions and the final reply`() {
        graph.store.toggleLike(track("a"))
        provider = ScriptedProvider(listOf("create_playlist" to """{"name":"Lernen","track_ids":["soundcloud:a"]}"""))
        ai.send("Mach mir eine Playlist zum Lernen")
        idle()
        assertEquals("Lernen", graph.store.current.playlists.single().name)
        val items = ai.state.value.items
        assertTrue(items.any { it is AiItem.Action && it.tool == "create_playlist" && it.status == AiItem.Action.Status.OK }, items.toString())
        assertEquals("Fertig.", (items.last() as AiItem.Reply).text)
        assertTrue(ai.state.value.canUndo)
        // the context names what plays and the taste
        assertTrue("Playing" in ai.systemPrompt() || "Nothing playing" in ai.systemPrompt())
    }

    @Test fun `deleting waits for the user, a no is reported back to the model`() {
        graph.store.toggleLike(track("a"))
        val id = graph.store.createPlaylist("Alt").id
        provider = ScriptedProvider(listOf("delete_playlist" to """{"playlist_id":"$id"}"""))
        ai.send("Lösch die Playlist Alt")
        waitFor { pendingConfirm() != null }
        assertEquals(AiPolicy.Kind.DELETE, pendingConfirm()!!.kind)
        assertEquals(1, graph.store.current.playlists.size, "nothing deleted before the answer")
        ai.answer(pendingConfirm()!!.id, false)
        idle()
        assertEquals(1, graph.store.current.playlists.size)
        val r = assertNotNull(lastResultFor("t0"))
        assertTrue(r.isError && "declined" in r.content)
    }

    @Test fun `a loudness change is asked once per new value`() {
        provider = ScriptedProvider(listOf("set_volume" to """{"volume":0.5}"""))
        ai.send("leiser")
        waitFor { pendingConfirm() != null }
        assertEquals(AiPolicy.Kind.LOUDNESS, pendingConfirm()!!.kind)
        ai.answer(pendingConfirm()!!.id, true)
        idle()
        assertEquals(0.5f, graph.settings.current.volume)
        // the same level again (e.g. for the next track): no question
        graph.player.setVolume(0.8f)
        provider = ScriptedProvider(listOf("set_volume" to """{"volume":0.5}"""))
        ai.send("wieder wie vorhin")
        idle()
        assertNull(pendingConfirm())
        assertEquals(0.5f, graph.settings.current.volume)
        // a new level is asked again
        provider = ScriptedProvider(listOf("set_volume" to """{"volume":0.3}"""))
        ai.send("noch leiser")
        waitFor { pendingConfirm() != null }
        ai.answer(pendingConfirm()!!.id, true)
        idle()
        assertEquals(0.3f, graph.settings.current.volume)
    }

    @Test fun `an FX chain is switched on only after the OK, and undo takes it back`() {
        provider = ScriptedProvider(listOf("set_fx_chain" to """{"name":"Club","save":true,"effects":[{"type":"highpass","hz":40},{"type":"echo","beats":0.75,"mix":0.15},{"type":"room","preset":"rave"}]}"""))
        ai.send("Club-Sound bitte")
        waitFor { pendingConfirm() != null }
        val c = pendingConfirm()!!
        assertEquals(AiPolicy.Kind.FX, c.kind)
        assertTrue("Hochpass" in c.detail && "Echo" in c.detail && "Raum" in c.detail, c.detail)
        assertNull(graph.settings.current.fxChain)
        ai.answer(c.id, true)
        idle()
        val chain = assertNotNull(graph.settings.current.fxChain)
        assertEquals(listOf("highpass", "echo", "room"), chain.units.map { it.type })
        assertEquals("RAVE", chain.units[2].preset)
        assertEquals(listOf("Club"), graph.settings.current.fxChainPresets.map { it.name })
        ai.undoLast()
        waitFor { graph.settings.current.fxChain == null }
        assertTrue(graph.settings.current.fxChainPresets.isEmpty())
    }

    @Test fun `an impossible request fails before the user is asked`() {
        provider = ScriptedProvider(listOf("set_fx_chain" to """{"effects":[{"type":"compressor","ratio":4}]}"""))
        ai.send("Kompressor bitte")
        idle()
        assertNull(ai.state.value.items.filterIsInstance<AiItem.Confirm>().firstOrNull(), "no question for an invalid chain")
        val r = assertNotNull(lastResultFor("t0"))
        assertTrue(r.isError && "Unknown effect 'compressor'" in r.content, r.content)
    }

    @Test fun `deck effects split into loudness and FX questions`() {
        val p = AiPolicy()
        val s = graph.settings.current
        val fx = ai.toolDef("set_deck_fx")
        assertEquals(AiPolicy.Kind.LOUDNESS, p.check(fx, Json.parseToJsonElement("""{"param":"eq_low","value":3}""").jsonObject, s)?.kind)
        assertEquals(AiPolicy.Kind.FX, p.check(fx, Json.parseToJsonElement("""{"param":"lowpass","value":800}""").jsonObject, s)?.kind)
        assertEquals(AiPolicy.Kind.LOUDNESS, p.check(ai.toolDef("update_settings"), Json.parseToJsonElement("""{"settings":{"loudnessNormalization":false}}""").jsonObject, s)?.kind)
        assertNull(p.check(ai.toolDef("update_settings"), Json.parseToJsonElement("""{"settings":{"themeMode":"dark"}}""").jsonObject, s))
        assertNull(p.check(ai.toolDef("search"), Json.parseToJsonElement("""{"query":"x"}""").jsonObject, s))
    }

    @Test fun `streams from both APIs are assembled into text and tool calls`() {
        val shown = StringBuilder()
        val a = AnthropicStream { shown.append(it) }
        listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Ich such"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"e."}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu1","name":"search","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"query\": \"tech"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"no\"}"}}""",
            """{"type":"message_stop"}""",
        ).forEach(a::onData)
        val m = a.result()
        assertEquals("Ich suche.", shown.toString())
        assertEquals(Block.Text("Ich suche."), m.blocks[0])
        assertEquals("techno", ((m.blocks[1] as Block.ToolUse).input["query"] as kotlinx.serialization.json.JsonPrimitive).content)

        val o = OpenAiStream {}
        listOf(
            """{"choices":[{"delta":{"content":"<think>hmm</think>Okay"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"set_","arguments":"{\"volu"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"volume","arguments":"me\":0.4}"}}]}}]}""",
            "[DONE]",
        ).forEach(o::onData)
        val r = o.result()
        assertEquals(Block.Text("Okay"), r.blocks[0])
        val call = r.blocks[1] as Block.ToolUse
        assertEquals("set_volume", call.name); assertEquals("c1", call.id)
        assertEquals("0.4", call.input["volume"].toString())
        assertEquals("Antwort", AiAssistant.visibleText("<think>lange Überlegung</think>Antwort"))
        assertEquals("", AiAssistant.visibleText("<think>noch am Denk"))
    }

    @Test fun `master FX chain filters and switches without a click`() {
        val fx = MasterFx()
        var t0 = 0
        // continuous phase from block to block
        fun tone(hz: Double) = FloatArray(1024 * 2) { (0.5 * sin(2 * PI * hz * (t0 + it / 2) / SAMPLE_RATE)).toFloat() }.also { t0 += 1024 }
        fx.set(FxChain("t", listOf(FxUnit("highpass", mapOf("hz" to 400.0)))))
        var out = FloatArray(0)
        repeat(20) { out = tone(50.0).also { fx.process(it, 1024) } }
        assertTrue(MasterFx.rms(out, 0, out.size) < 0.02, "50 Hz should be gone")
        // switching chains crossfades: no jump larger than the signal's own steps
        val bufs = (0 until 3).map { tone(1000.0) }
        fx.set(FxChain("t", listOf(FxUnit("eq", mapOf("low" to 0.0, "mid" to 0.0, "high" to 0.0)))))
        bufs.forEach { fx.process(it, 1024) }
        val all = bufs.flatMap { it.toList() }
        val maxStep = all.indices.filter { it >= 2 && it % 2 == 0 }.maxOf { abs(all[it] - all[it - 2]) }
        assertTrue(maxStep < 0.1f, "click: step $maxStep")
        assertFalse(MasterFx.rms(all.toFloatArray(), all.size - 2048, all.size) < 0.3)
    }
}
