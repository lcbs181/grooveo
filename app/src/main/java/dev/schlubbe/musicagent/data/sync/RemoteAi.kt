package dev.schlubbe.musicagent.data.sync

/**
 * The phone's "KI" tab talks to the assistant of the paired PC over the sync connection
 * ([dev.schlubbe.musicagent.data.backup.SyncProtocol.AI_PATH]): the PC runs the model and the
 * tools, the phone shows the conversation and answers its questions. Shared by both apps, sent
 * as JSON (Gson) inside the encrypted sync messages.
 */
object RemoteAi {
    /** How long the PC holds a [Op.STATE] request open while nothing changes. */
    const val LONG_POLL_MS = 20_000L

    /** What the phone asks for; every request is answered with the current [State]. */
    object Op {
        /** Waits until the conversation changed after [Request.since] (or [LONG_POLL_MS] passed). */
        const val STATE = "state"
        const val SEND = "send"
        /** Answer to a confirmation card: [Request.id] and [Request.allow]. */
        const val ANSWER = "answer"
        const val STOP = "stop"
        const val NEW = "new"
        const val UNDO = "undo"
    }

    data class Request(
        val op: String,
        val text: String? = null,
        val id: Long? = null,
        val allow: Boolean? = null,
        val since: Long? = null,
    )

    /**
     * One entry of the conversation. [type] is "user", "reply", "action", "confirm" or "notice";
     * the other fields are set as that type needs them (like the PC's AiItem).
     */
    data class Item(
        val id: Long,
        val type: String,
        val text: String? = null,
        val streaming: Boolean = false,
        /** action: "running", "ok", "failed" or "declined". */
        val status: String? = null,
        val label: String? = null,
        val detail: String? = null,
        /** confirm: "DELETE", "FX", "LOUDNESS" or "OTHER". */
        val kind: String? = null,
        val title: String? = null,
        val answer: Boolean? = null,
        val error: Boolean = false,
    )

    /**
     * The PC's conversation. [rev] grows with every change. [enabled] is false when the user
     * switched off "KI vom Handy" on the PC; [message] then says so.
     */
    data class State(
        val rev: Long = 0,
        val items: List<Item> = emptyList(),
        val running: Boolean = false,
        val canUndo: Boolean = false,
        val undoFor: String? = null,
        val provider: String? = null,
        val enabled: Boolean = true,
        val message: String? = null,
    )

    /** At most this many of the latest items are sent. */
    const val MAX_ITEMS = 120
}
