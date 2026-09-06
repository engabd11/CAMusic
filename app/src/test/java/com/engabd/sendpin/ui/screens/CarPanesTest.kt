package com.engabd.sendpin.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which way the car's screen gets cut.
 *
 * The same reason [com.engabd.sendpin.car.CarBrowseOptionsTest] exists: the code
 * under test runs on hardware nobody here has, and the way it fails is silent. A rule
 * that reads the display instead of the window does not crash — it just puts two
 * halves into a region with room for neither, on the one device class that cannot be
 * checked before shipping.
 *
 * The numbers below are real head units and the two phone orientations, so a change
 * to either threshold has to say out loud which of these it is changing.
 */
class CarPanesTest {

    @Test
    fun `a landscape head unit puts the player beside the library`() {
        // The common shape: 1280x720 and 1024x600 at car density.
        assertEquals(CarPanes.SIDE_BY_SIDE, carPanes(1280f, 720f))
        assertEquals(CarPanes.SIDE_BY_SIDE, carPanes(1024f, 600f))
    }

    @Test
    fun `a portrait head unit stacks them`() {
        // A tall centre screen with the app in full possession of it.
        assertEquals(CarPanes.STACKED, carPanes(576f, 768f))
    }

    @Test
    fun `a portrait head unit sharing its screen splits sideways instead`() {
        // The case orientation alone gets wrong. The display is still portrait and
        // `Configuration.ORIENTATION_PORTRAIT` still says so, but the app's window is
        // now short — and stacking a player and a library into 210dp each leaves
        // neither usable, while side by side leaves both.
        assertEquals(CarPanes.SIDE_BY_SIDE, carPanes(576f, 420f))
    }

    @Test
    fun `a window too narrow to split stacks even though it is landscape`() {
        // The mirror of the case above, and the reason the rule is not just
        // "wider than tall": half of 500dp is not a library grid.
        assertEquals(CarPanes.STACKED, carPanes(500f, 560f))
    }

    @Test
    fun `the preview on a phone follows the phone's orientation`() {
        // Settings > Android Auto > "Preview the car layout here" is judged by turning
        // the phone over, so both splits have to be reachable that way.
        assertEquals(CarPanes.STACKED, carPanes(411f, 891f))
        assertEquals(CarPanes.SIDE_BY_SIDE, carPanes(891f, 411f))
    }

    @Test
    fun `a window with room for neither cut still answers`() {
        // Nothing legitimately produces this — it is a floating window or a bad
        // multi-window guess — but the layout has to draw something, and the answer
        // should still follow the shape rather than a hardcoded fallback.
        assertEquals(CarPanes.SIDE_BY_SIDE, carPanes(400f, 300f))
        assertEquals(CarPanes.STACKED, carPanes(300f, 400f))
    }
}
