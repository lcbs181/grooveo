package dev.schlubbe.musicagent.desktop.data

import dev.schlubbe.musicagent.data.backup.SyncProtocol
import dev.schlubbe.musicagent.desktop.ui.screens.qrModules
import kotlin.test.Test
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

    @Test fun `device id does not reveal the key and replies are matched by id`() {
        val k = SyncProtocol.newKey()
        val id = SyncProtocol.deviceId(k)
        assertTrue(k.replace("-", "") !in id)
        assertEquals(47821, SyncProtocol.parseDiscoveryReply(SyncProtocol.discoveryReply(k, 47821), id))
        assertNull(SyncProtocol.parseDiscoveryReply(SyncProtocol.discoveryReply(SyncProtocol.newKey(), 47821), id))
    }

    @Test fun `qr code is produced`() {
        val m = qrModules(SyncProtocol.pairingUri("192.168.1.7", 47821, SyncProtocol.newKey()))
        assertNotNull(m); assertTrue(m.size >= 21 && m.size == m[0].size)
    }
}
