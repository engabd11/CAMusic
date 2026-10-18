package com.engabd.sendpin.hue.ambience.scripts

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceEvent
import com.engabd.sendpin.hue.ambience.AmbienceParams
import com.engabd.sendpin.hue.ambience.AmbienceScript
import com.engabd.sendpin.hue.ambience.Blackbody
import com.engabd.sendpin.hue.ambience.ComfortProfile
import com.engabd.sendpin.hue.ambience.Keyframes
import com.engabd.sendpin.hue.ambience.OnePole
import com.engabd.sendpin.hue.ambience.PinkNoise
import com.engabd.sendpin.hue.ambience.Rng
import com.engabd.sendpin.hue.ambience.RoomModel
import com.engabd.sendpin.hue.ambience.SmoothNoise
import com.engabd.sendpin.hue.ambience.mix
import com.engabd.sendpin.hue.ambience.smoothstep
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The last of the sun, drifting across the room, and the evening that follows it.
 * A wind-down scene: it only ever gets dimmer.
 *
 * ## What the room does
 *
 * **The whole room is on a clock that runs thirty to forty-five minutes.** The length is
 * the intensity setting's: 30 minutes at 0, 45 at 1. The clock is an integral of elapsed
 * time over the *current* length, so moving the setting during the show changes the pace
 * from there on and never jumps the colour.
 *
 * **Four colours, one path.** Warm amber, then a deeper rose, then a dusk violet, then
 * a dim indigo night ([SKY]), blended with smoothsteps so there is never a corner. Lamps
 * a little higher in the room (see `RoomModel.heightOf`) are a touch ahead of the ones
 * low down, because the sky above goes dark first.
 *
 * **A patch of low sun crosses the room.** It starts beyond one end of the main axis and
 * moves to beyond the other over the first four fifths of the show, a soft Gaussian
 * lifting the lamps it passes with a redder, lower light than the sky. By the time the
 * sky is violet it has set and the room is only sky.
 *
 * **It ends dim and stays dim.** After the clock reaches 1 nothing moves except a very
 * slow drift. The last level is deliberately low: this is the scene to fall asleep to.
 *
 * Sound: a faint pad of air, with crickets fading in as the sky goes violet. No events.
 */
class GoldenHourScript : AmbienceScript {

    override val effect = AmbienceEffect.GOLDEN_HOUR

    override val comfort = ComfortProfile(risePerS = 0.08f, fallPerS = 0.08f, roomSwingPerS = 0.04f)

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    private val renderRng = Rng(0x60_1D_E1L)
    private lateinit var lampSeed: IntArray
    private var lastT = Double.NaN

    /** Where the evening is, 0..1. Written by the light tick, read by the sound. */
    @Volatile private var clock = 0f

    private val pad = PinkNoise(0x60_AD)
    private val padLp = OnePole()
    private var crickA = 0f
    private var crickB = 0f
    private var voiced = false

    override fun bind(room: RoomModel, params: AmbienceParams) {
        this.room = room
        this.params = params
        lampSeed = IntArray(room.count) { (renderRng.nextLong() ushr 33).toInt() }
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    /** Nothing happens in a sunset; it just goes on. */
    override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) = Unit

