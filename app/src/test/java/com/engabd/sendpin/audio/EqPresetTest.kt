package com.engabd.sendpin.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqPresetTest {

    // ── storage ───────────────────────────────────────────────────────────

    @Test
    fun `a preset survives a round trip through storage`() {
        val presets = listOf(
            EqPreset(
                id = "one",
                name = "Bass boost",
                bands = LocalDsp.Config.defaultBands().mapIndexed { i, b ->
                    if (i == 0) b.copy(gainDb = 6f) else b
                },
                preampDb = -2f,
                autoPreamp = false,
            ),
        )

        val back = EqPreset.decode(EqPreset.encode(presets))

        assertEquals(presets, back)
    }

    @Test
    fun `an unreadable list decodes to null, never to an empty one`() {
        // The distinction is the whole point. An empty list is a real answer a
        // caller may act on by overwriting, so returning one for a read failure
        // would let the next save destroy presets that were merely unreadable.
        assertNull(EqPreset.decode("not json at all"))
        assertNull(EqPreset.decode(""))
        assertTrue(EqPreset.decode("[]")!!.isEmpty())
    }

    @Test
    fun `a preset written by a newer version still loads`() {
        // encodeDefaults plus ignoreUnknownKeys: a field added later must not make
        // every existing preset unreadable on a downgrade.
        val withExtra = """[{"id":"x","name":"Bass","somethingNew":42}]"""

        val back = EqPreset.decode(withExtra)

        assertNotNull(back)
        assertEquals("Bass", back!!.single().name)
    }

    @Test
    fun `every starter has a name and a distinct id`() {
        val starters = EqPreset.starters()

        assertTrue(starters.isNotEmpty())
        assertTrue(starters.all { it.name.isNotBlank() })
        assertEquals(starters.size, starters.map { it.id }.toSet().size)
    }

    // ── starter curves ────────────────────────────────────────────────────

    @Test
    fun `the bass boost starter boosts 62 and 125 Hz`() {
        val bass = EqPreset.starters().first { it.name == "Bass boost" }

        assertEquals(6f, bass.bands.first { it.frequency == 62f }.gainDb)
        assertEquals(4f, bass.bands.first { it.frequency == 125f }.gainDb)
    }

    @Test
    fun `the flat starter is actually flat`() {
        val flat = EqPreset.starters().first { it.name == "Flat" }

        assertTrue(flat.bands.all { !it.altersSignal() })
    }

    @Test
    fun `the vocal forward starter boosts 1k and 2k, cuts 125`() {
        val vocal = EqPreset.starters().first { it.name == "Vocal forward" }

        assertEquals(3f, vocal.bands.first { it.frequency == 1_000f }.gainDb)
        assertEquals(2f, vocal.bands.first { it.frequency == 2_000f }.gainDb)
        assertEquals(-2f, vocal.bands.first { it.frequency == 125f }.gainDb)
    }

    @Test
    fun `the classical starter boosts 31 Hz and 16 kHz`() {
        val classical = EqPreset.starters().first { it.name == "Classical" }

        assertEquals(2f, classical.bands.first { it.frequency == 31f }.gainDb)
        assertEquals(1f, classical.bands.first { it.frequency == 16_000f }.gainDb)
    }

    // ── toConfig ─────────────────────────────────────────────────────────

    @Test
    fun `toConfig carries the bands and preamp settings`() {
        val preset = EqPreset(
            name = "Test",
            bands = LocalDsp.Config.defaultBands().map { it.copy(gainDb = 3f) },
            preampDb = -1.5f,
            autoPreamp = false,
        )

        val config = preset.toConfig(enabled = true)

        assertEquals(true, config.enabled)
        assertEquals(preset.bands, config.bands)
        assertEquals(-1.5f, config.preampDb)
        assertEquals(false, config.autoPreamp)
    }

    // ── genre matching ────────────────────────────────────────────────────

    @Test
    fun `a genre rule matches in both directions and ignores case`() {
        val rule = EqGenrePresetRule("jazz", "p1")

        // The rule is narrower than the tag.
        assertTrue(rule.matches("Vocal Jazz"))
        assertTrue(rule.matches("JAZZ"))
        // And the other way round: a broad tag catches a specific rule.
        assertTrue(EqGenrePresetRule("Progressive House", "p1").matches("house"))

        assertTrue(!rule.matches("Ambient"))
        assertTrue(!rule.matches(""))
    }

    @Test
    fun `the first matching rule wins, so list order is priority order`() {
        val presets = listOf(EqPreset(id = "a", name = "A"), EqPreset(id = "b", name = "B"))
        val rules = listOf(EqGenrePresetRule("house", "a"), EqGenrePresetRule("electronic", "b"))

        // "Electronic House" matches both; the earlier rule takes it.
        assertEquals("A", EqGenrePresetRule.presetFor(rules, presets, "Electronic House")?.name)
        assertEquals("B", EqGenrePresetRule.presetFor(rules, presets, "Electronic")?.name)
    }

    @Test
    fun `an unmatched or absent genre leaves the curve alone`() {
        val presets = listOf(EqPreset(id = "a", name = "A"))
        val rules = listOf(EqGenrePresetRule("jazz", "a"))

        // Null rather than a default: the alternative is the EQ resetting itself
        // partway through a record because one track happened to carry a tag.
        assertNull(EqGenrePresetRule.presetFor(rules, presets, "Doom Metal"))
        assertNull(EqGenrePresetRule.presetFor(rules, presets, null))
        assertNull(EqGenrePresetRule.presetFor(rules, presets, "   "))
        assertNull(EqGenrePresetRule.presetFor(emptyList(), presets, "Jazz"))
    }

    @Test
    fun `a rule pointing at a deleted preset matches nothing`() {
        val rules = listOf(EqGenrePresetRule("jazz", "gone"))

        assertNull(EqGenrePresetRule.presetFor(rules, listOf(EqPreset(id = "a")), "Jazz"))
    }

    @Test
    fun `rules survive a round trip through storage`() {
        val rules = listOf(
            EqGenrePresetRule("jazz", "p1"),
            EqGenrePresetRule("electronic", "p2"),
        )

        val back = EqGenrePresetRule.decode(EqGenrePresetRule.encode(rules))

        assertEquals(rules, back)
    }

    @Test
    fun `unreadable rules decode to null`() {
        assertNull(EqGenrePresetRule.decode("garbage"))
    }
}