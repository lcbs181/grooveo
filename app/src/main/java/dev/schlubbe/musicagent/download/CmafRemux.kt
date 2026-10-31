package dev.schlubbe.musicagent.download

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Turns a concatenated fMP4/CMAF HLS download into a file every player reads the
 * full length of.
 *
 * SoundCloud's HLS segments each carry their own `styp` + `sidx` + `moof` + `mdat`.
 * Glued together as-is, the file has one `sidx` per ~10 s segment. ExoPlayer only
 * reads the first `sidx` as the seek map, so the player showed a short duration,
 * could not seek past it, and only learned the real length when it reached EOF.
 *
 * [rewrite] drops every `styp` and per-segment `sidx` and writes one `sidx` after
 * the `moov` that indexes every segment, the layout a plain single-file CMAF has.
 */
object CmafRemux {
    private class Box(val type: String, val offset: Long, val size: Long)

    private class Segment(
        val referenceId: Int,
        val timescale: Long,
        val earliestPts: Long,
        val duration: Long,
        val sapWord: Int,
        val boxes: MutableList<Box> = mutableListOf(),
    ) {
        val size get() = boxes.sumOf { it.size }
    }

    /** Rewrites [input] into [output]. Returns false (and writes nothing) when
     * [input] is not a multi-segment fragmented file, e.g. a progressive download. */
    fun rewrite(input: File, output: File): Boolean {
        RandomAccessFile(input, "r").use { file ->
            val boxes = topLevelBoxes(file) ?: return false
            val head = mutableListOf<Box>()
            val segments = mutableListOf<Segment>()
            for (box in boxes) {
                when {
                    box.type == "styp" -> Unit
                    box.type == "sidx" -> segments += readSidx(file, box) ?: return false
                    segments.isEmpty() -> {
                        // a fragment before any sidx: not the layout this fixes
                        if (box.type == "moof" || box.type == "mdat") return false
                        head += box
                    }
                    else -> segments.last().boxes += box
                }
            }
            if (segments.size < 2 || head.none { it.type == "moov" }) return false
            if (segments.map { it.timescale }.distinct().size != 1) return false
            if (segments.any { it.size > 0x7FFF_FFFFL || it.duration > 0xFFFF_FFFFL } || segments.size > 0xFFFF) return false

            output.outputStream().buffered().use { out ->
                head.forEach { copy(file, it, out) }
                out.write(sidx(segments))
                segments.forEach { segment -> segment.boxes.forEach { copy(file, it, out) } }
            }
            return true
        }
    }

    /** Same as [rewrite], in place. Returns true when [file] was changed. */
    fun rewriteInPlace(file: File): Boolean {
        val tmp = File(file.parentFile, file.name + ".remux")
        return try {
            if (!rewrite(file, tmp)) return false
            if (!file.delete() || !tmp.renameTo(file)) error("Datei konnte nicht ersetzt werden")
            true
        } finally {
            tmp.delete()
        }
    }

    private fun topLevelBoxes(file: RandomAccessFile): List<Box>? {
        val boxes = mutableListOf<Box>()
        val length = file.length()
        var offset = 0L
        val header = ByteArray(8)
        while (offset + 8 <= length) {
            file.seek(offset)
            file.readFully(header)
            var size = u32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) size = file.readLong()
            if (size == 0L) size = length - offset
            if (size < 8 || offset + size > length) return null
            boxes += Box(type, offset, size)
            offset += size
        }
        return boxes.takeIf { offset == length }
    }

    private fun readSidx(file: RandomAccessFile, box: Box): Segment? {
        file.seek(box.offset + 8)
        val version = file.readUnsignedByte()
        file.skipBytes(3)
        val referenceId = file.readInt()
        val timescale = file.readInt().toLong() and 0xFFFF_FFFFL
        val earliestPts: Long
        if (version == 0) {
            earliestPts = file.readInt().toLong() and 0xFFFF_FFFFL
            file.skipBytes(4)
        } else {
            earliestPts = file.readLong()
            file.skipBytes(8)
        }
        file.skipBytes(2)
        val count = file.readUnsignedShort()
        // a sidx pointing at further sidx boxes (hierarchical index) is not handled
        if (count != 1) return null
        val typeAndSize = file.readInt()
        if (typeAndSize < 0) return null
        val duration = file.readInt().toLong() and 0xFFFF_FFFFL
        val sapWord = file.readInt()
        return Segment(referenceId, timescale, earliestPts, duration, sapWord)
    }

    private fun sidx(segments: List<Segment>): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).apply {
            writeInt(1 shl 24) // version 1, flags 0
            writeInt(segments.first().referenceId)
            writeInt(segments.first().timescale.toInt())
            writeLong(segments.first().earliestPts)
            writeLong(0) // first_offset: segments start right after this box
            writeShort(0)
            writeShort(segments.size)
            for (segment in segments) {
                writeInt(segment.size.toInt())
                writeInt(segment.duration.toInt())
                writeInt(segment.sapWord)
            }
        }
        val bytes = body.toByteArray()
        return ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(bytes.size + 8)
                writeBytes("sidx")
                write(bytes)
            }
        }.toByteArray()
    }

    private fun copy(file: RandomAccessFile, box: Box, out: java.io.OutputStream) {
        file.seek(box.offset)
        val buffer = ByteArray(64 * 1024)
        var left = box.size
        while (left > 0) {
            val n = file.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n <= 0) error("Unerwartetes Dateiende")
            out.write(buffer, 0, n)
            left -= n
        }
    }

    private fun u32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)
}
