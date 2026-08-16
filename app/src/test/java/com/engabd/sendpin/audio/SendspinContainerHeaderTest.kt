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
    fun `flac header also accepts a full magic-prefixed codec header`() {
        // Confirmed on-device against a real Music Assistant server: codec_header
        // decoded to 42 bytes - "fLaC" + the metadata-block header + STREAMINFO -
        // not the bare 34-byte STREAMINFO this code originally assumed exclusively.
        // Every MA stream failed to open with "missing or malformed codec_header
        // (decodedSize=42, want 34)" until extractStreamInfo handled this shape too.
        val streamInfo = ByteArray(34) { (it + 1).toByte() }
        val full = ByteBuffer.allocate(42).apply {
            put("fLaC".toByteArray(Charsets.US_ASCII))
            put(0x80.toByte()); put(0x00); put(0x00); put(34.toByte())
            put(streamInfo)
        }.array()
        val b64 = Base64.getEncoder().encodeToString(full)

        val header = SendspinContainerHeader.flacStreamHeader(b64)!!

        assertEquals(42, header.size)
        assertEquals("fLaC", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals(streamInfo.toList(), header.copyOfRange(8, 42).toList())
    }

    @Test
    fun `the codec_header this Music Assistant server actually sent parses`() {
        // Verbatim from the device log that surfaced the 42-byte shape, rather than a
        // synthetic buffer: it is the one input known for certain to occur in the wild,
        // and a synthetic 42 bytes cannot catch a regression in how the real one is
        // framed. Decodes to "fLaC" + 80 00 00 22 + a STREAMINFO describing 48 kHz,
        // 2 channels, 16-bit, unknown length (total_samples = 0, MD5 all zero - both
        // expected for a live stream).
        val observed = "ZkxhQ4AAACISABIAAAAAAEpYC7gC8AAAAAAAAAAAAAAAAAAAAAAAAAAA"

        val header = SendspinContainerHeader.flacStreamHeader(observed)!!

        assertEquals(42, header.size)
        assertEquals("fLaC", String(header, 0, 4, Charsets.US_ASCII))
        // Re-wrapping must reproduce the server's own bytes exactly - this header is
        // handed straight to ExoPlayer's FlacExtractor to sniff and parse.
        assertEquals(Base64.getDecoder().decode(observed).toList(), header.toList())

        // The STREAMINFO fields ExoPlayer will read out of it, decoded here so a
        // regression in the slice shows up as a wrong format rather than a silent one.
        val info = header.copyOfRange(8, 42)
        val sampleRate = ((info[10].toInt() and 0xFF) shl 12) or
            ((info[11].toInt() and 0xFF) shl 4) or
            ((info[12].toInt() and 0xFF) ushr 4)
        assertEquals(48_000, sampleRate)
        assertEquals(2, ((info[12].toInt() ushr 1) and 0x07) + 1)                     // channels
        assertEquals(16, (((info[12].toInt() and 0x01) shl 4) or ((info[13].toInt() and 0xFF) ushr 4)) + 1) // bits
    }

    @Test
    fun `extractStreamInfo rejects a magic-prefixed buffer of the wrong total size`() {
        // 42 bytes is only trusted as "fLaC" + header + STREAMINFO when the size
        // matches exactly - a coincidentally magic-prefixed 41 or 43 byte buffer is
        // not that shape and must not be sliced as if it were.
        val wrongTotal = ByteArray(41).also {
            "fLaC".toByteArray(Charsets.US_ASCII).copyInto(it)
        }
        assertNull(SendspinContainerHeader.extractStreamInfo(wrongTotal))
    }

    @Test
    fun `flac header decodes unpadded base64`() {
        // 34 bytes -> 2 trailing '=' from the standard (padded) encoder. Nothing
        // guarantees the server pads its base64, and java.util.Base64's decoders
        // reject unpadded input unless asked not to - unlike android.util.Base64.DEFAULT,
        // which this replaced (see SendspinContainerHeader.decodeBase64).
        val streamInfo = ByteArray(34) { (it + 1).toByte() }
        val padded = Base64.getEncoder().encodeToString(streamInfo)
        val unpadded = padded.trimEnd('=')
        assertEquals(2, padded.length - unpadded.length, "test setup: expected 2 bytes of padding for a 34-byte input")

        val header = SendspinContainerHeader.flacStreamHeader(unpadded)!!
        assertEquals(streamInfo.toList(), header.copyOfRange(8, 42).toList())
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
