package com.engabd.sendpin.hue.ambience.scripts

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceEvent
import com.engabd.sendpin.hue.ambience.AmbienceParams
import com.engabd.sendpin.hue.ambience.AmbienceScript
import com.engabd.sendpin.hue.ambience.Blackbody
import com.engabd.sendpin.hue.ambience.ComfortProfile
import com.engabd.sendpin.hue.ambience.Envelope
import com.engabd.sendpin.hue.ambience.Keyframes
import com.engabd.sendpin.hue.ambience.OnePole
import com.engabd.sendpin.hue.ambience.PinkNoise
import com.engabd.sendpin.hue.ambience.Rng
import com.engabd.sendpin.hue.ambience.RoomModel
import com.engabd.sendpin.hue.ambience.SmoothNoise
import com.engabd.sendpin.hue.ambience.VoicePool
import com.engabd.sendpin.hue.ambience.mix
import com.engabd.sendpin.hue.ambience.panGains
import com.engabd.sendpin.hue.ambience.smoothstep
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * Dawn in a forest, over twenty minutes, and then a soft morning that stays.
 *
 * ## What the room does
 *
 * **The sky comes up in four steps and no corners.** Blue hour, then a lavender pink, a
 * peach pink, and finally gold ([SKY]), each blended into the next with a smoothstep so
 * the colour is always moving and never changing. The whole room is on the same
 * clock, but the lamps on the high end of the room's axis see the light a few percent
 * of the ramp sooner, as though the window were over there. After twenty minutes it
 * holds the last key, a soft gold, and nothing else about the room's brightness moves.
 *
 * **The light is filtered through leaves.** Two slow, crossing fields of smooth noise
 * are sampled at each lamp's position and drift in opposite directions, so a patch of
 * shade moves across the room from one lamp to the next in half a minute or so. A
 * little fast flutter rides on top, the leaves moving. The shade is shallow at first,
 * when there is no sun to cast it, and deepens as the light turns gold. Shaded lamps
 * are a touch dimmer and a touch greener.
 *
 * **Birds lead the swells.** From about a minute in, a bird sings now and then, rarely
 * at first and every ten to twenty seconds by morning. Each phrase lifts the lamps near
 * where the bird is by a few points over two or three seconds, which is the only event
 * in the scene. The sound is a chirp phrase to match, over a faint breeze in the
 * canopy.
 */
class ForestDawnScript : AmbienceScript {

    override val effect = AmbienceEffect.FOREST_DAWN

    override val comfort = ComfortProfile(risePerS = 0.12f, fallPerS = 0.12f, roomSwingPerS = 0.05f)

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    private val rng = Rng(0xF0_4E_57L)
    private val renderRng = Rng(0xDA_44_01L)
    private var nextBirdS = Double.NaN

    private lateinit var lampSeed: IntArray
    private var fieldA = 0
    private var fieldB = 0

    private val breeze = PinkNoise(0xB4_EE)
    private val breezeLp = OnePole()
    private var voiced = false

    /** A phrase's own phase, so a chirp is a clean sine rather than a re-started one. */
    private class Songbird {
        var phase = 0f
        fun reset() { phase = 0f }
    }

    private val voices = VoicePool(6) { Songbird() }
    private val pan = FloatArray(2)

    override fun bind(room: RoomModel, params: AmbienceParams) {
        this.room = room
        this.params = params
        lampSeed = IntArray(room.count) { (renderRng.nextLong() ushr 33).toInt() }
        fieldA = (renderRng.nextLong() ushr 33).toInt()
        fieldB = (renderRng.nextLong() ushr 33).toInt()
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    private fun progress(tS: Double) = (tS / RAMP_S).toFloat().coerceIn(0f, 1f)

    override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) {
        if (nextBirdS.isNaN()) nextBirdS = maxOf(fromS, FIRST_BIRD_S)
        while (nextBirdS < toS) {
            val p = progress(nextBirdS)
            val spot = room.ids.getOrNull(rng.nextInt(room.count))?.let { room.positions[it] } ?: room.centre()
            emit(
                AmbienceEvent(
                    kind = AmbienceEvent.SWELL,
                    startS = nextBirdS,
                    env = Envelope(attackS = 1.4f, holdS = 0.8f, decayTauS = 1.0f, tailCut = 0.05f),
                    gain = 1f,
                    origin = spot,
                    azimuth = rng.f01(),
                    colour = Rgb(1f, 0.85f, 0.55f),
                    timbre = rng.f01(),
                    seed = rng.nextLong(),
                    soundS = 5f,
                ),
            )
            val intensity = params.intensity.coerceIn(0f, 1f)
            val gap = (GAP_EARLY_S + (GAP_LATE_S - GAP_EARLY_S) * p) * (1.25f - 0.5f * intensity)
            nextBirdS += gap * (0.6 + 0.8 * rng.f01())
        }
    }

