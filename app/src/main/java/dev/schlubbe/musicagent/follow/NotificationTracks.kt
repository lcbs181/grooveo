package dev.schlubbe.musicagent.follow

import android.content.Context
import com.google.gson.Gson
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import java.security.SecureRandom

/**
 * Tracks behind "Neu von …" notifications. MainActivity is exported, so a track
 * inside the launch intent itself could be sent by any app. The intent carries a
 * random ticket instead, and only tickets this app handed out play anything.
 */
object NotificationTracks {
    private const val PREFS = "notification_tracks"
    private const val MAX = 50
    private val random = SecureRandom()

    fun ticketFor(context: Context, track: TrackResultDto): String {
        val ticket = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        // keep the store small: drop the oldest tickets
        val all = prefs.all.entries.sortedBy { (it.value as? String)?.substringBefore('|')?.toLongOrNull() ?: 0 }
        all.take((all.size - MAX + 1).coerceAtLeast(0)).forEach { edit.remove(it.key) }
        edit.putString(ticket, "${System.currentTimeMillis()}|${Gson().toJson(track)}").apply()
        return ticket
    }

    /** The track for [ticket], once: the ticket is used up. */
    fun redeem(context: Context, ticket: String): TrackResultDto? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(ticket, null) ?: return null
        prefs.edit().remove(ticket).apply()
        return runCatching { Gson().fromJson(stored.substringAfter('|'), TrackResultDto::class.java) }.getOrNull()
    }
}
