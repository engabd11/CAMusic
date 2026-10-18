package com.engabd.sendpin.hue.ambience.scripts

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceEvent
import com.engabd.sendpin.hue.ambience.AmbienceParams
import com.engabd.sendpin.hue.ambience.AmbienceScript
import com.engabd.sendpin.hue.ambience.ComfortProfile
import com.engabd.sendpin.hue.ambience.OnePole
import com.engabd.sendpin.hue.ambience.PinkNoise
import com.engabd.sendpin.hue.ambience.Rng
import com.engabd.sendpin.hue.ambience.RoomModel
import com.engabd.sendpin.hue.ambience.SmoothNoise
import com.engabd.sendpin.hue.ambience.Svf
import com.engabd.sendpin.hue.ambience.mix
import com.engabd.sendpin.hue.ambience.smoothstep
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Slow curtains of colour drifting through a mostly dark sky. Nothing sudden.
 *
 * The quiet one, and deliberately the *only* effect with no events at all: [schedule]
 * emits nothing, and the whole show is a continuous function of position and time. An
 * aurora that had discrete events would have moments, and the point of this one is that
 * it does not — it is the effect to leave on while reading.
 *
 * ## What the room does
 *
 * **Curtains travel along the room's main axis.** Three curtains, each a soft band of
 * light, cross the room end to end in 45, 60 and 90 seconds, so at any moment there is
 * usually one passing a lamp and often none. Each folds as it goes: its width and its
 * brightness wander slowly on smooth noise, so it swells and thins like a real one and
 * never repeats.
 *
 * **Mostly dark.** Between curtains a lamp sits at a deep, dim blue-violet, a sky with a
 * little glow in it. The curtains are the only bright thing, and even they peak at about
 * a third of a lamp's range at the default intensity.
 *
 * **Green, then violet, and rarely pink.** Colour follows height, as it does in the sky:
 * green low, violet high. Now and then, when a slow noise crosses a high threshold, the
 * leading edge of a curtain flushes pink, which is the rare, lovely thing. Intensity
 * lifts the brightness as well as the pace of the folding.
 *
 * The audio is a pad with no transients whatsoever — filtered noise, two slow sweeps, no
 * grains. Anything with an attack would be a moment, and there are none here.
 */
class AuroraScript : AmbienceScript {

    override val effect = AmbienceEffect.AURORA

    /**
     * Barely movable: a tenth of a unit a second takes a lamp from dark to the top of
     * its range in about four seconds, which is quicker than any curtain asks for. The
     * limiter is a backstop here, not a shaper.
     */
    override val comfort = ComfortProfile(risePerS = 0.15f, fallPerS = 0.15f, roomSwingPerS = 0.08f)

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    // No scheduler to share with: the light tick owns its generator and only draws seeds
    // at bind.
    private val renderRng = Rng(0xA0_2A_01L)
    private var seedA = 0
    private var seedB = 0
    private var seedC = 0
    private var seedPink = 0
    private var phase = FloatArray(CURTAINS)

    private val padNoise = PinkNoise(0xA1A1)
    private val padLp = OnePole()
    private val bodySvf = Svf()
    private val airSvf = Svf()
    private var voiced = false

    override fun bind(room: RoomModel, params: AmbienceParams) {
        this.room = room
        this.params = params
        seedA = (renderRng.nextLong() ushr 33).toInt()
        seedB = (renderRng.nextLong() ushr 33).toInt()
        seedC = (renderRng.nextLong() ushr 33).toInt()
        seedPink = (renderRng.nextLong() ushr 33).toInt()
        // Spread the curtains out so the room never starts with all three on top of each
        // other.
        phase = FloatArray(CURTAINS) { (it + renderRng.f01() * 0.6f) / CURTAINS }
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    /** Nothing happens in an aurora. That is the effect. */
    override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) = Unit

