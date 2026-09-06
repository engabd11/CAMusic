package com.engabd.sendpin.hue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two ways a show gets chosen for you, and the bug that stopped one of them
 * working at all.
 *
 * ## Why the starter ids are pinned
 *
 * "Pick a show by genre" did nothing, and tying a genre to a preset immediately drew
 * "Jazz → (deleted)" under a preset that was plainly still on screen. One cause:
 * `AppSettings.showPresets` falls back to [ShowPreset.starters] for as long as nothing
 * has been saved, that flow is re-read on *every* DataStore write, and `starters()`
 * minted a fresh [java.util.UUID] per call. So the three shipped shows changed identity
 * whenever any setting anywhere in the app changed — including the very write that
 * saved the rule. The rule was therefore filed against an id that had stopped existing
 * before the row was drawn, and [GenrePresetRule.presetFor] could never resolve it.
 *
 * Nothing in the app can hold a reference to a preset unless its id is stable, so this
 * is tested at the level the guarantee lives: two calls, same ids.
 */
class SongShowRuleTest {

    @Test
    fun `the starter shows keep their identity across calls`() {
        val first = ShowPreset.starters()
        val second = ShowPreset.starters()

        assertEquals(
            first.map { it.id },
            second.map { it.id },
            "starter ids changed between calls, so nothing can point at one",
        )
        assertTrue(first.all { it.id.isNotBlank() })
        // Distinct, or two starters would be one preset as far as a rule is concerned.
        assertEquals(first.size, first.map { it.id }.distinct().size)
        // And never the built-in's, which `showPresets` filters out of the stored list.
        assertTrue(first.none { it.id == ShowPreset.DEFAULT_ID })
    }

    @Test
    fun `a genre rule survives being saved against a starter`() {
        // The reported failure end to end: tie "jazz" to the shipped Dinner show, then
        // resolve it again from a freshly-read preset list, as the applier does.
        val dinner = ShowPreset.starters().first { it.name == "Dinner" }
        val rules = listOf(GenrePresetRule("jazz", dinner.id))

        val resolved = GenrePresetRule.presetFor(rules, ShowPreset.starters(), "Vocal Jazz")

        assertNotNull(resolved, "the rule pointed at a preset that no longer existed")
        assertEquals("Dinner", resolved.name)
    }

    // ── Per-song shows ────────────────────────────────────────────────────

    @Test
    fun `a song is found again under any identity it was saved with`() {
        // The keys are what makes this work across backends: the same record played
        // from Navidrome and then through Music Assistant has two library ids and one
        // name, and only one of the two is available at any given moment.
        val saved = TrackShowRule.keysFor("Blue in Green", "Miles Davis", "nav-42")
        val rule = TrackShowRule(keys = saved, presetId = "p1", label = "Blue in Green")
        val presets = listOf(ShowPreset(id = "p1", name = "Dinner"))

        // Title and artist only — the Music Assistant feed, which carries no track id.
        val byName = TrackShowRule.keysFor("Blue in Green", "Miles Davis", null)
        assertEquals("Dinner", TrackShowRule.presetFor(listOf(rule), presets, byName)?.name)

        // Id only — a track whose tags are blank.
        val byId = TrackShowRule.keysFor(null, null, "nav-42")
        assertEquals("Dinner", TrackShowRule.presetFor(listOf(rule), presets, byId)?.name)
    }

    @Test
    fun `case and padding in the tags do not lose a song`() {
        val saved = TrackShowRule.keysFor("Blue In Green", "Miles Davis", null)
        val seen = TrackShowRule.keysFor("  blue in green ", "miles davis", null)

        assertTrue(TrackShowRule(keys = saved, presetId = "p1").matches(seen))
    }

    @Test
    fun `a different song is not caught by another song's rule`() {
        val rule = TrackShowRule(
            keys = TrackShowRule.keysFor("Blue in Green", "Miles Davis", "nav-42"),
            presetId = "p1",
        )
        val presets = listOf(ShowPreset(id = "p1", name = "Dinner"))
        val other = TrackShowRule.keysFor("So What", "Miles Davis", "nav-43")

        assertNull(TrackShowRule.presetFor(listOf(rule), presets, other))
    }

    @Test
    fun `a song with nothing to identify it pins nothing`() {
        // Blank in, blank out — the alternative is every untagged track sharing one
        // key and therefore one show.
        assertTrue(TrackShowRule.keysFor(null, null, null).isEmpty())
        assertTrue(TrackShowRule.keysFor("  ", "  ", "  ").isEmpty())

        val rule = TrackShowRule(keys = emptyList(), presetId = "p1")
        assertTrue(!rule.matches(TrackShowRule.keysFor("Anything", null, null)))
    }

    @Test
    fun `a pinned show whose preset was deleted resolves to nothing`() {
        // Rather than to a default. The room is left where it is, which is the same
        // answer GenrePresetRule gives for the same situation.
        val rule = TrackShowRule(
            keys = TrackShowRule.keysFor("Blue in Green", "Miles Davis", null),
            presetId = "gone",
        )
        val keys = TrackShowRule.keysFor("Blue in Green", "Miles Davis", null)

        assertNull(TrackShowRule.presetFor(listOf(rule), listOf(ShowPreset(id = "p1")), keys))
    }

    @Test
    fun `rules survive a round trip through storage`() {
        val rules = listOf(
            TrackShowRule(
                keys = TrackShowRule.keysFor("Blue in Green", "Miles Davis", "nav-42"),
                presetId = "p1",
                label = "Blue in Green - Miles Davis",
            ),
        )

        val decoded = TrackShowRule.decode(TrackShowRule.encode(rules))

        assertEquals(rules, decoded)
    }

    @Test
    fun `an unreadable store is null rather than an empty list`() {
        // Same contract as ShowPreset.decode, for the same reason: an empty list is a
        // real answer a caller may act on by overwriting, and turning a decode failure
        // into "you have pinned nothing" would destroy the lot on the next write.
        assertNull(TrackShowRule.decode("not json"))
    }
}
