package dev.schlubbe.musicagent.desktop.ai

import dev.schlubbe.musicagent.data.sync.RemoteAi
import dev.schlubbe.musicagent.desktop.data.gson
import java.io.File

/** A saved chat as the chat list shows it. */
data class AiChat(val id: String, val title: String, val updatedAt: Long)

/**
 * The assistant's saved chats: one JSON file per chat in [dir], holding what the panel shows
 * (as [RemoteAi.Item], the same form the phone gets). The model's own history is not stored:
 * a chat opened again continues from its messages and replies ([historyOf]).
 */
internal class AiChatStore(private val dir: File, private val max: Int = MAX_CHATS) {
    class Saved(val id: String, val title: String, val updatedAt: Long, val items: List<RemoteAi.Item>)

    private val lock = Any()
    private var cache: List<AiChat>? = null

    fun list(): List<AiChat> = synchronized(lock) {
        cache ?: (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> read(f)?.let { AiChat(it.id, it.title, it.updatedAt) } }
            .sortedByDescending { it.updatedAt })
            .also { cache = it }
    }

    fun load(id: String): Saved? = synchronized(lock) { fileOf(id)?.let(::read) }

    fun save(id: String, title: String, items: List<RemoteAi.Item>, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val f = fileOf(id) ?: return
        dir.mkdirs()
        val tmp = File(dir, "${f.name}.tmp")
        tmp.writeText(gson.toJson(Saved(id, title, now, items)))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        cache = null
        // the oldest go once there are too many
        list().drop(max).forEach { old -> fileOf(old.id)?.delete() }
        cache = null
    }

    fun delete(id: String) = synchronized(lock) {
        fileOf(id)?.delete()
        cache = null
    }

    private fun fileOf(id: String): File? = id.takeIf { ID.matches(it) }?.let { File(dir, "$it.json") }

    private fun read(f: File): Saved? = runCatching { gson.fromJson(f.readText(), Saved::class.java) }.getOrNull()
        ?.takeIf { it.id != null && it.title != null && it.items != null }

    companion object {
        const val MAX_CHATS = 100
        private val ID = Regex("[A-Za-z0-9-]{1,64}")

        /** A chat's title: its first message, shortened. */
        fun titleOf(firstMessage: String): String =
            firstMessage.replace(Regex("\\s+"), " ").trim().let { if (it.length > 60) it.take(60).trimEnd() + "…" else it }

        /** The model's history for a chat opened again: the user's messages and the replies, as text. */
        fun historyOf(items: List<AiItem>): ArrayList<Msg> {
            val h = ArrayList<Msg>()
            for (i in items) {
                val (role, text) = when (i) {
                    is AiItem.User -> Msg.Role.USER to i.text
                    is AiItem.Reply -> Msg.Role.ASSISTANT to i.text
                    else -> continue
                }
                if (text.isBlank()) continue
                val last = h.lastOrNull()
                if (last?.role == role) h[h.lastIndex] = last.copy(blocks = last.blocks + Block.Text(text))
                else h += Msg(role, listOf(Block.Text(text)))
            }
            // the next turn adds the user's text: the history has to end on a reply
            if (h.lastOrNull()?.role == Msg.Role.USER) h.removeAt(h.lastIndex)
            return h
        }
    }
}

/** An item as the phone and the chat files carry it. */
internal fun AiItem.toWire(): RemoteAi.Item = when (this) {
    is AiItem.User -> RemoteAi.Item(id, "user", text = text)
    is AiItem.Reply -> RemoteAi.Item(id, "reply", text = text, streaming = streaming)
    is AiItem.Action -> RemoteAi.Item(id, "action", status = status.name.lowercase(), label = label, detail = detail)
    is AiItem.Confirm -> RemoteAi.Item(id, "confirm", kind = kind.name, title = title, detail = detail, answer = answer)
    is AiItem.Notice -> RemoteAi.Item(id, "notice", text = text, error = error)
}

/** The item back from a chat file; null for what cannot be read. Nothing in it is still running. */
internal fun RemoteAi.Item.toAiItem(): AiItem? = runCatching {
    when (type) {
        "user" -> AiItem.User(id, text.orEmpty())
        "reply" -> AiItem.Reply(id, text.orEmpty(), streaming = false)
        "action" -> AiItem.Action(id, "", label.orEmpty(),
            AiItem.Action.Status.valueOf(status.orEmpty().uppercase()).let { if (it == AiItem.Action.Status.RUNNING) AiItem.Action.Status.FAILED else it }, detail)
        // an unanswered question of an earlier session can no longer be answered
        "confirm" -> AiItem.Confirm(id, AiPolicy.Kind.valueOf(kind.orEmpty()), title.orEmpty(), detail.orEmpty(), answer ?: false)
        "notice" -> AiItem.Notice(id, text.orEmpty(), error)
        else -> null
    }
}.getOrNull()
