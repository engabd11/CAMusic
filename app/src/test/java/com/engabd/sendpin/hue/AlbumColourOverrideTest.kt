package com.engabd.sendpin.hue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumColourOverrideTest {

    // ── storage round-trip ────────────────────────────────────────────────

    @Test
    fun `an override map survives a round trip through storage`() {
        val overrides = mapOf(
            "album-one" to listOf(
                Triple(1.0f, 0.2f, 0.3f),
                Triple(0.1f, 0.5f, 0.9f),
            ),
            "album-two" to listOf(
                Triple(0.0f, 1.0f, 0.0f),
            ),
        )

        val back = AlbumColourOverride.decode(AlbumColourOverride.encode(overrides))

        assertEquals(overrides, back)
    }

    @Test
    fun `a single album with one colour round-trips`() {
        val overrides = mapOf("solo" to listOf(Triple(0.5f, 0.5f, 0.5f)))

        val back = AlbumColourOverride.decode(AlbumColourOverride.encode(overrides))

        assertEquals(overrides, back)
    }

    // ── null on garbage ──────────────────────────────────────────────────

    @Test
    fun `garbage decodes to null, never to an empty map`() {
        // The distinction is the whole point. An empty map is a real answer a
        // caller may act on by overwriting, so returning one for a read failure
        // would let the next save destroy overrides that were merely unreadable.
        assertNull(AlbumColourOverride.decode("not json at all"))
        assertNull(AlbumColourOverride.decode(""))
        assertNull(AlbumColourOverride.decode("{broken"))
    }

    // ── empty map handling ───────────────────────────────────────────────

    @Test
    fun `an empty map round-trips and stays empty`() {
        val back = AlbumColourOverride.decode(AlbumColourOverride.encode(emptyMap()))

        assertNotNull(back)
        assertTrue(back!!.isEmpty())
    }

    @Test
    fun `an empty overrides object decodes to an empty map`() {
        // The serialised form of an empty AlbumColourOverride is valid JSON
        // and should decode back to an empty map, not to null.
        val back = AlbumColourOverride.decode("{\"overrides\":{}}")

        assertNotNull(back)
        assertTrue(back!!.isEmpty())
    }

    // ── forward compatibility ─────────────────────────────────────────────

    @Test
    fun `a blob with an unknown key still loads`() {
        // ignoreUnknownKeys: a field added later must not make existing
        // overrides unreadable on a downgrade. Rgb is serialised as "r,g,b" strings.
        val withExtra = "{\"overrides\":{\"a\":[\"0.1,0.2,0.3\"]},\"somethingNew\":42}"

        val back = AlbumColourOverride.decode(withExtra)

        assertNotNull(back)
        assertEquals(1, back!!.size)
        assertEquals(Triple(0.1f, 0.2f, 0.3f), back["a"]!!.first())
    }
}