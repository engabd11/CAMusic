package com.engabd.sendpin.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalTagsTest {

    @Test
    fun `multi-valued genre columns split on every common separator`() {
        assertEquals(listOf("Rock", "Pop"), LocalTags.genres("Rock;Pop"))
        assertEquals(listOf("Rock", "Pop"), LocalTags.genres("Rock / Pop"))
        assertEquals(listOf("Jazz", "Soul"), LocalTags.genres("Jazz, Soul, jazz"))
        assertEquals(emptyList(), LocalTags.genres(null))
        // An unmapped ID3v1 number is not a genre anyone would recognise.
        assertEquals(listOf("Ambient"), LocalTags.genres("(26);Ambient"))
    }

    @Test
    fun `the codec comes from the MIME subtype, not its type`() {
        // The old code took the part before the slash, so every badge said "audio".
        assertEquals("flac", LocalTags.codecOf("audio/flac"))
        assertEquals("mp3", LocalTags.codecOf("audio/mpeg"))
        assertEquals("aac", LocalTags.codecOf("audio/mp4"))
        assertEquals("opus", LocalTags.codecOf("audio/opus"))
        assertEquals("wav", LocalTags.codecOf("audio/x-wav"))
        assertNull(LocalTags.codecOf("audio"))
        assertNull(LocalTags.codecOf(null))
    }
}
