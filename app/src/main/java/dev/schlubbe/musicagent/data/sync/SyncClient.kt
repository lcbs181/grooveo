package dev.schlubbe.musicagent.data.sync

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.schlubbe.musicagent.data.backup.BackupManager
import dev.schlubbe.musicagent.data.backup.BackupPayload
import dev.schlubbe.musicagent.data.backup.SyncProtocol
import dev.schlubbe.musicagent.data.backup.SyncTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs the library with the PC app ("Mit PC synchronisieren"). The phone is paired once, by
 * scanning the QR code of the PC (or typing its code); the pairing is kept. Each sync finds the
 * PC again by itself: first at the address it had last time, otherwise by a UDP broadcast. Either
 * way the PC first has to prove that it holds the key ([SyncProtocol]); the key itself is never
 * sent. The library is POSTed to the PC encrypted, which merges it with its own and answers with
 * the merged result that is applied here.
 *
 * Plain HTTP over a raw socket ([SyncTransport]): it only ever talks to a LAN address, the bodies
 * are encrypted, and Android's network policy blocks cleartext HTTP for release builds.
 */
@Singleton
class SyncClient @Inject constructor(
    @ApplicationContext context: Context,
    private val backupManager: BackupManager,
) {
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("sync_pairing", Context.MODE_PRIVATE)
    @Volatile private var derived: Pair<String, SyncProtocol.Keys>? = null

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
            val keys = derived?.takeIf { it.first == key }?.second ?: SyncProtocol.keys(key).also { derived = key to it }
            val local = backupManager.buildPayload()
            val json = gson.toJson(local).toByteArray(Charsets.UTF_8)
            val deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS
            var otherVersion = false
            var lastStatus: Int? = null

            // The PC from last time, once it has answered a challenge with our key.
            var answer = lastPeer(keys)?.let { last ->
                otherVersion = last.otherVersion
                last.peer?.let { peer -> runCatching { SyncTransport.sync(peer, keys, json, deadline) }.getOrNull() }
            }
            answer?.let { otherVersion = otherVersion || it.otherVersion; lastStatus = it.status }
            if (answer == null || (answer.status != 200 && answer.status != 429)) {
                val found = SyncTransport.discover(keys, key)
                val peer = found.peer ?: error(
                    when {
                        found.otherVersion || otherVersion -> SyncProtocol.UPDATE_BOTH
                        lastStatus != null -> statusMessage(lastStatus!!)
                        else -> "PC nicht gefunden – gleiches WLAN? Sync am PC eingeschaltet? (Manche Router blockieren die Suche zwischen Geräten.)"
                    },
                )
                answer = try { SyncTransport.sync(peer, keys, json, deadline) } catch (_: IOException) {
                    error("PC nicht erreichbar – gleiches WLAN? Sync am PC eingeschaltet?")
                } catch (_: SecurityException) {
                    error("Antwort vom PC konnte nicht geprüft werden – bitte erneut versuchen")
                }
                prefs.edit().putString(HOST, peer.host).putInt(PORT, peer.port).apply()
            }
            if (answer.otherVersion) error(SyncProtocol.UPDATE_BOTH)
            if (answer.status != 200) error(statusMessage(answer.status))
            val merged = gson.fromJson(String(answer.json ?: ByteArray(0), Charsets.UTF_8), BackupPayload::class.java)
                ?: error("Ungültige Antwort vom PC")
            backupManager.applySynced(merged)
        }
    }

    /** Challenges the PC at the address it had last time; null when there is none. */
    private fun lastPeer(keys: SyncProtocol.Keys): SyncTransport.Discovery? {
        val host = prefs.getString(HOST, null) ?: return null
        val port = prefs.getInt(PORT, 0).takeIf { it > 0 } ?: SyncProtocol.PORT
        return SyncTransport.hello(host, port, keys)
    }

    private fun statusMessage(status: Int) = when (status) {
        401 -> "PC kennt diesen Code nicht mehr – am PC wurde ein neuer erzeugt. Bitte neu koppeln."
        403 -> "PC hat die Anfrage abgelehnt – bitte erneut versuchen"
        426 -> SyncProtocol.UPDATE_BOTH
        429 -> "Zu viele Fehlversuche – bitte kurz warten"
        else -> "PC antwortet mit Fehler $status"
    }

    private companion object {
        const val KEY = "key"
        const val HOST = "host"
        const val PORT = "port"
        const val TOTAL_TIMEOUT_MS = 120_000L
    }
}
