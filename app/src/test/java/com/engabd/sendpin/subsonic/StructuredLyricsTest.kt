package com.engabd.sendpin.subsonic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructuredLyricsTest {

    private fun arr(s: String) = Json.parseToJsonElement(s) as JsonArray

    /**
     * The regression that mattered: a lyric starting at exactly 0 ms was emitted
     * without an LRC stamp, and MaLyrics drops unstamped lines out of a synced set —
     * so the opening line of the song silently disappeared.
     */
    @Test
    fun `a line at zero keeps its stamp`() {
        val r = StructuredLyrics.parse(
            arr(
                """[{"synced":true,"line":[
                     {"start":0,"value":"first"},
                     {"start":1500,"value":"second"}]}]"""
            )
        )!!
        assertTrue(r.synced)
        assertEquals("[0:00.00]first\n[0:01.50]second", r.text)
        // And it survives all the way through MaLyrics' own parser.
        assertEquals(2, r.lines.size)
        assertEquals("first", r.lines.first().text)
    }

    /**
     * A server that lists a plain set before the synced one used to win, because the
     * first entry was taken unconditionally.
     */
    @Test
    fun `the synced set wins even when listed second`() {
        val r = StructuredLyrics.parse(
            arr(
                """[{"synced":false,"line":[{"start":0,"value":"plain"}]},
                    {"synced":true,"line":[{"start":0,"value":"timed"}]}]"""
            )
        )!!
        assertTrue(r.synced)
        assertTrue("timed" in r.text)
    }

    @Test
    fun `a plain set is still returned when there is no synced one`() {
        val r = StructuredLyrics.parse(
            arr("""[{"synced":false,"line":[{"value":"just words"},{"value":"more"}]}]""")
        )!!
        assertEquals(false, r.synced)
        assertEquals("just words\nmore", r.text)
    }

    /** Servers vary on whether `synced` is a boolean or the string "true". */
    @Test
    fun `synced is accepted as a string too`() {
        val r = StructuredLyrics.parse(
            arr("""[{"synced":"true","line":[{"start":65010,"value":"x"}]}]""")
        )!!
        assertTrue(r.synced)
        assertEquals("[1:05.01]x", r.text)
    }

    @Test
    fun `minutes past ten format correctly`() {
        val r = StructuredLyrics.parse(
            arr("""[{"synced":true,"line":[{"start":754320,"value":"late"}]}]""")
        )!!
        assertEquals("[12:34.32]late", r.text)
    }

    @Test
    fun `nothing in, nothing out`() {
        assertNull(StructuredLyrics.parse(null))
        assertNull(StructuredLyrics.parse(arr("[]")))
        assertNull(StructuredLyrics.parse(arr("""[{"synced":true}]""")))
    }
}
