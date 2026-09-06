package com.engabd.sendpin.hue

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Auto has to *move*.
 *
 * [AutoIntensityPickerTest] covers which rung a song settles on, and every one of
 * those assertions passed while the reported bug was live: "the auto picker sticks to
 * one setting in the entire song". Settling correctly and never moving again are not
 * in tension — they were the same behaviour, and nothing tested the second half.
 *
 * The cause was that the character band and the rung cells were measured on one shared
 * 0..1 axis and did not line up. A mid-character song earned roughly 0.22..0.72 of that
 * axis while the default Subtle/Medium/High selection cut it at 0.115 and 0.377, so the
 * song could reach two rungs at best — and once [IntensityProfile.dynamics] had pulled
 * the moment toward the middle of its band, which for a modern loudness-war master it
 * does hard, only one. See `AutoIntensityPicker.windowFor`.
 *
 * These tests drive the picker with a *changing* song rather than a constant one, which
 * is the thing the existing suite never did.
 */
class AutoIntensityMovementTest {

    private val dt = 1f / 50f

    /**
     * Play a song whose intensity rises and falls, and report every rung it visited.
     *
     * [character] and [dynamics] stand in for an offline scan, which is the path the
     * bug bit hardest: with a scan the picker is handed the song's own signal curve and
     * its own p10/p95 window, so the moment is already normalised before the dynamics
     * term compresses it a second time.
     */
    private fun rungsVisited(
        character: Float,
        dynamics: Float?,
        allowed: List<SyncMode> = DEFAULT_AUTO_LEVELS,
        cycles: Int = 3,
        secondsPerCycle: Float = 40f,
    ): Set<SyncMode> {
        val picker = AutoIntensityPicker()
        val seen = mutableSetOf<SyncMode>()
        val frames = (secondsPerCycle / dt).toInt()
        repeat(cycles) {
            for (i in 0 until frames) {
                // A triangle from the song's own floor to its own ceiling and back:
                // an intro, a build, a chorus, a breakdown.
                val phase = i.toFloat() / frames
                val level = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
                seen += picker.update(
                    dt = dt,
                    energy = 0.2f + 0.7f * level,
                    salience = 0.3f + 0.6f * level,
                    bpm = 120f,
                    beat = i % 25 == 0,
                    allowed = allowed,
                    onsetWidth = 0.2f,
                    centroid = 0.4f,
                    flux = 0.2f,
                    // What a scan supplies. `signal` is the curve at this position,
                    // already inside the [lo, hi] window below.
                    signal = SIG_LO_REF + (SIG_HI_REF - SIG_LO_REF) * level,
                    character = character,
                    dynamics = dynamics,
                )
            }
        }
        return seen
    }

    @Test
    fun `a song that rises and falls does not hold one rung`() {
        for (character in listOf(0.2f, 0.35f, 0.5f, 0.7f, 0.9f)) {
            val seen = rungsVisited(character, dynamics = null)
            assertTrue(
                seen.size >= 2,
                "character $character held a single rung ($seen) across three builds",
            )
        }
    }

    @Test
    fun `an over-compressed master still moves`() {
        // The case that reduced Auto to one rung for a whole song. A p95-p10 spread of
        // 0.06 is ordinary for a modern pop or rock master, and it used to squeeze the
        // operating point into a range narrower than the hysteresis dead-band, so no
        // edge could ever be crossed. See PICK_DYN_FLOOR.
        for (character in listOf(0.3f, 0.5f, 0.8f)) {
            val seen = rungsVisited(character, dynamics = 0.06f)
            assertTrue(
                seen.size >= 2,
                "a flat master at character $character held a single rung ($seen)",
            )
        }
    }

    @Test
    fun `a dynamic song still moves more than a flat one cannot`() {
        // The dynamics term is floored, not removed: a song that genuinely moves must
        // still be free to use at least as much of its window as one that does not.
        val flat = rungsVisited(0.5f, dynamics = 0.02f, allowed = SyncMode.entries.toList())
        val wide = rungsVisited(0.5f, dynamics = 0.40f, allowed = SyncMode.entries.toList())
        assertTrue(
            wide.size >= flat.size,
            "a dynamic song ($wide) used less of its window than a flat one ($flat)",
        )
    }

    @Test
    fun `the window still respects the character ceiling`() {
        // Movement must not have been bought by letting every song reach the top.
        val chill = rungsVisited(0.15f, dynamics = 0.3f, allowed = SyncMode.entries.toList())
        assertTrue(
            chill.none { LADDER_ORDER.indexOf(it) > LADDER_ORDER.indexOf(SyncMode.MEDIUM) },
            "an ambient-character song reached $chill",
        )
        val heavy = rungsVisited(0.95f, dynamics = 0.3f, allowed = SyncMode.entries.toList())
        assertTrue(
            heavy.none { LADDER_ORDER.indexOf(it) < LADDER_ORDER.indexOf(SyncMode.HIGH) },
            "a heavy song dropped as low as $heavy",
        )
    }

    @Test
    fun `every visited rung is one the listener allowed`() {
        val allowed = listOf(SyncMode.MEDIUM, SyncMode.INTENSE)
        for (character in listOf(0.2f, 0.6f, 0.95f)) {
            val seen = rungsVisited(character, dynamics = 0.2f, allowed = allowed)
            assertTrue(seen.all { it in allowed }, "picked outside the selection: $seen")
        }
    }

    private companion object {
        val LADDER_ORDER = listOf(
            SyncMode.SUBTLE, SyncMode.MEDIUM, SyncMode.HIGH, SyncMode.INTENSE, SyncMode.EXTREME,
        )
    }
}
