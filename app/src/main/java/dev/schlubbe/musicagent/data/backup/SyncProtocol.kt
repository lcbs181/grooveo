package dev.schlubbe.musicagent.data.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing, discovery and wire rules shared by the phone and the PC for the library sync.
 *
 * The PC owns a persistent random [newKey] (the "Kopplungscode"). It is shown as a QR code
 * ([pairingUri]) or as text; the phone stores it once. The key itself never travels over the
 * network: both sides derive [Keys] from it (PBKDF2, then separate subkeys for authentication,
 * encryption and the device id).
 *
 * - Discovery: the phone broadcasts [discoveryRequest] with a random nonce over UDP; the PC answers
 *   with [discoveryReply], which proves that it knows the key (HMAC over the nonce). The phone only
 *   talks to a PC whose reply verifies. The PC last used is checked the same way over HTTP
 *   ([HELLO_PATH]) before anything is sent to it.
 * - Sync: the phone POSTs its library to [SYNC_PATH], AES-GCM encrypted and authenticated with
 *   [requestMac] (method, path, time, nonce and body hash). The PC checks the time window and that
 *   the nonce is new, merges, and answers encrypted with [responseMac] bound to the same nonce.
 * - The assistant ([AI_PATH]) and MCP tool calls ([MCP_PATH]) use the same sealed requests.
 *
 * [VERSION] is sent with every request and reply; when phone and PC differ, both have to update.
 */
object SyncProtocol {
    const val PORT = 47821
    const val DISCOVERY_PORT = 47822
    const val VERSION = 2
    const val UPDATE_BOTH = "Grooveo auf Handy und PC passen nicht zusammen – bitte beide Apps aktualisieren."

    const val SYNC_PATH = "/sync"
    const val HELLO_PATH = "/sync/hello"
    /** The phone's "KI" tab: the PC's assistant ([dev.schlubbe.musicagent.data.sync.RemoteAi]). */
    const val AI_PATH = "/sync/ai"
    /** MCP tool calls (JSON-RPC: tools/list, tools/call) from the phone to the PC. */
    const val MCP_PATH = "/sync/mcp"
    /** Largest request body for [AI_PATH] and [MCP_PATH]. */
    const val MAX_SMALL_SEALED = 256 * 1024
    const val VERSION_HEADER = "X-Grooveo-Sync"
    const val TIME_HEADER = "X-Grooveo-Time"
    const val NONCE_HEADER = "X-Grooveo-Nonce"
    const val AUTH_HEADER = "X-Grooveo-Auth"
    /** Version 1 discovery request, sent as well so an outdated PC can be recognised. */
    const val LEGACY_DISCOVERY_REQUEST = "GROOVEO-SYNC?"

    /** Largest library JSON accepted in either direction. */
    const val MAX_BODY = 32 * 1024 * 1024
    /** Largest encrypted body (JSON plus IV and tag, with room to spare). */
    const val MAX_SEALED = MAX_BODY + 1024
    const val TIME_WINDOW_MS = 120_000L

    // no 0/O/1/I/L: the code may be typed by hand
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private const val KEY_CHARS = 12
    private val random = SecureRandom()

    /** `GRV-XXXX-XXXX-XXXX` (60 bits). */
    fun newKey(): String {
        val raw = String(CharArray(KEY_CHARS) { ALPHABET[random.nextInt(ALPHABET.length)] })
        return "GRV-" + raw.chunked(4).joinToString("-")
    }

    /** Canonical form of a typed or scanned key, or null when it is not a valid key. */
    fun normalizeKey(text: String): String? {
        val raw = text.uppercase().removePrefix("GRV").filter { it.isLetterOrDigit() }
        if (raw.length != KEY_CHARS || raw.any { it !in ALPHABET }) return null
        return "GRV-" + raw.chunked(4).joinToString("-")
    }

    /** Secrets derived from the pairing key. [deviceId] names the PC in discovery replies. */
    class Keys(val auth: ByteArray, val enc: ByteArray, val deviceId: String)

    /**
     * Derives [Keys] from a pairing key. PBKDF2 makes guessing the key from a captured reply slow;
     * the result should be kept rather than derived for every message.
     */
    fun keys(key: String): Keys {
        val spec = PBEKeySpec(key.toCharArray(), "grooveo-sync-v2".toByteArray(), 100_000, 256)
        val root = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        fun sub(label: String) = hmac(root, label.toByteArray())
        return Keys(sub("auth"), sub("enc"), hex(sub("device-id").copyOf(8)))
    }

    /** Identifies a PC to the phone. It is derived from the key but needs the key to be checked. */
    fun deviceId(key: String): String = keys(key).deviceId

