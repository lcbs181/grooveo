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
import dev.schlubbe.musicagent.desktop.ai.tightenSecretFile
import dev.schlubbe.musicagent.desktop.ai.writeSecretFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * What the phone may do besides syncing: talk to the PC's assistant ([SyncProtocol.AI_PATH])
 * and call its tools ([SyncProtocol.MCP_PATH]). Both take and return JSON; they may block
 * (the assistant holds a request open until its conversation changes).
 */
interface PhoneRemote {
    fun ai(json: String): String
    fun mcp(json: String): String
}

/** Persistent sync state: the pairing key stays the same across restarts, [enabled] is what the user switched on. */
data class SyncState(val key: String = "", val enabled: Boolean = false)

/**
 * Sync endpoint for the phone, reachable in the local network only while the user has it
 * switched on (and again after a restart, when it was on). The phone POSTs its library to
 * `/sync`, encrypted and signed with keys derived from the pairing key ([SyncProtocol]); the PC
 * merges it with its own ([SyncMerge]), applies the result and answers with the same merged
 * library, so both end up identical. A UDP responder (and `/sync/hello`) lets the phone check that
 * this PC holds the key before it sends anything. Only peers in the local network are answered;
 * bad signatures are rate limited per address with a growing lock-out. With a [remote], the
 * phone's "KI" tab can use the PC's assistant and tools over the same keys.
 *
 * Before anything is authenticated, a request costs little: oversized bodies are refused by
 * their Content-Length, a malformed signature before the body is read, each address may
 * have only a few requests open, and slow requests time out ([HTTP_TIMEOUTS]).
 */
