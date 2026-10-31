package dev.schlubbe.musicagent.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files

class CmafRemuxTest {
    private fun box(type: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).apply { writeInt(payload.size + 8); writeBytes(type); write(payload) }
    }.toByteArray()

    private fun segmentSidx(ept: Long, size: Int, duration: Int): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).apply {
            writeInt(1 shl 24); writeInt(1); writeInt(44100); writeLong(ept); writeLong(0)
            writeShort(0); writeShort(1); writeInt(size); writeInt(duration); writeInt(0x90000000.toInt())
        }
    }.toByteArray()

    private fun topLevel(bytes: ByteArray): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        var i = 0
        while (i < bytes.size) {
            val size = ((bytes[i].toInt() and 0xFF) shl 24) or ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            out += String(bytes, i + 4, 4) to i
            i += size
        }
        return out
    }

    @Test fun `concatenated segments get one sidx covering all of them`() {
        val dir = Files.createTempDirectory("cmaf").toFile()
        val input = File(dir, "in.m4a")
        val parts = ByteArrayOutputStream()
        parts.write(box("ftyp", ByteArray(8)))
        parts.write(box("moov", ByteArray(16)))
        repeat(3) { n ->
            val moof = box("moof", ByteArray(10 + n))
            val mdat = box("mdat", ByteArray(100))
            parts.write(box("styp", ByteArray(8)))
            parts.write(box("sidx", segmentSidx(n * 441344L, moof.size + mdat.size, 441344)))
            parts.write(moof)
            parts.write(mdat)
        }
        input.writeBytes(parts.toByteArray())

        assertTrue(CmafRemux.rewriteInPlace(input))
        val types = topLevel(input.readBytes()).map { it.first }
        assertEquals(listOf("ftyp", "moov", "sidx", "moof", "mdat", "moof", "mdat", "moof", "mdat"), types)
    }

    @Test fun `progressive file is left alone`() {
        val dir = Files.createTempDirectory("cmaf").toFile()
        val input = File(dir, "in.m4a")
        input.writeBytes(box("ftyp", ByteArray(8)) + box("moov", ByteArray(16)) + box("mdat", ByteArray(50)))
        val before = input.readBytes()
        assertFalse(CmafRemux.rewriteInPlace(input))
        assertTrue(before.contentEquals(input.readBytes()))
    }

    @Test fun `real SoundCloud dump`() {
        val path = System.getenv("CMAF_DUMP") ?: return
        assertTrue(CmafRemux.rewrite(File(path), File("$path.fixed.m4a")))
    }
}
