package dev.schlubbe.musicagent.playback.visualizer

/**
 * A single-producer/single-consumer ring of mono float samples, used to hand decoded
 * audio from ExoPlayer's audio output thread to the visualizer's analysis thread.
 *
 * The producer side exists to be *fast and non-blocking*, not to be correct under
 * contention. Whatever runs on an audio output thread has a hard deadline - the buffer
 * has to be handed to the device before it underruns - so that side must never allocate,
 * never take a lock, and never wait. All it does here is copy floats into a preallocated
 * array and publish one index. Everything expensive (the transform, the band reduction,
 * the envelope followers) happens on the consumer side.
 *
 * Overflow is handled by *dropping the oldest* samples rather than blocking the producer
 * or refusing the write. For a visualizer that is the right trade: if analysis has fallen
 * behind, the useful thing to show is the most recent audio, not a backlog of stale audio
 * played out late. A gross lag therefore shows up as a skip, never as an audio glitch.
 *
 * The consequence of a lock-free reader is that a sample can in principle be overwritten
 * between the availability check and the copy, if the producer laps the consumer mid-read.
 * That is accepted deliberately: the worst case is a handful of samples from the wrong
 * position inside one 46ms analysis window, which is invisible in a spectrum, and paying
 * for a lock on the audio thread to prevent it would risk something actually audible.
 */
class PcmRingBuffer(capacity: Int) {

    init {
        require(capacity > 0 && capacity and (capacity - 1) == 0) {
            "capacity must be a power of two, was $capacity"
        }
    }

    private val data = FloatArray(capacity)
    private val mask = (capacity - 1).toLong()

    /** Total samples ever written. Monotonic; wraps via [mask] on indexing. */
    @Volatile
    private var head = 0L

    /** Total samples ever read. */
    @Volatile
    private var tail = 0L

    /** Samples written but not yet read, capped at the ring's capacity. */
    val available: Int
        get() = (head - tail).coerceAtMost(data.size.toLong()).toInt()

    /**
     * Producer side. Copies [count] samples from [src] and publishes them with a single
     * volatile write, rather than one per sample - a store barrier for every sample at
     * 44.1kHz is real cost on the one thread that cannot afford any.
     */
    fun write(src: FloatArray, count: Int) {
        var h = head
        for (i in 0 until count) {
            data[(h and mask).toInt()] = src[i]
            h++
        }
        head = h
    }

    /**
     * Consumer side. Copies up to [max] samples into [dst] and returns how many.
     *
     * If the producer has lapped us, the read position jumps forward to the newest
     * capacity-worth of samples: the backlog is unrecoverable and the recent audio is
     * what matters.
     */
    fun read(dst: FloatArray, max: Int): Int {
        val h = head
        var t = tail
        val capacity = data.size.toLong()
        if (h - t > capacity) t = h - capacity
        val count = minOf(h - t, max.toLong()).toInt()
        for (i in 0 until count) {
            dst[i] = data[(t and mask).toInt()]
            t++
        }
        tail = t
        return count
    }

    /** Consumer side only. Discards everything currently buffered. */
    fun clear() {
        tail = head
    }
}