    override fun renderLights(tS: Double, live: Array<AmbienceEvent?>, n: Int, out: MutableMap<Int, Rgb>) {
        val dt = if (lastT.isNaN()) 0f else (tS - lastT).coerceIn(0.0, 0.25).toFloat()
        lastT = tS
        val intensity = params.intensity.coerceIn(0f, 1f)
        val lengthS = (MIN_MINUTES + (MAX_MINUTES - MIN_MINUTES) * intensity) * 60f
        clock = (clock + dt / lengthS).coerceIn(0f, 1f)
        val p = clock

        val sunAt = -0.15f + 1.3f * smoothstep(0f, 0.8f, p)
        val sunGain = SUN_GAIN * (1f - smoothstep(0.60f, 0.90f, p))
        val sunColour = mix(Blackbody.rgb(2000f), SUNSET_RED, smoothstep(0.35f, 0.80f, p))

        for ((i, id) in room.ids.withIndex()) {
            val axis = room.axisPos[id] ?: 0.5f
            val h = room.heightOf(id)
            val pl = (p + 0.05f * (h - 0.5f)).coerceIn(0f, 1f)
            val sky = SKY.levelAt(pl)
            val dir = SKY.colourAt(pl)

            val d = (axis - sunAt) / SUN_WIDTH
            val glow = sunGain * exp(-d * d)
            val drift = 1f + 0.03f * SmoothNoise.fractal(lampSeed[i], tS, 1f / 80f, 2, 2.3f)
            val level = ((sky + glow) * drift).coerceIn(0f, 1f)
            val colour = mix(dir, sunColour, if (level > 1e-4f) glow / (sky + glow) else 0f)
            out[id] = Rgb(colour.first * level, colour.second * level, colour.third * level)
        }
    }

    override fun renderAudio(
        out: FloatArray,
        frames: Int,
        startS: Double,
        sampleRate: Int,
        live: Array<AmbienceEvent?>,
        n: Int,
    ) {
        if (!voiced) {
            padLp.setCutoff(520f, sampleRate)
            voiced = true
        }
        val intensity = params.intensity.coerceIn(0f, 1f)
        val night = smoothstep(0.35f, 0.75f, clock)
        val air = 0.020f + 0.010f * intensity
        val crick = 0.016f * night
        val tau = 2f * PI.toFloat()
        var i = 0
        while (i < frames) {
            val t = startS + i.toDouble() / sampleRate
            val s0 = padLp.lp(pad.next()) * air * (0.7f + 0.3f * SmoothNoise.fractal(77, t, 0.04f, 2))
            var s = s0
            if (crick > 0f) {
                // Two crickets: bursts of fast pulses on a bright carrier, at their own
                // rates so they never line up.
                crickA += tau * 4300f / sampleRate
                crickB += tau * 5100f / sampleRate
                if (crickA > tau) crickA -= tau
                if (crickB > tau) crickB -= tau
                val bursts = burst(t, 0.95) * pulse(t, 31.0) * sin(crickA)
                val bursts2 = burst(t + 0.37, 1.35) * pulse(t, 27.0) * sin(crickB)
                s += (bursts + 0.7f * bursts2) * crick
            }
            out[i * 2] += s * 0.95f
            out[i * 2 + 1] += s * 1.05f
            i++
        }
    }

    /** On for the first 28 percent of each [period], eased at both ends. */
    private fun burst(t: Double, period: Double): Float {
        val x = ((t % period) / period).toFloat()
        return smoothstep(0f, 0.04f, x) * (1f - smoothstep(0.24f, 0.28f, x))
    }

    private fun pulse(t: Double, hz: Double): Float {
        val s = sin(2.0 * PI * hz * t).toFloat()
        return if (s > 0f) s * s else 0f
    }

    private companion object {
        const val MIN_MINUTES = 30f
        const val MAX_MINUTES = 45f

        /** Perceived extra brightness under the low sun, at its strongest. */
        const val SUN_GAIN = 0.20f
        const val SUN_WIDTH = 0.30f

        val SUNSET_RED = Rgb(1.00f, 0.32f, 0.28f)

        val SKY = Keyframes(
            floatArrayOf(0f, 0.28f, 0.55f, 0.78f, 1f),
            arrayOf(
                Blackbody.rgb(2300f),           // amber
                Rgb(1.00f, 0.55f, 0.22f),       // deep gold
                Rgb(1.00f, 0.42f, 0.55f),       // rose
                Rgb(0.58f, 0.38f, 0.90f),       // dusk violet
                Rgb(0.24f, 0.24f, 0.62f),       // night indigo
            ),
            floatArrayOf(0.44f, 0.40f, 0.29f, 0.16f, 0.05f),
        )
    }
}
