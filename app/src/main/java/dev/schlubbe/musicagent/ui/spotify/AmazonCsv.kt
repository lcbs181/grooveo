package dev.schlubbe.musicagent.ui.spotify

/** Writes Amazon Music playlists as the TuneMyMusic-style CSV that
 * [dev.schlubbe.musicagent.data.spotify.SpotifyExport] already reads, so the
 * Amazon import runs through the same matching and import as Spotify's.
 * "Meine Likes" / "My Likes" end up as liked songs there. */
object AmazonCsv {
    fun write(playlists: List<AmazonPlaylist>): String = buildString {
        append("Track name,Artist name,Album,Playlist name\n")
        for (p in playlists) for (t in p.tracks) {
            append(field(t.title)).append(',')
            append(field(t.artists.joinToString(", ") { it.replace(",", "\\,") })).append(',')
            append(field(t.album.orEmpty())).append(',')
            append(field(p.name)).append('\n')
        }
    }

    private fun field(value: String): String {
        val clean = value.replace('\r', ' ').replace('\n', ' ')
        return if (clean.any { it == ',' || it == '"' || it == ';' }) "\"" + clean.replace("\"", "\"\"") + "\"" else clean
    }
}
