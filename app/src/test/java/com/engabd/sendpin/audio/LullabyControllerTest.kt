package com.engabd.sendpin.audio

import com.engabd.sendpin.hue.ambience.AmbienceEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lullaby wind-down is a pure list of steps — no Android types, no side effects — so
 * the whole curve can be tested with plain JUnit.
 *
 * What matters is the *shape*: three phases that each address a different thing that
 * keeps a listener awake, and the transitions between them must be smooth, not stepped.
 */
class LullabyControllerTest {

    @Test
    fun `phase 1 keeps current effect and dims to 30 percent`() {
        // Phase 1 (0-50%) does not switch effects — changing what is playing is more
        // disruptive than changing how bright it is.
        for (i in 0..49) {
            val step = LullabyController.stepAt(i / 100f, AmbienceEffect.THUNDERSTORM)
            assertNull(step.effect, "phase 1 should not switch effect at ${i}%")
            assertEquals(0.30f, step.brightness, 0.01f, "phase 1 brightness at ${i}%")
            assertFalse(step.stop, "phase 1 should not stop at ${i}%")
        }
    }

    @Test
    fun `phase 2 switches to aurora and dims from 30 to 15 percent`() {
        // Phase 2 (50-80%) replaces whatever was running with aurora — the quietest
        // effect — and dims to 15%.
        for (i in 50..79) {
            val step = LullabyController.stepAt(i / 100f, AmbienceEffect.FIREWORKS)
            assertEquals(AmbienceEffect.AURORA, step.effect, "phase 2 should use aurora at ${i}%")
            assertTrue(step.brightness <= 0.30f, "phase 2 brightness should be <= 30% at ${i}%")
            assertTrue(step.brightness >= 0.14f, "phase 2 brightness should be >= ~15% at ${i}%")
            assertFalse(step.stop, "phase 2 should not stop at ${i}%")
        }
    }

    @Test
    fun `phase 3 fades from 15 percent to zero`() {
        // Phase 3 (80-100%) fades the aurora from 15% to nothing.
        for (i in 80..99) {
            val step = LullabyController.stepAt(i / 100f)
            assertEquals(AmbienceEffect.AURORA, step.effect, "phase 3 should use aurora at ${i}%")
            assertTrue(step.brightness <= 0.16f, "phase 3 brightness should be <= ~15% at ${i}%")
            assertTrue(step.brightness >= 0f, "phase 3 brightness should be >= 0 at ${i}%")
            assertFalse(step.stop, "phase 3 should not stop before 100% at ${i}%")
        }
    }

    @Test
    fun `last step stops the show`() {
        val step = LullabyController.stepAt(1f)
        assertTrue(step.stop, "final step should stop")
        assertEquals(0f, step.brightness, 0.01f, "final step brightness should be zero")
    }

    @Test
    fun `brightness is monotonically non-increasing`() {
        // The wind-down never brightens — a room that got brighter mid-lullaby would
        // wake the listener, which is the opposite of what this is for.
        var prev = Float.MAX_VALUE
        for (i in 0..100) {
            val step = LullabyController.stepAt(i / 100f)
            assertTrue(step.brightness <= prev + 0.001f, "brightness increased at ${i}%: ${step.brightness} > $prev")
            prev = step.brightness
        }
    }

    @Test
    fun `brightness starts at 30 percent`() {
        val step = LullabyController.stepAt(0f)
        assertEquals(0.30f, step.brightness, 0.01f)
    }

    @Test
    fun `brightness ends at zero`() {
        val step = LullabyController.stepAt(1f)
        assertEquals(0f, step.brightness, 0.01f)
    }

    @Test
    fun `schedule produces the right number of steps for 20 minutes`() {
        val steps = LullabyController.schedule(durationMinutes = 20)
        // 20 min * 60 s / 2 s per step = 600 steps, plus the endpoint = 601
        assertEquals(601, steps.size, "20-minute schedule should have 601 steps")
    }

    @Test
    fun `schedule produces the right number of steps for 10 minutes`() {
        val steps = LullabyController.schedule(durationMinutes = 10)
        assertEquals(301, steps.size, "10-minute schedule should have 301 steps")
    }

    @Test
    fun `schedule first step is phase 1, last step stops`() {
        val steps = LullabyController.schedule(durationMinutes = 5)
        assertNull(steps.first().effect, "first step should be phase 1 (no effect switch)")
        assertTrue(steps.last().stop, "last step should stop")
    }

    @Test
    fun `schedule transitions through all three phases`() {
        val steps = LullabyController.schedule(durationMinutes = 20)
        val hasPhase1 = steps.any { it.effect == null }
        val hasPhase2 = steps.any { it.effect == AmbienceEffect.AURORA && it.brightness > 0.10f && it.brightness < 0.30f }
        val hasPhase3 = steps.any { it.effect == AmbienceEffect.AURORA && it.brightness < 0.15f && !it.stop }

        assertTrue(hasPhase1, "schedule should include phase 1 steps")
        assertTrue(hasPhase2, "schedule should include phase 2 steps")
        assertTrue(hasPhase3, "schedule should include phase 3 steps")
    }

    @Test
    fun `phase 2 transition happens at 50 percent`() {
        // At exactly 50%, the effect switches to aurora. This is the transition point.
        val before = LullabyController.stepAt(0.499f, AmbienceEffect.THUNDERSTORM)
        val at = LullabyController.stepAt(0.50f, AmbienceEffect.THUNDERSTORM)

        assertNull(before.effect, "just before 50% should still be phase 1")
        assertEquals(AmbienceEffect.AURORA, at.effect, "at 50% should switch to aurora")
    }

    @Test
    fun `phase 3 transition happens at 80 percent`() {
        // At 80%, the fade-to-zero begins. Brightness should start dropping below 15%.
        val at80 = LullabyController.stepAt(0.80f)
        assertTrue(at80.brightness < 0.16f, "at 80% brightness should be near 15%, got ${at80.brightness}")
    }

    @Test
    fun `clamps outside 0..1 without crashing`() {
        // A timing jitter may produce a progress slightly outside [0,1].
        LullabyController.stepAt(-0.1f)
        LullabyController.stepAt(1.1f)
    }

    @Test
    fun `minimum duration is 1 minute`() {
        // A 0 or negative duration would produce an empty schedule, which is not
        // a wind-down — coerce to at least 1 minute.
        val steps = LullabyController.schedule(durationMinutes = 0)
        assertTrue(steps.size > 1, "0 minutes should be coerced to at least 1 minute")
    }

    @Test
    fun `shouldStop only returns true for the final step`() {
        val steps = LullabyController.schedule(durationMinutes = 5)
        val stopCount = steps.count { LullabyController.shouldStop(it) }
        assertEquals(1, stopCount, "exactly one step should stop")
        assertTrue(steps.last().stop, "the stopping step should be the last one")
    }
}