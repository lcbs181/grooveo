package dev.schlubbe.musicagent.playback.eq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EqCodecTest {
    @Test fun `profile round-trips through json`() {
        val p = EqProfile.PRESETS[1].copy(loudness = true, bands = EqProfile.PRESETS[1].bands + EqBand(FilterType.HIGH_PASS, 30.0, 0.0, 0.7, slope = 3))
        assertEquals(p, EqCodec.decode(EqCodec.encode(p)))
        assertEquals(listOf(p, EqProfile.flat()), EqCodec.decodeList(EqCodec.encodeList(listOf(p, EqProfile.flat()))))
    }

    @Test fun `older json without new fields is repaired`() {
        val p = EqCodec.decode("""{"name":"Alt","preampDb":-3.0,"bands":[{"type":"PEAK","freq":100.0,"gainDb":4.0,"q":1.0,"enabled":true}]}""")!!
        assertEquals("Alt", p.name)
        assertEquals(1, p.bands.single().slope)
        assertEquals(90.0, p.bassEnhanceFreq)
    }

    @Test fun `garbage decodes to null or empty`() {
        assertNull(EqCodec.decode("nope"))
        assertNull(EqCodec.decode(null))
        assertEquals(emptyList(), EqCodec.decodeList("[[["))
    }

    @Test fun `legacy presets map to parametric presets`() {
        assertEquals("Bass-Boost", EqCodec.fromLegacy("BASS_BOOST", null).name)
        assertEquals("Höhen-Boost", EqCodec.fromLegacy("TREBLE_BOOST", null).name)
        assertEquals("Vocal", EqCodec.fromLegacy("VOCAL", null).name)
        assertEquals(EqProfile.flat(), EqCodec.fromLegacy("FLAT", null))
        assertEquals(EqProfile.flat(), EqCodec.fromLegacy(null, null))
        assertEquals(EqProfile.flat(), EqCodec.fromLegacy("CUSTOM", listOf(1f)))
    }

    @Test fun `legacy custom gains become bands at the old frequencies`() {
        val p = EqCodec.fromLegacy("CUSTOM", listOf(6f, 3f, 0f, -2f, 4f))
        assertEquals(listOf(60.0, 230.0, 910.0, 3600.0, 14000.0), p.bands.map { it.freq })
        assertEquals(FilterType.LOW_SHELF, p.bands.first().type)
        assertEquals(FilterType.HIGH_SHELF, p.bands.last().type)
        assertEquals(6.0, p.responseDb(30.0) - p.preampDb, 0.6)
        assertTrue(p.preampDb < 0, "auto preamp leaves headroom")
    }
}
