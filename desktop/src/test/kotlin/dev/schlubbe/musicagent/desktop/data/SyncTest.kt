package dev.schlubbe.musicagent.desktop.data

import dev.schlubbe.musicagent.data.backup.BackupPayload
import dev.schlubbe.musicagent.data.backup.BackupPlaylistDto
import dev.schlubbe.musicagent.data.backup.BackupTrackDto
import dev.schlubbe.musicagent.data.backup.SyncMerge
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import dev.schlubbe.musicagent.data.backup.SyncProtocol
import dev.schlubbe.musicagent.data.backup.SyncTransport
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {
    private fun pc(): Triple<LibraryStore, BackupManager, SyncServer> {
        val dir = Files.createTempDirectory("grooveo-sync").toFile()
        val store = LibraryStore(dir)
        val backup = BackupManager(store, SettingsRepository(java.io.File(dir, "settings.json")), dir)
        return Triple(store, backup, SyncServer(backup, java.io.File(dir, "sync.json"), port = 0, discoveryPort = 0))
    }

    private fun dto(t: TrackResultDto, likedAt: String? = null) =
        BackupTrackDto(t.source, t.sourceId, t.title, t.artist, t.album, t.durationSec, t.thumbnailUrl, t.webpageUrl, likedAt)

    private fun peer(server: SyncServer) = SyncTransport.Peer("127.0.0.1", server.boundPortOrDefault, 0)

    private fun send(server: SyncServer, keys: SyncProtocol.Keys, payload: BackupPayload): SyncTransport.Answer =
        SyncTransport.sync(peer(server), keys, gson.toJson(payload).toByteArray(), System.currentTimeMillis() + 10_000)

    /** A raw request that comes from [from] (any 127.x address works on loopback). */
    private fun rawStatus(server: SyncServer, from: String, request: String): Int = Socket().use { s ->
        s.bind(InetSocketAddress(from, 0))
        s.connect(InetSocketAddress("127.0.0.1", server.boundPortOrDefault), 2000)
        s.soTimeout = 5000
        s.getOutputStream().write(request.toByteArray())
        s.getInputStream().readBytes().decodeToString().lineSequence().first().split(' ')[1].toInt()
    }

    private fun hello(nonce: String) =
        "GET /sync/hello HTTP/1.1\r\nHost: x\r\n${SyncProtocol.VERSION_HEADER}: ${SyncProtocol.VERSION}\r\n${SyncProtocol.NONCE_HEADER}: $nonce\r\nConnection: close\r\n\r\n"

    @Test fun `phone and pc end up with the union and a second sync changes nothing`() {
        val (store, backup, server) = pc()
        store.toggleLike(track("pc")); store.toggleLike(track("both"))
        server.start()
        try {
            val keys = SyncProtocol.keys(server.key)
            val phone = BackupPayload(
                createdAt = "2026-10-10T10:00:00Z",
                likes = listOf(dto(track("phone"), "2025-01-02T03:04:05Z"), dto(track("both"))),
                settings = backup.payload().settings,
            )
            val a = send(server, keys, phone)
            assertEquals(200, a.status)
            val merged = gson.fromJson(a.json!!.decodeToString(), BackupPayload::class.java)
            assertEquals(setOf("pc", "phone", "both"), merged.likes.map { it.sourceId }.toSet())
            assertEquals(3, merged.likes.size)
            assertEquals(setOf("pc", "phone", "both"), store.current.likes.map { it.track.sourceId }.toSet())
            // the like keeps its date from the phone
            assertEquals(Instant.parse("2025-01-02T03:04:05Z").toEpochMilli(), store.current.likes.first { it.track.sourceId == "phone" }.createdAt)
            // syncing again changes nothing
            assertEquals(200, send(server, keys, merged).status)
            assertEquals(3, store.current.likes.size)
        } finally { server.stop() }
    }

    @Test fun `the key is never sent and a replayed or tampered request is refused`() {
        val (_, backup, server) = pc()
        server.start()
        try {
            val keys = SyncProtocol.keys(server.key)
            val nonce = SyncProtocol.newNonce()
            val time = System.currentTimeMillis()
            val body = SyncProtocol.seal(keys, gson.toJson(BackupPayload(createdAt = "x", settings = backup.payload().settings)).toByteArray(), SyncProtocol.requestAad(nonce))
            assertFalse(server.key.toByteArray().let { k -> body.toList().windowed(k.size).any { it == k.toList() } })
            val headers = mapOf(
                SyncProtocol.TIME_HEADER to time.toString(), SyncProtocol.NONCE_HEADER to nonce,
                SyncProtocol.AUTH_HEADER to SyncProtocol.requestMac(keys, "POST", SyncProtocol.SYNC_PATH, time, nonce, body),
            )
            val deadline = System.currentTimeMillis() + 10_000
            val ok = SyncTransport.exchange("127.0.0.1", server.boundPortOrDefault, "POST", SyncProtocol.SYNC_PATH, headers, body, deadline)
            assertEquals(200, ok.status)
            assertTrue(SyncProtocol.macEquals(ok.headers[SyncProtocol.AUTH_HEADER.lowercase()], SyncProtocol.responseMac(keys, 200, nonce, ok.body)))
            // same request again
            assertEquals(403, SyncTransport.exchange("127.0.0.1", server.boundPortOrDefault, "POST", SyncProtocol.SYNC_PATH, headers, body, deadline).status)
            // changed body, old signature
            val changed = body.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
            assertEquals(401, SyncTransport.exchange("127.0.0.1", server.boundPortOrDefault, "POST", SyncProtocol.SYNC_PATH,
                headers + (SyncProtocol.NONCE_HEADER to SyncProtocol.newNonce()), changed, deadline).status)
            // too old
            val old = time - 10 * 60_000
            val n2 = SyncProtocol.newNonce()
            assertEquals(403, SyncTransport.exchange("127.0.0.1", server.boundPortOrDefault, "POST", SyncProtocol.SYNC_PATH, mapOf(
                SyncProtocol.TIME_HEADER to old.toString(), SyncProtocol.NONCE_HEADER to n2,
                SyncProtocol.AUTH_HEADER to SyncProtocol.requestMac(keys, "POST", SyncProtocol.SYNC_PATH, old, n2, body),
            ), body, deadline).status)
        } finally { server.stop() }
    }

    @Test fun `an outdated phone gets a clear version error`() {
        val (_, _, server) = pc()
        server.start()
        try {
            // a version 1 request: key in a header, no version
            assertEquals(426, rawStatus(server, "127.0.0.1", "POST /sync HTTP/1.1\r\nHost: x\r\nX-Grooveo-Key: ${server.key}\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"))
        } finally { server.stop() }
    }

    @Test fun `wrong key is rejected and locks out only that address`() {
        val (_, backup, server) = pc()
        server.start()
        try {
            val wrong = SyncProtocol.keys(SyncProtocol.newKey())
            val payload = BackupPayload(createdAt = "x", settings = backup.payload().settings)
            repeat(SyncServer.MAX_FAILURES) { assertEquals(401, send(server, wrong, payload).status) }
            // even the right key is refused from that address now
            assertEquals(429, send(server, SyncProtocol.keys(server.key), payload).status)
            assertEquals(429, rawStatus(server, "127.0.0.1", hello(SyncProtocol.newNonce())))
            // another device in the network is not affected
            assertEquals(200, rawStatus(server, "127.0.0.2", hello(SyncProtocol.newNonce())))
            assertTrue(server.running)
        } finally { server.stop() }
    }

    @Test fun `hello proves the key and fails for another key`() {
        val (_, _, server) = pc()
        server.start()
        try {
            val ok = SyncTransport.hello("127.0.0.1", server.boundPortOrDefault, SyncProtocol.keys(server.key))
            assertEquals(server.boundPortOrDefault, ok.peer?.port)
            assertNull(SyncTransport.hello("127.0.0.1", server.boundPortOrDefault, SyncProtocol.keys(SyncProtocol.newKey())).peer)
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

    @Test fun `discovery finds the pc that proves the key and ignores the old request`() {
        val (_, _, server) = pc()
        server.start()
        try {
            val keys = SyncProtocol.keys(server.key)
            val found = SyncTransport.discover(keys, server.key, InetAddress.getLoopbackAddress(), server.discoveryPortBound, attempts = 2)
            assertEquals(server.boundPortOrDefault, found.peer?.port)
            assertNull(SyncTransport.discover(SyncProtocol.keys(SyncProtocol.newKey()), "x", InetAddress.getLoopbackAddress(), server.discoveryPortBound, attempts = 1).peer)
            DatagramSocket().use { s ->
                s.soTimeout = 1000
                val req = SyncProtocol.LEGACY_DISCOVERY_REQUEST.toByteArray()
                s.send(DatagramPacket(req, req.size, InetAddress.getLoopbackAddress(), server.discoveryPortBound))
                val got = runCatching { s.receive(DatagramPacket(ByteArray(256), 256)) }
                assertTrue(got.isFailure) // no answer that would reveal the old, crackable id
            }
        } finally { server.stop() }
    }

    @Test fun `two playlists with the same name stay apart on both sides`() {
        val (store, backup, server) = pc()
        val pc1 = store.createPlaylist("Neue Playlist", listOf(track("p1")))
        val pc2 = store.createPlaylist("Neue Playlist", listOf(track("p2")))
        server.start()
        try {
            val keys = SyncProtocol.keys(server.key)
            fun pl(id: String, vararg t: String) = BackupPlaylistDto(id, "Neue Playlist", "2026-01-01T00:00:00Z", null, null, emptyList(), t.map { dto(track(it)) }, syncId = id)
            val phone = BackupPayload(createdAt = "x", playlists = listOf(pl("a1", "a1"), pl("a2", "a2")), settings = backup.payload().settings)
            val a = send(server, keys, phone)
            assertEquals(200, a.status)
            val merged = gson.fromJson(a.json!!.decodeToString(), BackupPayload::class.java)
            assertEquals(4, merged.playlists.size)
            assertEquals(setOf(pc1.id, pc2.id, "a1", "a2"), merged.playlists.map { SyncMerge.syncKey(it) }.toSet())
            merged.playlists.forEach { assertEquals(1, it.tracks.size) }
            val pcs = store.current.playlists
            assertEquals(4, pcs.size)
            assertEquals(listOf("p1"), pcs.first { it.id == pc1.id }.tracks.map { it.track.sourceId })
            assertEquals(listOf("p2"), pcs.first { it.id == pc2.id }.tracks.map { it.track.sourceId })
            // a track added on the phone goes into that one playlist only
            val again = merged.copy(playlists = merged.playlists.map { if (SyncMerge.syncKey(it) == "a1") it.copy(tracks = it.tracks + dto(track("new"))) else it })
            assertEquals(200, send(server, keys, again).status)
            assertEquals(4, store.current.playlists.size)
            assertEquals(listOf("a1", "new"), store.current.playlists.first { it.id == "a1" }.tracks.map { it.track.sourceId })
            assertEquals(1, store.current.playlists.count { p -> p.tracks.any { it.track.sourceId == "new" } })
        } finally { server.stop() }
    }

    @Test fun `playlists without a sync id fall back to a unique name`() {
        fun pl(id: String, name: String, vararg t: String) =
            BackupPlaylistDto(id, name, "2026-01-01T00:00:00Z", null, null, emptyList(), t.map { dto(track(it)) })
        val settings = pc().second.payload().settings
        val local = BackupPayload(createdAt = "x", playlists = listOf(pl("l1", "Chill", "a"), pl("l2", "Mix", "b"), pl("l3", "Mix", "c")), settings = settings)
        val remote = BackupPayload(createdAt = "x", playlists = listOf(pl("r1", " chill ", "d"), pl("r2", "Mix", "e")), settings = settings)
        val m = SyncMerge.merge(local, remote)
        // "Chill" is unique on both sides: folded; "Mix" is not unique here: kept apart
        assertEquals(listOf("l1", "l2", "l3", "r2"), m.playlists.map { SyncMerge.syncKey(it) })
        assertEquals(listOf("a", "d"), m.playlists[0].tracks.map { it.sourceId })
        // a playlist matched by id is never also matched by name
        val matched = SyncMerge.matchPlaylists(listOf(SyncMerge.Local("x", "Chill")), listOf(pl("x", "Other"), pl("y", "Chill")))
        assertEquals(listOf(0, null), matched)
        assertNotNull(SyncMerge.matchPlaylists(listOf(SyncMerge.Local("x", "Chill")), listOf(pl("y", "Chill")))[0])
    }

    @Test fun `likes keep the earliest date in the merge`() {
        val settings = pc().second.payload().settings
        val a = BackupPayload(createdAt = "x", likes = listOf(dto(track("t"), "2026-05-01T00:00:00Z")), settings = settings)
        val b = BackupPayload(createdAt = "x", likes = listOf(dto(track("t"), "2026-01-01T00:00:00Z")), settings = settings)
        assertEquals("2026-01-01T00:00:00Z", SyncMerge.merge(a, b).likes.single().likedAt)
    }
}
