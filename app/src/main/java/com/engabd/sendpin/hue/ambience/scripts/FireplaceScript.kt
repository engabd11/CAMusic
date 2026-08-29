package com.engabd.sendpin.hue.ambience.scripts

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceEvent
import com.engabd.sendpin.hue.ambience.AmbienceParams
import com.engabd.sendpin.hue.ambience.AmbienceScript
import com.engabd.sendpin.hue.ambience.Envelope
import com.engabd.sendpin.hue.ambience.GrainCloud
import com.engabd.sendpin.hue.ambience.OnePole
import com.engabd.sendpin.hue.ambience.PinkNoise
import com.engabd.sendpin.hue.ambience.RoomModel
import com.engabd.sendpin.hue.ambience.Rng
import com.engabd.sendpin.hue.ambience.Svf
import com.engabd.sendpin.hue.ambience.VoicePool
import com.engabd.sendpin.hue.ambience.panGains
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * A fire breathing in the corner of the room, popping now and then.
 *
 * Two things make this read as a fire rather than as lamps flickering.
 *
 * **The flicker is correlated.** Each lamp gets its own slow 1/f wander, but the wanders
 * are mixed through `RoomModel.coupling` — the Gaussian neighbour kernel — so lamps near
 * each other brighten together and the room breathes as one fire seen from different
 * angles. Independent per-lamp noise looks like a fault.
 *
 * **The flicker is bounded.** Fire is restless, not strobing. The per-frame swing is kept
 * well under the flash limiter's threshold, so a fire never trips it — which matters,
 * because a limiter engaging would compress the whole room and the fire would go flat.
 *
 * Pops are the exception: a real event, a flash on the nearest lamp and a filtered
 * impulse from the same [AmbienceEvent].
 */
class FireplaceScript : AmbienceScript {

    override val effect = AmbienceEffect.FIREPLACE

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    private val rng = Rng(0xF12E_9L)
    private var nextPopS = 0.4

    /** Per-lamp 1/f wander, and the coupled result. */
    private lateinit var wander: FloatArray
    private lateinit var coupled: FloatArray
    private lateinit var phase: FloatArray
    private var lastT = 0.0

    /** Where the fire is: the lamp lowest in the room, and its neighbours by distance. */
    private var hearth = 0
    private lateinit var hearthFalloff: FloatArray

    private val bed = PinkNoise(0x0F1E)
    private val bedLp = OnePole()
    private val roar = OnePole()
    private lateinit var crackle: GrainCloud
    /** Per event, so two overlapping ones cannot share filter state. See [VoicePool]. */
    private class PopVoice {
        val filter = Svf()
        val noise = Rng(0x2A2A)
        fun reset() { filter.reset() }
    }

    private val voices = VoicePool(8) { PopVoice() }
    private val pan = FloatArray(2)
    private var voiced = false

    override fun bind(room: RoomModel, params: AmbienceParams) {
        this.room = room
        this.params = params
        val n = room.count
        wander = FloatArray(n) { rng.f01() }
        coupled = FloatArray(n)
        phase = FloatArray(n) { rng.f01() }
        // The hearth is the lowest lamp — real height where the room recorded it,
        // synthetic otherwise. See RoomModel.heightOf: without the fallback every lamp
        // would be at z=0 and "the fire is on the floor over there" could not be said.
        hearth = room.ids.minByOrNull { room.heightOf(it) } ?: room.ids.firstOrNull() ?: 0
        hearthFalloff = FloatArray(n) { i ->
            val d = room.gap(room.ids[i], hearth)
            // Near the hearth is fire; far from it is the glow the fire throws.
            (1f / (1f + 2.2f * d * d)).coerceIn(0.12f, 1f)
        }
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) {
        if (nextPopS < fromS) nextPopS = fromS
        val intensity = params.intensity.coerceIn(0f, 1f)
        val lambda = 0.5f + 2.5f * intensity          // pops per second
        while (nextPopS < toS) {
            // Pops come from the fire, so they scatter around the hearth rather than
            // around the room.
            val hp = room.positions[hearth] ?: room.centre()
            emit(
                AmbienceEvent(
                    kind = AmbienceEvent.POP,
                    startS = nextPopS,
                    // Attack spans more than one render frame on purpose. A 4 ms attack
                    // takes a lamp from the fire's glow to full spark inside a single
                    // 60 Hz tick, which is a strobe rather than a crackle — and it is
                    // exactly the per-frame swing the flash limiter exists to catch, so
                    // the fire would engage the limiter and the whole room would flatten.
                    env = Envelope(attackS = 0.020f, holdS = 0.006f, decayTauS = 0.09f),
                    gain = 0.25f + 0.75f * rng.pow2(),
                    origin = hp,
                    azimuth = room.azimuth[hearth] ?: 0.25f,
                    colour = EMBER,
                    timbre = 0.4f + 0.6f * rng.f01(),   // how bright the pop is
                    seed = rng.nextLong(),
                ),
            )
            nextPopS += (-ln(rng.f01().coerceAtLeast(1e-6f)) / lambda).toDouble()
        }
    }

