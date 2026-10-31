package dev.schlubbe.musicagent.data.spotify

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** A Spotify track as an export file describes it - only what matching needs. */
data class SpotifyTrack(
    /** Spotify's track id when the file has it, otherwise a key built from title
     * and artists - only used to search each song once. */
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long?,
    /** When it was liked / added to the playlist, if the file says. */
    val addedAt: String? = null,
)

data class SpotifyPlaylist(val id: String, val name: String)

data class SpotifyArtist(val name: String)

data class SpotifyLibrary(
    val likes: List<SpotifyTrack>,
    val playlists: List<Pair<SpotifyPlaylist, List<SpotifyTrack>>>,
    val artists: List<SpotifyArtist>,
) {
    val isEmpty get() = likes.isEmpty() && playlists.all { it.second.isEmpty() } && artists.isEmpty()
}

/** Reads a Spotify library from the files people can actually get out of Spotify:
 *
 * - Exportify's "Export all" ZIP (one CSV per playlist, `liked.csv` for the liked
 *   songs) or a single Exportify CSV - headers in any of its languages,
 * - a TuneMyMusic CSV (one file, a "Playlist name" column),
 * - Spotify's own "Download your data" ZIP (`YourLibrary.json`, `Playlist*.json`).
 *
 * Pure - no Android, no network. Unknown columns and files are skipped. */
object SpotifyExport {

    private const val MAX_ENTRY_BYTES = 40L * 1024 * 1024
    private val LIKED_NAMES = setOf("liked", "liked songs", "lieblingssongs", "favorites", "favourites", "favorite songs", "saved tracks")

