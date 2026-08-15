package com.engabd.sendpin.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pure-JVM tests: run with `./gradlew :app:testDebugUnitTest`. */
class SendspinContainerHeaderTest {

    @Test
    fun `flac header wraps the STREAMINFO payload verbatim`() {
        val streamInfo = ByteArray(34) { (it + 1).toByte() }
        val b64 = Base64.getEncoder().encodeToString(streamInfo)

        val header = SendspinContainerHeader.flacStreamHeader(b64)!!

        assertEquals(42, header.size)
        assertEquals("fLaC", String(header, 0, 4, Charsets.US_ASCII))
        // Metadata block header: last-block=1, type=0 (STREAMINFO), length=34.
        assertEquals(0x80.toByte(), header[4])
        assertEquals(0x00.toByte(), header[5])
        assertEquals(0x00.toByte(), header[6])
        assertEquals(0x22.toByte(), header[7])
        assertEquals(streamInfo.toList(), header.copyOfRange(8, 42).toList())
    }

    @Test
    fun `flac header rejects a missing or malformed codec header`() {
        assertNull(SendspinContainerHeader.flacStreamHeader(null))
        assertNull(SendspinContainerHeader.flacStreamHeader(""))
        val wrongSize = Base64.getEncoder().encodeToString(ByteArray(10))
        assertNull(SendspinContainerHeader.flacStreamHeader(wrongSize))
    }

    @Test
    fun `wav header encodes format fields little-endian at the documented offsets`() {
        val header = SendspinContainerHeader.wavStreamHeader(sampleRate = 48_000, channels = 2, bitDepth = 24)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals(16, buf.getInt(16))          // fmt chunk size
        assertEquals(1, buf.getShort(20).toInt()) // PCM
        assertEquals(2, buf.getShort(22).toInt()) // channels
        assertEquals(48_000, buf.getInt(24))      // sample rate
        assertEquals(48_000 * 2 * 3, buf.getInt(28)) // byte rate = rate * blockAlign
        assertEquals(2 * 3, buf.getShort(32).toInt()) // block align
        assertEquals(24, buf.getShort(34).toInt())    // bits per sample
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
    }

    @Test
    fun `wav header declared sizes never overflow into negative`() {
        val header = SendspinContainerHeader.wavStreamHeader(sampleRate = 192_000, channels = 2, bitDepth = 24)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assert(buf.getInt(4) > 0) { "RIFF chunk size went negative" }
        assert(buf.getInt(40) > 0) { "data chunk size went negative" }
    }
}
