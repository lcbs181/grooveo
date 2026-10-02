package dev.schlubbe.musicagent.desktop.audio

/** Something playable: a resolved stream URL or local file, plus request headers. */
data class AudioSource(
    val id: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val startSec: Double = 0.0,
)

/**
 * One decoder "deck". The engine mixes up to two decks (for crossfades and gapless
 * hand-over). Output is 48 kHz interleaved stereo float, pulled by the mixer.
 */
interface Deck {
    /** Loads [source] and starts decoding immediately. Discards any buffered audio. */
    fun load(source: AudioSource)
    fun seek(sec: Double)
    /** Stops decoding and discards buffered audio. */
    fun stop()
    /** Copies up to [frames] frames into [out] (interleaved L/R); returns frames copied. Never blocks. */
    fun read(out: FloatArray, frames: Int): Int
    /** Frames ready to read without blocking. */
    fun available(): Int
    /** True once the decoder finished the file and all its audio was read. */
    val ended: Boolean
    /** Non-null when loading/decoding failed. */
    val error: String?
    val source: AudioSource?
    /** Playback position of the next frame [read] returns, in seconds. */
    val positionSec: Double
    val durationSec: Double?
    fun close()
}
