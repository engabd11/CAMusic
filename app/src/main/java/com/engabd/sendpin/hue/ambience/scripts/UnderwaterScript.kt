package com.engabd.sendpin.hue.ambience.scripts

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.Vec3
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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Being underwater: caustics drifting across the room, everything a little muffled.
 *
 * The light is two things laid over each other.
 *
 * **Caustics** — the bright net that sunlight makes on a pool floor — are two slow
 * sinusoidal fields crossing at an angle, sampled at each lamp's real position. Two is
 * enough: one is a stripe, two is a mesh, and three is indistinguishable from two while
 * costing more. They drift rather than pulse, which is what stops it looking like a
 * breathing lamp.
 *
 * **Swells** are scheduled events: a slow brightening that travels outward from a point,
 * the way a wave passing overhead changes the light everywhere beneath it in turn. The
 * same event drives the audio's low swell, so the room brightens as the sound swells.
 *
 * The whole thing is coupled through `RoomModel.coupling` afterwards, because water
 * carries light between neighbouring points; without it the caustics look like six
 * independent lamps rather than one body of water.
 */
class UnderwaterScript : AmbienceScript {

    override val effect = AmbienceEffect.UNDERWATER

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    private val rng = Rng(0x0CEA_11L)
    private var nextSwellS = 1.2

    private lateinit var raw: FloatArray
    private lateinit var smooth: FloatArray

    private val bed = PinkNoise(0x00A1)
    private val bedLp = OnePole()
    private val sweepLp = OnePole()
    /** Per event, so two overlapping ones cannot share filter state. See [VoicePool]. */
    private class SwellVoice {
        val filter = Svf()
        val noise = PinkNoise(0x00B2)
        fun reset() { filter.reset() }
    }

    private val voices = VoicePool(8) { SwellVoice() }
    private lateinit var bubbles: GrainCloud
    private val pan = FloatArray(2)
    private var voiced = false

    override fun bind(room: RoomModel, params: AmbienceParams) {
        this.room = room
        this.params = params
        raw = FloatArray(room.count)
        smooth = FloatArray(room.count)
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) {
        if (nextSwellS < fromS) nextSwellS = fromS
        val intensity = params.intensity.coerceIn(0f, 1f)
        val lambda = 0.12f + 0.35f * intensity
        while (nextSwellS < toS) {
            val az = rng.f01()
            val origin = Vec3(
                0.5f + 0.6f * cos(2f * PI.toFloat() * az),
                0.5f + 0.6f * sin(2f * PI.toFloat() * az),
                0.9f,
            )
            emit(
                AmbienceEvent(
                    kind = AmbienceEvent.SWELL,
                    startS = nextSwellS,
                    // Nothing here has an attack. A swell that arrived suddenly would be
                    // a splash, which is the wrong side of the surface.
                    env = Envelope(attackS = 1.1f, holdS = 0.5f, decayTauS = 1.6f),
                    gain = 0.35f + 0.45f * rng.f01(),
                    origin = origin,
                    azimuth = az,
                    colour = CAUSTIC,
                    timbre = 0.4f + 0.6f * rng.f01(),
                    seed = rng.nextLong(),
                ),
            )
            nextSwellS += (-ln(rng.f01().coerceAtLeast(1e-6f)) / lambda).toDouble()
        }
    }

