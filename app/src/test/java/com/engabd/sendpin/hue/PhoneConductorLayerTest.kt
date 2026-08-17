package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ConductorRenderer] is [PhoneConductorLayer]'s motion-to-colour transform,
 * split out specifically so it can be driven here with a synthetic
 * [DeviceMotionState] and no real `SensorManager`/`Context` — this project's
 * test suite has no Robolectric, so anything needing an actual `Context`
 * (`PhoneConductorLayer` itself, `DeviceMotionSource`) can't be constructed
 * from a plain JVM unit test.
 */
class PhoneConductorLayerTest {

    private fun contextOf(
        positions: Map<Int, Vec3>,
        topology: RoomTopology,
        dt: Float = 0.05f,
        brightness: Float = 1f,
    ) = LayerContext(
        frame = AnalysisFrame(),
        structure = null,
        scan = null,
        positions = positions,
        topology = topology,
        trackPositionS = -1f,
        dt = dt,
        brightness = brightness,
    )

    private val onePosition = mapOf(1 to Vec3(0.8f, 0.5f, 0.5f))

    // Saturated (so a hue shift is visible — hsvToRgb(h, 0, v) == (v, v, v)
    // for every h on a grey input) but at half brightness, not full (so a
    // flick has headroom to brighten it rather than clamping at an already
    // maxed-out value channel).
    private val base = mapOf(1 to Triple(0.5f, 0f, 0f) as Rgb)

    @Test
    fun `an inactive state is a no-op, regardless of what the sensors say`() {
        val state = DeviceMotionState(active = false, tiltX = 1f, flick = true, rotationRateZ = 5f)
        val out = ConductorRenderer().apply(base, contextOf(onePosition, RoomTopology.FIELD), state)
        assertEquals(base, out)
    }

    @Test
    fun `with the phone active but perfectly still, the room is untouched`() {
        val renderer = ConductorRenderer()
        val out = renderer.apply(base, contextOf(onePosition, RoomTopology.FIELD), DeviceMotionState(active = true))
        assertEquals(base, out, "zero tilt, no flick, no rotation should leave the colour untouched")
    }

    @Test
    fun `a flick brightens every channel`() {
        val renderer = ConductorRenderer()
        val ctx = contextOf(onePosition, RoomTopology.FIELD)
        val calm = renderer.apply(base, ctx, DeviceMotionState(active = true))
        val flicked = renderer.apply(base, ctx, DeviceMotionState(active = true, flick = true))

        fun brightness(rgb: Rgb) = maxOf(rgb.first, rgb.second, rgb.third)
        assertTrue(
            brightness(flicked.getValue(1)) > brightness(calm.getValue(1)),
            "a flick should brighten the room: ${calm.getValue(1)} -> ${flicked.getValue(1)}",
        )
    }

    @Test
    fun `tilting left-right shifts colour, tilting up-down does not`() {
        val renderer = ConductorRenderer()
        val ctx = contextOf(onePosition, RoomTopology.FIELD)
        val still = renderer.apply(base, ctx, DeviceMotionState(active = true))
        val tilted = renderer.apply(base, ctx, DeviceMotionState(active = true, tiltX = 1f))
        assertTrue(still != tilted, "a left-right tilt should visibly shift the colour")
    }

    @Test
    fun `sustained rotation keeps changing the colour across several frames, not just the first`() {
        val renderer = ConductorRenderer()
        val ctx = contextOf(onePosition, RoomTopology.FIELD)
        val spinning = DeviceMotionState(active = true, rotationRateZ = 2f)

        val outputs = mutableListOf(renderer.apply(base, ctx, spinning).getValue(1))
        repeat(20) { outputs += renderer.apply(base, ctx, spinning).getValue(1) }

        assertTrue(
            outputs.toSet().size > 1,
            "sustained rotation should keep moving the colour across frames rather than settling on the first one",
        )
    }

    @Test
    fun `neither a flick nor a tilt-up may push a lamp above the brightness ceiling`() {
        val renderer = ConductorRenderer()
        // Tilt fully up (the 1.5x multiplier) and flick at the same time, which
        // is the brightest this layer can ever ask for.
        val brightest = DeviceMotionState(active = true, tiltY = 1f, flick = true)
        val out = renderer.apply(base, contextOf(onePosition, RoomTopology.FIELD, brightness = 0.5f), brightest)

        val v = out.getValue(1).let { maxOf(it.first, it.second, it.third) }
        assertTrue(v <= 0.5f + 1e-5f, "the loudest gesture must still respect a 50% ceiling, was $v")
    }

    @Test
    fun `a tilt-up still visibly brightens a lamp sitting below the ceiling`() {
        val renderer = ConductorRenderer()
        val ctx = contextOf(onePosition, RoomTopology.FIELD, brightness = 1f)
        fun brightness(rgb: Rgb) = maxOf(rgb.first, rgb.second, rgb.third)

        // base sits at 0.5, so a 1.5x tilt has room to climb without clamping —
        // capping at the ceiling must not have flattened the gesture itself.
        val still = brightness(renderer.apply(base, ctx, DeviceMotionState(active = true)).getValue(1))
        val up = brightness(renderer.apply(base, ctx, DeviceMotionState(active = true, tiltY = 1f)).getValue(1))
        assertTrue(up > still, "tilting up should still brighten: $still -> $up")
    }

    @Test
    fun `reset drops a decaying flash instead of carrying it into the next track`() {
        val renderer = ConductorRenderer()
        val ctx = contextOf(onePosition, RoomTopology.FIELD)
        val active = DeviceMotionState(active = true)

        renderer.apply(base, ctx, DeviceMotionState(active = true, flick = true))
        renderer.reset()

        assertEquals(base, renderer.apply(base, ctx, active), "a reset flash must not still be lighting the room")
    }

    @Test
    fun `rotation below the threshold does not accumulate a spin`() {
        val renderer = ConductorRenderer()
        val ctx = contextOf(onePosition, RoomTopology.FIELD)
        val jitter = DeviceMotionState(active = true, rotationRateZ = 0.05f) // well under the 0.5 rad/s gate

        var last = base
        repeat(20) { last = renderer.apply(base, ctx, jitter) }
        assertEquals(base, last, "sub-threshold gyroscope noise should not read as a conducting gesture")
    }
}
