package dev.schlubbe.musicagent.data.backup

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Removals on this phone (unlike, unfollow, a deleted playlist or playlist track) for the
 * PC sync: they travel with the next sync, so the PC removes the same items instead of
 * bringing them back (see [SyncMerge]).
 */
@Singleton
class SyncTombstoneStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("sync_tombstones", Context.MODE_PRIVATE)
    private val playlistSyncIds = context.getSharedPreferences("sync_playlist_ids", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<List<SyncTombstone>>() {}.type

    @Synchronized
    fun all(): List<SyncTombstone> =
        runCatching { gson.fromJson<List<SyncTombstone>>(prefs.getString(KEY, null) ?: "[]", type) }.getOrNull().orEmpty()

    @Synchronized
    fun replace(list: List<SyncTombstone>) {
        prefs.edit().putString(KEY, gson.toJson(SyncMerge.mergeTombstones(list))).apply()
    }

    fun add(kind: String, key: String) = replace(all() + SyncTombstone(kind, key, System.currentTimeMillis()))

    /** The sync key of a local playlist (see BackupManager's sync ids). */
    fun playlistKey(localId: String): String = playlistSyncIds.getString(localId, null) ?: localId

    private companion object {
        const val KEY = "list"
    }
}
