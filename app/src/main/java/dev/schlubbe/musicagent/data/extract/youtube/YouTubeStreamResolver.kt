package dev.schlubbe.musicagent.data.extract.youtube

import dev.schlubbe.musicagent.data.extract.ResolvedStream
import dev.schlubbe.musicagent.data.extract.StreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import javax.inject.Inject
import javax.inject.Singleton

/** On-device replacement for the (now-removed) backend's resolver.py's
 * `format: bestaudio[ext=m4a]/bestaudio/best` yt-dlp selection — picks the
 * best-quality audio-only stream NewPipeExtractor found, preferring M4A. The
 * chosen stream is a plain progressive/DASH URL (not HLS), so it's already
 * Range-seekable the same way the old backend's YouTube path was. */
@Singleton
class YouTubeStreamResolver @Inject constructor() : StreamResolver {

    override fun supports(source: String): Boolean = source == "ytmusic"

    override suspend fun resolve(sourceId: String): ResolvedStream = withContext(Dispatchers.IO) {
        val watchUrl = "https://www.youtube.com/watch?v=$sourceId"
        val info = StreamInfo.getInfo(ServiceList.YouTube, watchUrl)
        val best = pickBestAudioStream(info.audioStreams)
            ?: error("No audio stream found for ytmusic:$sourceId")
        ResolvedStream(url = best.content, isHls = false)
    }

    private fun pickBestAudioStream(streams: List<AudioStream>): AudioStream? =
        streams
            .sortedWith(
                compareByDescending<AudioStream> { it.format == MediaFormat.M4A }
                    .thenByDescending { it.averageBitrate },
            )
            .firstOrNull()
}