    /** The id version 1 used (a plain hash of the key); only used to recognise an outdated PC. */
    fun legacyDeviceId(key: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(("grooveo-sync:" + key).toByteArray()).copyOf(8))

    fun newNonce(): String = hex(ByteArray(16).also { random.nextBytes(it) })

    fun isNonce(text: String?): Boolean = text != null && NONCE.matches(text)

    fun discoveryRequest(nonce: String) = "GROOVEO-SYNC? v=$VERSION n=$nonce"

    /** Nonce of a discovery request (of any version that has one), or null for anything else. */
    fun parseDiscoveryRequest(text: String): String? =
        Regex("""^GROOVEO-SYNC\? v=\d{1,3} n=([0-9a-f]{32})$""").matchEntire(text.trim())?.groupValues?.get(1)

    /** The PC's answer to [nonce]: its id, sync port and clock, signed with the key. */
    fun discoveryReply(keys: Keys, nonce: String, port: Int, now: Long = System.currentTimeMillis()): String {
        val head = "GROOVEO-SYNC! v=$VERSION id=${keys.deviceId} port=$port t=$now"
        return "$head mac=${mac(keys, "disc\n$nonce\n$head")}"
    }

    /** A verified [discoveryReply]: the PC's sync port and its clock when it answered. */
    class Reply(val port: Int, val time: Long)

    /** The reply to [nonce] of the PC that holds [keys], or null for anything else. */
    fun parseDiscoveryReply(text: String, keys: Keys, nonce: String): Reply? {
        val m = REPLY.matchEntire(text.trim()) ?: return null
        val (head, v, id, port, time, mac) = m.destructured
        if (v != VERSION.toString() || id != keys.deviceId) return null
        if (!macEquals(mac, mac(keys, "disc\n$nonce\n$head"))) return null
        return Reply(port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null, time.toLongOrNull() ?: return null)
    }

    /** True for a discovery reply of another protocol version (version 1 only when it is our PC). */
    fun isOtherVersionReply(text: String, key: String): Boolean {
        val t = text.trim()
        Regex("""^GROOVEO-SYNC! v=(\d{1,3}) """).find(t)?.let { return it.groupValues[1] != VERSION.toString() }
        val m = Regex("""^GROOVEO-SYNC! id=([0-9a-f]{16}) port=\d{1,5}$""").matchEntire(t) ?: return false
        return m.groupValues[1] == legacyDeviceId(key)
    }

    fun requestMac(keys: Keys, method: String, path: String, time: Long, nonce: String, body: ByteArray): String =
        mac(keys, "req\n$method\n$path\n$time\n$nonce\n${sha256Hex(body)}")

    fun responseMac(keys: Keys, status: Int, nonce: String, body: ByteArray): String =
        mac(keys, "res\n$status\n$nonce\n${sha256Hex(body)}")

    fun macEquals(given: String?, expected: String): Boolean =
        given != null && MessageDigest.isEqual(given.lowercase().toByteArray(), expected.toByteArray())

