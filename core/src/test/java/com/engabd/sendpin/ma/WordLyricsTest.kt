package com.engabd.sendpin.ma

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Enhanced LRC parsing, TTML conversion, estimation and active-word arithmetic. */
class WordLyricsTest {

    private fun lines(text: String) = MaLyrics(text, synced = true).lines

    // ── Enhanced LRC ──────────────────────────────────────────────────────

    @Test
    fun `enhanced lrc gives each word its own start and end`() {
        val l = lines("[00:12.00]<00:12.00>Hello <00:12.50>big <00:13.00>world<00:13.60>").single()
        assertEquals("Hello big world", l.text)
        val w = l.words!!
        assertEquals(listOf("Hello ", "big ", "world"), w.map { it.text })
        assertEquals(listOf(12_000L, 12_500L, 13_000L), w.map { it.atMs })
        assertEquals(listOf(12_500L, 13_000L, 13_600L), w.map { it.endMs })
        assertTrue(w.none { it.estimated })
    }

    @Test
    fun `a final word without an end stamp runs to the next line but is capped`() {
        val l = lines("[00:10.00]<00:10.00>one <00:10.50>two\n[00:11.00]next")
        assertEquals(11_000L, l[0].words!!.last().endMs)
        val far = lines("[00:10.00]<00:10.00>one <00:10.50>two\n[00:40.00]next")
        assertEquals(10_500L + LrcParser.MAX_TAIL_MS, far[0].words!!.last().endMs)
    }

    @Test
    fun `the last line with no end stamp still gets a closing time`() {
        val w = lines("[00:10.00]<00:10.00>solo").single().words!!
        assertTrue(w.single().endMs > 10_000L)
    }

    @Test
    fun `plain and enhanced lines can be mixed in one file`() {
        val l = lines("[00:01.00]plain line\n[00:05.00]<00:05.00>word <00:05.40>level")
        assertNull(l[0].words)
        assertEquals("plain line", l[0].text)
        assertNotNull(l[1].words)
    }

    @Test
    fun `text before the first word stamp belongs to the line start`() {
        val l = lines("[00:05.00]Oh <00:05.50>yeah<00:06.00>").single()
        assertEquals("Oh yeah", l.text)
        assertEquals(5_000L, l.words!![0].atMs)
        assertEquals("Oh ", l.words!![0].text)
        assertEquals(5_500L, l.words!![1].atMs)
    }

    @Test
    fun `adjacent stamps with no text are skipped and a bare space is kept`() {
        val l = lines("[00:02.00]<00:02.00>a<00:02.30> <00:02.40>b<00:02.80>").single()
        assertEquals("a b", l.text)
        assertEquals(listOf("a ", "b"), l.words!!.map { it.text })
    }

    @Test
    fun `crlf line endings parse`() {
        val l = lines("[00:01.00]<00:01.00>a <00:01.50>b<00:02.00>\r\n[00:03.00]<00:03.00>c<00:03.40>\r\n")
        assertEquals(listOf("a b", "c"), l.map { it.text })
        assertEquals(3_400L, l[1].words!!.single().endMs)
    }

    @Test
    fun `a repeated chorus shifts its word times with each stamp`() {
        val l = lines("[00:10.00][01:00.00]<00:10.00>la <00:10.50>la<00:11.00>")
        assertEquals(2, l.size)
        assertEquals(listOf(10_000L, 10_500L), l[0].words!!.map { it.atMs })
        assertEquals(listOf(60_000L, 60_500L), l[1].words!!.map { it.atMs })
        assertEquals(61_000L, l[1].words!!.last().endMs)
    }

    @Test
    fun `out of order word stamps never produce a negative duration`() {
        val w = lines("[00:05.00]<00:05.00>a <00:04.00>b<00:06.00>").single().words!!
        assertTrue(w.all { it.endMs >= it.atMs })
    }

    @Test
    fun `background vocals are marked and keep their words`() {
        val l = lines("[00:05.00]<00:05.00>main<00:06.00>\n[00:05.50][bg:<00:05.50>(ooh)<00:06.20>]")
        assertFalse(l[0].background)
        assertTrue(l[1].background)
        assertEquals("(ooh)", l[1].text)
        assertEquals(6_200L, l[1].words!!.single().endMs)
    }

