package dev.schlubbe.musicagent.data.backup

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pairing and discovery rules shared by the phone and the PC for the library sync.
 *
 * The PC owns a persistent random [newKey] (the "Kopplungscode"). It is shown as a QR code
 * ([pairingUri]) or as text; the phone stores it once. To find the PC in the network the phone
 * broadcasts [DISCOVERY_REQUEST] over UDP; the PC answers with [discoveryReply], which carries only
 * [deviceId] (a hash of the key), never the key itself. The phone only talks to a PC whose id
 * matches its stored key, so the key is never sent to an unknown device.
 */
object SyncProtocol {
    const val PORT = 47821
    const val DISCOVERY_PORT = 47822
    const val DISCOVERY_REQUEST = "GROOVEO-SYNC?"
    const val KEY_HEADER = "X-Grooveo-Key"

    // no 0/O/1/I/L: the code may be typed by hand
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private const val KEY_CHARS = 12

    /** `GRV-XXXX-XXXX-XXXX` (60 bits). */
    fun newKey(): String {
        val r = SecureRandom()
        val raw = String(CharArray(KEY_CHARS) { ALPHABET[r.nextInt(ALPHABET.length)] })
        return "GRV-" + raw.chunked(4).joinToString("-")
    }

    /** Canonical form of a typed or scanned key, or null when it is not a valid key. */
    fun normalizeKey(text: String): String? {
        val raw = text.uppercase().removePrefix("GRV").filter { it.isLetterOrDigit() }
        if (raw.length != KEY_CHARS || raw.any { it !in ALPHABET }) return null
        return "GRV-" + raw.chunked(4).joinToString("-")
    }

    /** Identifies a PC to the phone without revealing the key. */
    fun deviceId(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(("grooveo-sync:" + key).toByteArray()).take(8)
            .joinToString("") { "%02x".format(it) }

    fun discoveryReply(key: String, port: Int) = "GROOVEO-SYNC! id=${deviceId(key)} port=$port"

    /** Port of the PC in a [discoveryReply] for [id], or null for any other message. */
    fun parseDiscoveryReply(text: String, id: String): Int? {
        val m = Regex("""^GROOVEO-SYNC! id=([0-9a-f]{16}) port=(\d{1,5})$""").matchEntire(text.trim()) ?: return null
        return if (m.groupValues[1] == id) m.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } else null
    }

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
}
