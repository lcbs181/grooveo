package dev.schlubbe.musicagent.desktop.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** A conversation turn in a provider-neutral form. */
data class Msg(val role: Role, val blocks: List<Block>) {
    enum class Role { USER, ASSISTANT }
}

sealed interface Block {
    data class Text(val text: String) : Block
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : Block
    data class ToolResult(val id: String, val content: String, val isError: Boolean) : Block
}

/** A tool as the model sees it: [schema] is the full JSON schema object of its input. */
data class ToolSpec(val name: String, val description: String, val schema: JsonObject)

/** A chat model that streams text and asks for tool calls. */
interface LlmProvider {
    val label: String

    /** Sends the conversation; [onText] gets text as it streams. Returns the complete assistant message. */
    suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, onText: (String) -> Unit): Msg
}

class LlmException(message: String) : Exception(message)

internal val llmHttp: OkHttpClient by lazy {
    OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
}

/**
 * For servers on this PC only (127.0.0.1, localhost, ::1): also accepts a self-signed
 * certificate, as local proxies like llama-swap use with TLS. The traffic never leaves
 * the machine, so there is no one in between whom a certificate would keep out.
 */
internal val localLlmHttp: OkHttpClient by lazy {
    val trustAll = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }
    val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), java.security.SecureRandom()) }
    llmHttp.newBuilder().sslSocketFactory(ssl.socketFactory, trustAll).hostnameVerifier { host, _ -> isLoopback(host) }.build()
}

internal fun isLoopback(host: String) = host == "localhost" || host == "::1" || host == "[::1]" || host.startsWith("127.")

/** The client for [baseUrl]: the lenient local one only for this PC. */
internal fun httpFor(baseUrl: String): OkHttpClient =
    if (runCatching { isLoopback(java.net.URI(baseUrl).host ?: "") }.getOrDefault(false)) localLlmHttp else llmHttp
private val json = Json { ignoreUnknownKeys = true }
private val JSON_TYPE = "application/json".toMediaType()

/** Posts [body] and feeds every `data:` line of the server-sent event stream to [onData]. */
private suspend fun postSse(http: OkHttpClient, url: String, headers: Map<String, String>, body: JsonObject, onData: (String) -> Unit) = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON_TYPE)).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
    val call = http.newCall(req)
    val ctx = coroutineContext
    call.execute().use { resp ->
        if (!resp.isSuccessful) {
            val text = resp.body?.string().orEmpty()
            val msg = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.let { e -> (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: e.toString() } }.getOrNull()
            throw LlmException("HTTP ${resp.code}: ${msg ?: text.take(300)}")
        }
        val reader: BufferedReader = resp.body!!.charStream().buffered()
        while (true) {
            if (!ctx.isActiveSafe()) { call.cancel(); break }
            val line = reader.readLine() ?: break
            if (line.startsWith("data:")) onData(line.removePrefix("data:").trim())
        }
    }
}

private fun kotlin.coroutines.CoroutineContext.isActiveSafe() = runCatching { ensureActive(); true }.getOrDefault(false)