    /** AES-256-GCM: random IV followed by ciphertext and tag. [aad] binds it to its request. */
    fun seal(keys: Keys, plain: ByteArray, aad: String): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.enc, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad.toByteArray())
        return iv + c.doFinal(plain)
    }

    /** Opens [seal]ed data; throws when it was not sealed with the same key and [aad]. */
    fun open(keys: Keys, sealed: ByteArray, aad: String): ByteArray {
        require(sealed.size > 12 + 16) { "too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.enc, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        c.updateAAD(aad.toByteArray())
        return c.doFinal(sealed, 12, sealed.size - 12)
    }

    fun requestAad(nonce: String) = "req\n$nonce"
    fun responseAad(nonce: String) = "res\n$nonce"

    class Pairing(val key: String, val host: String? = null, val port: Int? = null)

    /** What the QR code contains: key plus the PC's current address as a hint. */
    fun pairingUri(host: String?, port: Int, key: String): String =
        "grooveo://sync?key=" + URLEncoder.encode(key, "UTF-8") + (host?.let { "&host=$it&port=$port" } ?: "")

    /** Parses a scanned QR code (`grooveo://sync?...`), `host:port-KEY` or a bare key. */
    fun parsePairing(text: String): Pairing? {
        val t = text.trim()
        if (t.startsWith("grooveo://sync?")) {
            val q = t.substringAfter('?').split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to URLDecoder.decode(it[1], "UTF-8") } }.toMap()
            val key = normalizeKey(q["key"] ?: return null) ?: return null
            return Pairing(key, q["host"]?.takeIf { isPrivateAddress(it) }, q["port"]?.toIntOrNull()?.takeIf { it in 1..65535 })
        }
        Regex("""^(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})-(.+)$""").matchEntire(t)?.let { m ->
            val key = normalizeKey(m.groupValues[3]) ?: return null
            val port = m.groupValues[2].toInt()
            val host = m.groupValues[1].takeIf { isPrivateAddress(it) && port in 1..65535 } ?: return null
            return Pairing(key, host, port)
        }
        return normalizeKey(t)?.let { Pairing(it) }
    }

    /** Only addresses of a home/office network are ever contacted. */
    fun isPrivateAddress(host: String): Boolean {
        val o = host.split('.').mapNotNull { it.toIntOrNull() }
        return o.size == 4 && o.all { it in 0..255 } &&
            (o[0] == 10 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in 16..31))
    }

    /**
     * Whether a peer is in the local network: private IPv4 ranges, IPv6 unique local (fc00::/7),
     * link-local (169.254/16, fe80::/10) or loopback.
     */
    fun isLocalAddress(a: InetAddress): Boolean =
        a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress ||
            (a is Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc)

    private val NONCE = Regex("[0-9a-f]{32}")
    private val REPLY = Regex("""^(GROOVEO-SYNC! v=(\d{1,3}) id=([0-9a-f]{16}) port=(\d{1,5}) t=(\d{1,15})) mac=([0-9a-f]{64})$""")

    private fun mac(keys: Keys, text: String) = hex(hmac(keys.auth, text.toByteArray()))

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun sha256Hex(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

/**
 * The phone's side of [SyncProtocol] over plain sockets (no Android types, so the PC tests use it
 * too). Every call has a deadline and caps what it reads.
 */
object SyncTransport {
    private const val CONNECT_TIMEOUT_MS = 2_500
    private const val HELLO_TIMEOUT_MS = 3_000L
    private const val MAX_RESPONSE = SyncProtocol.MAX_SEALED + 64 * 1024

    /** Where the PC was found and how far its clock is ahead of ours. */
    class Peer(val host: String, val port: Int, val clockOffset: Long)

    /** [peer] is null when no PC with our key answered; [otherVersion] when an outdated or newer one did. */
    class Discovery(val peer: Peer?, val otherVersion: Boolean)

    /** Answer to a sync: [json] is the PC's merged library, only set for a verified 200. */
    class Answer(val status: Int, val json: ByteArray?, val otherVersion: Boolean)

    class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    /** Asks [host] to prove that it holds the key before anything else is sent to it. */
    fun hello(host: String, port: Int, keys: SyncProtocol.Keys): Discovery {
        val nonce = SyncProtocol.newNonce()
        val res = try {
            exchange(host, port, "GET", SyncProtocol.HELLO_PATH, mapOf(SyncProtocol.NONCE_HEADER to nonce), ByteArray(0),
                System.currentTimeMillis() + HELLO_TIMEOUT_MS)
        } catch (_: IOException) { return Discovery(null, false) }
        if (res.headers[SyncProtocol.VERSION_HEADER.lowercase()] != SyncProtocol.VERSION.toString()) return Discovery(null, isLegacyPc(res, "POST only"))
        if (res.status != 200) return Discovery(null, false)
        val reply = SyncProtocol.parseDiscoveryReply(String(res.body, Charsets.UTF_8), keys, nonce) ?: return Discovery(null, false)
        return Discovery(Peer(host, reply.port, reply.time - System.currentTimeMillis()), false)
    }

    /** Broadcasts (or sends to [target]) a discovery request and waits for our PC to answer. */
    fun discover(
        keys: SyncProtocol.Keys,
        key: String,
        target: InetAddress = InetAddress.getByName("255.255.255.255"),
        port: Int = SyncProtocol.DISCOVERY_PORT,
        attempts: Int = 3,
    ): Discovery {
        var otherVersion = false
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 700
                repeat(attempts) {
                    val nonce = SyncProtocol.newNonce()
                    for (msg in listOf(SyncProtocol.discoveryRequest(nonce), SyncProtocol.LEGACY_DISCOVERY_REQUEST)) {
                        val b = msg.toByteArray()
                        s.send(DatagramPacket(b, b.size, target, port))
                    }
                    val deadline = System.currentTimeMillis() + 1_000
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val p = DatagramPacket(ByteArray(512), 512)
                            s.receive(p)
                            if (!SyncProtocol.isLocalAddress(p.address)) continue
                            val text = String(p.data, 0, p.length, Charsets.UTF_8)
                            val reply = SyncProtocol.parseDiscoveryReply(text, keys, nonce)
                            if (reply == null) {
                                if (SyncProtocol.isOtherVersionReply(text, key)) otherVersion = true
                                continue
                            }
                            val host = p.address.hostAddress ?: continue
                            return Discovery(Peer(host, reply.port, reply.time - System.currentTimeMillis()), otherVersion)
                        } catch (_: SocketTimeoutException) { break }
                    }
                }
            }
        }
        return Discovery(null, otherVersion)
    }

    /**
     * Sends the library [json] to [peer] and returns its answer. A 200 whose signature does not
     * verify throws [SecurityException]; network errors throw [IOException].
     */
    fun sync(peer: Peer, keys: SyncProtocol.Keys, json: ByteArray, deadline: Long): Answer =
        call(peer, keys, SyncProtocol.SYNC_PATH, json, deadline)

    /**
     * One sealed, signed POST of [json] to [path] on [peer] ([SyncProtocol.SYNC_PATH],
     * [SyncProtocol.AI_PATH], [SyncProtocol.MCP_PATH]); same errors as [sync].
     */
    fun call(peer: Peer, keys: SyncProtocol.Keys, path: String, json: ByteArray, deadline: Long): Answer {
        val nonce = SyncProtocol.newNonce()
        val time = System.currentTimeMillis() + peer.clockOffset
        val body = SyncProtocol.seal(keys, json, SyncProtocol.requestAad(nonce))
        val mac = SyncProtocol.requestMac(keys, "POST", path, time, nonce, body)
        val res = exchange(peer.host, peer.port, "POST", path, mapOf(
            SyncProtocol.TIME_HEADER to time.toString(),
            SyncProtocol.NONCE_HEADER to nonce,
            SyncProtocol.AUTH_HEADER to mac,
            "Content-Type" to "application/octet-stream",
        ), body, deadline)
        if (res.headers[SyncProtocol.VERSION_HEADER.lowercase()] != SyncProtocol.VERSION.toString()) return Answer(res.status, null, isLegacyPc(res, "wrong key"))
        if (res.status != 200) return Answer(res.status, null, false)
        if (!SyncProtocol.macEquals(res.headers[SyncProtocol.AUTH_HEADER.lowercase()], SyncProtocol.responseMac(keys, 200, nonce, res.body))) {
            throw SecurityException("Antwort vom PC ist nicht signiert")
        }
        val plain = try { SyncProtocol.open(keys, res.body, SyncProtocol.responseAad(nonce)) } catch (e: Exception) {
            throw SecurityException("Antwort vom PC lässt sich nicht entschlüsseln", e)
        }
        if (plain.size > SyncProtocol.MAX_BODY) throw IOException("Antwort vom PC ist zu groß")
        return Answer(200, plain, false)
    }

    /** One HTTP/1.1 request with `Connection: close`; reads at most [MAX_RESPONSE] bytes until [deadline]. */
    fun exchange(host: String, port: Int, method: String, path: String, headers: Map<String, String>, body: ByteArray, deadline: Long): Response =
        Socket().use { s ->
            val left = { (deadline - System.currentTimeMillis()).also { if (it <= 0) throw SocketTimeoutException("Zeitlimit überschritten") } }
            s.connect(InetSocketAddress(host, port), minOf(CONNECT_TIMEOUT_MS.toLong(), left()).toInt())
            val hostHeader = if (':' in host) "[${host.substringBefore('%')}]:$port" else "$host:$port"
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: $hostHeader\r\n")
                append("${SyncProtocol.VERSION_HEADER}: ${SyncProtocol.VERSION}\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            }
            s.soTimeout = left().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            s.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(body); flush() }
            val raw = ByteArrayOutputStream()
            val input = s.getInputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                s.soTimeout = left().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val n = input.read(buf)
                if (n < 0) break
                raw.write(buf, 0, n)
                if (raw.size() > MAX_RESPONSE) throw IOException("Antwort vom PC ist zu groß")
            }
            parseResponse(raw.toByteArray())
        }

    /** A PC with protocol version 1 answers new requests with these plain errors. */
    private fun isLegacyPc(res: Response, text: String) = (res.status == 405 || res.status == 401) && String(res.body, Charsets.UTF_8) == text

    private fun parseResponse(raw: ByteArray): Response {
        var split = -1
        for (i in 0..raw.size - 4) {
            if (raw[i] == 13.toByte() && raw[i + 1] == 10.toByte() && raw[i + 2] == 13.toByte() && raw[i + 3] == 10.toByte()) { split = i; break }
        }
        if (split <= 0) throw IOException("Ungültige Antwort vom PC")
        val lines = String(raw, 0, split, Charsets.ISO_8859_1).split("\r\n")
        val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Ungültige Antwort vom PC")
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
        }.toMap()
        var body = raw.copyOfRange(split + 4, raw.size)
        headers["content-length"]?.toIntOrNull()?.let { len ->
            if (len > body.size) throw IOException("Antwort vom PC ist unvollständig")
            body = body.copyOf(len)
        }
        return Response(status, headers, body)
    }
}
