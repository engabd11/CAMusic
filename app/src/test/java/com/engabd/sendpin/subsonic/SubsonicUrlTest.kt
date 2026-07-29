package com.engabd.sendpin.subsonic

import com.engabd.sendpin.ma.MaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The URL half of the Subsonic client is pure and is where the errors that look
 * like "the server is down" actually live — a wrong scheme, a lost port, a
 * password on the query string.
 */
class SubsonicUrlTest {

    private fun client(url: String) = SubsonicClient(url, "abdullah", "hunter2")

    @Test
    fun `a LAN address without a scheme is http, not https`() {
        // Defaulting a LAN box to https just fails to connect — nothing self-hosted
        // on 192.168/x is holding a valid certificate.
        assertEquals("http://192.168.0.10:4533", client("192.168.0.10:4533").serverUrl)
        assertEquals("http://nas.local:4533", client("nas.local:4533").serverUrl)
        assertEquals("http://localhost:4533", client("localhost:4533").serverUrl)
    }

    @Test
    fun `a public hostname without a scheme is https`() {
        // The opposite default matters more: it decides whether the password's
        // salted token crosses the internet in the clear.
        assertEquals("https://music.example.com", client("music.example.com").serverUrl)
    }

    @Test
    fun `an explicit scheme is left alone, and a trailing slash is trimmed`() {
        assertEquals("http://music.example.com", client("http://music.example.com/").serverUrl)
        assertEquals("https://192.168.0.10:4533", client("https://192.168.0.10:4533").serverUrl)
    }

    @Test
    fun `stream urls ask for the original file and never carry the password`() {
        val url = client("192.168.0.10:4533").streamUrl("tr-42")
        assertTrue(url.startsWith("http://192.168.0.10:4533/rest/stream.view?"), url)
        assertTrue("format=raw" in url, "stream must not be transcoded: $url")
        assertTrue("id=tr-42" in url, url)
        // Token auth: a salted md5, never the password itself.
        assertTrue(Regex("[?&]t=[0-9a-f]{32}([&]|$)").containsMatchIn(url), url)
        assertTrue(Regex("[?&]s=[0-9a-f]{8}([&]|$)").containsMatchIn(url), url)
        assertFalse("hunter2" in url, "the password must never ride on a URL: $url")
    }

    @Test
    fun `each call gets a fresh salt`() {
        val c = client("192.168.0.10:4533")
        fun salt(u: String) = Regex("[?&]s=([0-9a-f]{8})").find(u)?.groupValues?.get(1)
        // A hundred repeats of one salt would make the token a reusable secret.
        val salts = (1..20).map { salt(c.streamUrl("x")) }.toSet()
        assertTrue(salts.size > 1, "salt is not being regenerated")
    }

    @Test
    fun `download urls take the original, with no format hint`() {
        val url = client("192.168.0.10:4533").downloadUrl("tr-42")
        assertTrue(url.startsWith("http://192.168.0.10:4533/rest/download.view?"), url)
        assertFalse("format=" in url, "/download already returns the original: $url")
    }

    @Test
    fun `a blank cover id yields no url rather than a broken one`() {
        val c = client("192.168.0.10:4533")
        assertEquals(null, c.coverUrl(null))
        assertEquals(null, c.coverUrl(""))
        assertTrue(c.coverUrl("al-1")!!.contains("getCoverArt.view"))
    }

    @Test
    fun `a genre browses but does not play`() {
        // Genres are containers with no uri; treating them as playable would put a
        // play button on something with nothing to hand the player.
        val genre = MaItem("Shoegaze", SubsonicClient.PROVIDER, "Shoegaze", null, "genre", null, null, null)
        assertTrue(genre.browsable)
        assertFalse(genre.playable)
    }
}
