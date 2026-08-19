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
    suspend fun resolve(sourceId: String): ResolvedStream
}
