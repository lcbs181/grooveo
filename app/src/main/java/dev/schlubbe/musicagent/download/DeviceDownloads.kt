package dev.schlubbe.musicagent.download

/**
 * Finds the owner of a downloaded file again by its name. Downloads are written as
 * "Artist - Title.ext" ([baseName]); after a reinstall (or a backup import) the app's list of
 * downloads is gone but the files are still in Music/ and Download/, so their names are matched
 * against the tracks the app knows (likes, playlists, played tracks).
 */
object DeviceDownloads {
    /** Where [DownloadWorker] puts its files ([MediaStoreWriter]). */
    val FOLDERS = listOf("Music/PrivateMusicAgent/", "Download/PrivateMusicAgent/")

    /** The file name (without extension) a download of this track gets. */
    fun baseName(artist: String?, title: String): String =
        (if (artist.isNullOrBlank()) title else "$artist - $title").replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()

    /** Comparable form of a file name: no extension, no " (1)" Android adds to duplicates, lower case. */
    fun key(fileName: String): String =
        fileName.substringBeforeLast('.', fileName).replace(Regex("""\s*\(\d+\)$"""), "").trim().lowercase()

    /** A track the app knows, with its id ("source:sourceId"). */
    data class Known(val id: String, val artist: String?, val title: String)

    /** Known tracks by [key] of their download file name; the first one wins when names repeat. */
    fun index(known: List<Known>): Map<String, Known> {
        val out = LinkedHashMap<String, Known>()
        for (k in known) out.putIfAbsent(key(baseName(k.artist, k.title) + ".x"), k)
        return out
    }

    /** Artist and title read from a file name "Artist - Title.ext" (artist null when there is no " - "). */
    fun parse(fileName: String): Pair<String?, String> {
        val base = fileName.substringBeforeLast('.', fileName).replace(Regex("""\s*\(\d+\)$"""), "").trim()
        val i = base.indexOf(" - ")
        return if (i > 0) base.substring(0, i).trim() to base.substring(i + 3).trim() else null to base
    }
}
