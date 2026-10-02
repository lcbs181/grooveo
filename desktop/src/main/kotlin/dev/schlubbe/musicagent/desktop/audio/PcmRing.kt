package dev.schlubbe.musicagent.desktop.audio

/**
 * Bounded byte FIFO between a decoder's stdout reader thread (blocking producer)
 * and the mixer (non-blocking consumer). [generation] lets a flush invalidate a
 * chunk the producer is still holding.
 */
class PcmRing(capacityBytes: Int) {
    private val buf = ByteArray(capacityBytes)
    private var readPos = 0
    private var size = 0
    private val lock = Object()
    @Volatile var generation = 0
        private set

    /** Blocks while full. Returns false if the ring was cleared since [gen] (chunk dropped). */
    fun write(src: ByteArray, len: Int, gen: Int): Boolean {
        var off = 0
        synchronized(lock) {
            while (off < len) {
                if (gen != generation) return false
                while (size == buf.size) {
                    lock.wait(50)
                    if (gen != generation) return false
                }
                val writePos = (readPos + size) % buf.size
                val n = minOf(len - off, buf.size - size, buf.size - writePos)
                System.arraycopy(src, off, buf, writePos, n)
                size += n
                off += n
            }
        }
        return true
    }

    fun read(dst: ByteArray, len: Int): Int = synchronized(lock) {
        val n = minOf(len, size)
        var copied = 0
        while (copied < n) {
            val chunk = minOf(n - copied, buf.size - readPos)
            System.arraycopy(buf, readPos, dst, copied, chunk)
            readPos = (readPos + chunk) % buf.size
            copied += chunk
        }
        size -= n
        lock.notifyAll()
        n
    }

    fun size(): Int = synchronized(lock) { size }

    fun clear() = synchronized(lock) {
        readPos = 0
        size = 0
        generation++
        lock.notifyAll()
    }
}