    @Test
    fun `plain lrc is unchanged and has no words`() {
        val l = MaLyrics("[00:12.34]hello\n[01:05.00]world", synced = true)
        assertEquals(listOf(12_340L, 65_000L), l.lines.map { it.atMs })
        assertTrue(l.lines.all { it.words == null })
        assertFalse(l.hasWordTiming)
    }

    @Test
    fun `hasWordTiming is true only for source timing`() {
        assertTrue(MaLyrics("[00:01.00]<00:01.00>a<00:02.00>", synced = true).hasWordTiming)
    }

    @Test
    fun `per-song and global offsets move the playhead and the word together`() {
        // The pane adds the offsets to the playhead, so a word keeps its place in the line.
        val line = lines("[00:10.00]<00:10.00>one <00:11.00>two<00:12.00>").single()
        val offset = 500L
        assertEquals(0, LyricTiming.activeWordIndex(line, 10_900L))
        assertEquals(1, LyricTiming.activeWordIndex(line, 10_900L + offset))
        // A negative offset (lyrics run early) holds the previous word for longer.
        assertEquals(-1, LyricTiming.activeWordIndex(line, 10_300L - offset))
    }

    // ── Estimation ────────────────────────────────────────────────────────

    @Test
    fun `estimated words cover the line in proportion to length`() {
        val w = LyricTiming.estimateWords("aa bbbb", 1_000, 2_000)!!
        assertEquals(listOf("aa ", "bbbb"), w.map { it.text })
        assertTrue(w.all { it.estimated })
        assertEquals(1_000L, w[0].atMs)
        // "aa " is 3 of 7 characters.
        assertEquals(1_000L + 1_000L * 3 / 7, w[1].atMs)
        assertTrue(w.last().endMs <= 2_000L)
    }

    @Test
    fun `no estimated word is longer than the cap`() {
        val w = LyricTiming.estimateWords("a very long held note", 0, 60_000)!!
        assertTrue(w.all { it.endMs - it.atMs <= LyricTiming.MAX_WORD_MS })
    }

    @Test
    fun `a short line before a long gap is not stretched across it`() {
        val w = LyricTiming.estimateWords("hi there", 0, 30_000)!!
        assertTrue(w.last().endMs < 5_000L)
    }

    @Test
    fun `lines with real word timing are left alone by estimation`() {
        val parsed = lines("[00:01.00]<00:01.00>a<00:02.00>\n[00:03.00]plain words here\n[00:08.00]end")
        val out = LyricTiming.withEstimatedWords(parsed)
        assertTrue(out[0].words!!.none { it.estimated })
        assertTrue(out[1].words!!.all { it.estimated })
    }

    @Test
    fun `blank lines and unsynced zero times are not estimated`() {
        assertNull(LyricTiming.estimateWords("   ", 0, 1000))
        val out = LyricTiming.withEstimatedWords(listOf(LyricLine(1000, ""), LyricLine(2000, "x")))
        assertNull(out[0].words)
    }

    @Test
    fun `cjk text splits per character`() {
        assertEquals(listOf("你", "好", "吗"), LyricTiming.tokenise("你好吗"))
        assertEquals(listOf("hi ", "你", "好"), LyricTiming.tokenise("hi 你好"))
    }

    // ── Active word ───────────────────────────────────────────────────────

    private val timed = LyricLine(
        10_000, "ab cd",
        listOf(LyricWord(10_000, 11_000, "ab "), LyricWord(11_000, 12_000, "cd")),
    )

    @Test
    fun `active word index follows the playhead`() {
        assertEquals(-1, LyricTiming.activeWordIndex(timed, 9_000))
        assertEquals(0, LyricTiming.activeWordIndex(timed, 10_500))
        assertEquals(1, LyricTiming.activeWordIndex(timed, 11_000))
        assertEquals(1, LyricTiming.activeWordIndex(timed, 20_000))
        assertEquals(-1, LyricTiming.activeWordIndex(LyricLine(0, "x"), 5_000))
    }

    @Test
    fun `word progress is clamped to 0 and 1`() {
        val w = LyricWord(1000, 2000, "x")
        assertEquals(0f, LyricTiming.wordProgress(w, 500))
        assertEquals(0.5f, LyricTiming.wordProgress(w, 1500))
        assertEquals(1f, LyricTiming.wordProgress(w, 9000))
        assertEquals(1f, LyricTiming.wordProgress(LyricWord(1000, 1000, "x"), 1001))
    }

