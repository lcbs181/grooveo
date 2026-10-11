package dev.schlubbe.musicagent.desktop.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.schlubbe.musicagent.data.backup.BackupPayload
import dev.schlubbe.musicagent.data.backup.SyncMerge
import dev.schlubbe.musicagent.data.backup.SyncProtocol
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Persistent sync state: the pairing key stays the same across restarts, [enabled] is what the user switched on. */
data class SyncState(val key: String = "", val enabled: Boolean = false)

/**
 * Sync endpoint for the phone, reachable in the local network only while the user has it
 * switched on (and again after a restart, when it was on). The phone POSTs its library to
 * `/sync` with the pairing key; the PC merges it with its own ([SyncMerge]), applies the result and
 * answers with the same merged library, so both end up identical. A UDP responder lets the phone
 * find this PC by [SyncProtocol.deviceId] when its address changed ([SyncProtocol] documents the
 * protocol). Wrong keys are rate limited with a growing lock-out.
 */
class SyncServer(private val backup: BackupManager, private val stateFile: File, private val port: Int = SyncProtocol.PORT, private val discoveryPort: Int = SyncProtocol.DISCOVERY_PORT) {
    private var http: HttpServer? = null
    private var udp: DatagramSocket? = null
    private var state: SyncState = (readJson<SyncState>(stateFile, SyncState::class.java) ?: SyncState()).let {
        if (SyncProtocol.normalizeKey(it.key) == null) it.copy(key = SyncProtocol.newKey()).also(::save) else it
    }
    @Volatile private var failures = 0
    @Volatile private var lockedUntil = 0L
    @Volatile var lastSyncAt: Long? = null
        private set
    private var boundPort = port

    val running: Boolean get() = http != null
    /** The user wants the server on (it starts again with the app). */
    val enabled: Boolean get() = state.enabled
    val key: String get() = state.key
    val deviceId: String get() = SyncProtocol.deviceId(state.key)
    val boundPortOrDefault: Int get() = boundPort
    val discoveryPortBound: Int get() = udp?.localPort ?: -1
    /** Content of the QR code. */
    val pairingUri: String get() = SyncProtocol.pairingUri(localAddress(), boundPort, state.key)

    private fun save(s: SyncState) { state = s; writeJson(stateFile, s) }

    @Synchronized fun start() {
        if (http != null) return
        val s = HttpServer.create(InetSocketAddress(port), 0)
        s.createContext("/sync") { ex -> runCatching { handle(ex) }.onFailure { reply(ex, 500, "error") } }
        s.executor = Executors.newSingleThreadExecutor { r -> Thread(r, "sync-server").apply { isDaemon = true } }
        s.start()
        http = s
        boundPort = s.address.port
        startDiscovery()
        if (!state.enabled) save(state.copy(enabled = true))
    }

    /** Switches the server off for good (until the user switches it on again). */
    @Synchronized fun stop() {
        shutdown()
        if (state.enabled) save(state.copy(enabled = false))
    }

    /** Stops the sockets without changing what the user chose (app exit). */
    @Synchronized fun shutdown() {
        http?.stop(0); http = null
        udp?.close(); udp = null
    }

    /** New pairing key: every phone paired before has to scan again. */
    @Synchronized fun regenerateKey() = save(state.copy(key = SyncProtocol.newKey()))

    private fun startDiscovery() {
        val sock = runCatching { DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(discoveryPort)) } }.getOrNull() ?: return
        udp = sock
        Thread({
            val buf = ByteArray(256)
            while (!sock.isClosed) {
                runCatching {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    if (String(p.data, 0, p.length, Charsets.UTF_8).trim() == SyncProtocol.DISCOVERY_REQUEST) {
                        val r = SyncProtocol.discoveryReply(state.key, boundPort).toByteArray()
                        sock.send(DatagramPacket(r, r.size, p.address, p.port))
                    }
                }
            }
        }, "sync-discovery").apply { isDaemon = true; start() }
    }

    private fun handle(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return reply(ex, 405, "POST only")
        if (System.currentTimeMillis() < lockedUntil) return reply(ex, 429, "locked")
        val given = ex.requestHeaders.getFirst(SyncProtocol.KEY_HEADER).orEmpty()
        if (!MessageDigest.isEqual(given.toByteArray(), state.key.toByteArray())) {
            if (++failures >= MAX_FAILURES) lockedUntil = System.currentTimeMillis() + LOCK_MS * (1L shl minOf(failures - MAX_FAILURES, 6))
            return reply(ex, 401, "wrong key")
        }
        failures = 0
        val body = ex.requestBody.readNBytes(MAX_BODY + 1)
        if (body.size > MAX_BODY) return reply(ex, 413, "too large")
        val theirs = runCatching { gson.fromJson(String(body, Charsets.UTF_8), BackupPayload::class.java) }.getOrNull()
            ?: return reply(ex, 400, "bad payload")
        val merged = synchronized(this) {
            SyncMerge.merge(backup.payload(), theirs).also { backup.applySynced(it); lastSyncAt = System.currentTimeMillis() }
        }
        reply(ex, 200, gson.toJson(merged), "application/json")
    }

    private fun reply(ex: HttpExchange, code: Int, text: String, type: String = "text/plain") {
        val b = text.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "$type; charset=utf-8")
        ex.sendResponseHeaders(code, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    companion object {
        const val MAX_FAILURES = 5
        const val LOCK_MS = 30_000L
        const val MAX_BODY = 32 * 1024 * 1024

        /** First private IPv4 address of this machine (the one the phone can reach). */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}