    fun read(fileName: String?, input: InputStream): SpotifyLibrary {
        val bytes = input.readBytes()
        val builder = Builder()
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast('/')
                    if (!wanted(name)) continue
                    val text = readLimited(zip) ?: continue
                    builder.addFile(name, text)
                }
            }
        } else {
            builder.addFile(fileName ?: "export.csv", String(bytes, Charsets.UTF_8))
        }
        return builder.build()
    }

    private fun wanted(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".csv") || n == "yourlibrary.json" || Regex("playlist\\d*\\.json").matches(n)
    }

    private fun readLimited(input: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private class Builder {
        val likes = ArrayList<SpotifyTrack>()
        val playlists = LinkedHashMap<String, Pair<SpotifyPlaylist, MutableList<SpotifyTrack>>>()
        val artists = ArrayList<SpotifyArtist>()

        fun addFile(name: String, text: String) {
            val body = text.removePrefix("﻿")
            val lower = name.lowercase()
            when {
                lower.endsWith(".json") || body.trimStart().startsWith("{") -> addJson(body)
                lower.startsWith("saved_albums") -> Unit // albums, not songs
                else -> addCsv(name, body)
            }
        }

        fun playlist(name: String): MutableList<SpotifyTrack> {
            val clean = name.trim().ifEmpty { "Spotify-Playlist" }
            val key = clean.lowercase()
            return playlists.getOrPut(key) { SpotifyPlaylist("name:$key", clean) to ArrayList() }.second
        }

        fun addCsv(fileName: String, text: String) {
            val rows = Csv.parse(text)
            if (rows.size < 2) return
            val cols = Columns.detect(rows[0]) ?: return
            val fileList = fileName.substringAfterLast('/').substringBeforeLast('.')
            val fileIsLikes = fileList.lowercase().replace('_', ' ') in LIKED_NAMES
            for (row in rows.drop(1)) {
                val track = cols.track(row) ?: continue
                val listName = cols.playlist?.let { row.getOrNull(it) }?.trim()
                when {
                    listName != null && listName.isNotEmpty() ->
                        if (listName.lowercase() in LIKED_NAMES) likes += track else playlist(listName) += track
                    fileIsLikes -> likes += track
                    else -> playlist(prettyFileName(fileList)) += track
                }
            }
        }

        fun addJson(text: String) {
            val root = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return
            // YourLibrary.json
            root.array("tracks").forEach { el ->
                val o = el.obj() ?: return@forEach
                val title = o.str("track") ?: return@forEach
                val artist = o.str("artist")
                likes += SpotifyTrack(idFrom(o.str("uri"), title, listOfNotNull(artist)), title, listOfNotNull(artist), o.str("album"), null)
            }
            root.array("artists").forEach { el ->
                el.obj()?.str("name")?.let { artists += SpotifyArtist(it) }
            }
            // Playlist1.json, Playlist2.json, ...
            root.array("playlists").forEach { el ->
                val p = el.obj() ?: return@forEach
                val list = playlist(p.str("name") ?: "Spotify-Playlist")
                p.array("items").forEach { itemEl ->
                    val item = itemEl.obj() ?: return@forEach
                    val t = item.objOrNull("track") ?: return@forEach
                    val title = t.str("trackName") ?: return@forEach
                    val artist = t.str("artistName")
                    list += SpotifyTrack(
                        idFrom(t.str("trackUri"), title, listOfNotNull(artist)),
                        title, listOfNotNull(artist), t.str("albumName"), null, item.str("addedDate"),
                    )
                }
            }
        }

        fun build(): SpotifyLibrary {
            val distinctLikes = likes.distinctBy { it.id }
            val lists = playlists.values.filter { it.second.isNotEmpty() }.map { it.first to it.second.distinctBy { t -> t.id } }
            val followed = artists.distinctBy { it.name.lowercase() }.ifEmpty { guessArtists(distinctLikes, lists) }
            return SpotifyLibrary(distinctLikes, lists, followed)
        }
    }

    /** Exports without followed artists (Exportify, TuneMyMusic): the artists that
     * show up again and again in the library stand in for them. */
    internal fun guessArtists(likes: List<SpotifyTrack>, playlists: List<Pair<SpotifyPlaylist, List<SpotifyTrack>>>): List<SpotifyArtist> {
        val tracks = (likes + playlists.flatMap { it.second }).distinctBy { it.id }
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (t in tracks) {
            val name = t.artists.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val key = name.lowercase()
            counts[key] = name to ((counts[key]?.second ?: 0) + 1)
        }
        return counts.values.filter { it.second >= 2 }
            .sortedByDescending { it.second }
            .take(30)
            .map { SpotifyArtist(it.first) }
    }

    /** Exportify writes `my_chill_mix.csv` for "My Chill Mix". */
    internal fun prettyFileName(base: String): String {
        val words = base.replace(Regex("\\s*\\(\\d+\\)$"), "").split('_', ' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return "Spotify-Playlist"
        return words.joinToString(" ").replaceFirstChar { it.uppercase() }
    }

    internal fun idFrom(uri: String?, title: String, artists: List<String>): String {
        val fromUri = uri?.trim()?.substringAfterLast(':')?.takeIf { it.length in 10..40 && it.all(Char::isLetterOrDigit) }
        return fromUri ?: ("t:" + title.lowercase().trim() + "|" + artists.joinToString(",") { it.lowercase().trim() })
    }

    /** Which CSV column holds what - matched on the header names Exportify (in its
     * languages) and TuneMyMusic use. */
    internal class Columns(
        val title: Int,
        val artist: Int?,
        val album: Int?,
        val duration: Int?,
        val added: Int?,
        val uri: Int?,
        val playlist: Int?,
    ) {
        fun track(row: List<String>): SpotifyTrack? {
            val title = row.getOrNull(title)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val artists = artist?.let { row.getOrNull(it) }?.let(::splitArtists).orEmpty()
            val uri = uri?.let { row.getOrNull(it) }
            if (uri != null && (uri.startsWith("spotify:local:") || uri.startsWith("spotify:episode:"))) return null
            return SpotifyTrack(
                id = idFrom(uri, title, artists),
                title = title,
                artists = artists,
                album = album?.let { row.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() },
                durationMs = duration?.let { row.getOrNull(it) }?.trim()?.toLongOrNull()?.takeIf { it > 0 },
                addedAt = added?.let { row.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        companion object {
            fun detect(header: List<String>): Columns? {
                val h = header.map { it.trim().lowercase().replace('_', ' ').replace('-', ' ') }
                fun find(vararg test: (String) -> Boolean) = test.firstNotNullOfOrNull { t -> h.indexOfFirst(t).takeIf { it >= 0 } }
                val isAlbumArtist = { s: String -> "album" in s }
                val title = find(
                    { it == "track name" || it == "trackname" || it == "song name" },
                    { it == "title" || it == "titel" || it == "song" || it == "name" || it == "track" },
                    { "track name" in it || "titel" in it || "nombre de la canción" in it || "nom du titre" in it },
                ) ?: return null
                val artist = find(
                    { (it.startsWith("artist name") || it.startsWith("künstlername")) && !isAlbumArtist(it) },
                    { (it == "artist" || it == "artists" || it == "künstler" || it == "interpret") },
                    { ("artist" in it || "künstler" in it || "artiste" in it || "artista" in it) && "uri" !in it && !isAlbumArtist(it) && "genre" !in it },
                )
                val album = find(
                    { it == "album name" || it == "album" },
                    { "album" in it && ("name" in it || "nom" in it || "nombre" in it) && "artist" !in it && "künstler" !in it },
                )
                val duration = find({ "duration" in it || "dauer" in it || "durée" in it || "duración" in it })
                val added = find({ it == "added at" || it == "hinzugefügt am" || it == "added date" || it == "date added" || it == "ajouté le" })
                val uri = find(
                    { it == "track uri" || it == "spotify   id" || it == "spotify id" || it == "spotify uri" },
                    { it.startsWith("track") && "uri" in it },
                )
                val playlist = find({ it == "playlist name" || it == "playlist" || it == "playlistname" })
                return Columns(title, artist, album, duration, added, uri, playlist)
            }

            /** Exportify joins several artists with ", " and escapes commas inside a
             * name as "\,". */
            fun splitArtists(raw: String): List<String> =
                raw.split(Regex("(?<!\\\\),\\s*"))
                    .map { it.replace("\\,", ",").trim() }
                    .filter { it.isNotEmpty() }
        }
    }

    /** RFC 4180 CSV with the delimiter (comma, semicolon or tab) picked from the
     * header line. Quoted fields may contain delimiters, quotes ("") and newlines. */
    internal object Csv {
        fun parse(text: String): List<List<String>> {
            val delimiter = delimiterOf(text)
            val rows = ArrayList<List<String>>()
            var row = ArrayList<String>()
            val field = StringBuilder()
            var quoted = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                    } else {
                        field.append(c)
                    }
                } else when (c) {
                    '"' -> quoted = true
                    delimiter -> { row += field.toString(); field.clear() }
                    '\r' -> Unit
                    '\n' -> {
                        row += field.toString(); field.clear()
                        if (row.any { it.isNotEmpty() }) rows += row
                        row = ArrayList()
                    }
                    else -> field.append(c)
                }
                i++
            }
            row += field.toString()
            if (row.any { it.isNotEmpty() }) rows += row
            return rows
        }

        private fun delimiterOf(text: String): Char {
            val header = StringBuilder()
            var quoted = false
            for (c in text) {
                if (c == '"') quoted = !quoted
                if (c == '\n' && !quoted) break
                if (!quoted) header.append(c)
            }
            return listOf(',', ';', '\t').maxBy { d -> header.count { it == d } }
        }
    }

    private fun JsonElement.obj(): JsonObject? = if (isJsonObject) asJsonObject else null
    private fun JsonObject.objOrNull(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.array(key: String): List<JsonElement> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList()
    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
