package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotAudioGuardTest {

    @Test
    fun `content type is read whatever the header's case, without parameters`() {
        assertEquals("application/json", NotAudioGuard.contentType(mapOf("content-type" to listOf("application/json; charset=utf-8"))))
        assertEquals("audio/flac", NotAudioGuard.contentType(mapOf("Content-Type" to listOf("audio/flac"))))
        assertNull(NotAudioGuard.contentType(emptyMap()))
    }

    @Test
    fun `documents are refused`() {
        listOf(
            "application/json", "application/xml", "text/xml", "text/html", "text/plain",
            "application/problem+json", "application/xhtml+xml",
        ).forEach { assertTrue(NotAudioGuard.isDocumentType(it), it) }
    }

    @Test
    fun `audio however it is labelled passes`() {
        listOf(
            "audio/flac", "audio/mpeg", "application/octet-stream", "video/mp4", "audio/mp4",
            "application/ogg", "audio/mpegurl", "application/vnd.apple.mpegurl", "binary/octet-stream",
        ).forEach { assertFalse(NotAudioGuard.isDocumentType(it), it) }
    }

    @Test
    fun `a stored error document is told apart from audio`() {
        assertTrue(NotAudioGuard.looksLikeDocument("""{"subsonic-response":{"status":"failed"}}""".toByteArray()))
        assertTrue(NotAudioGuard.looksLikeDocument("""<?xml version="1.0"?><subsonic-response/>""".toByteArray()))
        assertTrue(NotAudioGuard.looksLikeDocument("\n  <html>".toByteArray()))
        assertTrue(NotAudioGuard.looksLikeDocument(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), '{'.code.toByte())))

        assertFalse(NotAudioGuard.looksLikeDocument("fLaC".toByteArray() + byteArrayOf(0, 0, 0, 0x22)))
        assertFalse(NotAudioGuard.looksLikeDocument("ID3".toByteArray() + byteArrayOf(4, 0)))
        assertFalse(NotAudioGuard.looksLikeDocument(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64)))
        assertFalse(NotAudioGuard.looksLikeDocument("OggS".toByteArray()))
        assertFalse(NotAudioGuard.looksLikeDocument(byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())))
        assertFalse(NotAudioGuard.looksLikeDocument("RIFF".toByteArray()))
        assertFalse(NotAudioGuard.looksLikeDocument(ByteArray(0)))
        assertFalse(NotAudioGuard.looksLikeDocument("   ".toByteArray()))
    }

    @Test
    fun `only the bytes read are looked at`() {
        val buf = "{xxxxxxx".toByteArray()
        assertFalse(NotAudioGuard.looksLikeDocument(buf, 0))
        assertTrue(NotAudioGuard.looksLikeDocument(buf, 1))
    }

    @Test
    fun `navidrome's json error message is found`() {
        val body = """{"subsonic-response":{"status":"failed","version":"1.16.1","type":"navidrome","serverVersion":"0.61.2 (aa84e645)","openSubsonic":true,"error":{"code":70,"message":"data not found"}}}"""
        assertEquals("data not found", NotAudioGuard.errorMessage(body))
    }

    @Test
    fun `navidrome's xml error message is found and unescaped`() {
        val body = """<subsonic-response xmlns="http://subsonic.org/restapi" status="failed" version="1.16.1"><error code="10" message="missing parameter: &#39;u&#39;"></error></subsonic-response>"""
        assertEquals("missing parameter: 'u'", NotAudioGuard.errorMessage(body))
    }

    @Test
    fun `a document with no error message gives none`() {
        assertNull(NotAudioGuard.errorMessage("<html><body>Bad gateway</body></html>"))
        assertNull(NotAudioGuard.errorMessage("""{"error":{"code":70,"message":""}}"""))
    }
}
