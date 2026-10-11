package dev.schlubbe.musicagent.desktop.ai

import dev.schlubbe.musicagent.data.sync.RemoteAi
import dev.schlubbe.musicagent.desktop.AppGraph
import dev.schlubbe.musicagent.desktop.data.PhoneRemote
import dev.schlubbe.musicagent.desktop.data.gson
import dev.schlubbe.musicagent.desktop.mcp.GrooveoTools
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The PC's side of the phone's "KI" tab, served by the sync server over the paired keys:
 *  - [ai]: the phone drives the same assistant as the KI panel (one shared conversation, the
 *    PC's model and tools, the same questions before deleting, FX and loudness);
 *  - [mcp]: MCP tool calls (JSON-RPC `initialize`, `tools/list`, `tools/call`) for the phone's
 *    own controls. Destructive tools are left out there; they only run through the assistant,
 *    which asks first.
 * Both only work while "KI vom Handy" is on in the PC's settings.
 */
class PhoneBridge(private val g: AppGraph, private val assistant: () -> AiAssistant = { g.ai }) : PhoneRemote {
    private val tools by lazy { GrooveoTools(g) }
    private val revLock = Any()
    private var lastState: AiState? = null
    /** Starts at the clock, so a phone that saw the PC before a restart never mistakes an old revision for the current one. */
    private var rev = System.currentTimeMillis()

    private val enabled get() = g.settings.current.aiFromPhone

    override fun ai(json: String): String {
        val r = runCatching { gson.fromJson(json, RemoteAi.Request::class.java) }.getOrNull()
            ?: return gson.toJson(RemoteAi.State(message = "Ungültige Anfrage"))
        if (!enabled) return gson.toJson(RemoteAi.State(enabled = false, message = DISABLED))
        val ai = assistant()
        when (r.op) {
            RemoteAi.Op.STATE -> runBlocking {
                val changed = withTimeoutOrNull(RemoteAi.LONG_POLL_MS) { ai.state.first { current().first != r.since } }
                // streamed text arrives in many small steps: one answer carries a few of them
                if (changed != null && r.since != null) delay(COALESCE_MS)
            }
            RemoteAi.Op.SEND -> r.text?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }?.let { if (!ai.state.value.running) ai.send(it) }
            RemoteAi.Op.ANSWER -> r.id?.let { ai.answer(it, r.allow == true) }
            RemoteAi.Op.STOP -> ai.stop()
            RemoteAi.Op.NEW -> ai.newConversation()
            RemoteAi.Op.UNDO -> ai.undoLast()
            else -> return gson.toJson(RemoteAi.State(message = "Unbekannte Anfrage ${r.op}"))
        }
        val (rev, s) = current()
        return gson.toJson(wire(rev, s, ai))
    }

    private fun current(): Pair<Long, AiState> = synchronized(revLock) {
        val s = assistant().state.value
        if (s !== lastState) { lastState = s; rev++ }
        rev to s
    }

    private fun wire(rev: Long, s: AiState, ai: AiAssistant) = RemoteAi.State(
        rev = rev,
        items = s.items.takeLast(RemoteAi.MAX_ITEMS).map(::wire),
        running = s.running,
        canUndo = s.canUndo,
        undoFor = s.undoFor,
        provider = s.provider.ifBlank { ai.providerLabel() },
    )

    private fun wire(i: AiItem): RemoteAi.Item = when (i) {
        is AiItem.User -> RemoteAi.Item(i.id, "user", text = i.text)
        is AiItem.Reply -> RemoteAi.Item(i.id, "reply", text = i.text, streaming = i.streaming)
        is AiItem.Action -> RemoteAi.Item(i.id, "action", status = i.status.name.lowercase(), label = i.label, detail = i.detail)
        is AiItem.Confirm -> RemoteAi.Item(i.id, "confirm", kind = i.kind.name, title = i.title, detail = i.detail, answer = i.answer)
        is AiItem.Notice -> RemoteAi.Item(i.id, "notice", text = i.text, error = i.error)
    }

    override fun mcp(json: String): String {
        val req = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return rpcError(JsonNull, -32700, "Parse error")
        val id = req["id"] ?: JsonNull
        if (!enabled) return rpcError(id, -32001, DISABLED)
        val params = req["params"] as? JsonObject
        return when ((req["method"] as? JsonPrimitive)?.contentOrNull) {
            "initialize" -> rpcResult(id, buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                putJsonObject("capabilities") { putJsonObject("tools") {} }
                putJsonObject("serverInfo") { put("name", "grooveo-pc"); put("version", dev.schlubbe.musicagent.desktop.APP_VERSION) }
            })
            "tools/list" -> rpcResult(id, buildJsonObject {
                putJsonArray("tools") {
                    tools.defs.filter { !it.destructive }.forEach { d ->
                        addJsonObject {
                            put("name", d.name)
                            put("description", d.description)
                            putJsonObject("inputSchema") {
                                put("type", "object")
                                put("properties", d.properties)
                                if (d.required.isNotEmpty()) putJsonArray("required") { d.required.forEach { add(it) } }
                            }
                            putJsonObject("annotations") { put("readOnlyHint", !d.mutates); put("openWorldHint", d.openWorld) }
                        }
                    }
                }
            })
            "tools/call" -> {
                val name = (params?.get("name") as? JsonPrimitive)?.contentOrNull
                val def = name?.let(tools::def) ?: return rpcError(id, -32602, "Unknown tool '$name'")
                val (ok, text) = if (def.destructive) false to "'$name' deletes data: from the phone it only runs through the assistant, which asks first."
                else runBlocking { withTimeoutOrNull(TOOL_TIMEOUT_MS) { def.call(params["arguments"] as? JsonObject) } ?: (false to "Timed out") }
                rpcResult(id, buildJsonObject {
                    putJsonArray("content") { addJsonObject { put("type", "text"); put("text", text) } }
                    put("isError", !ok)
                })
            }
            else -> rpcError(id, -32601, "Method not found")
        }
    }

    private fun rpcResult(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", result)
    }.toString()

    private fun rpcError(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        putJsonObject("error") { put("code", code); put("message", message) }
    }.toString()

    companion object {
        const val DISABLED = "Am PC ist „KI vom Handy“ ausgeschaltet (Einstellungen › KI-Assistent)."
        const val PROTOCOL_VERSION = "2025-06-18"
        const val MAX_TEXT = 4000
        const val COALESCE_MS = 80L
        const val TOOL_TIMEOUT_MS = 60_000L
    }
}
