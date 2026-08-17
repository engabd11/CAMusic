package com.engabd.sendpin.hue

import android.content.Context
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Layer 4 — Phone Conductor: the phone's motion becomes a lighting
 * controller. See `docs/creative-light-shows.md`.
 *
 * A pure modulator — it never invents a base colour, only shifts and
 * augments whatever the engine and the other layers already produced.
 * [start]/[stop] gate the underlying [DeviceMotionSource]; the caller
 * (`DirectLightSync`) is expected to call them whenever this layer's
 * setting or the streaming state changes, so sensors are only ever live
 * while both are true.
 *
 * The actual motion-to-colour transform lives in [ConductorRenderer],
 * entirely separate from this class's `Context`/`SensorManager` dependency —
 * that split is what makes the transform unit-testable with a synthetic
 * [DeviceMotionState] and no real sensor or Android context at all.
 */
class PhoneConductorLayer(context: Context) : LightShowLayer {
    override val id = "phone_conductor"

    private val motion = DeviceMotionSource(context)
    private val renderer = ConductorRenderer()

    fun start() = motion.start()
    fun stop() = motion.stop()

    override fun reset() = renderer.reset()

    override fun apply(base: Map<Int, Rgb>, context: LayerContext): Map<Int, Rgb> =
        renderer.apply(base, context, motion.state)
}

/**
 * The pure per-frame transform behind [PhoneConductorLayer], as its own
 * class purely so it can be constructed and driven from a test with no
 * `Context`/`SensorManager` in sight — see [PhoneConductorLayerTest].
 */
internal class ConductorRenderer {
    private var cachedPositions: Map<Int, Vec3>? = null
    private var ring: Ring? = null

    /** Accumulated hue-rotation phase from sustained gyroscope rotation. */
    private var rotationPhase = 0f

    /**
     * How much of the rotation wave to show, 0..1 — ramps up while the phone
     * is actually spinning and fades back down once it stops, rather than
     * the wave being a permanent position-dependent texture the moment the
     * layer is merely active. Without this, a phone lying still (active, but
     * never once rotated) would still show a static hue shift from
     * [rotationPhase]'s resting value of zero, which is a wave sitting still,
     * not a wave that isn't there.
     */
    private var spinEnvelope = 0f

    /** Decaying flash level from the most recent flick. */
    private var flashLevel = 0f

    /**
     * Drop the accumulated gesture state. The fitted [ring] is not part of it —
     * that describes the room, which a track change does not alter; it is
     * re-fitted when the positions themselves change identity.
     */
    fun reset() {
        rotationPhase = 0f
        spinEnvelope = 0f
        flashLevel = 0f
    }

    fun apply(base: Map<Int, Rgb>, context: LayerContext, state: DeviceMotionState): Map<Int, Rgb> {
        if (context.positions !== cachedPositions) {
            cachedPositions = context.positions
            ring = if (context.topology == RoomTopology.RING) {
                fitRing(context.positions.values.toList())
            } else {
                null
            }
        }

        flashLevel *= exp(-context.dt / FLASH_DECAY_S)
        if (flashLevel < 0.01f) flashLevel = 0f

        // Full revert: no lingering colour or brightness offset once the
        // phone has sat still long enough to auto-disable. A flash already
        // decaying stops being shown too, rather than finishing on its own —
        // "put the phone down" should read as "back to normal", not "normal,
        // except for one more pulse".
        if (!state.active) return base

        if (state.flick) flashLevel = 1f

        val spinning = abs(state.rotationRateZ) > ROTATION_THRESHOLD_RAD_S
        spinEnvelope = if (spinning) {
            (spinEnvelope + context.dt / SPIN_ENVELOPE_RISE_S).coerceAtMost(1f)
        } else {
            spinEnvelope * exp(-context.dt / SPIN_ENVELOPE_FALL_S)
        }
        if (spinEnvelope < 0.01f) spinEnvelope = 0f
        if (spinning) {
            rotationPhase = wrap1(rotationPhase + state.rotationRateZ * context.dt * ROTATION_HUE_SPEED)
        }

        val brightnessMul = (1f + state.tiltY.coerceIn(-1f, 1f) * TILT_BRIGHTNESS_RANGE)
            .coerceIn(1f - TILT_BRIGHTNESS_RANGE, 1f + TILT_BRIGHTNESS_RANGE)

        // Tilt-up and a flick both push *above* what the engine rendered, and
        // the engine had already applied the user's ceiling — so clamp to that,
        // not to 1f. The gesture still reads: most lamps sit well below the
        // ceiling on any given frame, so there is room to brighten into.
        val ceiling = context.brightness.coerceIn(0f, 1f)

        return base.mapValues { (id, rgb) ->
            val pos = context.positions[id]
            val (h0, s, v0) = rgbToHsv(rgb)
            var h = h0

            if (pos != null) {
                val axis = colourAxisProjection(pos)
                // Tilt left/right: colour shifts through the spatial field —
                // the same 3-axis colour projection the engine already uses
                // for room-wide hue drift, fed from the phone instead.
                h = wrap1(h + state.tiltX * TILT_HUE_GAIN * (axis - 0.5f))

                if (spinEnvelope > 0f) {
                    // Sustained rotation: a hue-shift wave travels around
                    // the room. On a ring, "around" means azimuth;
                    // anywhere else it means along the same colour axis
                    // tilt already uses, so "spin" still means something on
                    // a line or a scattered room.
                    val coord = ring?.let { azimuthOf(pos, it) } ?: axis
                    val wave = sin((coord - rotationPhase) * 2f * PI.toFloat())
                    h = wrap1(h + wave * ROTATION_HUE_AMPLITUDE * spinEnvelope)
                }
            }

            val v = (v0 * brightnessMul + flashLevel * FLASH_BRIGHTNESS_GAIN * ceiling)
                .coerceIn(0f, ceiling)
            hsvToRgb(h, s, v)
        }
    }

    private companion object {
        private const val TILT_HUE_GAIN = 0.25f

        /** "up to 1.5x .. down to 0.5x", per spec. */
        private const val TILT_BRIGHTNESS_RANGE = 0.5f

        private const val ROTATION_THRESHOLD_RAD_S = 0.5f
        private const val ROTATION_HUE_SPEED = 0.15f
        private const val ROTATION_HUE_AMPLITUDE = 0.08f
        private const val SPIN_ENVELOPE_RISE_S = 0.3f
        private const val SPIN_ENVELOPE_FALL_S = 1.0f

        private const val FLASH_DECAY_S = 0.35f
        private const val FLASH_BRIGHTNESS_GAIN = 0.6f
    }
}