    override fun renderLights(
        tS: Double,
        live: Array<AmbienceEvent?>,
        n: Int,
        out: MutableMap<Int, Rgb>,
    ) {
        val intensity = params.intensity.coerceIn(0f, 1f)
        val t = tS.toFloat()

        for ((i, id) in room.ids.withIndex()) {
            val p = room.positions[id] ?: room.centre()
            // Two crossing wavefronts, at 31 degrees to each other and drifting at
            // different rates so the mesh never repeats.
            val a = sin(2f * PI.toFloat() * (p.x * 2.1f + p.y * 0.7f - t * 0.13f))
            val b = sin(2f * PI.toFloat() * (p.x * 0.8f - p.y * 2.4f - t * 0.097f))
            // Sharpened towards the bright lines: caustics are mostly dark with bright
            // edges, not a smooth ripple.
            val mesh = ((a * b) * 0.5f + 0.5f).let { it * it }
            var lvl = 0.22f + 0.55f * mesh

            var k = 0
            while (k < n) {
                val e = live[k]
                k++
                if (e == null || e.kind != AmbienceEvent.SWELL) continue
                val d = room.distanceTo(id, e.origin)
                // The swell travels: the far side of the room brightens after the near.
                val lvlE = e.gain * e.env.at(e.ageAt(tS) - d * SWELL_TRAVEL_S)
                lvl += lvlE * 0.5f
            }
            raw[i] = lvl
        }

        // Water spreads light. Without the coupling pass this reads as lamps, not depth.
        for (i in smooth.indices) {
            var acc = 0f
            val row = room.coupling[i]
            for (j in row.indices) acc += row[j] * raw[j]
            smooth[i] = acc
        }

        for ((i, id) in room.ids.withIndex()) {
            val depth = 1f - room.heightOf(id)      // lower lamps are deeper, so bluer
            val v = (smooth[i] * (0.45f + 0.55f * intensity)).coerceIn(0f, 1f)
            val c = lerpRgb(SHALLOW, DEEP, depth)
            out[id] = Rgb(c.first * v, c.second * v, c.third * v)
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
            // Everything above a few hundred hertz is simply gone underwater — that
            // single fact is most of what makes this convincing.
            bedLp.setCutoff(420f, sampleRate)
            sweepLp.setCutoff(700f, sampleRate)
            bubbles = GrainCloud(0x0BB1, sampleRate)
            voiced = true
        }
        val intensity = params.intensity.coerceIn(0f, 1f)
        val bedLevel = 0.06f + 0.05f * intensity

        var i = 0
        while (i < frames) {
            val t = (startS + i.toDouble() / sampleRate).toFloat()
            // The muffled bed, with a slow filter sweep so the "depth" breathes.
            val cut = 320f + 220f * sin(2f * PI.toFloat() * t * 0.043f)
            sweepLp.setCutoff(cut, sampleRate)
            val body = sweepLp.lp(bedLp.lp(bed.next())) * 2.2f
            val bub = bubbles.next(1.5f + 5f * intensity, 900f, 22f) * 0.16f
            val s = body * bedLevel + bub
            out[i * 2] += s
            out[i * 2 + 1] += s * 0.92f      // very slight width; water is not stereo
            i++
        }

        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e == null || e.kind != AmbienceEvent.SWELL) continue
            // No block-boundary skip: it would make the shared voices' state depend
            // on the buffer size. Gated per sample below instead.
            panGains(sin(2f * PI.toFloat() * e.azimuth), pan)
            val v = voices.voiceFor(e) { it.reset() }
            val entry = voices.entryGain(v, frames)
            v.filter.set(90f + 130f * e.timbre, 1.1f, sampleRate)
            var j = 0
            while (j < frames) {
                val age = (startS + j.toDouble() / sampleRate - e.startS).toFloat()
                if (age >= 0f) {
                    val env = e.env.at(age)
                    if (env > 0f) {
                        val s = v.filter.lp(v.noise.next()) * env * e.gain * entry * 0.55f
                        out[j * 2] += s * pan[0]
                        out[j * 2 + 1] += s * pan[1]
                    }
                }
                j++
            }
        }
    }

    private fun lerpRgb(a: Rgb, b: Rgb, t: Float) = Rgb(
        a.first + (b.first - a.first) * t,
        a.second + (b.second - a.second) * t,
        a.third + (b.third - a.third) * t,
    )

    private companion object {
        /** Seconds per unit of room distance for a swell to travel. Slow on purpose. */
        const val SWELL_TRAVEL_S = 0.55f

        val SHALLOW = Rgb(0.30f, 0.85f, 0.95f)
        val DEEP = Rgb(0.05f, 0.30f, 0.75f)
        val CAUSTIC = Rgb(0.55f, 0.95f, 1.00f)

        @Suppress("unused") val UNUSED = exp(0f)
    }
}
