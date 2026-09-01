package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The policy behind Exclusive output, on its own: whether a route can carry a
 * stream untouched, and the one caveat the mode can never remove (Android's
 * own mixer rate). Both matter more here than most policy objects in this
 * app, because [LocalPlayer] and the Settings UI both trust this rather than
 * checking for themselves — a wrong answer here is a wrong answer everywhere
 * at once.
 */
class ExclusiveOutputTest {

    // A plausible USB DAC: 16/24/32-bit, several rates up to 192 kHz.
    private val dacRates = listOf(44_100, 48_000, 96_000, 192_000)
    private val dacEncodings = listOf(2, 21, 22) // 16-bit, 24-bit packed, 32-bit

    @Test
    fun `a rate and depth the route reported both match`() {
        assertTrue(ExclusiveOutput.canCarryUntouched(96_000, 24, dacRates, dacEncodings))
    }

    @Test
    fun `float is recognised as 32-bit`() {
        assertTrue(ExclusiveOutput.canCarryUntouched(96_000, 32, dacRates, listOf(4)))
    }

    @Test
    fun `a rate the route did not report blocks it`() {
        assertFalse(ExclusiveOutput.canCarryUntouched(352_800, 24, dacRates, dacEncodings))
    }

    @Test
    fun `a depth the route did not report blocks it`() {
        // The phone speaker typically reports 16-bit only.
        assertFalse(ExclusiveOutput.canCarryUntouched(48_000, 24, dacRates, listOf(2)))
    }

    /**
     * [DeviceCapabilities.Route] uses an empty list for "the platform didn't
     * say", not "the platform said zero" — this has to honour the same
     * convention, or a device that simply didn't report a capability would
     * read as refusing every rate and depth there is.
     */
    @Test
    fun `an unreported capability list is not evidence either way`() {
        assertTrue(ExclusiveOutput.canCarryUntouched(192_000, 32, emptyList(), emptyList()))
        assertTrue(ExclusiveOutput.canCarryUntouched(192_000, 32, dacRates, emptyList()))
        assertTrue(ExclusiveOutput.canCarryUntouched(192_000, 32, emptyList(), dacEncodings))
    }

    @Test
    fun `mixer rate caveat is null when they agree`() {
        assertNull(ExclusiveOutput.mixerRateCaveat(48_000, 48_000))
    }

    @Test
    fun `mixer rate caveat is null when either side is unknown`() {
        assertNull(ExclusiveOutput.mixerRateCaveat(0, 48_000))
        assertNull(ExclusiveOutput.mixerRateCaveat(48_000, 0))
    }

    @Test
    fun `mixer rate caveat fires when they disagree`() {
        val note = ExclusiveOutput.mixerRateCaveat(96_000, 48_000)
        assertNotNull(note)
        assertTrue(note.contains("48") && note.contains("96"), "expected both rates named: $note")
    }

    @Test
    fun `disables lists every stage the mode turns off`() {
        val titles = ExclusiveOutput.disables.map { it.title }
        assertTrue(titles.size >= 5, "expected the equaliser, Light Sync, sound modes, " +
            "ReplayGain, in-app volume and resampling: $titles")
        assertTrue(titles.any { it.contains("Equaliser", ignoreCase = true) })
        assertTrue(titles.any { it.contains("Light Sync", ignoreCase = true) })
        assertTrue(titles.any { it.contains("ReplayGain", ignoreCase = true) })
        assertTrue(titles.any { it.contains("volume", ignoreCase = true) })
        assertTrue(titles.any { it.contains("resampl", ignoreCase = true) })
        // Every entry has to actually say something, or the UI renders a blank line.
        assertTrue(ExclusiveOutput.disables.all { it.reason.isNotBlank() })
    }

    @Test
    fun `the Android ceiling note is stated, not implied`() {
        val note = ExclusiveOutput.ANDROID_CEILING_NOTE
        assertTrue(note.contains("AudioFlinger"), "must not overclaim past the mixer: $note")
    }
}
