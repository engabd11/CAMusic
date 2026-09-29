package com.engabd.sendpin.lyrics

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddedLyricsTest {

    private fun le32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
    }

    private fun flac(vararg comments: String, pictureFirst: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        // STREAMINFO, 34 bytes
        out.write(0x00); out.write(0); out.write(0); out.write(34); out.write(ByteArray(34))
        if (pictureFirst) {
            val pic = ByteArray(5000)
            out.write(0x06); out.write(0); out.write((pic.size shr 8) and 0xFF); out.write(pic.size and 0xFF); out.write(pic)
        }
        val block = ByteArrayOutputStream()
        val vendor = "reference libFLAC".toByteArray()
        le32(block, vendor.size); block.write(vendor)
        le32(block, comments.size)
        comments.forEach { val b = it.toByteArray(); le32(block, b.size); block.write(b) }
        val bytes = block.toByteArray()
        out.write(0x84) // last block, type 4
        out.write((bytes.size shr 16) and 0xFF); out.write((bytes.size shr 8) and 0xFF); out.write(bytes.size and 0xFF)
        out.write(bytes)
        out.write(ByteArray(100)) // "audio"
        return out.toByteArray()
    }

    @Test
    fun `flac synced lyrics are found past a picture block`() {
        val l = EmbeddedLyrics.read(flac("TITLE=x", "LYRICS=[00:01.00]Hello\n[00:03.50]World").inputStream())!!
        assertTrue(l.synced)
        assertEquals(listOf(1_000L, 3_500L), l.lines.map { it.atMs })
    }

    @Test
    fun `flac plain lyrics stay plain`() {
        val l = EmbeddedLyrics.read(flac("unsyncedlyrics=Just words\nMore words").inputStream())!!
        assertFalse(l.synced)
        assertEquals("Just words\nMore words", l.text)
    }

    @Test
    fun `a flac with no lyrics has none`() {
        assertNull(EmbeddedLyrics.read(flac("TITLE=x").inputStream()))
    }

    @Test
    fun `id3v2_3 USLT in utf-8 and utf-16`() {
        fun tag(frame: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write("ID3".toByteArray()); out.write(3); out.write(0); out.write(0)
            val size = 10 + frame.size
            out.write((size shr 21) and 0x7F); out.write((size shr 14) and 0x7F); out.write((size shr 7) and 0x7F); out.write(size and 0x7F)
            out.write("USLT".toByteArray())
            out.write((frame.size shr 24) and 0xFF); out.write((frame.size shr 16) and 0xFF); out.write((frame.size shr 8) and 0xFF); out.write(frame.size and 0xFF)
            out.write(0); out.write(0)
            out.write(frame)
            return out.toByteArray()
        }
        val utf8 = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + "Sing it".toByteArray()
        assertEquals("Sing it", EmbeddedLyrics.read(tag(utf8).inputStream())!!.text)

        val utf16 = byteArrayOf(1) + "eng".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) +
            "Sing it".toByteArray(Charsets.UTF_16LE).let { byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + it }
        assertEquals("Sing it", EmbeddedLyrics.read(tag(utf16).inputStream())!!.text)
    }

    @Test
    fun `not an audio file`() {
        assertNull(EmbeddedLyrics.read("hello world".byteInputStream()))
    }
}