/** Claude over the Anthropic Messages API (streaming, tool use). */
class AnthropicProvider(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val http: OkHttpClient = llmHttp,
    private val baseUrl: String = "https://api.anthropic.com",
) : LlmProvider {
    override val label = "Anthropic · $model"

    override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, onText: (String) -> Unit): Msg {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            put("stream", true)
            put("system", system)
            putJsonArray("tools") {
                tools.forEach { t -> addJsonObject { put("name", t.name); put("description", t.description); put("input_schema", t.schema) } }
            }
            putJsonArray("messages") {
                messages.forEach { m ->
                    addJsonObject {
                        put("role", if (m.role == Msg.Role.USER) "user" else "assistant")
                        putJsonArray("content") {
                            m.blocks.forEach { b ->
                                when (b) {
                                    is Block.Text -> addJsonObject { put("type", "text"); put("text", b.text) }
                                    is Block.ToolUse -> addJsonObject { put("type", "tool_use"); put("id", b.id); put("name", b.name); put("input", b.input) }
                                    is Block.ToolResult -> addJsonObject {
                                        put("type", "tool_result"); put("tool_use_id", b.id); put("content", b.content)
                                        if (b.isError) put("is_error", true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val acc = AnthropicStream(onText)
        postSse(http, "$baseUrl/v1/messages", mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"), body, acc::onData)
        return acc.result()
    }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
        val MODELS = listOf("claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5")
    }
}

/** Assembles an Anthropic event stream into one message. */
internal class AnthropicStream(private val onText: (String) -> Unit) {
    private val blocks = sortedMapOf<Int, Any>() // StringBuilder for text, ToolAcc for tool use
    private class ToolAcc(val id: String, val name: String, val input: StringBuilder = StringBuilder())
    private var error: String? = null

    fun onData(data: String) {
        val e = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        when (e["type"]?.jsonPrimitive?.contentOrNull) {
            "content_block_start" -> {
                val i = e["index"]!!.jsonPrimitive.int
                val cb = e["content_block"]!!.jsonObject
                blocks[i] = when (cb["type"]?.jsonPrimitive?.contentOrNull) {
                    "tool_use" -> ToolAcc(cb["id"]!!.jsonPrimitive.content, cb["name"]!!.jsonPrimitive.content)
                    else -> StringBuilder()
                }
            }
            "content_block_delta" -> {
                val i = e["index"]!!.jsonPrimitive.int
                val d = e["delta"]!!.jsonObject
                when (d["type"]?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> d["text"]?.jsonPrimitive?.contentOrNull?.let { t -> (blocks[i] as? StringBuilder)?.append(t); onText(t) }
                    "input_json_delta" -> d["partial_json"]?.jsonPrimitive?.contentOrNull?.let { (blocks[i] as? ToolAcc)?.input?.append(it) }
                }
            }
            "error" -> error = e["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: data
        }
    }

    fun result(): Msg {
        error?.let { throw LlmException(it) }
        return Msg(Msg.Role.ASSISTANT, blocks.values.mapNotNull { b ->
            when (b) {
                is StringBuilder -> b.toString().takeIf { it.isNotBlank() }?.let { Block.Text(it) }
                is ToolAcc -> Block.ToolUse(b.id, b.name, parseArgs(b.input.toString()))
                else -> null
            }
        })
    }
}

/**
 * Any OpenAI-compatible chat completions server: llama.cpp (llama-server --jinja),
 * Ollama, LM Studio, vLLM, OpenRouter. Streaming with tool calls.
 */
class OpenAiProvider(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String? = null,
    private val http: OkHttpClient = httpFor(baseUrl),
) : LlmProvider {
    override val label = "Lokal · ${model.ifBlank { "Standardmodell" }}"
    /** With no model set: the server's first model that can call tools (llama-swap and Ollama need a name). */
    private var resolved: String? = model.ifBlank { null }

    override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, onText: (String) -> Unit): Msg {
        val m = resolved ?: (runCatching { listModels(baseUrl, http).firstOrNull() }.getOrNull() ?: "default").also { resolved = it }
        val body = buildJsonObject {
            put("model", m)
            put("stream", true)
            putJsonArray("tools") {
                tools.forEach { t ->
                    addJsonObject { put("type", "function"); putJsonObject("function") { put("name", t.name); put("description", t.description); put("parameters", t.schema) } }
                }
            }
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                messages.forEach { m ->
                    if (m.role == Msg.Role.USER) {
                        // tool results are their own messages; plain text is a user message
                        m.blocks.filterIsInstance<Block.ToolResult>().forEach { r ->
                            addJsonObject { put("role", "tool"); put("tool_call_id", r.id); put("content", if (r.isError) "ERROR: ${r.content}" else r.content) }
                        }
                        m.blocks.filterIsInstance<Block.Text>().takeIf { it.isNotEmpty() }?.let { t ->
                            addJsonObject { put("role", "user"); put("content", t.joinToString("\n") { it.text }) }
                        }
                    } else {
                        addJsonObject {
                            put("role", "assistant")
                            put("content", m.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text })
                            val calls = m.blocks.filterIsInstance<Block.ToolUse>()
                            if (calls.isNotEmpty()) putJsonArray("tool_calls") {
                                calls.forEach { c ->
                                    addJsonObject {
                                        put("id", c.id); put("type", "function")
                                        putJsonObject("function") { put("name", c.name); put("arguments", c.input.toString()) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val acc = OpenAiStream(onText)
        postSse(http, baseUrl.trimEnd('/') + "/chat/completions", apiKey?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap(), body, acc::onData)
        return acc.result()
    }

    companion object {
        /** Model ids the server offers (GET /models), those that report tool calling first. */
        suspend fun listModels(baseUrl: String, http: OkHttpClient = httpFor(baseUrl)): List<String> = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(baseUrl.trimEnd('/') + "/models").build()).execute().use { r ->
                if (!r.isSuccessful) throw LlmException("HTTP ${r.code}")
                val data = json.parseToJsonElement(r.body!!.string()).jsonObject["data"]?.jsonArray ?: return@use emptyList()
                data.mapNotNull { e ->
                    val o = e.jsonObject
                    val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val tools = (o["capabilities"] as? JsonObject)?.get("function_calling")?.jsonPrimitive?.contentOrNull == "true"
                    id to tools
                }.sortedByDescending { it.second }.map { it.first }
            }
        }
    }
}

/** Assembles an OpenAI chat-completions stream into one message. */
internal class OpenAiStream(private val onText: (String) -> Unit) {
    private val text = StringBuilder()
    private class Call(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())
    private val calls = sortedMapOf<Int, Call>()

    fun onData(data: String) {
        if (data == "[DONE]") return
        val e = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        e["error"]?.let { throw LlmException((it as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: it.toString()) }
        val delta = (e["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("delta") as? JsonObject ?: return
        (delta["content"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotEmpty()) { text.append(it); onText(it) } }
        (delta["tool_calls"] as? JsonArray)?.forEach { tc ->
            val o = tc.jsonObject
            val c = calls.getOrPut((o["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: calls.size) { Call() }
            (o["id"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotEmpty()) c.id = it }
            (o["function"] as? JsonObject)?.let { f ->
                (f["name"] as? JsonPrimitive)?.contentOrNull?.let { c.name += it }
                (f["arguments"] as? JsonPrimitive)?.contentOrNull?.let { c.args.append(it) }
            }
        }
    }

    fun result(): Msg {
        val blocks = ArrayList<Block>()
        // some local models put their reasoning in <think>…</think>; it is not part of the answer
        val visible = text.toString().replace(Regex("(?s)<think>.*?</think>"), "").trim()
        if (visible.isNotEmpty()) blocks += Block.Text(visible)
        calls.values.forEachIndexed { i, c -> if (c.name.isNotBlank()) blocks += Block.ToolUse(c.id.ifBlank { "call_$i" }, c.name, parseArgs(c.args.toString())) }
        return Msg(Msg.Role.ASSISTANT, blocks)
    }
}

/** Tool arguments as an object; a model that sends broken JSON gets an empty object (the tool then reports what is missing). */
internal fun parseArgs(s: String): JsonObject =
    if (s.isBlank()) JsonObject(emptyMap()) else runCatching { json.parseToJsonElement(s).jsonObject }.getOrDefault(JsonObject(emptyMap()))
