package com.engabd.sendpin.hue

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The eye-safety limiter's guarantees.
 *
 * These matter more in the direct path than they do in syncoV2. Streaming
 * straight to the bridge means nothing sits between the engine and the bulbs, so
 * a bad analysis frame reaches the room unless this stops it.
 *
 * Ported from syncoV2 `tests/test_safety.py`.
 */
class FieldSafetyTest {

    private val dt = 0.025f  // 40 fps

    private fun field(colors: Map<Int, Rgb>): Float {
        if (colors.isEmpty()) return 0f
        var s = 0f
        for (c in colors.values) s += max(c.first, max(c.second, c.third))
        return s / colors.size
    }

    /** Count opposing-transition pairs of at least FLASH_DELTA in a series. */
    private fun countFlashes(fields: List<Float>): Int {
        var dir = 0
        var lastExtreme = fields.firstOrNull() ?: 0f
        var half = 0
        var prev = fields.firstOrNull() ?: 0f
        for (f in fields.drop(1)) {
            val cur = when {
                f > prev + 1e-4f -> 1
                f < prev - 1e-4f -> -1
                else -> dir
            }
            if (dir != 0 && cur != 0 && cur != dir) {
                if (abs(prev - lastExtreme) >= FLASH_DELTA) half++
                lastExtreme = prev
            }
            if (cur != 0) dir = cur
            prev = f
        }
        return half / 2
    }

    @Test
    fun `the flash limiter bounds any strobe rate`() {
        // The headline guarantee. A pathological whole-field black/white strobe
        // at any rate must never exceed three flashes in a rolling second.
        for (hold in listOf(1, 2, 3, 4, 6)) {
            val safety = FieldSafety()
            var maxWindow = 0
            for (i in 0 until 800) {
                val v = if ((i / hold) % 2 == 0) 1f else 0f
                safety.process(mapOf(0 to Rgb(v, v, v)), dt)
                maxWindow = max(maxWindow, safety.flashesInWindow)
            }
            assertTrue(
                maxWindow <= MAX_FLASHES_PER_S,
                "a strobe held for $hold frames reached $maxWindow flashes in a second",
            )
        }
    }

    @Test
    fun `the limiter is transparent on calm content`() {
        // Swings below the threshold must pass through untouched. A limiter that
        // dulls ordinary motion would be worse than none — it would be tuned
        // around and switched off.
        val safety = FieldSafety()
        for (i in 0 until 400) {
            val v = 0.6f + 0.04f * sin(i * 0.4f)  // ±4%, under the 10% threshold
            val input = mapOf(0 to Rgb(v, v, v), 1 to Rgb(v * 0.5f, 0f, v))
            val out = safety.process(input, dt)
            for ((cid, c) in input) {
                val o = out.getValue(cid)
                assertTrue(
                    abs(o.first - c.first) < 1e-6f &&
                        abs(o.second - c.second) < 1e-6f &&
                        abs(o.third - c.third) < 1e-6f,
                    "calm content was altered at frame $i: $c -> $o",
                )
            }
        }
    }

    @Test
    fun `it recovers from a dark-seeded anchor`() {
        // Reproduces syncoV2's "switch to a club rung, room goes dark and stays
        // dark" bug. After a reset the next frame seeds the anchor; if that frame
        // is dark and the anchor were frozen there, the room would be pinned
        // black indefinitely. The anchor must climb toward the real field.
        val safety = FieldSafety()
        safety.reset()
        val fields = ArrayList<Float>()
        for (i in 0 until 120) {  // 3 s at 40 fps
            val v = if (i == 0) 0f else if (i % 2 == 0) 1f else 0.05f
            fields.add(field(safety.process(mapOf(0 to Rgb(v, v, v)), dt)))
        }
        assertTrue(fields.takeLast(40).max() > 0.4f, "the room stayed pinned dark")
        assertTrue(
            countFlashes(fields) <= MAX_FLASHES_PER_S * 3 + 1,
            "recovery came at the cost of the flash ceiling",
        )
    }

    @Test
    fun `the red guard desaturates rapid red flashing`() {
        // Saturated red carries a stricter threshold, so a red strobe should come
        // out with white mixed in on its lit frames.
        val safety = FieldSafety()
        val lit = ArrayList<Rgb>()
        for (i in 0 until 200) {
            val v = if (i % 2 == 0) 1f else 0f
            val out = safety.process(mapOf(0 to Rgb(v, 0f, 0f)), dt).getValue(0)
            if (i % 2 == 0) lit.add(out)
        }
        assertTrue(
            lit.any { min(it.second, it.third) > 0.01f },
            "a saturated red strobe was never desaturated",
        )
    }

