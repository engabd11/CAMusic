package com.engabd.sendpin.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LogRedactorTest {

    @Test
    fun `subsonic signature parameters are masked, the rest of the url is kept`() {
        val url = "http://nas.lan:4533/rest/getCoverArt?u=alice&t=26719a1196d2a940705a59634eb18eab&s=c19b2d&v=1.16.1&c=camusic&id=al-42&size=1000"
        assertEquals(
            "http://nas.lan:4533/rest/getCoverArt?u=***&t=***&s=***&v=1.16.1&c=camusic&id=al-42&size=1000",
            LogRedactor.scrub(url),
        )
    }

    @Test
    fun `legacy plaintext password is masked`() {
        assertEquals("http://h/rest/ping?u=***&p=***", LogRedactor.scrub("http://h/rest/ping?u=bob&p=enc:6869"))
    }

    @Test
    fun `jellyfin, emby and plex tokens are masked whatever their case`() {
        assertEquals(
            "http://jf:8096/Items/1/Images/Primary?maxWidth=600&api_key=***",
            LogRedactor.scrub("http://jf:8096/Items/1/Images/Primary?maxWidth=600&api_key=0123abcd"),
        )
        assertEquals(
            "http://plex:32400/photo/:/transcode?width=300&X-Plex-Token=***",
            LogRedactor.scrub("http://plex:32400/photo/:/transcode?width=300&X-Plex-Token=zzYY11"),
        )
        assertEquals("x?API_KEY=***", LogRedactor.scrub("x?API_KEY=secret"))
    }

    @Test
    fun `parameters that only start with a secret name survive`() {
        val url = "http://h/x?status=ok&size=0&type=album&path=a%2Fb&provider=filesystem"
        assertEquals(url, LogRedactor.scrub(url))
    }

    @Test
    fun `a url inside an exception message is masked in place`() {
        val line = "09-27 10:00:01.123  1234  1300 E ExoPlayerImplInternal: Unable to connect to http://nas/rest/stream?id=9&u=a&t=abc&s=xyz, cause: timeout"
        val out = LogRedactor.scrub(line)
        assertFalse(out.contains("abc") || out.contains("xyz"))
        assertEquals(
            "09-27 10:00:01.123  1234  1300 E ExoPlayerImplInternal: Unable to connect to http://nas/rest/stream?id=9&u=***&t=***&s=***, cause: timeout",
            out,
        )
    }

    @Test
    fun `bearer and mediabrowser header tokens are masked`() {
        assertEquals("Authorization: Bearer ***", LogRedactor.scrub("Authorization: Bearer eyJhbGciOi.J9.x-y"))
        assertEquals(
            "MediaBrowser Client=\"CAMusic\", Token=\"***\"",
            LogRedactor.scrub("MediaBrowser Client=\"CAMusic\", Token=\"f00dfeed\""),
        )
    }

    @Test
    fun `null and empty are handled`() {
        assertEquals("null", LogRedactor.url(null))
        assertEquals("", LogRedactor.scrub(""))
    }
}