    override fun renderLights(
        tS: Double,
        live: Array<AmbienceEvent?>,
        n: Int,
        out: MutableMap<Int, Rgb>,
    ) {
        val intensity = params.intensity.coerceIn(0f, 1f)
        // Intensity lifts the curtains' brightness as well as how lively they are.
        val peak = PEAK_MIN + (PEAK_MAX - PEAK_MIN) * intensity
        val lively = 0.8f + 0.4f * intensity

        // Where each curtain is on the axis, how wide, and how bright, right now. Done
        // once per frame; every lamp then just asks how far it is from each.
        for (k in 0 until CURTAINS) {
            val cycle = tS / PERIOD_S[k] + phase[k]
            // -0.35..1.35: well off both ends of the room, so a curtain arrives and
            // leaves with no lamp ever seeing it appear.
            centre[k] = -0.35f + 1.7f * (cycle - floor(cycle)).toFloat()
            val seed = when (k) { 0 -> seedA; 1 -> seedB; else -> seedC }
            width[k] = (0.20f + 0.07f * k) * (1f + 0.30f * lively * SmoothNoise.fractal(seed, tS, 0.018f, 2))
            // Never quite off: a curtain thins to a third of its height, not to nothing,
            // so the sky keeps some structure.
            level[k] = (0.65f + 0.35f * SmoothNoise.fractal(seed + 31, tS, 0.014f, 2)).coerceIn(0.33f, 1f)
        }
        // The pink flush: rare, slow, and only on the leading edge. Positive for a
        // minute or so perhaps a few times an hour.
        val pink = smoothstep(PINK_FROM, PINK_TO, 0.5f + 0.5f * SmoothNoise.fractal(seedPink, tS, 0.006f, 2))

        for (id in room.ids) {
            val axis = room.axisPos[id] ?: 0.5f
            val h = room.heightOf(id)

            var glow = 0f
            var lead = 0f          // how much of the glow is on a curtain's leading edge
            for (k in 0 until CURTAINS) {
                val d = (axis - centre[k]) / width[k]
                val g = level[k] * exp(-d * d)
                glow += g
                // The curtains move towards higher axis positions, so the leading edge is
                // the side with the larger coordinate.
                if (d > 0f) lead += g
            }
            glow = glow.coerceAtMost(1f)

            // Green low, violet high; the leading edge flushes pink on the rare occasions
            // the pink noise allows.
            val hue = (0.15f + 0.85f * h).coerceIn(0f, 1f)
            var colour = mix(GREEN, VIOLET, hue)
            if (pink > 0f && glow > 0.05f) colour = mix(colour, PINK, pink * (lead / (glow + 1e-4f)).coerceIn(0f, 1f))

            val lamp = SKY + (peak - SKY) * glow
            out[id] = Rgb(colour.first * lamp, colour.second * lamp, colour.third * lamp)
        }
    }

    /** Per-frame scratch for the curtains. Render thread only. */
    private val centre = FloatArray(CURTAINS)
    private val width = FloatArray(CURTAINS)
    private val level = FloatArray(CURTAINS)

    override fun renderAudio(
        out: FloatArray,
        frames: Int,
        startS: Double,
        sampleRate: Int,
        live: Array<AmbienceEvent?>,
        n: Int,
    ) {
        if (!voiced) {
            padLp.setCutoff(600f, sampleRate)
            voiced = true
        }
        val intensity = params.intensity.coerceIn(0f, 1f)
        val levelOut = 0.05f + 0.04f * intensity

        var i = 0
        while (i < frames) {
            val t = (startS + i.toDouble() / sampleRate).toFloat()
            // Two very slow sweeps, at incommensurate rates so the pad never lands back
            // where it started. Set per sample rather than per block because these move
            // so slowly that per-block stepping would be free but pointless.
            bodySvf.set(150f + 70f * sin(2f * PI.toFloat() * t * 0.017f), 2.2f, sampleRate)
            airSvf.set(1100f + 500f * sin(2f * PI.toFloat() * t * 0.011f), 1.6f, sampleRate)
            val src = padLp.lp(padNoise.next())
            val body = bodySvf.bp(src) * 1.8f
            val airy = airSvf.bp(src) * 0.5f
            // Slightly different mix per channel: width without anything moving.
            out[i * 2] += (body * 0.9f + airy * 1.1f) * levelOut
            out[i * 2 + 1] += (body * 1.1f + airy * 0.9f) * levelOut
            i++
        }
    }

    private companion object {
        const val CURTAINS = 3

        /** Seconds each curtain takes to cross the room end to end: 45, 60 and 90. */
        val PERIOD_S = floatArrayOf(45f, 60f, 90f)

        /**
         * Perceived brightness of a lamp in the empty sky, and of one in the middle of a
         * curtain at intensity 0 and 1. The sky is dim enough that the curtains are the
         * only thing you look at.
         */
        const val SKY = 0.04f
        const val PEAK_MIN = 0.20f
        const val PEAK_MAX = 0.50f

        /** The pink noise has to climb past this before any pink shows, and is full by that. */
        const val PINK_FROM = 0.74f
        const val PINK_TO = 0.88f

        val GREEN = Rgb(0.15f, 1.00f, 0.55f)
        val VIOLET = Rgb(0.55f, 0.25f, 1.00f)
        val PINK = Rgb(1.00f, 0.35f, 0.75f)
    }
}
