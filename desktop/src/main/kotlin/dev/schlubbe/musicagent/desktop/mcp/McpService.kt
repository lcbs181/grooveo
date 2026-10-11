package dev.schlubbe.musicagent.desktop.mcp

import dev.schlubbe.musicagent.desktop.AppGraph
import dev.schlubbe.musicagent.desktop.ai.tightenSecretFile
import dev.schlubbe.musicagent.desktop.ai.writeSecretFile
import dev.schlubbe.musicagent.desktop.data.gson
import dev.schlubbe.musicagent.desktop.data.readJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Persistent MCP state: [token] stays the same across restarts, [enabled] is what the user switched on. */
data class McpState(val enabled: Boolean = false, val token: String = "", val readOnly: Boolean = false)

/** One tool call by the AI, for the activity list in the settings. */
data class McpActivity(val at: Long, val tool: String, val ok: Boolean, val summary: String)

/**
 * "KI-Steuerung (MCP)": a Model Context Protocol server inside the running app, so an AI client
 * (Claude Code, Claude Desktop, ...) can control the player, queue, library, EQ and every setting
 * through the tools in [GrooveoTools]. Streamable HTTP on 127.0.0.1 only, with a bearer token and
 * the SDK's DNS-rebinding protection (Host/Origin must be localhost). Read-only mode hides every
 * tool that changes something.
 */
class McpService(private val graph: AppGraph, private val stateFile: File, private val port: Int = DEFAULT_PORT) {
    private var server: EmbeddedServer<*, *>? = null
    private var state: McpState = (readJson<McpState>(stateFile, McpState::class.java) ?: McpState()).let {
        tightenSecretFile(stateFile)
        if (it.token.length < 32) it.copy(token = newToken()).also(::save) else it
    }
    private val _activity = MutableStateFlow<List<McpActivity>>(emptyList())
    /** Most recent tool calls first. */
    val activity: StateFlow<List<McpActivity>> = _activity.asStateFlow()

    val running: Boolean get() = server != null
    val enabled: Boolean get() = state.enabled
    val readOnly: Boolean get() = state.readOnly
    val token: String get() = state.token
    var boundPort: Int = port
        private set
    val url: String get() = "http://127.0.0.1:$boundPort/mcp"

    /** Command that registers Grooveo in Claude Code. */
    val claudeCodeCommand: String get() = "claude mcp add --transport http grooveo $url --header \"Authorization: Bearer $token\""

    /** The file holds the access token: readable by the user only. */
    private fun save(s: McpState) { state = s; writeSecretFile(stateFile, gson.toJson(s)) }

    @Synchronized fun start() {
        if (server != null) return
        val tools = GrooveoTools(graph, readOnly = state.readOnly, log = ::record)
        val expected = "Bearer ${state.token}".toByteArray()
        val s = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            // every request must carry the token, also the SDK's own GET/DELETE on /mcp
            intercept(ApplicationCallPipeline.Plugins) {
                val given = call.request.headers["Authorization"].orEmpty().toByteArray()
                if (!MessageDigest.isEqual(given, expected)) {
                    call.respond(HttpStatusCode.Unauthorized, "Grooveo: missing or wrong token")
                    finish()
                }
            }
            mcpStreamableHttp(enableDnsRebindingProtection = true) { newServer(tools) }
        }
        s.start(wait = false)
        boundPort = kotlinx.coroutines.runBlocking { s.engine.resolvedConnectors().first().port }
        server = s
        if (!state.enabled) save(state.copy(enabled = true))
    }

    /** Switches the server off for good (until the user switches it on again). */
    @Synchronized fun stop() {
        shutdown()
        if (state.enabled) save(state.copy(enabled = false))
    }

    /** Stops the server without changing what the user chose (app exit). */
    @Synchronized fun shutdown() {
        server?.stop(200, 1000)
        server = null
    }

    @Synchronized fun setReadOnly(on: Boolean) {
        save(state.copy(readOnly = on))
        if (running) { shutdown(); start() }
    }

    /** New token: every AI client set up before has to be updated. */
    @Synchronized fun regenerateToken() {
        save(state.copy(token = newToken()))
        if (running) { shutdown(); start() }
    }

    private fun newServer(tools: GrooveoTools): Server = Server(
        serverInfo = Implementation(name = "grooveo", version = dev.schlubbe.musicagent.desktop.APP_VERSION),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
                prompts = ServerCapabilities.Prompts(listChanged = false),
                resources = ServerCapabilities.Resources(listChanged = false, subscribe = false),
            ),
        ),
        instructions = GrooveoTools.INSTRUCTIONS,
    ).also(tools::register)

    private fun record(a: McpActivity) = _activity.update { (listOf(a) + it).take(50) }

    companion object {
        const val DEFAULT_PORT = 47830
        fun newToken(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
}
