package com.engabd.sendpin.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioMathTest {

    @Test
    fun `packets per second - full speed frames, high speed microframes by interval`() {
        assertEquals(1000, UsbAudioMath.packetsPerSecond(UsbAudioMath.SPEED_FULL, 1))
        assertEquals(8000, UsbAudioMath.packetsPerSecond(UsbAudioMath.SPEED_HIGH, 1))
        assertEquals(4000, UsbAudioMath.packetsPerSecond(UsbAudioMath.SPEED_HIGH, 2))
        assertEquals(1000, UsbAudioMath.packetsPerSecond(UsbAudioMath.SPEED_HIGH, 4))
    }

    @Test
    fun `uac rate encodings round-trip`() {
        assertArrayEquals(byteArrayOf(0x44, 0xAC.toByte(), 0x00), UsbAudioMath.uac1Rate(44_100))
        assertEquals(96_000, UsbAudioMath.readUac1Rate(UsbAudioMath.uac1Rate(96_000), 3))
        assertEquals(null, UsbAudioMath.readUac1Rate(ByteArray(3), 2))
        assertEquals(192_000, UsbAudioMath.readUac2Rate(UsbAudioMath.uac2Rate(192_000), 4))
    }

    /** The native engine's accumulator, restated: every packet takes the whole frames it has earned. */
    private fun schedule(rate: Int, pps: Int, packets: Int): List<Int> {
        var owed = 0L
        return List(packets) {
            owed += rate
            val n = (owed / pps).toInt()
            owed -= n.toLong() * pps
            n
        }
    }

    @Test
    fun `44_1 kHz at full speed is 44 nine times then 45, and never drifts`() {
        val one = schedule(44_100, 1000, 10)
        assertEquals(listOf(44, 44, 44, 44, 44, 44, 44, 44, 44, 45), one)
        assertEquals(44_100 * 60, schedule(44_100, 1000, 60_000).sum())
        assertEquals(96, schedule(96_000, 1000, 5).distinct().single())
    }

    @Test
    fun `tone - 16-bit little endian, both channels equal, right level`() {
        val pcm = UsbAudioMath.tone(48, 0, 48_000, 2, 2, 16, hz = 1000.0, dbfs = -20.0)
        assertEquals(48 * 4, pcm.size)
        fun s16(i: Int) = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
        // Quarter period of 1 kHz at 48 kHz is frame 12: the peak.
        val peak = s16(12 * 4)
        assertEquals(peak, s16(12 * 4 + 2))
        assertEquals((0.1 * 32767).toInt().toDouble(), peak.toDouble(), 1.0)
        assertEquals(0, s16(0))
    }

    @Test
    fun `tone - 24-bit in 3-byte slots, and left-justified in 4-byte slots`() {
        val p3 = UsbAudioMath.tone(48, 0, 48_000, 2, 3, 24)
        val v3 = (p3[72].toInt() and 0xFF) or ((p3[73].toInt() and 0xFF) shl 8) or (p3[74].toInt() shl 16) // frame 12, 6-byte frames
        assertEquals(0.1 * 8_388_607, v3.toDouble(), 1.0)

        val p4 = UsbAudioMath.tone(48, 0, 48_000, 2, 4, 24)
        assertEquals(0, p4[12 * 8].toInt()) // the low byte of a left-justified 24-bit sample is padding
        assertTrue(p4[12 * 8 + 3].toInt() != 0)
    }

    @Test
    fun `tone phase is continuous across chunks`() {
        val whole = UsbAudioMath.tone(100, 0, 44_100, 2, 2, 16)
        val a = UsbAudioMath.tone(37, 0, 44_100, 2, 2, 16)
        val b = UsbAudioMath.tone(63, 37, 44_100, 2, 2, 16)
        assertArrayEquals(whole, a + b)
    }
}
