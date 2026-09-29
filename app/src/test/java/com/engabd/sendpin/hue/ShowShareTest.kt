package com.engabd.sendpin.hue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShowShareTest {

    private val party = ShowPreset(
        id = "sender-id",
        name = "Party",
        intensity = "extreme",
        color = "neon",
        brightness = 80,
        tunables = mapOf(SyncoEngine.TUNABLE_KEYS.first() to 1.4f),
        musicDna = true,
    )

    @Test
    fun `a shared show comes back as the same show under a new id`() {
        val back = ShowShare.parse(ShowShare.link(party))!!
        assertTrue(back.matches(party))
        assertEquals("Party", back.name)
        assertNotEquals("sender-id", back.id)
        assertTrue(back.id.isNotBlank())
    }

    @Test
    fun `the link is found inside a whole shared message`() {
        val back = ShowShare.parse("Try this!\n" + ShowShare.message(party) + "\n\nsent from my phone")
        assertEquals("Party", back?.name)
    }

    @Test
    fun `the sender's id does not travel`() {
        assertTrue("sender-id" !in ShowShare.link(party))
    }

    @Test
    fun `values are clamped to what the controls allow`() {
        val wild = party.copy(brightness = 500, tunables = mapOf("notAKnob" to 9f, SyncoEngine.TUNABLE_KEYS.first() to 9f))
        val back = ShowShare.parse(ShowShare.link(wild))!!
        assertEquals(100, back.brightness)
        assertEquals(setOf(SyncoEngine.TUNABLE_KEYS.first()), back.tunables.keys)
        assertEquals(2f, back.tunables.values.single())
    }

    @Test
    fun `anything else is not a show`() {
        assertNull(ShowShare.parse(null))
        assertNull(ShowShare.parse("hello"))
        assertNull(ShowShare.parse("camusic://show/"))
        assertNull(ShowShare.parse("camusic://show/not-base64-json"))
    }
}
