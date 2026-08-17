package com.engabd.sendpin.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps raw Opus packets in Ogg pages, per RFC 3533 (Ogg) + RFC 7845 (Ogg
 * encapsulation for Opus) - the container `OggExtractor`/`OpusReader` need, since
 * ExoPlayer has no notion of a bare Opus elementary stream the way a MediaCodec
 * decoder does when handed `codec_header` as CSD directly. No audio bytes are
 * touched - each Sendspin frame becomes exactly one packet in exactly one page.
 *
 * One packet per page (rather than batching several packets into one page, which
 * real encoders do to amortise the ~27+ byte page overhead) trades bandwidth
 * efficiency for simplicity: it sidesteps packet-spanning-multiple-pages framing
 * entirely, which real Opus packets (a few hundred bytes at most, comfortably
 * under the 255-segment-table limit of ~65 KB) never need anyway.
 *
 * Unlike [SendspinContainerHeader]'s FLAC/WAV framing, this has no working
 * reference to port from - Massdroid doesn't route Sendspin through ExoPlayer at
 * all (see the plan doc). The page/CRC framing here is a direct implementation of
 * the RFC, not a port, and the [OpusToc] duration table in particular needs
 * on-device verification against a real Opus decode: an unnoticed off-by-one in
 * the granule position wouldn't crash anything, but would drift renderer
 * presentation timestamps out of sync with what's audible.
 */
class OggOpusPacketizer(private val serialNumber: Int) {

    private var pageSequence = 0
    private var granulePosition = 0L

    companion object {
        private const val HEADER_TYPE_CONTINUED = 0x01
        private const val HEADER_TYPE_BOS = 0x02
        private const val HEADER_TYPE_EOS = 0x04

        /** RFC 7845 4.1: fixed 3840-sample (80ms @ 48kHz) pre-skip granule offset convention is per-encoder; we don't know it, so header pages simply carry granule 0 - only audio pages accumulate. */
        private const val HEADER_GRANULE = 0L
    }

    /**
     * The two mandatory header pages (OpusHead, then OpusTags) that must open
     * every Ogg-Opus stream before any audio page, per RFC 7845 §5.
     *
     * @param opusHeadPacket the `OpusHead` packet - either `stream/start.codec_header`
     *   decoded, or a synthesized minimal OpusHead ([SendspinDataSource.opusHeadPacket])
     *   when the server omits it.
     */
    fun headerPages(opusHeadPacket: ByteArray): ByteArray {
        val head = buildPage(
            headerType = HEADER_TYPE_BOS,
            granule = HEADER_GRANULE,
            packet = opusHeadPacket,
        )
        val tags = buildPage(
            headerType = 0,
            granule = HEADER_GRANULE,
            packet = opusTagsPacket(),
        )
        return head + tags
    }

    /**
     * One Ogg page carrying one Opus audio packet. The granule position is
     * advanced by the packet's own duration (see [OpusToc]) before the page is
     * built, so it always reflects "total 48kHz-equivalent samples through the end
     * of this page" - what RFC 7845 §4 requires.
     */
    fun audioPage(opusPacket: ByteArray, endOfStream: Boolean = false): ByteArray {
        granulePosition += OpusToc.packetDurationSamples48k(opusPacket)
        return buildPage(
            headerType = if (endOfStream) HEADER_TYPE_EOS else 0,
            granule = granulePosition,
            packet = opusPacket,
        )
    }

    private fun opusTagsPacket(): ByteArray {
        val vendor = "CAMusic".toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(8 + 4 + vendor.size + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusTags".toByteArray(Charsets.US_ASCII))
            putInt(vendor.size)
            put(vendor)
            putInt(0) // zero user comments
        }.array()
    }

    /** One packet, one page: the segment table is this packet's length alone. */
    private fun buildPage(headerType: Int, granule: Long, packet: ByteArray): ByteArray {
        val segments = lacingValues(packet.size)
        val headerSize = 27 + segments.size
        val page = ByteBuffer.allocate(headerSize + packet.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(MAGIC_OGGS)
            put(0) // version
            put(headerType.toByte())
            putLong(granule)
            putInt(serialNumber)
            putInt(pageSequence)
            putInt(0) // checksum placeholder, filled in below
            put(segments.size.toByte())
            for (s in segments) put(s.toByte())
            put(packet)
        }.array()
        pageSequence++
        writeChecksum(page)
        return page
    }

    /**
     * Segment ("lacing") table for a single packet: as many 255-valued entries as
     * fit, then the remainder - even when the remainder is 0, which is exactly how
     * Ogg distinguishes "packet is a multiple of 255 bytes and ends here" from
     * "packet continues on the next page" (we never split a packet across pages,
     * so the terminating entry is always present).
     */
    private fun lacingValues(packetLength: Int): IntArray {
        val full = packetLength / 255
        val remainder = packetLength % 255
        val values = IntArray(full + 1)
        for (i in 0 until full) values[i] = 255
        values[full] = remainder
        return values
    }

    /**
     * Ogg's CRC-32 (RFC 3533 Appendix A): polynomial 0x04c11db7, MSB-first, no
     * reflection, zero init/final-XOR - distinct from the reflected CRC-32 used by
     * zip/PNG/[java.util.zip.CRC32], so that class can't be reused here.
     */
    private fun writeChecksum(page: ByteArray) {
        // The checksum field (bytes 22..25) must read as zero while it's computed.
        var crc = 0L
        for (b in page) {
            crc = ((crc shl 8) xor CRC_TABLE[(((crc ushr 24) xor (b.toLong() and 0xFF)) and 0xFF).toInt()]) and 0xFFFFFFFFL
        }
        page[22] = (crc and 0xFF).toByte()
        page[23] = ((crc ushr 8) and 0xFF).toByte()
        page[24] = ((crc ushr 16) and 0xFF).toByte()
        page[25] = ((crc ushr 24) and 0xFF).toByte()
    }

    private val MAGIC_OGGS = "OggS".toByteArray(Charsets.US_ASCII)

    private val CRC_TABLE: LongArray by lazy {
        LongArray(256) { i ->
            var r = i.toLong() shl 24
            repeat(8) {
                r = if ((r and 0x80000000L) != 0L) ((r shl 1) xor 0x04c11db7L) else (r shl 1)
            }
            r and 0xFFFFFFFFL
        }
    }
}

/**
 * Opus packet duration from the TOC (table-of-contents) byte alone, per RFC 6716
 * §3.1 (frame size table) and §3.2 (frame count coding) - no decode needed.
 */
object OpusToc {

    /** Per-frame duration in tenths of a millisecond, indexed by TOC config (0-31). */
    private fun frameDurationMsTenths(config: Int): Int = when (config) {
        in 0..11 -> intArrayOf(100, 200, 400, 600)[config % 4]   // SILK: 10/20/40/60ms
        in 12..15 -> intArrayOf(100, 200)[config % 2]            // Hybrid: 10/20ms
        else -> intArrayOf(25, 50, 100, 200)[config % 4]          // CELT: 2.5/5/10/20ms
    }

    /** Total packet duration in 48kHz-equivalent samples - the Ogg-Opus granule position unit. */
    fun packetDurationSamples48k(packet: ByteArray): Long {
        if (packet.isEmpty()) return 0L
        val toc = packet[0].toInt() and 0xFF
        val config = (toc ushr 3) and 0x1F
        val frameCountCode = toc and 0x03
        val numFrames = when (frameCountCode) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size >= 2) (packet[1].toInt() and 0x3F) else 1
        }
        val perFrameSamples = frameDurationMsTenths(config).toLong() * 48L / 10L
        return perFrameSamples * numFrames
    }
}
