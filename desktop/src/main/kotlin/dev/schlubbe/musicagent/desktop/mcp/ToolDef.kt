package dev.schlubbe.musicagent.desktop.mcp

import dev.schlubbe.musicagent.desktop.data.gson
import kotlinx.serialization.json.JsonObject

/**
 * One tool an AI can call, shared by the MCP server and the in-app assistant.
 * [properties] is the JSON schema of its arguments; [mutates] tools change something,
 * [destructive] ones delete or clear data.
 */
class ToolDef(
    val name: String,
    val description: String,
    val properties: JsonObject,
    val required: List<String>,
    val mutates: Boolean,
    val destructive: Boolean,
    val openWorld: Boolean,
    private val body: suspend (GrooveoTools.Args) -> Any?,
    /** Checks the arguments without doing anything; throws with a helpful message when they are wrong. */
    private val precheck: ((GrooveoTools.Args) -> Unit)? = null,
) {
    /** Null when [arguments] look valid, else what is wrong (asked before the user is asked to confirm). */
    fun problem(arguments: JsonObject?): String? {
        val args = GrooveoTools.Args(arguments)
        return runCatching {
            required.forEach { require(args.has(it)) { "Missing argument '$it'" } }
            precheck?.invoke(args)
        }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
    }

    /** Runs the tool; returns whether it succeeded and its result (JSON text, or the error message). */
    suspend fun call(arguments: JsonObject?): Pair<Boolean, String> {
        val args = GrooveoTools.Args(arguments)
        val result = runCatching {
            required.forEach { require(args.has(it)) { "Missing argument '$it'" } }
            body(args)
        }
        return result.fold(
            onSuccess = { true to if (it is String) it else gson.toJson(it) },
            onFailure = { false to (it.message ?: it.javaClass.simpleName) },
        )
    }
}
