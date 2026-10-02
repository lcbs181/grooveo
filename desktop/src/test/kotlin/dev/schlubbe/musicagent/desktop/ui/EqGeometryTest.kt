package dev.schlubbe.musicagent.desktop.ui

import androidx.compose.ui.geometry.Offset
import dev.schlubbe.musicagent.desktop.audio.EqBand
import dev.schlubbe.musicagent.desktop.audio.EqProfile
import dev.schlubbe.musicagent.desktop.audio.FilterType
import dev.schlubbe.musicagent.desktop.ui.screens.EqGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EqGeometryTest {
    @Test fun `frequency axis is logarithmic and invertible`() {
        assertEquals(0f, EqGeometry.freqToX(20.0, 1000f), 1e-3f)
        assertEquals(1000f, EqGeometry.freqToX(20000.0, 1000f), 1e-3f)
        assertEquals(500f, EqGeometry.freqToX(Math.sqrt(20.0 * 20000.0), 1000f), 1e-2f)
        for (f in listOf(31.5, 440.0, 1000.0, 12345.0)) assertEquals(f, EqGeometry.xToFreq(EqGeometry.freqToX(f, 800f), 800f), f * 1e-6)
    }

    @Test fun `gain axis maps and clamps`() {
        assertEquals(150f, EqGeometry.dbToY(0.0, 300f), 1e-3f)
        assertEquals(6.0, EqGeometry.yToDb(EqGeometry.dbToY(6.0, 300f), 300f), 1e-6)
        assertEquals(EqBand.MAX_GAIN, EqGeometry.yToDb(0f, 300f))
    }

    @Test fun `rounding and formatting`() {
        assertEquals(1230.0, EqGeometry.roundFreq(1234.5), 1e-9)
        assertEquals(87.6, EqGeometry.roundFreq(87.63), 1e-9)
        assertEquals(-3.3, EqGeometry.roundDb(-3.26))
        assertEquals("440 Hz", EqGeometry.formatFreq(440.0))
        assertEquals("2,5 kHz", EqGeometry.formatFreq(2500.0))
    }

    @Test fun `hit testing finds the nearest node`() {
        val p = EqProfile(bands = listOf(EqBand(FilterType.PEAK, 100.0, 6.0), EqBand(FilterType.HIGH_PASS, 1000.0, 9.0)))
        val n0 = EqGeometry.nodePos(p, 0, 1000f, 300f)
        assertEquals(0, EqGeometry.hitBand(p, n0 + Offset(5f, 5f), 1000f, 300f, 18f))
        // gainless filter nodes sit on the 0 dB line
        assertEquals(150f, EqGeometry.nodePos(p, 1, 1000f, 300f).y, 1e-3f)
        assertNull(EqGeometry.hitBand(p, Offset(990f, 5f), 1000f, 300f, 18f))
    }
}