    override fun renderLights(
        tS: Double,
        live: Array<AmbienceEvent?>,
        n: Int,
        out: MutableMap<Int, Rgb>,
    ) {
        val dt = ((tS - lastT).coerceIn(0.0, 0.1)).toFloat()
        lastT = tS
        val intensity = params.intensity.coerceIn(0f, 1f)

        // 1. Per-lamp 1/f wander, stepped towards a fresh target. Slow enough that the
        //    flicker never approaches the limiter's per-frame delta.
        val rate = (dt * (2.2f + 2.5f * intensity)).coerceIn(0f, 1f)
        for (i in wander.indices) {
            phase[i] += dt * (0.7f + 0.9f * ((i * 37) % 11) / 11f)
            val target = 0.5f + 0.5f * sin(2f * PI.toFloat() * phase[i]) * (0.4f + 0.6f * rng.f01())
            wander[i] += (target - wander[i]) * rate
        }

        // 2. Mix through the neighbour kernel so the room breathes as one fire.
        for (i in coupled.indices) {
            var acc = 0f
            val row = room.coupling[i]
            for (j in row.indices) acc += row[j] * wander[j]
            coupled[i] = acc
        }

        for ((i, id) in room.ids.withIndex()) {
            val flick = 0.55f + 0.45f * coupled[i]
            val level = (0.20f + 0.55f * intensity) * flick * hearthFalloff[i]
            // Hotter at the heart, redder at the edges — a fire is not one colour.
            val heat = (hearthFalloff[i] * flick).coerceIn(0f, 1f)
            var r = lerp(EMBER.first, FLAME.first, heat) * level
            var g = lerp(EMBER.second, FLAME.second, heat) * level
            var b = lerp(EMBER.third, FLAME.third, heat) * level

            var k = 0
            while (k < n) {
                val e = live[k]
                k++
                if (e == null || e.kind != AmbienceEvent.POP) continue
                val d = room.distanceTo(id, e.origin)
                // A pop is a glint off the embers, not a flash of lightning. POP_LIGHT
                // keeps it that side of the line; the audio pop is unscaled, because a
                // crackle that is quiet is just a fire further away.
                val lvl = POP_LIGHT * e.levelAt(tS) * e.timbre / (1f + 5f * d * d)
                if (lvl <= 0f) continue
                r = minOf(1f, r + SPARK.first * lvl)
                g = minOf(1f, g + SPARK.second * lvl)
                b = minOf(1f, b + SPARK.third * lvl)
            }
            out[id] = Rgb(r, g, b)
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
            bedLp.setCutoff(900f, sampleRate)
            roar.setCutoff(180f, sampleRate)
            crackle = GrainCloud(0x77AA, sampleRate)
            voiced = true
        }
        val intensity = params.intensity.coerceIn(0f, 1f)
        val bedLevel = 0.05f + 0.05f * intensity
        val crackleRate = 7f + 26f * intensity

        panGains(sin(2f * PI.toFloat() * (room.azimuth[hearth] ?: 0.25f)), pan)

        var i = 0
        while (i < frames) {
            val raw = bed.next()
            // A fire is a low roar with a fine crackle riding on it. The two need
            // different filters, or it sounds like rain.
            val body = roar.lp(raw) * 1.6f + bedLp.lp(raw) * 0.5f
            val grains = crackle.next(crackleRate, 2600f, 6f)
            val s = body * bedLevel + grains * (0.10f + 0.10f * intensity)
            out[i * 2] += s * pan[0]
            out[i * 2 + 1] += s * pan[1]
            i++
        }

        // Pops: one impulse each, from the same event that flashed the lamp.
        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e == null || e.kind != AmbienceEvent.POP) continue
            // No block-boundary skip: it would make the shared voices' state depend
            // on the buffer size. Gated per sample below instead.
            val v = voices.voiceFor(e) { it.reset() }
            v.filter.set(700f + 2200f * e.timbre, 4.5f, sampleRate)
            var j = 0
            while (j < frames) {
                val age = (startS + j.toDouble() / sampleRate - e.startS).toFloat()
                if (age >= 0f) {
                    val env = e.env.at(age)
                    if (env > 0f) {
                        val s = v.filter.bp(v.noise.bipolar()) * env * e.gain * 0.5f
                        out[j * 2] += s * pan[0]
                        out[j * 2 + 1] += s * pan[1]
                    }
                }
                j++
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        /** How much of a lamp a single pop may claim. */
        const val POP_LIGHT = 0.40f

        val EMBER = Rgb(1.00f, 0.28f, 0.05f)
        val FLAME = Rgb(1.00f, 0.62f, 0.18f)
        val SPARK = Rgb(1.00f, 0.85f, 0.55f)
        // Kept for symmetry with the other scripts' unused-tail guard.
        @Suppress("unused") val DECAY = exp(-1f)
    }
}
