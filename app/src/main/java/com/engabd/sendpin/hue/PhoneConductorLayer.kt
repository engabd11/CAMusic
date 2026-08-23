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

    override fun apply(
        base: Map<Int, Rgb>,
        context: LayerContext,
        out: MutableMap<Int, Rgb>,
    ): Map<Int, Rgb> = renderer.apply(base, context, motion.snapshot(), out)
}

/**
 * The pure per-frame transform behind [PhoneConductorLayer], as its own
 * class purely so it can be constructed and driven from a test with no
 * `Context`/`SensorManager` in sight — see [PhoneConductorLayerTest].
 */
internal class ConductorRenderer {
    private var cachedPositions: Map<Int, Vec3>? = null
    private var ring: Ring? = null

    /**
     * Per-lamp colour-axis projection and ring azimuth, cached alongside the
     * positions they were derived from.
     *
     * Both are pure functions of a lamp position, and positions change identity
     * once per stream — so computing them inside the per-lamp loop was repeating
     * the same trigonometry sixty times a second for an answer that had not
     * moved since the area was opened.
     */
    private var axisOf: Map<Int, Float> = emptyMap()
    private var azimuthOf: Map<Int, Float> = emptyMap()

    /** Smoothed yaw rate, and whether the phone currently counts as spinning. */
    private var smoothedRate = 0f
    private var spinning = false

    private val hsv = FloatArray(3)

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
        smoothedRate = 0f
        spinning = false
    }

    fun apply(
        base: Map<Int, Rgb>,
        context: LayerContext,
        state: DeviceMotionState,
        out: MutableMap<Int, Rgb> = HashMap(),
    ): Map<Int, Rgb> {
        if (context.positions !== cachedPositions) {
            cachedPositions = context.positions
            val fitted = if (context.topology == RoomTopology.RING) {
                fitRing(context.positions.values.toList())
            } else {
                null
            }
            ring = fitted
            axisOf = context.positions.mapValues { (_, pos) -> colourAxisProjection(pos) }
            azimuthOf = fitted?.let { r ->
                context.positions.mapValues { (_, pos) -> azimuthOf(pos, r) }
            } ?: emptyMap()
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

        // Smoothed, and with separate start and stop thresholds. The raw value
        // is the single most recent gyroscope sample, so a hand-held phone
        // sitting near one threshold used to flip `spinning` frame to frame and
        // the envelope below stuttered between rising and falling instead of
        // doing either. A wrist is never that decisive.
        smoothedRate += (state.rotationRateZ - smoothedRate) * alpha(ROTATION_TAU_S, context.dt)
        val rate = abs(smoothedRate)
        spinning = if (spinning) rate > ROTATION_STOP_RAD_S else rate > ROTATION_START_RAD_S
        spinEnvelope = if (spinning) {
            (spinEnvelope + context.dt / SPIN_ENVELOPE_RISE_S).coerceAtMost(1f)
        } else {
            spinEnvelope * exp(-context.dt / SPIN_ENVELOPE_FALL_S)
        }
        if (spinEnvelope < 0.01f) spinEnvelope = 0f
        if (spinning) {
            rotationPhase = wrap1(rotationPhase + smoothedRate * context.dt * ROTATION_HUE_SPEED)
        }

        val brightnessMul = (1f + state.tiltY.coerceIn(-1f, 1f) * TILT_BRIGHTNESS_RANGE)
            .coerceIn(1f - TILT_BRIGHTNESS_RANGE, 1f + TILT_BRIGHTNESS_RANGE)

        // Tilt-up and a flick both push *above* what the engine rendered, and
        // the engine had already applied the user's ceiling — so clamp to that,
        // not to 1f. The gesture still reads: most lamps sit well below the
        // ceiling on any given frame, so there is room to brighten into.
        val ceiling = context.brightness.coerceIn(0f, 1f)

        for ((id, rgb) in base) {
            rgbToHsvInto(rgb, hsv)
            var h = hsv[0]
            val axis = axisOf[id]

            if (axis != null) {
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
                    val coord = azimuthOf[id] ?: axis
                    val wave = sin((coord - rotationPhase) * 2f * PI.toFloat())
                    h = wrap1(h + wave * ROTATION_HUE_AMPLITUDE * spinEnvelope)
                }
            }

            val v = (hsv[2] * brightnessMul + flashLevel * FLASH_BRIGHTNESS_GAIN * ceiling)
                .coerceIn(0f, ceiling)
            out[id] = hsvToRgb(h, hsv[1], v)
        }
        return out
    }

    /** EMA smoothing factor for a time constant, the idiom the other layers use. */
    private fun alpha(tauS: Float, dt: Float): Float = 1f - exp(-dt / tauS)

    private companion object {
        private const val TILT_HUE_GAIN = 0.25f

        /** "up to 1.5x .. down to 0.5x", per spec. */
        private const val TILT_BRIGHTNESS_RANGE = 0.5f

        /**
         * Yaw rate at which a spin starts and stops being read as one, rad/s,
         * and the smoothing applied before either is tested.
         *
         * Two thresholds rather than one because this is a gate on a noisy
         * sensor: a single threshold on the raw sample chatters for as long as
         * the hand holding the phone hovers near it.
         */
        private const val ROTATION_START_RAD_S = 0.5f
        private const val ROTATION_STOP_RAD_S = 0.3f
        private const val ROTATION_TAU_S = 0.12f
        private const val ROTATION_HUE_SPEED = 0.15f
        private const val ROTATION_HUE_AMPLITUDE = 0.08f
        private const val SPIN_ENVELOPE_RISE_S = 0.3f
        private const val SPIN_ENVELOPE_FALL_S = 1.0f

        private const val FLASH_DECAY_S = 0.35f
        private const val FLASH_BRIGHTNESS_GAIN = 0.6f
    }
}