    @Test
    fun `the red guard leaves non-red palettes alone`() {
        // A blue strobe is brightness-limited but never desaturated.
        val safety = FieldSafety()
        val lit = ArrayList<Rgb>()
        for (i in 0 until 200) {
            val v = if (i % 2 == 0) 1f else 0f
            val out = safety.process(mapOf(0 to Rgb(0f, 0f, v)), dt).getValue(0)
            if (i % 2 == 0) lit.add(out)
        }
        assertTrue(
            lit.all { it.first < 0.05f && it.second < 0.05f && it.third > 0.05f },
            "a blue strobe was desaturated by the red guard",
        )
    }

    @Test
    fun `the relaxed limiter is transparent at musical beat rates`() {
        // Intense uses this. Eight flashes a second is a beat at 480 BPM, so a
        // 2 Hz beat flash must pass straight through or the club rungs lose their
        // character.
        val safety = FieldSafety(RELAXED_MAX_FLASHES_PER_S, calmGated = false)
        var altered = 0
        for (i in 0 until 400) {
            val v = if (i % 20 < 3) 1f else 0.3f  // ~2 Hz beat flash at 40 fps
            val input = mapOf(0 to Rgb(v, v, v))
            val out = safety.process(input, dt).getValue(0)
            if (abs(out.first - v) > 1e-6f) altered++
        }
        assertTrue(altered == 0, "the relaxed limiter engaged on a musical beat rate ($altered frames)")
    }

    @Test
    fun `the relaxed limiter still caps a pathological strobe`() {
        val safety = FieldSafety(RELAXED_MAX_FLASHES_PER_S, calmGated = false)
        var maxWindow = 0
        for (i in 0 until 800) {
            val v = if (i % 2 == 0) 1f else 0f
            safety.process(mapOf(0 to Rgb(v, v, v)), dt)
            maxWindow = max(maxWindow, safety.flashesInWindow)
        }
        assertTrue(
            maxWindow <= RELAXED_MAX_FLASHES_PER_S,
            "even relaxed, a full strobe reached $maxWindow flashes/s",
        )
    }

    @Test
    fun `output always stays inside unit range`() {
        val safety = FieldSafety()
        val rnd = java.util.Random(11)
        for (i in 0 until 500) {
            val input = (0 until 6).associateWith {
                Rgb(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat())
            }
            for ((_, c) in safety.process(input, dt)) {
                assertTrue(c.first in 0f..1f && c.second in 0f..1f && c.third in 0f..1f, "out of range: $c")
            }
        }
    }

    @Test
    fun `an empty frame is passed through`() {
        val safety = FieldSafety()
        assertTrue(safety.process(emptyMap(), dt).isEmpty())
    }
}

/**
 * The 12.5 Hz ceiling is a hardware fact, not a comfort setting, so it binds
 * everywhere — including the paths that bypass [FieldSafety].
 */
class EffectRateLimiterTest {

    private val dt = 1f / 60f

    @Test
    fun `no channel changes faster than the physical ceiling`() {
        // Philips: the bridge relays at 25 Hz over Zigbee, so the fastest effect
        // rate should stay under 12.5 Hz. Beyond that the change is coalesced
        // away before it reaches a bulb.
        val limiter = EffectRateLimiter()
        var transitions = 0
        var prev: Rgb? = null
        val frames = 600  // 10 s at 60 fps
        for (i in 0 until frames) {
            // A 30 Hz strobe: twice the ceiling.
            val v = if (i % 2 == 0) 1f else 0f
            val out = limiter.process(mapOf(0 to Rgb(v, v, v)), dt).getValue(0)
            if (prev != null && abs(out.first - prev.first) >= FLASH_DELTA) transitions++
            prev = out
        }
        val hz = transitions / (frames * dt)
        assertTrue(
            hz <= PHYSICAL_MAX_EFFECT_HZ + 0.5f,
            "channel changed at $hz Hz, above the ${PHYSICAL_MAX_EFFECT_HZ} Hz ceiling",
        )
    }

    @Test
    fun `smooth fades are not quantised`() {
        // Only transitions are limited. A slow ramp is a gradation, not a flash,
        // and holding it would visibly step what should be a smooth fade.
        val limiter = EffectRateLimiter()
        var lastOut = 0f
        for (i in 0 until 600) {
            val v = i / 600f
            val out = limiter.process(mapOf(0 to Rgb(v, v, v)), dt).getValue(0)
            assertTrue(out.first >= lastOut - 1e-6f, "a monotonic fade went backwards")
            lastOut = out.first
        }
        assertTrue(lastOut > 0.9f, "the fade never arrived, ending at $lastOut")
    }

    @Test
    fun `a held frame keeps the previous value rather than going dark`() {
        // The Entertainment API asks for a continuous stream; suppressing a
        // change must never mean emitting nothing or emitting black.
        val limiter = EffectRateLimiter()
        limiter.process(mapOf(0 to Rgb(1f, 1f, 1f)), dt)
        val out = limiter.process(mapOf(0 to Rgb(0f, 0f, 0f)), dt).getValue(0)
        assertTrue(out.first > 0.9f, "a rate-limited change emitted the new value instead of holding")
    }
}