    @Test
    fun `sung characters grow through a word and hold across a gap`() {
        assertEquals(0f, LyricTiming.sungChars(timed, 9_000))
        assertEquals(1f, LyricTiming.sungChars(timed, 10_500)) // half of "ab"
        assertEquals(3f, LyricTiming.sungChars(timed, 11_000)) // next word starts after the space
        assertEquals(4f, LyricTiming.sungChars(timed, 11_500)) // 3 + half of "cd"
        assertEquals(5f, LyricTiming.sungChars(timed, 99_000))
        val gap = LyricLine(0, "ab cd", listOf(LyricWord(0, 500, "ab "), LyricWord(2_000, 3_000, "cd")))
        assertEquals(2f, LyricTiming.sungChars(gap, 1_000))
    }

    @Test
    fun `stepping by word lights a word whole as soon as it starts`() {
        assertEquals(2f, LyricTiming.sungChars(timed, 10_100, stepByWord = true))
        assertEquals(5f, LyricTiming.sungChars(timed, 11_100, stepByWord = true))
    }

    // ── TTML ──────────────────────────────────────────────────────────────

    private val ttml = """<?xml version="1.0" encoding="UTF-8"?>
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
          <body><div>
            <p begin="00:12.000" end="00:14.000"><span begin="00:12.000" end="00:12.500">Hello</span> <span begin="00:12.500" end="00:13.000">there</span><span ttm:role="x-bg" begin="00:13.000" end="00:13.800">(ooh)</span></p>
            <p begin="00:20.000" end="00:22.000">Just a plain line</p>
          </div></body>
        </tt>"""

    @Test
    fun `ttml converts to enhanced lrc with words and a background line`() {
        assertTrue(Ttml.looksLikeTtml(ttml))
        val lrc = Ttml.toEnhancedLrc(ttml)!!
        val l = MaLyrics(lrc, synced = true).lines
        assertEquals(listOf("Hello there", "(ooh)", "Just a plain line"), l.map { it.text })
        assertEquals(listOf(12_000L, 13_000L, 20_000L), l.map { it.atMs })
        assertEquals(listOf("Hello ", "there"), l[0].words!!.map { it.text })
        assertEquals(13_000L, l[0].words!!.last().endMs)
        assertTrue(l[1].background)
        assertEquals(13_800L, l[1].words!!.single().endMs)
        assertNull(l[2].words)
        assertFalse(l[2].background)
    }

    @Test
    fun `ttml spans relative to their paragraph are shifted`() {
        val x = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="10s" end="12s"><span begin="0s" end="1s">a</span> <span begin="1s" end="2s">b</span></p>
            </div></body></tt>"""
        val l = MaLyrics(Ttml.toEnhancedLrc(x)!!, synced = true).lines.single()
        assertEquals(listOf(10_000L, 11_000L), l.words!!.map { it.atMs })
    }

    @Test
    fun `ttml time formats parse`() {
        assertEquals(62_500L, Ttml.parseTime("1:02.5"))
        assertEquals(3_662_500L, Ttml.parseTime("01:01:02.500"))
        assertEquals(12_500L, Ttml.parseTime("12.5s"))
        assertEquals(1_500L, Ttml.parseTime("1500ms"))
        assertNull(Ttml.parseTime("soon"))
        assertNull(Ttml.parseTime(""))
    }

    @Test
    fun `broken or empty ttml gives null instead of throwing`() {
        assertNull(Ttml.toEnhancedLrc("<tt><body><p begin='1s'"))
        assertNull(Ttml.toEnhancedLrc("<tt xmlns=\"http://www.w3.org/ns/ttml\"><body/></tt>"))
        assertFalse(Ttml.looksLikeTtml("[00:01.00]hello"))
    }

    @Test
    fun `ttml with a doctype is refused rather than expanded`() {
        val evil = "<?xml version=\"1.0\"?><!DOCTYPE tt [<!ENTITY x \"boom\">]><tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"1s\">&x;</p></div></body></tt>"
        // Either refused (null) or parsed without expanding the entity; never throws.
        val out = Ttml.toEnhancedLrc(evil)
        assertTrue(out == null || !out.contains("boom"))
    }
}
