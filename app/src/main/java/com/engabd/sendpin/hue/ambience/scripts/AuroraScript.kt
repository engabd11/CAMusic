package com.engabd.sendpin.hue.ambience.scripts

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceEvent
import com.engabd.sendpin.hue.ambience.AmbienceParams
import com.engabd.sendpin.hue.ambience.AmbienceScript
import com.engabd.sendpin.hue.ambience.OnePole
import com.engabd.sendpin.hue.ambience.PinkNoise
import com.engabd.sendpin.hue.ambience.RoomModel
import com.engabd.sendpin.hue.ambience.Svf
import kotlin.math.PI
import kotlin.math.sin

/**
 * Slow curtains of colour, folding over each other. Nothing sudden.
 *
 * The quiet one, and deliberately the *only* effect with no events at all: [schedule]
 * emits nothing, and the whole show is a continuous function of position and time. An
 * aurora that had discrete events would have moments, and the point of this one is that
 * it does not — it is the effect to leave on while reading, and the test that pins it
 * checks the total field brightness barely moves over a minute.
 *
 * Two counter-drifting phase fields give the folding. One would be a wave passing
 * through; two crossing at different speeds is a curtain that folds over itself and
 * never repeats, which is what an aurora actually looks like.
 *
 * The audio is a pad with no transients whatsoever — filtered noise, two slow sweeps, no
 * grains. Anything with an attack would be a moment, and there are none here.
 */
class AuroraScript : AmbienceScript {

    override val effect = AmbienceEffect.AURORA

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    private val padNoise = PinkNoise(0xA1A1)
    private val padLp = OnePole()
    private val bodySvf = Svf()
    private val airSvf = Svf()
    private var voiced = false

    override fun bind(room: RoomModel, params: AmbienceParams) {
        this.room = room
        this.params = params
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
        val t = tS.toFloat()
        // Intensity moves how fast the curtains fold, not how bright they are. A
        // brighter aurora would just be a lit room.
        val speed = 0.020f + 0.030f * intensity

        for (id in room.ids) {
            val p = room.positions[id] ?: room.centre()
            val h = room.heightOf(id)

            // Two fields, drifting in opposite directions at different rates.
            val a = sin(2f * PI.toFloat() * (p.x * 0.9f + h * 0.6f + t * speed))
            val b = sin(2f * PI.toFloat() * (p.y * 1.3f - h * 0.4f - t * speed * 0.61f))

            // Where the two agree is a fold, and folds are where the colour sits.
            val fold = (a * 0.5f + b * 0.5f) * 0.5f + 0.5f
            val hue = fold.coerceIn(0f, 1f)

            // Green low, violet high — the real thing is ordered by altitude, and the
            // synthetic height in a flat room keeps that ordering stable per lamp.
            val base = lerpRgb(GREEN, VIOLET, (hue * 0.7f + h * 0.3f).coerceIn(0f, 1f))
            // A slow, shallow breath so it is never quite still.
            val breath = 0.62f + 0.38f * (0.5f + 0.5f * sin(2f * PI.toFloat() * (t * 0.031f + p.x)))
            val level = (0.16f + 0.30f * intensity) * breath

            out[id] = Rgb(base.first * level, base.second * level, base.third * level)
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
            padLp.setCutoff(600f, sampleRate)
            voiced = true
        }
        val intensity = params.intensity.coerceIn(0f, 1f)
        val level = 0.05f + 0.04f * intensity

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
            out[i * 2] += (body * 0.9f + airy * 1.1f) * level
            out[i * 2 + 1] += (body * 1.1f + airy * 0.9f) * level
            i++
        }
    }

    private fun lerpRgb(a: Rgb, b: Rgb, t: Float) = Rgb(
        a.first + (b.first - a.first) * t,
        a.second + (b.second - a.second) * t,
        a.third + (b.third - a.third) * t,
    )

    private companion object {
        val GREEN = Rgb(0.15f, 1.00f, 0.55f)
        val VIOLET = Rgb(0.55f, 0.25f, 1.00f)
    }
}
