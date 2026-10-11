package dev.schlubbe.musicagent.desktop.data

import dev.schlubbe.musicagent.data.backup.BackupPayload
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import dev.schlubbe.musicagent.data.backup.SyncProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncTest {
    private fun pc(): Triple<LibraryStore, BackupManager, SyncServer> {
        val dir = Files.createTempDirectory("grooveo-sync").toFile()
        val store = LibraryStore(dir)
        val backup = BackupManager(store, SettingsRepository(java.io.File(dir, "settings.json")), dir)
        return Triple(store, backup, SyncServer(backup, java.io.File(dir, "sync.json"), port = 0, discoveryPort = 0))
    }

    private fun post(port: Int, pin: String, body: String): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$port/sync").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("X-Grooveo-Key", pin)
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        return code to (if (code == 200) c.inputStream else c.errorStream).readBytes().decodeToString()
    }

    @Test fun `phone and pc end up with the union and a second sync changes nothing`() {
        val (store, backup, server) = pc()
        store.toggleLike(track("pc")); store.toggleLike(track("both"))
        server.start()
        try {
            val port = server.boundPortOrDefault
            val phone = BackupPayload(
                createdAt = "2026-10-10T10:00:00Z",
                likes = listOf(track("phone"), track("both")).map { dev.schlubbe.musicagent.data.backup.BackupTrackDto(it.source, it.sourceId, it.title, it.artist, it.album, it.durationSec, it.thumbnailUrl, it.webpageUrl) },
                settings = backup.payload().settings,
            )
            val (code, body) = post(port, server.key, gson.toJson(phone))
            assertEquals(200, code)
            val merged = gson.fromJson(body, BackupPayload::class.java)
            assertEquals(setOf("pc", "phone", "both"), merged.likes.map { it.sourceId }.toSet())
            assertEquals(3, merged.likes.size)
            assertEquals(setOf("pc", "phone", "both"), store.current.likes.map { it.track.sourceId }.toSet())
            // syncing again changes nothing
            post(port, server.key, body)
            assertEquals(3, store.current.likes.size)
        } finally { server.stop() }
    }

    @Test fun `wrong pin is rejected and locks after repeated failures`() {
        val (_, _, server) = pc()
        server.start()
        try {
            val port = server.boundPortOrDefault
            repeat(SyncServer.MAX_FAILURES) { assertEquals(401, post(port, "GRV-WRONG", "{}").first) }
            assertEquals(429, post(port, server.key, "{}").first) // even the right pin is refused now
            assertTrue(server.running)
        } finally { server.stop() }
    }

    @Test fun `key survives a restart and regenerating changes it`() {
        val dir = Files.createTempDirectory("grooveo-sync").toFile()
        val store = LibraryStore(dir)
        val backup = BackupManager(store, SettingsRepository(java.io.File(dir, "settings.json")), dir)
        val f = java.io.File(dir, "sync.json")
        val a = SyncServer(backup, f, port = 0, discoveryPort = 0)
        val key = a.key
        assertTrue(SyncProtocol.normalizeKey(key) == key)
        assertEquals(key, SyncServer(backup, f, port = 0, discoveryPort = 0).key)
        a.regenerateKey()
        assertTrue(a.key != key)
        assertEquals(a.key, SyncServer(backup, f, port = 0, discoveryPort = 0).key)
    }

    @Test fun `server answers a discovery request with its id only`() {
        val (_, _, server) = pc()
        server.start()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 3000
                val req = SyncProtocol.DISCOVERY_REQUEST.toByteArray()
                s.send(DatagramPacket(req, req.size, InetAddress.getLoopbackAddress(), server.discoveryPortBound))
                val buf = ByteArray(256)
                val p = DatagramPacket(buf, buf.size); s.receive(p)
                val text = String(p.data, 0, p.length)
                assertEquals(server.boundPortOrDefault, SyncProtocol.parseDiscoveryReply(text, server.deviceId))
                assertTrue(server.key !in text)
            }
        } finally { server.stop() }
    }
}
