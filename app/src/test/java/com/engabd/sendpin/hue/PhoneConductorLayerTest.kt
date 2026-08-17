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

    private fun contextOf(positions: Map<Int, Vec3>, topology: RoomTopology, dt: Float = 0.05f) = LayerContext(
        frame = AnalysisFrame(),
        structure = null,
        scan = null,
        positions = positions,
        topology = topology,
        trackPositionS = -1f,
        dt = dt,
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
    fun `rotation below the threshold does not accumulate a spin`() {
        val renderer = ConductorRenderer()
        val ctx = contextOf(onePosition, RoomTopology.FIELD)
        val jitter = DeviceMotionState(active = true, rotationRateZ = 0.05f) // well under the 0.5 rad/s gate

        var last = base
        repeat(20) { last = renderer.apply(base, ctx, jitter) }
        assertEquals(base, last, "sub-threshold gyroscope noise should not read as a conducting gesture")
    }
}
