package dev.schlubbe.musicagent.data.spotify

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What the last import did - shown on the import screen's summary. Kept in a file
 * rather than the worker's output Data, which is capped at 10 KB and would not fit
 * a long "nicht gefunden" list. Holds no credentials. */
data class SpotifyImportSummary(
    val likesTotal: Int = 0,
    val likesAdded: Int = 0,
    val likesAlreadyThere: Int = 0,
    val playlistsTotal: Int = 0,
    val playlistsImported: Int = 0,
    val playlistTracksAdded: Int = 0,
    val artistsTotal: Int = 0,
    val artistsFollowed: Int = 0,
    /** "Titel – Künstler" of every Spotify track without a playable match (unique). */
    val notFound: List<String> = emptyList(),
    val artistsNotFound: List<String> = emptyList(),
    /** Playlists whose tracks Spotify refused to hand out (403/404). */
    val playlistsUnavailable: List<String> = emptyList(),
    val finishedAt: String? = null,
)

/** Pure decisions of the import, kept apart for unit tests. */
object SpotifyImportRules {

    /** Name for a newly imported playlist: the Spotify name, or "<name> (Spotify)"
     * (then "(Spotify 2)", ...) when a Grooveo playlist already uses it, so the
     * user's own playlists are never merged into. */
    fun playlistName(spotifyName: String, existing: Collection<String>): String {
        val taken = existing.map { it.trim().lowercase() }.toSet()
        val base = spotifyName.trim().ifEmpty { "Spotify-Playlist" }
        if (base.lowercase() !in taken) return base
        val suffixed = "$base (Spotify)"
        if (suffixed.lowercase() !in taken) return suffixed
        var n = 2
        while ("$base (Spotify $n)".lowercase() in taken) n++
        return "$base (Spotify $n)"
    }

    fun label(track: SpotifyTrack): String =
        if (track.artists.isEmpty()) track.title else "${track.title} – ${track.artists.joinToString(", ")}"
}

/** Persists [SpotifyImportSummary] and which Grooveo playlist each Spotify playlist
 * was imported into, so running the import again only adds what is new instead of
 * creating duplicates. */
@Singleton
class SpotifyImportStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gson = Gson()
    private val summaryFile get() = File(context.filesDir, "spotify_import_summary.json")
    private val prefs get() = context.getSharedPreferences("spotify_import", Context.MODE_PRIVATE)

    fun saveSummary(summary: SpotifyImportSummary) {
        runCatching { summaryFile.writeText(gson.toJson(summary)) }
    }

    fun loadSummary(): SpotifyImportSummary? =
        runCatching { gson.fromJson(summaryFile.readText(), SpotifyImportSummary::class.java) }.getOrNull()

    fun playlistMap(): Map<String, String> = runCatching {
        val json = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyMap()
        gson.fromJson<Map<String, String>>(json, object : TypeToken<Map<String, String>>() {}.type)
    }.getOrNull() ?: emptyMap()

    fun savePlaylistMap(map: Map<String, String>) {
        prefs.edit().putString(KEY_PLAYLISTS, gson.toJson(map)).apply()
    }

    private companion object {
        const val KEY_PLAYLISTS = "playlist_map"
    }
}
