package dev.schlubbe.musicagent.data.extract

/** A resolved, directly-playable stream location for a track — the on-device
 * equivalent of what the backend's /stream/{source}/{id} endpoint used to hand
 * ExoPlayer, now produced locally by [dev.schlubbe.musicagent.data.extract.youtube.YouTubeStreamResolver]
 * or [dev.schlubbe.musicagent.data.extract.soundcloud.SoundCloudStreamResolver]. */
data class ResolvedStream(
    val url: String,
    // true => play via Media3's native HLS extension, which supports real
    // segment-accurate seeking (unlike the old backend's ffmpeg offset-restart hack).
    val isHls: Boolean,
    val httpHeaders: Map<String, String> = emptyMap(),
)

interface StreamResolver {
    fun supports(source: String): Boolean

    /** [preferProgressive] asks for a single directly-downloadable file over a
     * segmented HLS stream where the source offers both, for callers (downloads)
     * that need a plain resumable byte stream rather than Media3's own HLS
     * handling. Resolvers for a source that never offers HLS (YouTube) simply
     * ignore it. */
    suspend fun resolve(sourceId: String, preferProgressive: Boolean = false): ResolvedStream
}
