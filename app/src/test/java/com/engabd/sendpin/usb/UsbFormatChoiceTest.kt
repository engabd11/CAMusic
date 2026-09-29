package com.engabd.sendpin.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbFormatChoiceTest {

    private val ep = IsoEndpoint(0x02, "none", "data", 576, 1, 1)
    private fun alt(n: Int, bits: Int, slot: Int, vararg rates: Int) =
        StreamingAlt(4, n, 7, 2, slot, bits, rates.toList(), null, ep, null)

    /** The Sennheiser BTD 700: 24-bit in 3-byte slots at 44.1/48/96, 16-bit at 44.1/48. */
    private val btd700 = listOf(alt(1, 24, 3, 96_000, 48_000, 44_100), alt(2, 16, 2, 48_000, 44_100))

    @Test
    fun `a 16-bit file goes to the 16-bit format, not padded`() {
        assertEquals(2, UsbFormatChoice.choose(btd700, 44_100, 2, 16)!!.alternateSetting)
    }

    @Test
    fun `a 24-bit file goes to the 24-bit format`() {
        assertEquals(1, UsbFormatChoice.choose(btd700, 44_100, 2, 24)!!.alternateSetting)
    }

    @Test
    fun `a rate only the 24-bit format has takes it, padding a 16-bit file`() {
        assertEquals(1, UsbFormatChoice.choose(btd700, 96_000, 2, 16)!!.alternateSetting)
    }

    @Test
    fun `a rate the DAC does not have is no choice at all`() {
        assertNull(UsbFormatChoice.choose(btd700, 192_000, 2, 24))
    }

    @Test
    fun `unknown depth takes the deepest`() {
        assertEquals(1, UsbFormatChoice.choose(btd700, 44_100, 2, null)!!.alternateSetting)
    }

    @Test
    fun `smallest format that holds every bit, before a deeper one`() {
        val dac = listOf(alt(1, 32, 4, 44_100), alt(2, 24, 4, 44_100))
        assertEquals(2, UsbFormatChoice.choose(dac, 44_100, 2, 20)!!.alternateSetting)
    }

    @Test
    fun `nothing deep enough falls back to the deepest there is`() {
        val dac = listOf(alt(1, 16, 2, 44_100))
        assertEquals(1, UsbFormatChoice.choose(dac, 44_100, 2, 24)!!.alternateSetting)
    }

    @Test
    fun `mono tracks do not take a stereo format`() {
        assertNull(UsbFormatChoice.choose(btd700, 44_100, 1, 16))
    }
}
