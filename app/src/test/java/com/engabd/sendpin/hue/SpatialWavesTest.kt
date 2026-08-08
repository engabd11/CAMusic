package com.engabd.sendpin.hue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Room geometry. Ported from syncoV2 `tests/test_spatial.py`.
 *
 * This is the part the direct path was missing entirely: y and z were parsed off
 * the entertainment configuration and then never used, so the channel model was
 * a left-to-right line and every 3-D parameter was inert.
 */
class SpatialWavesTest {

    private fun ch(id: Int, x: Float, y: Float, z: Float) =
        EntertainmentChannel(channelId = id, position = ChannelPosition(x, y, z))

    private val room = listOf(
        ch(0, -1f, -1f, 0f),
        ch(1, 1f, -1f, 0f),
        ch(2, -1f, 1f, 1f),
        ch(3, 1f, 1f, 1f),
    )

    @Test
    fun `positions normalise to the area's real extent`() {
        val p = normalizePositions(room)
        assertEquals(4, p.size)
        for (v in p.values) {
            assertTrue(v.x in 0f..1f && v.y in 0f..1f && v.z in 0f..1f, "outside the unit cube: $v")
        }
        assertEquals(Vec3(0f, 0f, 0f), p.getValue(0))
        assertEquals(Vec3(1f, 1f, 1f), p.getValue(3))
    }

    @Test
    fun `a degenerate axis collapses to the middle rather than dividing by zero`() {
        // Every lamp at the same height is the common case — a single shelf, or
        // the bridge reporting z as 0 for all of them.
        val flat = listOf(ch(0, -1f, 0f, 0f), ch(1, 1f, 0f, 0f))
        val p = normalizePositions(flat)
        assertTrue(p.values.all { it.z == 0.5f }, "a flat z axis should collapse to 0.5")
        assertTrue(p.values.all { it.y == 0.5f }, "a flat y axis should collapse to 0.5")
        assertEquals(0f, p.getValue(0).x)
        assertEquals(1f, p.getValue(1).x)
    }

    @Test
    fun `an empty area yields no positions and a safe origin`() {
        assertTrue(normalizePositions(emptyList()).isEmpty())
        assertEquals(Vec3(0.5f, 0.5f, 0f), floorOrigin(emptyMap()))
    }

    @Test
    fun `the room origin sits centrally at floor height`() {
        val o = floorOrigin(normalizePositions(room))
        assertEquals(0.5f, o.x, 1e-6f)
        assertEquals(0.5f, o.y, 1e-6f)
        assertEquals(0f, o.z, 1e-6f)
    }

    @Test
    fun `a screen area emanates from the front`() {
        val o = floorOrigin(normalizePositions(room), configurationType = "screen")
        assertTrue(o.y >= 0.8f, "screen areas should push the origin toward the screen, got ${o.y}")
    }

    @Test
    fun `a wavefront reaches near lamps before far ones`() {
        // The property the whole effect rests on: a kick has to sweep, not blink.
        val wave = Wave(origin = Vec3(0f, 0.5f, 0f), strength = 1f, speed = 1f, width = 0.2f)
        val near = 0.1f
        val far = 0.9f
        var nearPeak = 0f
        var nearPeakT = -1f
        var farPeak = 0f
        var farPeakT = -1f
        var t = 0f
        while (t < 2f) {
            val a = wave.amplitudeAt(near)
            val b = wave.amplitudeAt(far)
            if (a > nearPeak) { nearPeak = a; nearPeakT = t }
            if (b > farPeak) { farPeak = b; farPeakT = t }
            wave.advance(0.02f, decayTau = 0.45f)
            t += 0.02f
        }
        assertTrue(nearPeakT in 0f..farPeakT, "near lamp peaked at $nearPeakT, far at $farPeakT")
        assertTrue(nearPeak > farPeak, "the wave should weaken as it travels")
    }

    @Test
    fun `a wave eventually dies`() {
        // Waves are held in a bounded list; one that never dies would pin a slot
        // and slowly starve the effect.
        val wave = Wave(Vec3(0.5f, 0.5f, 0f), strength = 1f, speed = 2.2f, width = 0.3f)
        var t = 0f
        while (t < 10f && !wave.dead()) {
            wave.advance(0.02f, decayTau = 0.45f)
            t += 0.02f
        }
        assertTrue(wave.dead(), "a wave was still alive after 10 s")
        assertTrue(t < 5f, "a wave took ${t}s to die")
    }

    @Test
    fun `phrase origins cycle and return to centre`() {
        val p = normalizePositions(room)
        val origins = phraseOrigins(p)
        assertEquals(4, origins.size)
        assertEquals(origins[0], origins[3], "the cycle should return to the centre")
        assertTrue(origins[1].x < origins[2].x, "the cycle should sweep left then right")
    }

    @Test
    fun `melbank windows overlap and stay in range`() {
        val bins = 16
        var prevLo = -1
        for (i in 0..10) {
            val pos = i / 10f
            val (lo, hi) = melbankWindow(pos, bins)
            assertTrue(lo in 0 until bins, "lo $lo out of range")
            assertTrue(hi in (lo + 1)..bins, "hi $hi invalid for lo $lo")
            assertTrue(lo >= prevLo, "windows should advance monotonically")
            prevLo = lo
        }
        // Neighbours must overlap, or the room shows hard-edged frequency bands.
        val (loA, hiA) = melbankWindow(0.4f, bins)
        val (loB, _) = melbankWindow(0.5f, bins)
        assertTrue(hiA > loB, "adjacent lamps should share bins: [$loA,$hiA) vs [$loB,…)")
    }

    @Test
    fun `melbank window handles no bins`() {
        assertEquals(0 to 0, melbankWindow(0.5f, 0))
    }

    @Test
    fun `height maps floor to bass and ceiling to treble`() {
        assertEquals("sub_bass", heightBand(0f))
        assertEquals("high", heightBand(1f))
        assertEquals("high", heightBand(0.95f))
        assertTrue(heightBand(0.5f) == "low_mid" || heightBand(0.5f) == "mid")
    }
}