class SyncServer(private val backup: BackupManager, private val stateFile: File, private val port: Int = SyncProtocol.PORT, private val discoveryPort: Int = SyncProtocol.DISCOVERY_PORT) {
    private var http: HttpServer? = null
    private var executor: ExecutorService? = null
    private var udp: DatagramSocket? = null
    private var state: SyncState = (readJson<SyncState>(stateFile, SyncState::class.java) ?: SyncState()).let {
        // the file holds the pairing key: owner-only (older versions wrote it readable by all)
        tightenSecretFile(stateFile)
        if (SyncProtocol.normalizeKey(it.key) == null) it.copy(key = SyncProtocol.newKey()).also(::save) else it
    }
    /** Requests in progress per address. */
    private val open = ConcurrentHashMap<String, AtomicInteger>()
    /** Assistant and tool calls in progress (they may block for long, see [PhoneRemote]). */
    private val remoteSlots = Semaphore(REMOTE_SLOTS)
    private var derived: Pair<String, SyncProtocol.Keys>? = null
    private class Strikes(var count: Int = 0, var lockedUntil: Long = 0)
    private val strikes = object : LinkedHashMap<String, Strikes>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Strikes>?) = size > 256
    }
    private val seenNonces = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 4096
    }
    @Volatile var lastSyncAt: Long? = null
        private set
    private var boundPort = port
    /** Assistant and tools for the phone; null answers those paths with 404. */
    @Volatile var remote: PhoneRemote? = null
    /** The PC's mix for the phone ([SyncProtocol.LIVE_PATH]); null answers with 404. */
    @Volatile var live: dev.schlubbe.musicagent.desktop.audio.LiveStream? = null

    val running: Boolean get() = http != null
    /** The user wants the server on (it starts again with the app). */
    val enabled: Boolean get() = state.enabled
    val key: String get() = state.key
    val deviceId: String get() = keys().deviceId
    val boundPortOrDefault: Int get() = boundPort
    val discoveryPortBound: Int get() = udp?.localPort ?: -1
    /** Content of the QR code. */
    val pairingUri: String get() = SyncProtocol.pairingUri(localAddress(), boundPort, state.key)

    private fun save(s: SyncState) { state = s; writeSecretFile(stateFile, gson.toJson(s)) }

    @Synchronized private fun keys(): SyncProtocol.Keys {
        val k = state.key
        derived?.let { (forKey, keys) -> if (forKey == k) return keys }
        return SyncProtocol.keys(k).also { derived = k to it }
    }

    @Synchronized fun start() {
        if (http != null) return
        keys()
        val s = HttpServer.create(InetSocketAddress(port), 0)
        s.createContext(SyncProtocol.SYNC_PATH) { ex -> runCatching { handle(ex) }.onFailure { runCatching { reply(ex, 500, "error") } } }
        // threads on demand: the assistant's long polls (at most REMOTE_SLOTS) must not hold up a sync
        val pool = ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r -> Thread(r, "sync-server").apply { isDaemon = true } }
            .apply { allowCoreThreadTimeOut(true) }
        s.executor = pool
        s.start()
        http = s
        executor = pool
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
        live?.revokeAll()
        http?.stop(0); http = null
        executor?.shutdownNow(); executor = null
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
                    if (!SyncProtocol.isLocalAddress(p.address)) return@runCatching
                    val nonce = SyncProtocol.parseDiscoveryRequest(String(p.data, 0, p.length, Charsets.UTF_8)) ?: return@runCatching
                    val r = SyncProtocol.discoveryReply(keys(), nonce, boundPort).toByteArray()
                    sock.send(DatagramPacket(r, r.size, p.address, p.port))
                }
            }
        }, "sync-discovery").apply { isDaemon = true; start() }
    }

    private fun handle(ex: HttpExchange) {
        val from = ex.remoteAddress.address
        if (!SyncProtocol.isLocalAddress(from)) return reply(ex, 403, "local network only")
        val ip = from.hostAddress
        if (isLocked(ip)) return reply(ex, 429, "locked")
        // the audio itself: a plain GET (the phone's player cannot sign), allowed by the ticket in the path
        if (ex.requestURI.path.startsWith(LiveStreamPrefix)) return serveLive(ex, ip)
        val count = open.computeIfAbsent(ip) { AtomicInteger() }
        try {
            if (count.incrementAndGet() > PER_ADDRESS) return reply(ex, 429, "busy")
            route(ex, ip)
        } finally {
            if (count.decrementAndGet() <= 0) open.remove(ip, count)
        }
    }

    private fun route(ex: HttpExchange, ip: String) {
        if (ex.requestHeaders.getFirst(SyncProtocol.VERSION_HEADER) != SyncProtocol.VERSION.toString()) return reply(ex, 426, SyncProtocol.UPDATE_BOTH)
        val nonce = ex.requestHeaders.getFirst(SyncProtocol.NONCE_HEADER)?.takeIf { SyncProtocol.isNonce(it) }
            ?: return reply(ex, 400, "bad nonce")
        when (ex.requestURI.path) {
            SyncProtocol.HELLO_PATH -> reply(ex, 200, SyncProtocol.discoveryReply(keys(), nonce, boundPort))
            SyncProtocol.SYNC_PATH -> sealed(ex, ip, nonce, SyncProtocol.SYNC_PATH, SyncProtocol.MAX_SEALED) { json ->
                val theirs = runCatching { gson.fromJson(String(json, Charsets.UTF_8), BackupPayload::class.java) }.getOrNull() ?: return@sealed null
                val merged = synchronized(this) {
                    SyncMerge.merge(backup.payload(), theirs).also { backup.applySynced(it); lastSyncAt = System.currentTimeMillis() }
                }
                gson.toJson(merged).toByteArray(Charsets.UTF_8)
            }
            SyncProtocol.LIVE_PATH -> {
                val l = live ?: return reply(ex, 404, "not found")
                sealed(ex, ip, nonce, SyncProtocol.LIVE_PATH, SyncProtocol.MAX_SMALL_SEALED) { json ->
                    val req = runCatching { gson.fromJson(String(json, Charsets.UTF_8), LiveRequest::class.java) }.getOrNull() ?: return@sealed null
                    if (req.stop) {
                        l.revokeAll()
                        "{}".toByteArray()
                    } else {
                        val t = l.ticket(muteDevice = req.muteDevice)
                        gson.toJson(mapOf("path" to LiveStreamPrefix + t.token, "bitrate" to dev.schlubbe.musicagent.desktop.audio.LiveStream.BITRATE)).toByteArray()
                    }
                }
            }
            SyncProtocol.AI_PATH, SyncProtocol.MCP_PATH -> {
                val r = remote ?: return reply(ex, 404, "not found")
                val path = ex.requestURI.path
                if (!remoteSlots.tryAcquire()) return reply(ex, 503, "busy")
                try {
                    sealed(ex, ip, nonce, path, SyncProtocol.MAX_SMALL_SEALED) { json ->
                        val text = String(json, Charsets.UTF_8)
                        (if (path == SyncProtocol.AI_PATH) r.ai(text) else r.mcp(text)).toByteArray(Charsets.UTF_8)
                    }
                } finally {
                    remoteSlots.release()
                }
            }
            else -> reply(ex, 404, "not found")
        }
    }

    /**
     * A signed, encrypted POST to [path]: checks key, time window and nonce, opens the body,
     * lets [handle] answer it (null = the content is invalid) and seals the answer.
     */
    private inline fun sealed(ex: HttpExchange, ip: String, nonce: String, path: String, maxBody: Int, handle: (ByteArray) -> ByteArray?) {
        if (ex.requestMethod != "POST") return reply(ex, 405, "POST only")
        val time = ex.requestHeaders.getFirst(SyncProtocol.TIME_HEADER)?.toLongOrNull() ?: return reply(ex, 400, "bad time")
        // cheap checks before the body is read
        val length = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (length != null && length > maxBody) return reply(ex, 413, "too large")
        if (ex.requestHeaders.getFirst(SyncProtocol.AUTH_HEADER)?.matches(MAC_FORMAT) != true) {
            strike(ip)
            return reply(ex, 401, "wrong key")
        }
        val body = ex.requestBody.readNBytes(maxBody + 1)
        if (body.size > maxBody) return reply(ex, 413, "too large")
        val keys = keys()
        val expected = SyncProtocol.requestMac(keys, ex.requestMethod, path, time, nonce, body)
        if (!SyncProtocol.macEquals(ex.requestHeaders.getFirst(SyncProtocol.AUTH_HEADER), expected)) {
            strike(ip)
            return reply(ex, 401, "wrong key")
        }
        if (abs(System.currentTimeMillis() - time) > SyncProtocol.TIME_WINDOW_MS || !firstUse(nonce, time)) return reply(ex, 403, "stale request")
        synchronized(strikes) { strikes.remove(ip) }
        val json = runCatching { SyncProtocol.open(keys, body, SyncProtocol.requestAad(nonce)) }.getOrNull()
            ?: return reply(ex, 400, "bad payload")
        val answer = handle(json) ?: return reply(ex, 400, "bad payload")
        val out = SyncProtocol.seal(keys, answer, SyncProtocol.responseAad(nonce))
        ex.responseHeaders.add(SyncProtocol.AUTH_HEADER, SyncProtocol.responseMac(keys, 200, nonce, out))
        send(ex, 200, out, "application/octet-stream")
    }

    private class LiveRequest(val muteDevice: Boolean = false, val stop: Boolean = false)

    /**
     * Streams the live mix on its own thread (it runs for as long as the phone listens, and
     * must not take one of the few request threads). A wrong ticket counts as a bad signature.
     */
    private fun serveLive(ex: HttpExchange, ip: String) {
        val l = live ?: return reply(ex, 404, "not found")
        val token = ex.requestURI.path.removePrefix(LiveStreamPrefix)
        if (ex.requestMethod != "GET" || !TICKET.matches(token) || !l.isValid(token)) {
            strike(ip)
            return reply(ex, 404, "not found")
        }
        Thread({
            try {
                ex.responseHeaders.add("Content-Type", "audio/aac")
                ex.responseHeaders.add("Cache-Control", "no-store")
                // a fixed, huge length instead of chunked encoding: the phone reads it as a plain endless body
                ex.sendResponseHeaders(200, LIVE_LENGTH)
                ex.responseBody.use { l.serve(token, it) }
            } catch (_: Exception) {
            } finally {
                ex.close()
            }
        }, "sync-live").apply { isDaemon = true; start() }
    }

    private fun isLocked(ip: String) = synchronized(strikes) { (strikes[ip]?.lockedUntil ?: 0) > System.currentTimeMillis() }

    private fun strike(ip: String) = synchronized(strikes) {
        val s = strikes.getOrPut(ip) { Strikes() }
        if (++s.count >= MAX_FAILURES) s.lockedUntil = System.currentTimeMillis() + LOCK_MS * (1L shl minOf(s.count - MAX_FAILURES, 6))
    }

    /** False when [nonce] was used before (a replayed request). */
    private fun firstUse(nonce: String, time: Long) = synchronized(seenNonces) { seenNonces.putIfAbsent(nonce, time) == null }

    private fun reply(ex: HttpExchange, code: Int, text: String) = send(ex, code, text.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")

    private fun send(ex: HttpExchange, code: Int, b: ByteArray, type: String) {
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add(SyncProtocol.VERSION_HEADER, SyncProtocol.VERSION.toString())
        ex.sendResponseHeaders(code, if (b.isEmpty()) -1 else b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    companion object {
        const val MAX_FAILURES = 5
        const val LOCK_MS = 30_000L
        const val THREADS = 16
        /** Assistant/tool requests at once; the rest of the pool stays free for syncs. */
        const val REMOTE_SLOTS = 4
        /** Requests one address may have open at once. */
        const val PER_ADDRESS = 6
        /** HMAC-SHA256 in hex, as [SyncProtocol.requestMac] makes it. */
        private val MAC_FORMAT = Regex("[0-9a-fA-F]{64}")
        private const val LiveStreamPrefix = SyncProtocol.LIVE_PATH + "/"
        private val TICKET = Regex("[0-9a-f]{32}")
        private const val LIVE_LENGTH = 1L shl 52
        /**
         * Seconds the JDK server gives a client to send its request. Read once by the JDK's
         * HttpServer, so it is set before the first server starts. There is deliberately no
         * response limit (maxRspTime): the live stream's answer never ends.
         */
        private val HTTP_TIMEOUTS = mapOf("sun.net.httpserver.maxReqTime" to "30")

        init {
            HTTP_TIMEOUTS.forEach { (k, v) -> if (System.getProperty(k) == null) System.setProperty(k, v) }
        }

        /**
         * The private IPv4 address the phone can reach: the one the default route leaves
         * from (a UDP "connect" sends nothing, it only picks the outgoing interface), else
         * the first site-local address that is not a Docker/VM bridge or VPN tunnel.
         */
        fun localAddress(): String? = runCatching {
            java.net.DatagramSocket().use { s ->
                s.connect(java.net.InetAddress.getByName("192.0.2.1"), 9)
                (s.localAddress as? Inet4Address)?.takeIf { it.isSiteLocalAddress }?.hostAddress
            }
        }.getOrNull() ?: runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && !VIRTUAL_IFACE.containsMatchIn(it.name) }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        private val VIRTUAL_IFACE = Regex("^(docker|br-|virbr|veth|vmnet|vboxnet|tun|tap|wg|tailscale|zt)")
    }
}
