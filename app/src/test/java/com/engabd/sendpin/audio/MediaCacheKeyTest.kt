package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MediaCacheKeyTest {

    @Test
    fun `two signings of the same subsonic stream share one key`() {
        val a = "http://nas:4533/rest/stream?u=alice&t=aaaa1111&s=salt1&v=1.16.1&c=camusic&f=json&id=tr-9&format=raw"
        val b = "http://nas:4533/rest/stream?u=alice&t=bbbb2222&s=salt2&v=1.16.1&c=camusic&f=json&id=tr-9&format=raw"
        assertEquals(MediaCache.cacheKey(a), MediaCache.cacheKey(b))
        assertEquals("http://nas:4533/rest/stream?v=1.16.1&f=json&id=tr-9&format=raw", MediaCache.cacheKey(a))
    }

    @Test
    fun `what identifies the bytes is kept`() {
        val flac = "http://nas/rest/stream?u=a&t=x&s=y&id=1&format=raw"
        val mp3 = "http://nas/rest/stream?u=a&t=x&s=y&id=1&format=mp3&maxBitRate=320"
        val other = "http://nas/rest/stream?u=a&t=x&s=y&id=2&format=raw"
        assertNotEquals(MediaCache.cacheKey(flac), MediaCache.cacheKey(mp3))
        assertNotEquals(MediaCache.cacheKey(flac), MediaCache.cacheKey(other))
    }

    @Test
    fun `jellyfin and plex tokens are not part of the key`() {
        assertEquals(
            "http://jf:8096/Audio/42/universal?Container=flac",
            MediaCache.cacheKey("http://jf:8096/Audio/42/universal?api_key=secret&Container=flac"),
        )
        assertEquals(
            "http://plex:32400/library/parts/7/file.flac",
            MediaCache.cacheKey("http://plex:32400/library/parts/7/file.flac?X-Plex-Token=zz"),
        )
    }

    @Test
    fun `a url without a query is its own key`() {
        assertEquals("https://cdn/x.flac", MediaCache.cacheKey("https://cdn/x.flac"))
    }
}
