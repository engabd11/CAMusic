package com.engabd.sendpin.wake

import com.engabd.sendpin.hue.HueLightsApi
import com.engabd.sendpin.hue.kelvinToMirek

/**
 * The sunrise as bridge commands, sent at two moments so that a phone in Doze only has to
 * be awake for a second at each:
 *
 *  - [WakeStage.LIGHTS_START], at alarm minus ramp: on, deep red-orange, 1 percent,
 *    instantly; then leg one, 70 percent of the ramp, to 2700 K at 60 percent.
 *  - [WakeStage.LIGHTS_LEG2], at the end of leg one: the last 30 percent, to 4000 K at
 *    100 percent.
 *
 * The bridge does the interpolating. Joining part way (an alarm set 10 minutes before a
 * 30 minute ramp, a reboot mid-ramp) works out where the curve would be by now and sends
 * a jump to that point first, so a late start still reaches full brightness on time.
 */
object RampPlan {
    const val LEG_ONE_SHARE = 0.7
    const val START_X = 0.6
    const val START_Y = 0.36
    const val START_BRIGHTNESS = 1.0
    val MID_MIREK = kelvinToMirek(2700)
    const val MID_BRIGHTNESS = 60.0
    val END_MIREK = kelvinToMirek(4000)
    const val END_BRIGHTNESS = 100.0

    /** Where a deep red-orange sits on the white scale, for joining mid way: the warmest the lamps go. */
    const val START_MIREK = 500

    /** Within this of the nominal moment, a stage is on time and needs no jump first. */
    const val ON_TIME_MS = 10_000L

    fun legOneMs(rampMs: Long): Long = (rampMs * LEG_ONE_SHARE).toLong()

    /** The PUT bodies to send, in order, when [stage] runs [elapsedMs] after the ramp's start. */
    fun bodies(stage: WakeStage, rampMs: Long, elapsedMs: Long): List<String> {
        if (rampMs <= 0) return emptyList()
        val e = elapsedMs.coerceAtLeast(0)
        if (e >= rampMs) return listOf(HueLightsApi.bodyWhite(END_MIREK, END_BRIGHTNESS, 0))
        val leg1 = legOneMs(rampMs)
        val onePast = e >= leg1
        if (stage == WakeStage.LIGHTS_START && !onePast) {
            val out = mutableListOf<String>()
            if (e <= ON_TIME_MS) {
                out += HueLightsApi.bodyColour(START_X, START_Y, START_BRIGHTNESS, 0)
                out += HueLightsApi.bodyWhite(MID_MIREK, MID_BRIGHTNESS, leg1)
            } else {
                val f = e.toDouble() / leg1
                out += HueLightsApi.bodyWhite(
                    (START_MIREK + (MID_MIREK - START_MIREK) * f).toInt(),
                    round1(START_BRIGHTNESS + (MID_BRIGHTNESS - START_BRIGHTNESS) * f),
                    0,
                )
                out += HueLightsApi.bodyWhite(MID_MIREK, MID_BRIGHTNESS, leg1 - e)
            }
            return out
        }
        // Leg two: either the START stage arrived already past leg one, or LEG2 itself.
        val late = e - leg1
        val out = mutableListOf<String>()
        if (late > ON_TIME_MS) {
            val f = late.toDouble() / (rampMs - leg1)
            out += HueLightsApi.bodyWhite(
                (MID_MIREK + (END_MIREK - MID_MIREK) * f).toInt(),
                round1(MID_BRIGHTNESS + (END_BRIGHTNESS - MID_BRIGHTNESS) * f),
                0,
            )
        }
        out += HueLightsApi.bodyWhite(END_MIREK, END_BRIGHTNESS, rampMs - maxOf(e, leg1))
        return out
    }

    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0
}

/** Wind-down as bridge commands: amber now, fading to 1 percent over the timer, off at the end. */
object WindDownPlan {
    val AMBER_MIREK = kelvinToMirek(2200)
    const val FALLBACK_BRIGHTNESS = 40.0
    const val END_OFF_MS = 2_000L
    const val AMBER_MS = 1_000L

    /** Amber at the current brightness (or 40 percent when it is not known). */
    fun amberBody(currentBrightness: Double?): String =
        HueLightsApi.bodyWhite(
            AMBER_MIREK,
            (currentBrightness ?: FALLBACK_BRIGHTNESS).coerceIn(1.0, 100.0),
            AMBER_MS,
        )

    /** The long leg: down to 1 percent across the whole of what is left on the timer. */
    fun dimBody(remainingMs: Long): String = HueLightsApi.bodyDim(1.0, remainingMs.coerceAtLeast(0))

    fun offBody(): String = HueLightsApi.bodyOff(END_OFF_MS)

    /** Stops a transition where it is by re-sending the brightness the room has reached. */
    fun holdBody(currentBrightness: Double): String =
        HueLightsApi.bodyDim(currentBrightness.coerceIn(1.0, 100.0), 0)
}
