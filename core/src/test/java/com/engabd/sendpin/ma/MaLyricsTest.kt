package com.engabd.sendpin.ma

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaLyricsTest {

    @Test
    fun `plain lyrics become untimed lines`() {
        val l = MaLyrics("first line\nsecond line", synced = false)
        assertEquals(listOf("first line", "second line"), l.lines.map { it.text })
        assertTrue(l.lines.all { it.atMs == 0L })
    }

    @Test
    fun `lrc timestamps parse to milliseconds`() {
        val l = MaLyrics("[00:12.34]hello\n[01:05.00]world", synced = true)
        assertEquals(listOf(12_340L, 65_000L), l.lines.map { it.atMs })
        assertEquals(listOf("hello", "world"), l.lines.map { it.text })
    }

    /** Some providers emit 3-digit milliseconds rather than centiseconds. */
    @Test
    fun `three digit fractions are milliseconds not centiseconds`() {
        assertEquals(1_500L, MaLyrics("[00:01.500]x", synced = true).lines.single().atMs)
        assertEquals(1_500L, MaLyrics("[00:01.50]x", synced = true).lines.single().atMs)
    }

    /** A repeated chorus is one line carrying several timestamps. */
    @Test
    fun `a line with several timestamps repeats at each`() {
        val l = MaLyrics("[00:10.00][01:20.00]chorus", synced = true)
        assertEquals(2, l.lines.size)
        assertEquals(listOf(10_000L, 80_000L), l.lines.map { it.atMs })
        assertTrue(l.lines.all { it.text == "chorus" })
    }

    @Test
    fun `lines come back in time order and metadata tags are dropped`() {
        val l = MaLyrics("[ti:Song]\n[00:30.00]later\n[00:05.00]earlier", synced = true)
        assertEquals(listOf("earlier", "later"), l.lines.map { it.text })
    }

    @Test
    fun `minutes past 59 still parse`() {
        assertEquals(3_600_000L, MaLyrics("[60:00.00]x", synced = true).lines.single().atMs)
    }
}
