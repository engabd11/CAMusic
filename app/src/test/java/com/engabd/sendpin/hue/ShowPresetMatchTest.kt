package com.engabd.sendpin.hue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * "Which saved show is the room on?"
 *
 * The Lights tab used to answer this by remembering which chip was last tapped, which
 * is a different question and gave the wrong answer three ways: it stayed lit after a
 * tunable moved, it was forgotten the moment the tab was left, and it said nothing at
 * all when a genre rule applied a preset by itself. [ShowPreset.matches] answers the
 * real one — is the live tuning *this* show — so all three fix themselves.
 *
 * The traps are all in the tunables: the same show reaches this comparison spelled two
 * ways, because `applyShowPreset` writes whatever the preset carried while the screen
 * writes every key it has a slider for.
 */
class ShowPresetMatchTest {

    @Test
    fun `the built-in default is the app's own defaults`() {
        val default = ShowPreset.default()
        // A fresh install's live show: every AppSettings flow at its fallback, which
        // is what `readShowPreset` produces before anything has been written.
        val untouched = ShowPreset(name = "")
        assertTrue(
            default.matches(untouched),
            "Default must be the show a phone that has never been tuned is already on",
        )
        assertEquals(ShowPreset.DEFAULT_ID, default.id)
    }

    @Test
    fun `a name and an id are not part of the show`() {
        val a = ShowPreset(id = "one", name = "Dinner", brightness = 45)
        val b = ShowPreset(id = "two", name = "Supper", brightness = 45)
        assertTrue(a.matches(b))
    }

    @Test
    fun `any tuning difference clears the match`() {
        val base = ShowPreset(name = "Party", intensity = "extreme", brightness = 100)
        assertFalse(base.matches(base.copy(brightness = 99)))
        assertFalse(base.matches(base.copy(intensity = "high")))
        assertFalse(base.matches(base.copy(color = "ocean")))
        assertFalse(base.matches(base.copy(spatial = true)))
        assertFalse(base.matches(base.copy(phantomStage = true)))
        assertFalse(base.matches(base.copy(autoLevels = listOf("subtle"))))
    }

    /**
     * The one that matters in practice: nudge a slider and the highlight must go out.
     */
    @Test
    fun `moving a tunable clears the match`() {
        val preset = ShowPreset(name = "Party")
        val nudged = preset.copy(tunables = mapOf("glow" to 1.4f))
        assertFalse(preset.matches(nudged))
        assertFalse(nudged.matches(preset))
    }

    @Test
    fun `a neutral multiplier is the same show as no multiplier at all`() {
        val stored = ShowPreset(name = "Party", tunables = emptyMap())
        // What the screen writes once every slider has been touched and put back.
        val written = ShowPreset(
            name = "Party",
            tunables = SyncoEngine.TUNABLE_KEYS.associateWith { 1f },
        )
        assertTrue(
            stored.matches(written),
            "an explicit 1.0 and a missing key are the same room",
        )
    }

    @Test
    fun `keys the engine does not have are ignored`() {
        val a = ShowPreset(name = "Party", tunables = mapOf("glow" to 1.4f))
        val b = ShowPreset(name = "Party", tunables = mapOf("glow" to 1.4f, "not_a_tunable" to 0.2f))
        assertTrue(a.matches(b))
    }

    /**
     * These values make a round trip through JSON on every read, and come off a slider
     * on the way in. Comparing raw floats would make the highlight flicker on values
     * that are the same show.
     */
    @Test
    fun `a float that survived a round trip still matches`() {
        val a = ShowPreset(name = "Party", tunables = mapOf("glow" to 1.4f))
        val b = ShowPreset(name = "Party", tunables = mapOf("glow" to 1.4000004f))
        assertTrue(a.matches(b))
    }

    @Test
    fun `out-of-range multipliers are clamped the way the store clamps them`() {
        val a = ShowPreset(name = "Party", tunables = mapOf("glow" to 2f))
        val b = ShowPreset(name = "Party", tunables = mapOf("glow" to 9f))
        assertTrue(a.matches(b), "AppSettings coerces to 0..2 on the way in and out")
    }

    @Test
    fun `auto levels are a set, not an order`() {
        val a = ShowPreset(name = "Party", autoLevels = listOf("subtle", "high"))
        val b = ShowPreset(name = "Party", autoLevels = listOf("high", "subtle"))
        assertTrue(a.matches(b))
    }

    @Test
    fun `a preset still matches itself after an encode and decode`() {
        val preset = ShowPreset(
            name = "Party",
            intensity = "extreme",
            autoLevels = listOf("high", "intense", "extreme"),
            brightness = 88,
            tunables = mapOf("glow" to 1.4f, "movement" to 0.6f),
            phantomStage = true,
            spatial = true,
        )
        val decoded = ShowPreset.decode(ShowPreset.encode(listOf(preset)))
        assertNotNull(decoded)
        assertEquals(1, decoded.size)
        assertTrue(preset.matches(decoded.first()))
    }

    /**
     * How the screen uses it: exactly one of the shown presets may be the active one,
     * and the starters must not collide with each other or with Default.
     */
    @Test
    fun `at most one show matches a given live state`() {
        val presets = listOf(ShowPreset.default()) + ShowPreset.starters()
        for (live in presets) {
            assertEquals(
                1, presets.count { it.matches(live) },
                "more than one show claims to be '${live.name}'",
            )
        }
    }
}
