package com.engabd.sendpin.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure-JVM tests: run with `./gradlew :app:testDebugUnitTest`. */
class OggOpusPacketizerTest {

    /** Minimal reader for what these tests need: header fields plus the one packet. */
    private data class ParsedPage(
        val headerType: Int,
        val granule: Long,
        val serial: Int,
        val sequence: Int,
        val packet: ByteArray,
        val pageSize: Int,
    )

    private fun parsePage(bytes: ByteArray, offset: Int = 0): ParsedPage {
        val buf = ByteBuffer.wrap(bytes, offset, bytes.size - offset).slice().order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("OggS", String(bytes, offset, 4, Charsets.US_ASCII))
        val headerType = buf.get(5).toInt() and 0xFF
        val granule = buf.getLong(6)
        val serial = buf.getInt(14)
        val sequence = buf.getInt(18)
        val segCount = buf.get(26).toInt() and 0xFF
        var packetLen = 0
        for (i in 0 until segCount) packetLen += buf.get(27 + i).toInt() and 0xFF
        val packetStart = offset + 27 + segCount
        val packet = bytes.copyOfRange(packetStart, packetStart + packetLen)
        return ParsedPage(headerType, granule, serial, sequence, packet, (27 + segCount + packetLen))
    }

    /**
     * Independent, bit-by-bit implementation of the same Ogg CRC-32 (RFC 3533
     * Appendix A), deliberately not sharing code with [OggOpusPacketizer]'s
     * table-driven version - the point is to catch a transcription error in
     * either implementation by disagreement, not to duplicate one.
     */
    private fun bitwiseOggCrc(page: ByteArray): Long {
        var crc = 0L
        for (b in page) {
            crc = crc xor ((b.toLong() and 0xFF) shl 24)
            repeat(8) {
                crc = if ((crc and 0x80000000L) != 0L) {
                    ((crc shl 1) xor 0x04c11db7L) and 0xFFFFFFFFL
                } else {
                    (crc shl 1) and 0xFFFFFFFFL
                }
            }
        }
        return crc
    }

    private fun withChecksumZeroed(page: ByteArray): ByteArray =
        page.copyOf().also { it[22] = 0; it[23] = 0; it[24] = 0; it[25] = 0 }

    @Test
    fun `checksum matches an independently implemented CRC`() {
        val muxer = OggOpusPacketizer(serialNumber = 42)
        val page = muxer.audioPage(opusPacket(config = 0, frameCountCode = 0))
        val expected = bitwiseOggCrc(withChecksumZeroed(page))
        val buf = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
        val actual = buf.getInt(22).toLong() and 0xFFFFFFFFL
        assertEquals(expected, actual)
    }

    @Test
    fun `header pages are OpusHead then OpusTags, both BOS-correct`() {
        val muxer = OggOpusPacketizer(serialNumber = 7)
        val opusHead = "OpusHead".toByteArray() + ByteArray(11)
        val pages = muxer.headerPages(opusHead)

        val head = parsePage(pages, 0)
        assertEquals(0x02, head.headerType) // beginning-of-stream
        assertEquals(0L, head.granule)
        assertEquals(0, head.sequence)
        assertEquals(opusHead.toList(), head.packet.toList())

        val tags = parsePage(pages, head.pageSize)
        assertEquals(0, tags.headerType)
        assertEquals(1, tags.sequence)
        assertEquals("OpusTags", String(tags.packet, 0, 8, Charsets.US_ASCII))
    }

    @Test
    fun `granule position accumulates packet duration across pages`() {
        val muxer = OggOpusPacketizer(serialNumber = 1)
        // config 0 = SILK NB, code 0 = 1 frame -> 10ms -> 480 samples @ 48kHz.
        val packet = opusPacket(config = 0, frameCountCode = 0)

        val first = parsePage(muxer.audioPage(packet))
        assertEquals(480L, first.granule)

        val second = parsePage(muxer.audioPage(packet))
        assertEquals(960L, second.granule)
        assertEquals(1, second.sequence) // headerPages() wasn't called, so audio starts at 0
    }

    @Test
    fun `serial number is stable across every page of a stream`() {
        val muxer = OggOpusPacketizer(serialNumber = 999)
        val pages = listOf(
            parsePage(muxer.audioPage(opusPacket(0, 0))),
            parsePage(muxer.audioPage(opusPacket(0, 0))),
            parsePage(muxer.audioPage(opusPacket(0, 0), endOfStream = true)),
        )
        assertTrue(pages.all { it.serial == 999 })
        assertEquals(0x04, pages.last().headerType) // end-of-stream flag
    }

    @Test
    fun `a packet exactly on a 255-byte lacing boundary still terminates correctly`() {
        // 255 bytes of payload after the TOC byte: lacing must be [255, 0], not [255].
        val muxer = OggOpusPacketizer(serialNumber = 1)
        val packet = opusPacket(config = 0, frameCountCode = 0, totalSize = 255)
        val parsed = parsePage(muxer.audioPage(packet))
        assertEquals(packet.toList(), parsed.packet.toList())
    }

    // --- TOC duration table --------------------------------------------------

    @Test
    fun `single-frame durations match the RFC 6716 table`() {
        assertEquals(480L, OpusToc.packetDurationSamples48k(opusPacket(config = 0, frameCountCode = 0)))  // SILK NB 10ms
        assertEquals(2_880L, OpusToc.packetDurationSamples48k(opusPacket(config = 3, frameCountCode = 0))) // SILK NB 60ms
        assertEquals(480L, OpusToc.packetDurationSamples48k(opusPacket(config = 12, frameCountCode = 0)))  // Hybrid SWB 10ms
        assertEquals(120L, OpusToc.packetDurationSamples48k(opusPacket(config = 16, frameCountCode = 0)))  // CELT NB 2.5ms
        assertEquals(960L, OpusToc.packetDurationSamples48k(opusPacket(config = 31, frameCountCode = 0)))  // CELT FB 20ms
    }

    @Test
    fun `two-frame codes double the single-frame duration`() {
        // config 16 = CELT NB 2.5ms per frame; codes 1 and 2 both carry 2 frames.
        assertEquals(240L, OpusToc.packetDurationSamples48k(opusPacket(config = 16, frameCountCode = 1)))
        assertEquals(240L, OpusToc.packetDurationSamples48k(opusPacket(config = 16, frameCountCode = 2)))
    }

    @Test
    fun `arbitrary frame count code reads M from the second byte`() {
        // config 0 = 10ms/frame; 5 frames -> 50ms -> 2400 samples @ 48kHz.
        val packet = byteArrayOf(
            ((0 shl 3) or 3).toByte(), // config=0, code=3 (signalled count)
            5, // M=5, VBR/padding bits clear
        )
        assertEquals(2_400L, OpusToc.packetDurationSamples48k(packet))
    }

    private fun opusPacket(config: Int, frameCountCode: Int, totalSize: Int = 8): ByteArray {
        val toc = ((config shl 3) or frameCountCode).toByte()
        return ByteArray(totalSize).also { it[0] = toc }
    }
}
