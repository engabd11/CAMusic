package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The output ladder's invariants.
 *
 * These matter because [OutputMode] replaced three independent switches whose
 * illegal combinations were guarded ad hoc in the settings UI — the point of the
 * type is that those combinations can no longer be written down, and that is only
 * true while the round trip below holds.
 */
class OutputModeTest {

    @Test
    fun `each rung round-trips through the stored flags`() {
        for (mode in OutputMode.entries) {
            assertEquals(
                mode,
                OutputMode.of(mode.floatPath, mode.exclusive, mode.aaudio, mode.usb),
                "round trip for $mode",
            )
        }
    }

    @Test
    fun `the flags only ever accumulate going up the ladder`() {
        val rungs = OutputMode.entries
        for (i in 1 until rungs.size) {
            val below = rungs[i - 1]
            val here = rungs[i]
            assertTrue(!below.floatPath || here.floatPath, "$here dropped the float path")
            assertTrue(!below.exclusive || here.exclusive, "$here dropped exclusive")
        }
    }

    @Test
    fun `standard removes nothing and direct removes everything`() {
        assertFalse(OutputMode.STANDARD.floatPath)
        assertFalse(OutputMode.STANDARD.exclusive)
        assertFalse(OutputMode.STANDARD.aaudio)

        assertTrue(OutputMode.DIRECT.floatPath)
        assertTrue(OutputMode.DIRECT.exclusive)
        assertTrue(OutputMode.DIRECT.aaudio)
    }

    @Test
    fun `only direct uses aaudio, and only usb bit-perfect uses the usb driver`() {
        assertEquals(listOf(OutputMode.DIRECT), OutputMode.entries.filter { it.aaudio })
        assertEquals(listOf(OutputMode.USB_BITPERFECT), OutputMode.entries.filter { it.usb })
    }

    @Test
    fun `usb bit-perfect is the deepest rung and removes everything`() {
        assertEquals(OutputMode.USB_BITPERFECT, OutputMode.entries.last())
        assertTrue(OutputMode.USB_BITPERFECT.floatPath)
        assertTrue(OutputMode.USB_BITPERFECT.exclusive)
        assertTrue(OutputMode.USB_BITPERFECT.alwaysBypassesProcessors)
    }

    /** Like aaudio: the usb flag with no exclusive under it did nothing, and resolves to what played. */
    @Test
    fun `usb without exclusive resolves to what it actually did`() {
        assertEquals(OutputMode.HIGH_RESOLUTION, OutputMode.of(floatPath = true, exclusive = false, aaudio = false, usb = true))
    }

    /**
     * The combination the old three-switch UI could store and that did nothing:
     * AAudio requested with no exclusive under it. `LocalPlayer` computes
     * `aaudioBitperfect && exclusive`, so what it actually played was the plain
     * float path — which is what this has to resolve to.
     */
    @Test
    fun `aaudio without exclusive resolves to what it actually did`() {
        assertEquals(
            OutputMode.HIGH_RESOLUTION,
            OutputMode.of(floatPath = true, exclusive = false, aaudio = true),
        )
        assertEquals(
            OutputMode.STANDARD,
            OutputMode.of(floatPath = false, exclusive = false, aaudio = true),
        )
    }

    /** Exclusive forces the float path on regardless of the stored high-res flag. */
    @Test
    fun `exclusive without the float flag is still Pure`() {
        assertEquals(
            OutputMode.PURE,
            OutputMode.of(floatPath = false, exclusive = true, aaudio = false),
        )
    }

    @Test
    fun `plain mode offers two rungs and advanced offers them all`() {
        assertEquals(
            listOf(OutputMode.STANDARD, OutputMode.HIGH_RESOLUTION),
            OutputMode.offered(advanced = false, current = OutputMode.STANDARD),
        )
        assertEquals(
            listOf(OutputMode.STANDARD, OutputMode.HIGH_RESOLUTION, OutputMode.USB_BITPERFECT),
            OutputMode.offered(advanced = true, current = OutputMode.STANDARD),
        )
    }

    @Test
    fun `pure and direct are not offered, but stay visible to someone standing on them`() {
        val offered = OutputMode.offered(advanced = true, current = OutputMode.STANDARD)
        assertFalse(OutputMode.PURE in offered)
        assertFalse(OutputMode.DIRECT in offered)
        val onPure = OutputMode.offered(advanced = true, current = OutputMode.PURE)
        assertEquals(listOf(OutputMode.STANDARD, OutputMode.HIGH_RESOLUTION, OutputMode.PURE, OutputMode.USB_BITPERFECT), onPure)
    }

    @Test
    fun `a deep rung stays visible after advanced is turned off`() {
        // Otherwise the user is standing on a setting the UI cannot show them, and
        // has no way back down to Standard without turning advanced on again.
        val offered = OutputMode.offered(advanced = false, current = OutputMode.DIRECT)
        assertTrue(OutputMode.DIRECT in offered)
        assertTrue(OutputMode.STANDARD in offered)
    }
}
