package dev.schlubbe.musicagent.desktop.data

import dev.schlubbe.musicagent.data.backup.SyncProtocol
import dev.schlubbe.musicagent.desktop.ui.screens.qrModules
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncProtocolTest {
    @Test fun `keys are canonical and typed variants normalise`() {
        val k = SyncProtocol.newKey()
        assertTrue(Regex("GRV-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}").matches(k))
        assertEquals(k, SyncProtocol.normalizeKey(k.lowercase().replace("-", " ")))
        assertNull(SyncProtocol.normalizeKey("GRV-AAAA-BBBB")); assertNull(SyncProtocol.normalizeKey("GRV-0000-1111-OOOO"))
    }

    @Test fun `qr uri round trips and hints must be private`() {
        val k = SyncProtocol.newKey()
        val p = SyncProtocol.parsePairing(SyncProtocol.pairingUri("192.168.1.7", 47821, k))!!
        assertEquals(k, p.key); assertEquals("192.168.1.7", p.host); assertEquals(47821, p.port)
        assertNull(SyncProtocol.parsePairing("grooveo://sync?key=$k&host=8.8.8.8&port=1")!!.host)
        assertNull(SyncProtocol.parsePairing("grooveo://sync?host=192.168.1.7"))
        assertEquals(k, SyncProtocol.parsePairing(k)!!.key)
        assertEquals("10.0.0.2", SyncProtocol.parsePairing("10.0.0.2:5-$k")!!.host)
        assertNull(SyncProtocol.parsePairing("8.8.8.8:5-$k"))
    }

    @Test fun `device id does not reveal the key and replies must prove it`() {
        val k = SyncProtocol.newKey()
        val keys = SyncProtocol.keys(k)
        assertTrue(k.replace("-", "") !in keys.deviceId)
        assertNotEquals(SyncProtocol.legacyDeviceId(k), keys.deviceId)
        val nonce = SyncProtocol.newNonce()
        assertEquals(nonce, SyncProtocol.parseDiscoveryRequest(SyncProtocol.discoveryRequest(nonce)))
        assertNull(SyncProtocol.parseDiscoveryRequest(SyncProtocol.LEGACY_DISCOVERY_REQUEST))
        val reply = SyncProtocol.discoveryReply(keys, nonce, 47821, now = 1234)
        assertTrue(k !in reply)
        val r = SyncProtocol.parseDiscoveryReply(reply, keys, nonce)!!
        assertEquals(47821, r.port); assertEquals(1234, r.time)
        // another nonce (a replayed reply), another key or a changed port do not verify
        assertNull(SyncProtocol.parseDiscoveryReply(reply, keys, SyncProtocol.newNonce()))
        assertNull(SyncProtocol.parseDiscoveryReply(SyncProtocol.discoveryReply(SyncProtocol.keys(SyncProtocol.newKey()), nonce, 47821), keys, nonce))
        assertNull(SyncProtocol.parseDiscoveryReply(reply.replace("port=47821", "port=47820"), keys, nonce))
        // a reply with the same id but no proof, as anyone could send it
        assertNull(SyncProtocol.parseDiscoveryReply("GROOVEO-SYNC! v=2 id=${keys.deviceId} port=1 t=1 mac=" + "0".repeat(64), keys, nonce))
    }

    @Test fun `other protocol versions are recognised`() {
        val k = SyncProtocol.newKey()
        assertTrue(SyncProtocol.isOtherVersionReply("GROOVEO-SYNC! id=${SyncProtocol.legacyDeviceId(k)} port=47821", k))
        assertFalse(SyncProtocol.isOtherVersionReply("GROOVEO-SYNC! id=${SyncProtocol.legacyDeviceId(SyncProtocol.newKey())} port=47821", k))
        assertTrue(SyncProtocol.isOtherVersionReply("GROOVEO-SYNC! v=3 id=0 port=1", k))
        assertFalse(SyncProtocol.isOtherVersionReply(SyncProtocol.discoveryReply(SyncProtocol.keys(k), SyncProtocol.newNonce(), 1), k))
    }

    @Test fun `bodies are encrypted and bound to their request`() {
        val keys = SyncProtocol.keys(SyncProtocol.newKey())
        val plain = "{\"likes\":[]}".toByteArray()
        val sealed = SyncProtocol.seal(keys, plain, SyncProtocol.requestAad("n1"))
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("likes"))
        assertContentEquals(plain, SyncProtocol.open(keys, sealed, SyncProtocol.requestAad("n1")))
        assertFails { SyncProtocol.open(keys, sealed, SyncProtocol.requestAad("n2")) }
        assertFails { SyncProtocol.open(keys, sealed, SyncProtocol.responseAad("n1")) }
        assertFails { SyncProtocol.open(SyncProtocol.keys(SyncProtocol.newKey()), sealed, SyncProtocol.requestAad("n1")) }
        val mac = SyncProtocol.requestMac(keys, "POST", "/sync", 1, "n", sealed)
        assertTrue(SyncProtocol.macEquals(mac, SyncProtocol.requestMac(keys, "POST", "/sync", 1, "n", sealed)))
        assertFalse(SyncProtocol.macEquals(mac, SyncProtocol.requestMac(keys, "POST", "/sync", 2, "n", sealed)))
        assertFalse(SyncProtocol.macEquals(mac, SyncProtocol.requestMac(keys, "POST", "/sync", 1, "n", sealed + 0)))
    }

    @Test fun `only local network peers count`() {
        fun a(s: String) = SyncProtocol.isLocalAddress(InetAddress.getByName(s))
        listOf("10.1.2.3", "172.16.0.1", "192.168.178.20", "169.254.1.1", "127.0.0.1", "::1", "fe80::1", "fd12:3456::1", "fc00::1").forEach { assertTrue(a(it), it) }
        listOf("8.8.8.8", "172.32.0.1", "100.64.0.1", "2001:db8::1", "2a00:1450::1").forEach { assertFalse(a(it), it) }
    }

    @Test fun `qr code is produced`() {
        val m = qrModules(SyncProtocol.pairingUri("192.168.1.7", 47821, SyncProtocol.newKey()))
        assertNotNull(m); assertTrue(m.size >= 21 && m.size == m[0].size)
    }
}
