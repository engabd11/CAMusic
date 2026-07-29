package com.engabd.sendpin.ma

import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Music Assistant builds its image-proxy URLs as
 * `?provider=…&size=…&fmt=…&path=quote_plus(quote_plus(path))` and unquotes the
 * path twice on the way back in. Sending it singly-encoded is what left every
 * library cover blank, so the encoding is pinned here.
 */
class MaImageProxyTest {

    private fun pathParam(url: String): String =
        url.substringAfter("&path=")

    /** What the server does: one decode from the query string, one in the handler. */
    private fun serverDecode(wireValue: String): String =
        URLDecoder.decode(URLDecoder.decode(wireValue, "UTF-8"), "UTF-8")

    @Test
    fun `a filesystem path survives the servers double unquote`() {
        val path = "/media/Music/Björk & Co/Post (2005)/cover.jpg"
        val url = MaParse.imageProxyUrl("http://192.168.0.10:8095", "filesystem_local", path)
        assertEquals(path, serverDecode(pathParam(url)))
    }

    @Test
    fun `a nested url with its own query survives too`() {
        val path = "https://cdn.example.com/art?id=a+b%20c&x=1"
        val url = MaParse.imageProxyUrl("http://ma.local:8095", "spotify", path)
        assertEquals(path, serverDecode(pathParam(url)))
    }

    @Test
    fun `the path is encoded twice, not once`() {
        val url = MaParse.imageProxyUrl("http://ma.local:8095", "builtin", "/a b/c.png")
        // Singly-encoded would be `%2Fa+b%2Fc.png`; doubly-encoded escapes the escapes.
        assertTrue(pathParam(url).contains("%252F"), "expected a double-encoded path, got: $url")
    }

    @Test
    fun `a trailing slash on the base is not doubled`() {
        val url = MaParse.imageProxyUrl("http://ma.local:8095/", "builtin", "/a.png")
        assertTrue(url.startsWith("http://ma.local:8095/imageproxy?"), url)
    }

    @Test
    fun `the format follows the extension and falls back to png`() {
        assertTrue(MaParse.imageProxyUrl("http://h", "p", "/a.JPG").contains("fmt=jpeg"))
        assertTrue(MaParse.imageProxyUrl("http://h", "p", "/a.webp").contains("fmt=webp"))
        assertTrue(MaParse.imageProxyUrl("http://h", "p", "/no-extension").contains("fmt=png"))
    }
}
