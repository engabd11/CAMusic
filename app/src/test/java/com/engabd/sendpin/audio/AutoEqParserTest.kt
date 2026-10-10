package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading AutoEQ's ParametricEQ.txt, including the variants found in the wild. */
class AutoEqParserTest {

    // The shape AutoEQ publishes (Sennheiser HD 600, abridged).
    private val hd600 = """
        Preamp: -6.4 dB
        Filter 1: ON LSC Fc 105 Hz Gain 6.3 dB Q 0.70
        Filter 2: ON PK Fc 2399 Hz Gain -2.1 dB Q 1.74
        Filter 3: ON PK Fc 4920 Hz Gain 3.4 dB Q 4.06
        Filter 4: ON HSC Fc 10000 Hz Gain -1.2 dB Q 0.70
    """.trimIndent()

    @Test
    fun `an AutoEQ file becomes a parametric curve with its own preamp`() {
        val result = assertNotNull(AutoEqParser.parse(hd600))
        val config = result.config
        assertTrue(config.enabled)
        assertTrue(config.parametric)
        assertFalse(config.autoPreamp)
        assertEquals(-6.4f, config.preampDb, 1e-4f)
        assertEquals(4, config.bands.size)
        assertEquals(0, result.skipped)
        with(config.bands[0]) {
            assertEquals(LocalDsp.Band.Type.LOW_SHELF, type)
            assertEquals(105f, frequency)
            assertEquals(6.3f, gainDb, 1e-4f)
            assertEquals(0.70f, q, 1e-4f)
        }
        assertEquals(LocalDsp.Band.Type.PEAKING, config.bands[1].type)
        assertEquals(LocalDsp.Band.Type.HIGH_SHELF, config.bands[3].type)
    }

    @Test
    fun `comma decimals, kHz and unnumbered filters read the same`() {
        val text = "Preamp: -3,5 dB\r\nFilter: ON PK Fc 1,2 kHz Gain -2,5 dB Q 1,41\r\n"
        val config = assertNotNull(AutoEqParser.parse(text)).config
        assertEquals(-3.5f, config.preampDb, 1e-4f)
        with(config.bands.single()) {
            assertEquals(1_200f, frequency, 1e-2f)
            assertEquals(-2.5f, gainDb, 1e-4f)
            assertEquals(1.41f, q, 1e-4f)
        }
    }

    @Test
    fun `shelves and pass filters without a Q get Butterworth`() {
        val text = "Filter 1: ON LS 6dB Fc 80 Hz Gain 4 dB\nFilter 2: ON HP Fc 20 Hz"
        val bands = assertNotNull(AutoEqParser.parse(text)).config.bands
        assertEquals(LocalDsp.Band.Type.LOW_SHELF, bands[0].type)
        assertEquals(0.71f, bands[0].q, 1e-4f)
        assertEquals(LocalDsp.Band.Type.HIGH_PASS, bands[1].type)
        assertEquals(0.71f, bands[1].q, 1e-4f)
    }

    @Test
    fun `a filter switched off is kept, disabled`() {
        val text = "Filter 1: ON PK Fc 100 Hz Gain 2 dB Q 1\nFilter 2: OFF PK Fc 200 Hz Gain 3 dB Q 1"
        val bands = assertNotNull(AutoEqParser.parse(text)).config.bands
        assertEquals(listOf(true, false), bands.map { it.enabled })
    }

    @Test
    fun `kinds this equaliser cannot run are counted and left out`() {
        val text = "Filter 1: ON PK Fc 100 Hz Gain 2 dB Q 1\nFilter 2: ON NO Fc 60 Hz Q 30\nFilter 3: ON AP Fc 500 Hz Q 1"
        val result = assertNotNull(AutoEqParser.parse(text))
        assertEquals(1, result.config.bands.size)
        assertEquals(2, result.skipped)
    }

    @Test
    fun `text with nothing usable is not a curve`() {
        assertNull(AutoEqParser.parse(""))
        assertNull(AutoEqParser.parse("Preamp: -6 dB"))
        assertNull(AutoEqParser.parse("hello\nworld"))
        assertNull(AutoEqParser.parse("Filter 1: OFF PK Fc 100 Hz Gain 2 dB Q 1"))
    }

    @Test
    fun `wild values are kept inside what the filters can take`() {
        val text = "Preamp: -99 dB\nFilter 1: ON PK Fc 90000 Hz Gain 80 dB Q 0"
        val config = assertNotNull(AutoEqParser.parse(text)).config
        assertEquals(-30f, config.preampDb)
        with(config.bands.single()) {
            assertEquals(22_000f, frequency)
            assertEquals(30f, gainDb)
            assertEquals(0.71f, q, 1e-4f)
        }
    }

    @Test
    fun `no more bands than the cap`() {
        val text = (1..40).joinToString("\n") { "Filter $it: ON PK Fc ${it * 100} Hz Gain 1 dB Q 1" }
        val result = assertNotNull(AutoEqParser.parse(text))
        assertEquals(AutoEqParser.MAX_BANDS, result.config.bands.size)
        assertEquals(40 - AutoEqParser.MAX_BANDS, result.skipped)
    }

    @Test
    fun `an AutoEQ file name becomes the curve's name`() {
        assertEquals("Sennheiser HD 600", com.engabd.sendpin.ui.screens.nameFromFile("Sennheiser HD 600 ParametricEQ.txt"))
        assertEquals("My curve", com.engabd.sendpin.ui.screens.nameFromFile("My curve.txt"))
    }
}
