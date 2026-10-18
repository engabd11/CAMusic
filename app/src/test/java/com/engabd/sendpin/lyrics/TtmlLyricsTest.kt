package com.engabd.sendpin.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A `.ttml` sidecar or a server that returns TTML is read through the same entry point as LRC. */
class TtmlLyricsTest {

    @Test
    fun `ttml text becomes synced word-timed lyrics`() {
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="00:01.000" end="00:02.000"><span begin="00:01.000" end="00:01.500">Hi</span> <span begin="00:01.500" end="00:02.000">you</span></p>
            </div></body></tt>"""
        val l = EmbeddedLyrics.toLyrics(xml)!!
        assertTrue(l.synced)
        assertTrue(l.hasWordTiming)
        assertEquals("Hi you", l.lines.single().text)
    }

    @Test
    fun `unusable ttml is no lyrics rather than a crash`() {
        assertNull(EmbeddedLyrics.toLyrics("<tt xmlns=\"http://www.w3.org/ns/ttml\"><body></body></tt>"))
    }

    @Test
    fun `enhanced lrc in a tag is synced with word timing`() {
        val l = EmbeddedLyrics.toLyrics("[00:01.00]<00:01.00>a <00:01.50>b<00:02.00>")!!
        assertTrue(l.synced)
        assertNotNull(l.lines.single().words)
    }

    @Test
    fun `plain text is still plain`() {
        val l = EmbeddedLyrics.toLyrics("just words")!!
        assertFalse(l.synced)
    }
}
