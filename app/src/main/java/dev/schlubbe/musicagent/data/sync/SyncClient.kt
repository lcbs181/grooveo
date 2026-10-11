package dev.schlubbe.musicagent.data.sync

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.schlubbe.musicagent.data.backup.BackupManager
import dev.schlubbe.musicagent.data.backup.BackupPayload
import dev.schlubbe.musicagent.data.backup.SyncProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs the library with the PC app ("Mit PC synchronisieren"). The phone is paired once, by
 * scanning the QR code of the PC (or typing its code); the pairing is kept. Each sync finds the
 * PC again by itself: first at the address it had last time, otherwise by a UDP broadcast that
 * only the PC with the matching id answers ([SyncProtocol]). The library is POSTed to the PC,
 * which merges it with its own and answers with the merged result that is applied here.
 *
 * Plain HTTP over a raw socket: it only ever talks to a private LAN address, and Android's
 * network policy blocks cleartext HTTP for release builds.
 */
@Singleton
class SyncClient @Inject constructor(
    @ApplicationContext context: Context,
    private val backupManager: BackupManager,
) {
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("sync_pairing", Context.MODE_PRIVATE)

    /** Whether this phone has been paired with a PC. */
    val isPaired: Boolean get() = prefs.getString(KEY, null) != null

    /** Stores the pairing from a scanned QR code or typed code; false when [text] is not a valid code. */
    fun pair(text: String): Boolean {
        val p = SyncProtocol.parsePairing(text) ?: return false
        prefs.edit().putString(KEY, p.key).putString(HOST, p.host).putInt(PORT, p.port ?: 0).apply()
        return true
    }

    fun unpair() = prefs.edit().clear().apply()

    suspend fun sync(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val key = prefs.getString(KEY, null) ?: error("Noch nicht mit einem PC gekoppelt")
            val local = backupManager.buildPayload()
            val body = gson.toJson(local).toByteArray(Charsets.UTF_8)
            val (status, text) = tryLast(key, body) ?: discover(key, body)
            when (status) {
                200 -> Unit
                401 -> error("PC kennt diesen Code nicht mehr – am PC wurde ein neuer erzeugt. Bitte neu koppeln.")
                429 -> error("Zu viele Fehlversuche – bitte kurz warten")
                else -> error("PC antwortet mit Fehler $status")
            }
            val merged = gson.fromJson(text, BackupPayload::class.java) ?: error("Ungültige Antwort vom PC")
            backupManager.applySynced(merged)
        }
    }

    private fun tryLast(key: String, body: ByteArray): Pair<Int, String>? {
        val host = prefs.getString(HOST, null) ?: return null
        val port = prefs.getInt(PORT, 0).takeIf { it > 0 } ?: SyncProtocol.PORT
        return try { post(host, port, key, body) } catch (_: IOException) { null }
    }

    /** Looks for the PC with the broadcast and syncs with it. */
    private fun discover(key: String, body: ByteArray): Pair<Int, String> {
        val id = SyncProtocol.deviceId(key)
        val found = findHost(id) ?: throw IllegalStateException(
            "PC nicht gefunden – gleiches WLAN? Sync am PC eingeschaltet? (Manche Router blockieren die Suche zwischen Geräten.)",
        )
        val res = try { post(found.first, found.second, key, body) } catch (_: IOException) {
            throw IllegalStateException("PC nicht erreichbar – gleiches WLAN? Sync am PC eingeschaltet?")
        }
        prefs.edit().putString(HOST, found.first).putInt(PORT, found.second).apply()
        return res
    }

    private fun findHost(id: String): Pair<String, Int>? = runCatching {
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 700
            val req = SyncProtocol.DISCOVERY_REQUEST.toByteArray()
            repeat(3) {
                s.send(DatagramPacket(req, req.size, InetAddress.getByName("255.255.255.255"), SyncProtocol.DISCOVERY_PORT))
                val deadline = System.currentTimeMillis() + 1_000
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val p = DatagramPacket(ByteArray(256), 256)
                        s.receive(p)
                        val host = p.address.hostAddress ?: continue
                        val port = SyncProtocol.parseDiscoveryReply(String(p.data, 0, p.length), id) ?: continue
                        if (SyncProtocol.isPrivateAddress(host)) return@runCatching host to port
                    } catch (_: SocketTimeoutException) { break }
                }
            }
            null
        }
    }.getOrNull()

    private fun post(host: String, port: Int, key: String, body: ByteArray): Pair<Int, String> = Socket().use { s ->
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.soTimeout = READ_TIMEOUT_MS
        val head = "POST /sync HTTP/1.1\r\nHost: $host:$port\r\n${SyncProtocol.KEY_HEADER}: $key\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        s.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(body); flush() }
        val raw = ByteArrayOutputStream().also { s.getInputStream().copyTo(it) }.toByteArray()
        val split = indexOfHeaderEnd(raw)
        require(split > 0) { "Ungültige Antwort vom PC" }
        val status = String(raw, 0, split, Charsets.ISO_8859_1).lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull()
            ?: error("Ungültige Antwort vom PC")
        status to String(raw, split + 4, raw.size - split - 4, Charsets.UTF_8)
    }

    private fun indexOfHeaderEnd(b: ByteArray): Int {
        for (i in 0..b.size - 4) if (b[i] == 13.toByte() && b[i + 1] == 10.toByte() && b[i + 2] == 13.toByte() && b[i + 3] == 10.toByte()) return i
        return -1
    }

    private companion object {
        const val KEY = "key"
        const val HOST = "host"
        const val PORT = "port"
        const val CONNECT_TIMEOUT_MS = 2_500
        const val READ_TIMEOUT_MS = 60_000
    }
}
