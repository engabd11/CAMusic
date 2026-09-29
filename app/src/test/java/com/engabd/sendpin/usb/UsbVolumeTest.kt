package com.engabd.sendpin.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbVolumeTest {

    /** The Sennheiser BTD 700's playback volume, as it reported it: -45..0 dB in 1 dB steps. */
    private val btd700 = Triple(-11520, 0, 256)

    @Test
    fun `level maps linearly in decibels and snaps to the DAC's step`() {
        assertEquals(-11520, UsbVolume.valueOf(0f, btd700))
        assertEquals(0, UsbVolume.valueOf(1f, btd700))
        // Half way is -22.5 dB, which snaps to a whole dB.
        val mid = UsbVolume.valueOf(0.5f, btd700)
        assertEquals(0, mid % 256)
        assertEquals(-22.5, mid / 256.0, 0.5)
    }

    @Test
    fun `reading back gives the level it was set to`() {
        // -9 dB: what the BTD 700 was sitting at when first read.
        assertEquals(36f / 45f, UsbVolume.levelOf(-2304, btd700), 1e-4f)
        for (step in 0..45) {
            val level = step / 45f
            assertEquals(level, UsbVolume.levelOf(UsbVolume.valueOf(level, btd700), btd700), 1e-4f)
        }
    }

    @Test
    fun `one volume key per decibel on the BTD 700`() {
        assertEquals(45, UsbVolume.stepsOf(btd700))
        // A DAC with a very fine step is capped so keys still move audibly.
        assertEquals(100, UsbVolume.stepsOf(Triple(-32767, 0, 1)))
    }

    @Test
    fun `digital gain is unity at the top, silent at zero, decibel-linear between`() {
        assertEquals(1f, UsbVolume.gainOf(1f), 1e-6f)
        assertEquals(0f, UsbVolume.gainOf(0f), 0f)
        // Half way down a 60 dB range is -30 dB.
        assertEquals(Math.pow(10.0, -30.0 / 20).toFloat(), UsbVolume.gainOf(0.5f), 1e-6f)
    }
}
