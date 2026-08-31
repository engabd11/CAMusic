package com.engabd.sendpin.hue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowPresetTest {

    // ── storage ───────────────────────────────────────────────────────────

    @Test
    fun `a preset survives a round trip through storage`() {
        val presets = listOf(
            ShowPreset(
                id = "one",
                name = "Party",
                intensity = "extreme",
                autoLevels = listOf("high", "extreme"),
                color = "album_art_v2",
                brightness = 90,
                tunables = mapOf("reactivity" to 1.4f),
                phantomStage = true,
                spatial = true,
            ),
        )

        val back = ShowPreset.decode(ShowPreset.encode(presets))

        assertEquals(presets, back)
    }

    @Test
    fun `an unreadable list decodes to null, never to an empty one`() {
        // The distinction is the whole point. An empty list is a real answer a
        // caller may act on by overwriting, so returning one for a read failure
        // would let the next save destroy presets that were merely unreadable.
        assertNull(ShowPreset.decode("not json at all"))
        assertNull(ShowPreset.decode(""))
        assertTrue(ShowPreset.decode("[]")!!.isEmpty())
    }

    @Test
    fun `a preset written by a newer version still loads`() {
        // encodeDefaults plus ignoreUnknownKeys: a field added later must not make
        // every existing preset unreadable on a downgrade.
        val withExtra = """[{"id":"x","name":"Dinner","intensity":"subtle","somethingNew":42}]"""

        val back = ShowPreset.decode(withExtra)

        assertNotNull(back)
        assertEquals("Dinner", back!!.single().name)
        assertEquals("subtle", back.single().intensity)
    }

    @Test
    fun `every starter has a name and a distinct id`() {
        val starters = ShowPreset.starters()

        assertTrue(starters.isNotEmpty())
        assertTrue(starters.all { it.name.isNotBlank() })
        assertEquals(starters.size, starters.map { it.id }.toSet().size)
    }

    // ── the summary line ──────────────────────────────────────────────────

    @Test
    fun `the summary counts the layers that are on`() {
        val bare = ShowPreset(intensity = "medium", brightness = 70)
        assertEquals("Medium · 70%", bare.summary())

        val loaded = bare.copy(musicDna = true, emotionalArc = true, spatial = true)
        assertEquals("Medium · 70% · 3 layers", loaded.summary())

        assertEquals("Medium · 70% · 1 layer", bare.copy(musicDna = true).summary())
    }

    // ── genre matching ────────────────────────────────────────────────────

    @Test
    fun `a genre rule matches in both directions and ignores case`() {
        val rule = GenrePresetRule("jazz", "p1")

        // The rule is narrower than the tag.
        assertTrue(rule.matches("Vocal Jazz"))
        assertTrue(rule.matches("JAZZ"))
        // And the other way round: a broad tag catches a specific rule.
        assertTrue(GenrePresetRule("Progressive House", "p1").matches("house"))

        assertTrue(!rule.matches("Ambient"))
        assertTrue(!rule.matches(""))
    }

    @Test
    fun `the first matching rule wins, so list order is priority order`() {
        val presets = listOf(ShowPreset(id = "a", name = "A"), ShowPreset(id = "b", name = "B"))
        val rules = listOf(GenrePresetRule("house", "a"), GenrePresetRule("electronic", "b"))

        // "Electronic House" matches both; the earlier rule takes it.
        assertEquals("A", GenrePresetRule.presetFor(rules, presets, "Electronic House")?.name)
        assertEquals("B", GenrePresetRule.presetFor(rules, presets, "Electronic")?.name)
    }

    @Test
    fun `an unmatched or absent genre leaves the show alone`() {
        val presets = listOf(ShowPreset(id = "a", name = "A"))
        val rules = listOf(GenrePresetRule("jazz", "a"))

        // Null rather than a default: the alternative is the room resetting itself
        // partway through a record because one track happened to carry a tag.
        assertNull(GenrePresetRule.presetFor(rules, presets, "Doom Metal"))
        assertNull(GenrePresetRule.presetFor(rules, presets, null))
        assertNull(GenrePresetRule.presetFor(rules, presets, "   "))
        assertNull(GenrePresetRule.presetFor(emptyList(), presets, "Jazz"))
    }

    @Test
    fun `a rule pointing at a deleted preset matches nothing`() {
        val rules = listOf(GenrePresetRule("jazz", "gone"))

        assertNull(GenrePresetRule.presetFor(rules, listOf(ShowPreset(id = "a")), "Jazz"))
    }
}
