package com.engabd.sendpin.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sennheiser BTD 700's real descriptors, read on a Galaxy S23 through the USB DAC
 * report on 2026-09-29. A UAC1 headset dongle: 24-bit at 44.1/48/96 kHz and 16-bit at
 * 44.1/48 kHz on interface 4, a microphone on interface 3, HID on 0 and 1, and two feature
 * units — 8 on the playback path, 5 on the microphone's.
 */
class Btd700DescriptorsTest {

    private val raw: ByteArray = (
            "12 01 02 00 00 00 00 40 42 35 01 30 01 00 01 02 " +
            "05 01 09 02 37 01 05 01 00 80 32 09 04 00 00 01 " +
            "03 00 00 00 09 21 11 01 00 01 22 0d 01 07 05 81 " +
            "03 40 00 01 09 04 01 00 02 03 00 00 00 09 21 11 " +
            "01 00 01 22 6e 00 07 05 01 03 40 00 01 07 05 82 " +
            "03 40 00 01 09 04 02 00 00 01 01 00 00 0a 24 01 " +
            "00 01 47 00 02 03 04 0c 24 02 04 02 04 00 01 01 " +
            "00 00 00 09 24 06 05 04 01 01 00 00 09 24 03 06 " +
            "01 01 00 05 00 0c 24 02 07 01 01 00 02 03 00 00 " +
            "00 0a 24 06 08 07 01 03 00 00 00 09 24 03 09 02 " +
            "04 00 08 00 09 04 03 00 00 01 02 00 00 09 04 03 " +
            "01 01 01 02 00 00 07 24 01 06 00 01 00 14 24 02 " +
            "01 01 02 10 04 80 bb 00 00 7d 00 80 3e 00 40 1f " +
            "00 09 05 83 01 60 00 01 00 00 07 25 01 01 02 00 " +
            "00 09 04 04 00 00 01 02 00 00 09 04 04 01 01 01 " +
            "02 00 00 07 24 01 07 00 01 00 11 24 02 01 02 03 " +
            "18 03 00 77 01 80 bb 00 44 ac 00 09 05 02 01 40 " +
            "02 01 00 00 07 25 01 01 02 00 00 09 04 04 02 01 " +
            "01 02 00 00 07 24 01 07 00 01 00 0e 24 02 01 02 " +
            "02 10 02 80 bb 00 44 ac 00 09 05 02 01 c0 00 01 " +
            "00 00 07 25 01 01 02 00 00"
        ).trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private val d = checkNotNull(UacDescriptors.parse(raw))

    @Test
    fun `identity and class`() {
        assertEquals(0x3542, d.vendorId)
        assertEquals(0x3001, d.productId)
        assertEquals(0x0100, d.uacVersion)
        assertEquals(2, d.controlInterface)
        assertTrue(d.clockSources.isEmpty())
    }

    @Test
    fun `two playback formats on interface 4, microphone left out`() {
        assertEquals(2, d.outputs.size)
        val (a24, a16) = d.outputs
        assertEquals(4, a24.interfaceNumber)
        assertEquals(1, a24.alternateSetting)
        assertEquals(2, a24.channels)
        assertEquals(3, a24.subslotBytes)
        assertEquals(24, a24.bitResolution)
        assertEquals(listOf(96_000, 48_000, 44_100), a24.sampleRates)
        assertEquals(0x02, a24.endpoint!!.address)
        assertEquals(576, a24.endpoint!!.maxPacketBytes)
        assertNull(a24.feedback)

        assertEquals(2, a16.alternateSetting)
        assertEquals(2, a16.subslotBytes)
        assertEquals(16, a16.bitResolution)
        assertEquals(listOf(48_000, 44_100), a16.sampleRates)
        assertEquals(192, a16.endpoint!!.maxPacketBytes)
    }

    @Test
    fun `unit 8 is the playback volume, unit 5 is the microphone's`() {
        val byId = d.volumeUnits.associateBy { it.id }
        assertTrue(byId.getValue(8).playback)
        assertTrue(byId.getValue(8).masterVolume)
        assertFalse(byId.getValue(5).playback)
    }

    @Test
    fun `report lists only the playback volume`() {
        val text = UacReport.format("Sennheiser BTD 700", d, raw)
        assertTrue(text, text.contains("Hardware volume: unit 8, master, mute"))
        assertFalse(text, text.contains("unit 5"))
    }
}
