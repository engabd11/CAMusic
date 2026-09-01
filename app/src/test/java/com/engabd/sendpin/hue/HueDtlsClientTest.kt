package com.engabd.sendpin.hue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `splitRecords` and `splitHandshake` read length fields straight off the wire
 * and used to slice into the buffer on the strength of them alone. A short UDP
 * read - the network dropping the tail of a datagram, or a bridge sending a
 * malformed one - made the declared length overrun what actually arrived, and
 * `copyOfRange` threw `IndexOutOfBoundsException` out of the receive path,
 * which took the whole light-sync loop down with it. These pin down the fix:
 * a declared length that doesn't fit stops the parse and hands back whatever
 * was already complete, rather than throwing or guessing at a partial record.
 */
class HueDtlsClientTest {

    // A client instance costs nothing to build - the constructor only stores
    // its arguments, all the socket work happens in connect() - so one serves
    // as a receiver for the internal parsing functions under test.
    private val client = DtlsPskClient(host = "10.0.0.1", port = 2100, identity = ByteArray(0), psk = ByteArray(0))

    // ── Record framing helpers (mirror DtlsPskClient.record's layout) ───────

    private fun recordHeader(contentType: Byte, length: Int): ByteArray {
        val header = ByteArray(13)
        header[0] = contentType
        header[1] = 0xFE.toByte()  // DTLS 1.2 version, high byte
        header[2] = 0xFD.toByte()  // DTLS 1.2 version, low byte
        // bytes 3..10 (epoch + sequence) are irrelevant to splitRecords, left zero
        header[11] = ((length shr 8) and 0xFF).toByte()
        header[12] = (length and 0xFF).toByte()
        return header
    }

    private fun record(contentType: Byte, fragment: ByteArray): ByteArray =
        recordHeader(contentType, fragment.size) + fragment

    // ── Handshake framing helpers (mirror DtlsPskClient.handshakeMsg's layout) ──

    private fun handshakeHeader(hsType: Byte, length: Int, fragLen: Int): ByteArray {
        val header = ByteArray(12)
        header[0] = hsType
        header[1] = ((length shr 16) and 0xFF).toByte()
        header[2] = ((length shr 8) and 0xFF).toByte()
        header[3] = (length and 0xFF).toByte()
        // bytes 4..5 (message_seq) and 6..8 (frag_offset) are irrelevant here, left zero
        header[9] = ((fragLen shr 16) and 0xFF).toByte()
        header[10] = ((fragLen shr 8) and 0xFF).toByte()
        header[11] = (fragLen and 0xFF).toByte()
        return header
    }

    private fun handshakeMsg(hsType: Byte, body: ByteArray): ByteArray =
        handshakeHeader(hsType, body.size, body.size) + body

    // ── splitRecords ──────────────────────────────────────────────────────

    @Test
    fun `a well-formed two-record datagram splits into two records`() {
        val frag1 = byteArrayOf(1, 2, 3)
        val frag2 = byteArrayOf(9, 8, 7, 6)
        val data = record(22, frag1) + record(21, frag2)  // handshake, alert

        val records = client.splitRecords(data)

        assertEquals(2, records.size)
        assertEquals(22.toByte(), records[0].contentType)
        assertTrue(records[0].fragment.contentEquals(frag1))
        assertEquals(21.toByte(), records[1].contentType)
        assertTrue(records[1].fragment.contentEquals(frag2))
    }

    @Test
    fun `a record whose length overruns the buffer returns only the complete records before it`() {
        val goodFragment = byteArrayOf(1, 2, 3, 4)
        val good = record(22, goodFragment)
        // Declares a 50-byte fragment but the datagram only carries 5 more bytes -
        // exactly what a truncated read looks like on the wire.
        val truncated = recordHeader(22, 50) + byteArrayOf(9, 9, 9, 9, 9)
        val data = good + truncated

        val records = client.splitRecords(data)

        assertEquals(1, records.size, "the truncated second record should not appear at all")
        assertTrue(records[0].fragment.contentEquals(goodFragment))
    }

    @Test
    fun `a buffer too short to hold a record header returns nothing`() {
        val data = ByteArray(5)  // the header alone is 13 bytes

        assertTrue(client.splitRecords(data).isEmpty())
    }

    // ── splitHandshake ───────────────────────────────────────────────────

    @Test
    fun `a handshake fragment whose fragLen overruns returns only the complete messages before it`() {
        val goodBody = byteArrayOf(5, 6, 7)
        val good = handshakeMsg(hsType = 1, body = goodBody)  // ClientHello
        // Declares a 40-byte body but only 2 more bytes follow.
        val truncated = handshakeHeader(hsType = 2, length = 40, fragLen = 40) + byteArrayOf(1, 2)
        val fragment = good + truncated

        val messages = client.splitHandshake(fragment)

        assertEquals(1, messages.size, "the truncated second message should not appear at all")
        assertEquals(1.toByte(), messages[0].hsType)
        assertTrue(messages[0].body.contentEquals(goodBody))
    }
}
