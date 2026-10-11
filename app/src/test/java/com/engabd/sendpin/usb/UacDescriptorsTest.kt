package com.engabd.sendpin.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UacDescriptorsTest {

    private fun bytes(vararg v: Int) = v.map { it.toByte() }
    private fun le16(v: Int) = bytes(v and 0xFF, v shr 8 and 0xFF)
    private fun le24(v: Int) = bytes(v and 0xFF, v shr 8 and 0xFF, v shr 16 and 0xFF)
    private fun le32(v: Int) = bytes(v and 0xFF, v shr 8 and 0xFF, v shr 16 and 0xFF, v shr 24 and 0xFF)
    private fun desc(type: Int, body: List<Byte>) = bytes(body.size + 2, type) + body

    private fun device(vid: Int, pid: Int) =
        desc(1, bytes(0x00, 0x02, 0, 0, 0, 64) + le16(vid) + le16(pid) + bytes(0, 1, 1, 2, 3, 1))

    private fun iface(num: Int, alt: Int, eps: Int, sub: Int, protocol: Int) =
        desc(4, bytes(num, alt, eps, 1, sub, protocol, 0))

    private fun endpoint(addr: Int, attrs: Int, maxPacket: Int, interval: Int, uac1: Boolean) =
        desc(5, bytes(addr, attrs) + le16(maxPacket) + bytes(interval) + if (uac1) bytes(0, 0) else emptyList())

    /** A UAC1 dongle: 16- and 24-bit alternates, rates listed, adaptive OUT endpoint. */
    private fun uac1Dongle(): ByteArray = (
        device(0x3542, 0x3001) +
            iface(0, 0, 0, sub = 1, protocol = 0) +
            desc(0x24, bytes(0x01) + le16(0x0100) + le16(40) + bytes(1, 1)) +
            // Feature unit 2: bControlSize 1, master mute+volume, two channels none.
            desc(0x24, bytes(0x06, 2, 1, 1, 0x03, 0x00, 0x00, 0)) +
            iface(1, 0, 0, sub = 2, protocol = 0) +
            iface(1, 1, 1, sub = 2, protocol = 0) +
            desc(0x24, bytes(0x01, 1, 1) + le16(1)) +
            desc(0x24, bytes(0x02, 1, 2, 2, 16, 2) + le24(44_100) + le24(48_000)) +
            endpoint(0x01, 0x09, 192, 1, uac1 = true) +
            iface(1, 2, 1, sub = 2, protocol = 0) +
            desc(0x24, bytes(0x01, 1, 1) + le16(1)) +
            desc(0x24, bytes(0x02, 1, 2, 3, 24, 3) + le24(44_100) + le24(48_000) + le24(96_000)) +
            endpoint(0x01, 0x09, 576, 1, uac1 = true)
        ).toByteArray()

    /** A UAC2 high-speed DAC: clock source, async OUT with a feedback endpoint. */
    private fun uac2Dac(): ByteArray = (
        device(0x20B1, 0x3008) +
            iface(0, 0, 0, sub = 1, protocol = 0x20) +
            desc(0x24, bytes(0x01) + le16(0x0200) + bytes(0x08) + le16(60) + bytes(0)) +
            // Clock source 41: internal programmable, frequency control read/write.
            desc(0x24, bytes(0x0A, 41, 0x03, 0x07, 0, 0)) +
            // Feature unit 10 from 2: master volume (bits 2-3) and mute (0-1), two channels.
            desc(0x24, bytes(0x06, 10, 2) + le32(0x0F) + le32(0) + le32(0) + bytes(0)) +
            iface(1, 0, 0, sub = 2, protocol = 0x20) +
            iface(1, 1, 2, sub = 2, protocol = 0x20) +
            desc(0x24, bytes(0x01, 1, 0, 1) + le32(1) + bytes(2) + le32(3) + bytes(0)) +
            desc(0x24, bytes(0x02, 1, 4, 24)) +
            endpoint(0x01, 0x05, 1024, 1, uac1 = false) +
            endpoint(0x81, 0x11, 4, 4, uac1 = false) +
            // A microphone-direction streaming interface must not be listed as an output.
            iface(2, 1, 1, sub = 2, protocol = 0x20) +
            desc(0x24, bytes(0x02, 1, 2, 16)) +
            endpoint(0x82, 0x05, 196, 1, uac1 = false)
        ).toByteArray()

    @Test
    fun `uac1 dongle - ids, version, formats and listed rates`() {
        val d = checkNotNull(UacDescriptors.parse(uac1Dongle()))
        assertEquals(0x3542, d.vendorId)
        assertEquals(0x3001, d.productId)
        assertEquals(0x0100, d.uacVersion)
        assertEquals(0, d.controlInterface)
        assertEquals(2, d.outputs.size)

        val a16 = d.outputs[0]
        assertEquals(1, a16.alternateSetting)
        assertEquals(2, a16.channels)
        assertEquals(2, a16.subslotBytes)
        assertEquals(16, a16.bitResolution)
        assertEquals(listOf(44_100, 48_000), a16.sampleRates)
        assertEquals("adaptive", a16.endpoint!!.sync)
        assertNull(a16.feedback)

        val a24 = d.outputs[1]
        assertEquals(3, a24.subslotBytes)
        assertEquals(24, a24.bitResolution)
        assertEquals(listOf(44_100, 48_000, 96_000), a24.sampleRates)
        assertEquals(576, a24.endpoint!!.maxPacketBytes)

        assertEquals(1, d.volumeUnits.size)
        assertTrue(d.volumeUnits[0].masterVolume)
        assertTrue(d.volumeUnits[0].mute)
    }

    @Test
    fun `uac2 dac - clock source, async endpoint with feedback, mic interface ignored`() {
        val d = checkNotNull(UacDescriptors.parse(uac2Dac()))
        assertEquals(0x0200, d.uacVersion)
        assertEquals(1, d.clockSources.size)
        assertEquals(41, d.clockSources[0].id)
        assertEquals("internal programmable", d.clockSources[0].type)
        assertEquals("read/write", d.clockSources[0].frequencyControl)

        assertEquals(1, d.outputs.size)
        val out = d.outputs[0]
        assertEquals(2, out.channels)
        assertEquals(4, out.subslotBytes)
        assertEquals(24, out.bitResolution)
        assertTrue(out.sampleRates.isEmpty())
        assertEquals("asynchronous", out.endpoint!!.sync)
        assertEquals(0x81, out.feedback!!.address)
        assertEquals("feedback", out.feedback!!.usage)

        assertTrue(d.volumeUnits.single().masterVolume)
    }

    @Test
    fun `a device with no audio control interface is not audio`() {
        val raw = (device(1, 2) + desc(4, bytes(0, 0, 1, 3, 1, 1, 0))).toByteArray()
        assertNull(UacDescriptors.parse(raw))
    }

    @Test
    fun `truncated descriptors stop cleanly`() {
        val full = uac1Dongle()
        val d = UacDescriptors.parse(full.copyOf(full.size - 5))
        assertNotNull(d)
        assertEquals(1, d!!.outputs.size)
    }

    @Test
    fun `uac2 rate ranges - discrete and continuous`() {
        val reply = (
            le16(3) +
                le32(44_100) + le32(44_100) + le32(0) +
                le32(48_000) + le32(48_000) + le32(0) +
                le32(88_200) + le32(192_000) + le32(3_900)
            ).toByteArray()
        // 88.2k..192k in 3.9k steps lands on 96k (two steps) but not on 176.4k or 192k.
        val rates = UacDescriptors.parseRateRanges(reply)
        assertEquals(listOf(44_100, 48_000, 88_200, 96_000), rates)
    }

    @Test
    fun `rate reply shorter than it claims is read as far as it goes`() {
        val reply = (le16(2) + le32(96_000) + le32(96_000) + le32(0)).toByteArray()
        assertEquals(listOf(96_000), UacDescriptors.parseRateRanges(reply))
    }

    /**
     * A UAC2 DAC whose streaming terminal is clocked through a selector (pin 1: internal
     * 41, pin 2: a multiplier 50 over external 42) - the case where "the first clock
     * source in the descriptors" was the wrong place to set a rate.
     */
    private fun uac2WithSelector(terminalClock: Int): ByteArray = (
        device(0x1234, 0x5678) +
            iface(0, 0, 0, sub = 1, protocol = 0x20) +
            desc(0x24, bytes(0x01) + le16(0x0200) + bytes(0x08) + le16(80) + bytes(0)) +
            desc(0x24, bytes(0x0A, 41, 0x03, 0x07, 0, 0)) +
            desc(0x24, bytes(0x0A, 42, 0x00, 0x01, 0, 0)) +
            desc(0x24, bytes(0x0C, 50, 42, 0, 0)) +
            desc(0x24, bytes(0x0B, 40, 2, 41, 50, 0x03, 0)) +
            // USB streaming input terminal 2, clocked from [terminalClock].
            desc(0x24, bytes(0x02, 2) + le16(0x0101) + bytes(0, terminalClock, 2) + le32(3) + bytes(0) + le16(0) + bytes(0)) +
            iface(1, 0, 0, sub = 2, protocol = 0x20) +
            iface(1, 1, 1, sub = 2, protocol = 0x20) +
            desc(0x24, bytes(0x01, 2, 0, 1) + le32(1) + bytes(2) + le32(3) + bytes(0)) +
            desc(0x24, bytes(0x02, 1, 4, 24)) +
            endpoint(0x01, 0x05, 1024, 1, uac1 = false)
        ).toByteArray()

    @Test
    fun `uac2 clock - the terminal's selector decides, on its current pin`() {
        val d = checkNotNull(UacDescriptors.parse(uac2WithSelector(terminalClock = 40)))
        assertEquals(mapOf(2 to 40), d.terminalClocks)
        assertEquals(mapOf(40 to listOf(41, 50)), d.clockSelectors)
        assertEquals(mapOf(50 to 42), d.clockMultipliers)
        val alt = d.outputs.single()
        // Selector on pin 1: the internal clock.
        assertEquals(41, d.clockSourceFor(alt.terminalLink) { 1 }?.id)
        // Pin 2: through the multiplier to the external clock.
        assertEquals(42, d.clockSourceFor(alt.terminalLink) { 2 }?.id)
        // The selector would not say: its first pin.
        assertEquals(41, d.clockSourceFor(alt.terminalLink) { null }?.id)
    }

    @Test
    fun `uac2 clock - a terminal clocked straight from a source, and one with no terminal`() {
        val d = checkNotNull(UacDescriptors.parse(uac2WithSelector(terminalClock = 42)))
        assertEquals(42, d.clockSourceFor(2)?.id)
        // A device that names no terminal clock keeps the old answer: the first source.
        val plain = checkNotNull(UacDescriptors.parse(uac2Dac()))
        assertEquals(41, plain.clockSourceFor(1)?.id)
    }
}
