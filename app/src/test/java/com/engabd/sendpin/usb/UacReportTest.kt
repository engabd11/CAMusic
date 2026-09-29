package com.engabd.sendpin.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UacReportTest {

    private val out = StreamingAlt(
        interfaceNumber = 1, alternateSetting = 2, terminalLink = 1, channels = 2,
        subslotBytes = 3, bitResolution = 24, sampleRates = listOf(44_100, 48_000, 96_000),
        sampleRateRange = null,
        endpoint = IsoEndpoint(0x01, "asynchronous", "data", 576, 1, 1),
        feedback = IsoEndpoint(0x81, "asynchronous", "feedback", 3, 1, 1),
    )

    @Test
    fun `report names the device, its class, formats and raw bytes`() {
        val d = UacDevice(0x3542, 0x3001, 0x0100, 0, emptyList(), emptyList(), listOf(out))
        val text = UacReport.format("Sennheiser BTD 700", d, byteArrayOf(0x12, 0x01, 0x00, 0x02))
        assertTrue(text, text.contains("USB DAC: Sennheiser BTD 700"))
        assertTrue(text, text.contains("Id: 3542:3001"))
        assertTrue(text, text.contains("USB Audio Class: 1.0"))
        assertTrue(text, text.contains("2 ch, 24-bit in 24-bit slots, 44.1 kHz / 48 kHz / 96 kHz"))
        assertTrue(text, text.contains("Endpoint 0x01: asynchronous, up to 576 bytes x1, interval 1, feedback on 0x81"))
        assertTrue(text, text.contains("Hardware volume: none"))
        assertTrue(text, text.contains("12 01 00 02"))
    }

    @Test
    fun `uac2 clock rates are shown, or why they are missing`() {
        val clock = ClockSource(41, "internal programmable", "read/write")
        val d = UacDevice(1, 2, 0x0200, 0, listOf(clock), emptyList(), emptyList())
        assertTrue(UacReport.format("x", d, null, mapOf(41 to listOf(44_100, 192_000))).contains("rates 44.1 kHz / 192 kHz"))
        assertTrue(UacReport.format("x", d, null, null).contains("rates not readable while Android holds the DAC"))
    }

    @Test
    fun `khz formatting`() {
        assertEquals("44.1 kHz", UacReport.khz(44_100))
        assertEquals("48 kHz", UacReport.khz(48_000))
        assertEquals("352.8 kHz", UacReport.khz(352_800))
    }
}
