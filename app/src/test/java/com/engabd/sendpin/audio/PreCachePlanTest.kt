package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When "fetch ahead" runs, and what it fetches. */
class PreCachePlanTest {

    @Test
    fun `it runs only when switched on, playing, on an allowed network, for this phone's own queue`() {
        assertTrue(PreCachePlan.allowed(ahead = 1, playing = true, unmetered = true, wifiOnly = true, remotePlayer = false))
        assertFalse(PreCachePlan.allowed(ahead = 0, playing = true, unmetered = true, wifiOnly = true, remotePlayer = false))
        assertFalse(PreCachePlan.allowed(ahead = 3, playing = false, unmetered = true, wifiOnly = true, remotePlayer = false))
        assertFalse(PreCachePlan.allowed(ahead = 3, playing = true, unmetered = false, wifiOnly = true, remotePlayer = false))
        assertTrue(PreCachePlan.allowed(ahead = 3, playing = true, unmetered = false, wifiOnly = false, remotePlayer = false))
        assertFalse(PreCachePlan.allowed(ahead = 3, playing = true, unmetered = true, wifiOnly = true, remotePlayer = true))
    }

    @Test
    fun `only web streams are fetched, in play order, up to the count`() {
        val upcoming = listOf(
            "https://nav.example/rest/stream?id=1&u=me&t=abc&s=1",
            "/storage/emulated/0/Music/song.flac",
            null,
            "spotify:track:xyz",
            "http://nav.example/rest/stream?id=2&u=me&t=def&s=2",
            "https://nav.example/rest/stream?id=3",
        )
        assertEquals(listOf(upcoming[0]), PreCachePlan.targets(upcoming, 1))
        // Three ahead covers the first three songs; only one of them is a web stream.
        assertEquals(listOf(upcoming[0]), PreCachePlan.targets(upcoming, 3))
        assertEquals(listOf(upcoming[0], upcoming[4]), PreCachePlan.targets(upcoming, 5))
        assertEquals(emptyList(), PreCachePlan.targets(upcoming, 0))
    }

    @Test
    fun `the same song twice ahead is fetched once, however its URL was signed`() {
        // A Subsonic URL is signed with a fresh salt every time; the cache keys it without one.
        val a = "https://nav.example/rest/stream?id=7&u=me&t=aaa&s=111"
        val b = "https://nav.example/rest/stream?id=7&u=me&t=bbb&s=222"
        assertEquals(listOf(a), PreCachePlan.targets(listOf(a, b), 5))
    }

    @Test
    fun `cache sizes are kept to the ones offered, defaulting to the old 512 MB`() {
        assertEquals(512, PreCachePlan.sizeMb(null))
        assertEquals(1024, PreCachePlan.sizeMb(1024))
        assertEquals(512, PreCachePlan.sizeMb(300))
        assertTrue(PreCachePlan.DEFAULT_SIZE_MB in PreCachePlan.SIZE_CHOICES_MB)
        assertEquals(0, PreCachePlan.AHEAD_CHOICES.first())
    }
}
