package dev.schlubbe.musicagent.ui.settings

import dev.schlubbe.musicagent.playback.eq.EqBand
import dev.schlubbe.musicagent.playback.eq.FilterType
import kotlin.test.Test
import kotlin.test.assertEquals

class EqGraphTest {
    @Test fun `frequency axis is logarithmic and invertible`() {
        assertEquals(0f, EqGraph.freqToX(20.0, 300f), 1e-3f)
        assertEquals(300f, EqGraph.freqToX(20000.0, 300f), 1e-3f)
        assertEquals(100f, EqGraph.freqToX(200.0, 300f), 1e-3f)
        assertEquals(1234.0, EqGraph.xToFreq(EqGraph.freqToX(1234.0, 300f), 300f), 1e-3)
    }

    @Test fun `gain axis is centred and clamped`() {
        assertEquals(100f, EqGraph.dbToY(0.0, 200f), 1e-3f)
        assertEquals(0f, EqGraph.dbToY(15.0, 200f), 1e-3f)
        assertEquals(6.0, EqGraph.yToDb(EqGraph.dbToY(6.0, 200f), 200f), 1e-6)
        assertEquals(-15.0, EqGraph.yToDb(500f, 200f))
    }

    @Test fun `rounding snaps like eq software`() {
        assertEquals(63.0, EqGraph.roundFreq(63.4))
        assertEquals(1230.0, EqGraph.roundFreq(1234.0))
        assertEquals(12300.0, EqGraph.roundFreq(12340.0))
        assertEquals(2.5, EqGraph.roundDb(2.4))
        assertEquals("1 kHz", formatFreq(1000.0))
        assertEquals("63 Hz", formatFreq(63.0))
    }

    @Test fun `hit test picks nearest node within radius`() {
        val bands = listOf(EqBand(FilterType.PEAK, 100.0, 0.0), EqBand(FilterType.PEAK, 1000.0, 6.0), EqBand(FilterType.LOW_PASS, 10000.0, 9.0))
        val w = 300f; val h = 200f
        assertEquals(1, EqGraph.hit(bands, EqGraph.freqToX(1000.0, w) + 3, EqGraph.dbToY(6.0, h), w, h, 20f))
        // passes have no gain: their node sits on the 0 dB line
        assertEquals(2, EqGraph.hit(bands, EqGraph.freqToX(10000.0, w), h / 2, w, h, 20f))
        assertEquals(-1, EqGraph.hit(bands, 0f, 0f, w, h, 20f))
    }
}
