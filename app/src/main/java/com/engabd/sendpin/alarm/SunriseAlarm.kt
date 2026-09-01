package com.engabd.sendpin.alarm

import com.engabd.sendpin.hue.Rgb
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * The colour-temperature curve a sunrise alarm follows.
 *
 * A real dawn does not go bright → white. It goes dark → deep red → warm orange → soft yellow
 * → warm white, and the light is already warming before it is bright enough to see by. The
 * colour leads the brightness, which is what makes a simulated sunrise feel like waking up
 * with light rather than being woken *by* a light snapping on.
 *
 * All logic here is pure: no Android types, no side effects. That is what makes the ramp
 * testable without an AlarmManager, a bridge session or a running service.
 */
object SunriseAlarm {

    /** Default ramp duration in minutes. */
    const val DEFAULT_RAMP_MINUTES = 10

    /**
     * The colour at ramp progress `t` (0..1), interpolated across four anchor points.
     *
     * The anchors are not evenly spaced — red holds longer than the later stages do,
     * because the eye is far more sensitive to blue/green than to red in low light, and
     * a red that turns orange too quickly reads as "the light came on" rather than "the
     * sky is getting lighter".
     *
     *  - 0.00: deep red   — the first thing a dark-adapted eye can register
     *  - 0.30: warm orange — the sun is near the horizon
     *  - 0.60: soft yellow — the sun is up, the room is warming
     *  - 1.00: warm white  — full daylight, but not cold blue
     */
    fun colourAt(t: Float): Rgb {
        val p = t.coerceIn(0f, 1f)
        return when {
            p <= 0.30f -> lerp(DEEP_RED, WARM_ORANGE, p / 0.30f)
            p <= 0.60f -> lerp(WARM_ORANGE, SOFT_YELLOW, (p - 0.30f) / 0.30f)
            else       -> lerp(SOFT_YELLOW, WARM_WHITE, (p - 0.60f) / 0.40f)
        }
    }

    /**
     * The brightness ceiling at ramp progress `t`, as a 0..1 fraction of the user's
     * configured maximum.
     *
     * A smoothstep curve rather than linear: a linear ramp has a visible corner at the
     * start (dark → slightly-less-dark is the biggest perceptual jump), and smoothstep
     * eases that corner the same way it does for ambience event envelopes.
     */
    fun brightnessAt(t: Float): Float {
        val p = t.coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }

    /**
     * The aurora audio volume at ramp progress `t`, as a 0..1 fraction of the gentle
     * target volume.
     *
     * Starts later than the light — sound waking someone before the room is lit is an
     * alarm, not a sunrise. The audio stays at zero for the first 20% and then eases in,
     * so by the time it is audible the light has already done the first part of its job.
     */
    fun audioVolumeAt(t: Float): Float {
        val p = t.coerceIn(0f, 1f)
        if (p < 0.20f) return 0f
        val audioT = (p - 0.20f) / 0.80f
        return GENTLE_VOLUME * (audioT * audioT * (3f - 2f * audioT))
    }

    /**
     * Whether the optional music wake-up should start at this point in the ramp.
     *
     * Music begins at 80% ramp completion — the room is nearly at full light and the
     * aurora pad is already audible, so a track starting here is a continuation of the
     * wake-up rather than the thing that does the waking.
     */
    fun shouldStartMusic(t: Float): Boolean = t >= 0.80f

    private fun lerp(a: Rgb, b: Rgb, t: Float): Rgb = Rgb(
        a.first + (b.first - a.first) * t,
        a.second + (b.second - a.second) * t,
        a.third + (b.third - a.third) * t,
    )

    /**
     * The gentle target volume for the aurora pad during a sunrise.
     *
     * Deliberately low: the aurora is ambience, not an alarm sound. Its job is to give
     * the sunrise a soft acoustic presence, not to wake anyone the light missed.
     */
    private const val GENTLE_VOLUME = 0.35f

    private val DEEP_RED    = Rgb(0.50f, 0.07f, 0.03f)
    private val WARM_ORANGE = Rgb(0.90f, 0.40f, 0.10f)
    private val SOFT_YELLOW = Rgb(1.00f, 0.85f, 0.55f)
    private val WARM_WHITE  = Rgb(1.00f, 0.92f, 0.82f)
}