    override fun renderLights(tS: Double, live: Array<AmbienceEvent?>, n: Int, out: MutableMap<Int, Rgb>) {
        val intensity = params.intensity.coerceIn(0f, 1f)
        val p = progress(tS)
        // Shade only exists once there is sun to cast it.
        val depth = (0.05f + 0.22f * smoothstep(0.30f, 0.90f, p)) * (0.8f + 0.4f * intensity)

        for ((i, id) in room.ids.withIndex()) {
            val axis = room.axisPos[id] ?: 0.5f
            val pos = room.positions[id] ?: room.centre()
            val pl = (p + 0.05f * (axis - 0.5f)).coerceIn(0f, 1f)
            val dir = SKY.colourAt(pl)
            val sky = SKY.levelAt(pl) * (0.9f + 0.2f * intensity)

            // Two crossing fields drifting opposite ways, plus the leaves' own flutter.
            val a = SmoothNoise.value(fieldA, pos.x * 2.2 + pos.y * 1.6 + tS * 0.045)
            val b = SmoothNoise.value(fieldB, pos.x * 1.3 - pos.y * 2.1 - tS * 0.032)
            val flutter = SmoothNoise.fractal(lampSeed[i], tS, 0.22f, 2, 2.4f, 0.5f)
            val shade = (0.5f + 0.5f * (0.55f * a + 0.35f * b + 0.10f * flutter)).coerceIn(0f, 1f)

            var level = sky * (1f - depth * shade)
            val colour = mix(dir, LEAF, 0.22f * shade * smoothstep(0.3f, 0.8f, p))

            // Where a bird is singing, a few points of lift.
            var k = 0
            var lift = 0f
            while (k < n) {
                val e = live[k]
                k++
                if (e == null || e.kind != AmbienceEvent.SWELL) continue
                val d = room.distanceTo(id, e.origin)
                lift += e.levelAt(tS) * (1f / (1f + 14f * d * d))
            }
            level += BIRD_LIFT * lift
            level = level.coerceIn(0f, 1f)
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
            breezeLp.setCutoff(700f, sampleRate)
            voiced = true
        }
        val intensity = params.intensity.coerceIn(0f, 1f)
        val lvl = 0.018f + 0.014f * intensity
        var i = 0
        while (i < frames) {
            val t = startS + i.toDouble() / sampleRate
            // Wind in the canopy: a slow swell over a bed of filtered noise.
            val gust = 0.6f + 0.4f * SmoothNoise.fractal(fieldA + 3, t, 0.05f, 2)
            val s = breezeLp.lp(breeze.next()) * lvl * gust
            out[i * 2] += s
            out[i * 2 + 1] += s
            i++
        }

        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e == null || e.kind != AmbienceEvent.SWELL) continue
            val v = voices.voiceFor(e) { it.reset() }
            val entry = voices.entryGain(v, frames)
            panGains(sin(2f * PI.toFloat() * e.azimuth), pan)
            val chirps = 3 + (unit(e.seed, 0, 0) * 4f).toInt()
            val period = 0.38f + 0.22f * unit(e.seed, 0, 1)
            var f = 0
            while (f < frames) {
                val age = (startS + f.toDouble() / sampleRate - e.startS).toFloat()
                val idx = floor(age / period).toInt()
                if (age >= 0f && idx < chirps) {
                    val x = (age - idx * period) / CHIRP_S
                    if (x in 0f..1f) {
                        // One chirp: a sine gliding up or down, with a little tremble.
                        val f0 = 2500f + 2300f * unit(e.seed, idx, 2)
                        val glide = (unit(e.seed, idx, 3) - 0.5f) * 1800f
                        val hz = f0 + glide * x + 90f * sin(2f * PI.toFloat() * 38f * x * CHIRP_S)
                        v.phase += 2f * PI.toFloat() * hz / sampleRate
                        if (v.phase > 2f * PI.toFloat()) v.phase -= 2f * PI.toFloat()
                        val w = sin(PI.toFloat() * x)
                        val s = sin(v.phase) * w * w * 0.045f * entry
                        out[f * 2] += s * pan[0]
                        out[f * 2 + 1] += s * pan[1]
                    }
                }
                f++
            }
        }
    }

    /** A stable 0..1 from a seed, an index and a salt, so a phrase is reproducible with no state. */
    private fun unit(seed: Long, idx: Int, salt: Int): Float {
        var h = seed * -7046029254386353131L + idx * 0x9E3779B1L + salt * 0x85EBCA6BL
        h = h xor (h ushr 29)
        h *= -4658895280553007687L
        h = h xor (h ushr 32)
        return ((h and 0xFFFFFF).toFloat()) / 16777216f
    }

    private companion object {
        /** Twenty minutes from blue hour to gold. */
        const val RAMP_S = 1200.0

        const val FIRST_BIRD_S = 60.0
        const val GAP_EARLY_S = 45f
        const val GAP_LATE_S = 14f
        const val CHIRP_S = 0.09f

        /** Perceived lift a singing bird gives the lamps beside it, at the peak. */
        const val BIRD_LIFT = 0.05f

        val LEAF = Rgb(0.78f, 1.0f, 0.62f)

        val SKY = Keyframes(
            floatArrayOf(0f, 0.35f, 0.60f, 0.85f, 1f),
            arrayOf(
                Rgb(0.35f, 0.50f, 1.00f),       // blue hour
                Rgb(0.95f, 0.55f, 0.80f),       // lavender pink
                Rgb(1.00f, 0.60f, 0.55f),       // peach pink
                Rgb(1.00f, 0.74f, 0.40f),       // gold
                Blackbody.rgb(3100f),           // soft morning gold
            ),
            floatArrayOf(0.07f, 0.16f, 0.28f, 0.40f, 0.46f),
        )
    }
}
