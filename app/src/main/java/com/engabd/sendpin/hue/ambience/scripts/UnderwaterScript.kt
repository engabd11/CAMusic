package com.engabd.sendpin.hue.ambience.scripts

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.Vec3
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceEvent
import com.engabd.sendpin.hue.ambience.AmbienceParams
import com.engabd.sendpin.hue.ambience.AmbienceScript
import com.engabd.sendpin.hue.ambience.ComfortProfile
import com.engabd.sendpin.hue.ambience.Envelope
import com.engabd.sendpin.hue.ambience.GrainCloud
import com.engabd.sendpin.hue.ambience.OnePole
import com.engabd.sendpin.hue.ambience.PinkNoise
import com.engabd.sendpin.hue.ambience.RoomModel
import com.engabd.sendpin.hue.ambience.Rng
import com.engabd.sendpin.hue.ambience.SmoothNoise
import com.engabd.sendpin.hue.ambience.Svf
import com.engabd.sendpin.hue.ambience.VoicePool
import com.engabd.sendpin.hue.ambience.mix
import com.engabd.sendpin.hue.ambience.panGains
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Being underwater: deep teal going to black at the floor, light moving slowly overhead.
 *
 * Four things, all of them slow, laid over each other.
 *
 * **Depth.** The lamps lowest in the room are the bottom: a deep teal, close to black.
 * The lamps highest are the surface: brighter, greener, and the only ones the caustics
 * really reach. Depth is the lamp's height (`RoomModel.heightOf`), so the effect has a
 * vertical dimension even in a floor-plan room.
 *
 * **Caustics.** The bright net sunlight makes on a pool floor is two crossing fields
 * sampled at each lamp's position. Both drift at 0.08 to 0.12 cycles a second, so a lamp
 * sees a ripple pass every five to twelve seconds: slow enough to read as water seen from
 * a bench and not as a flickering lamp. Two fields are enough, because one is a stripe
 * and two is a mesh.
 *
 * **A shaft of sun.** Once a minute or so a pale shaft drifts along the room's long axis
 * over fifteen seconds or so, the way a ray moves across the bottom as the surface
 * rocks. It favours the high lamps, which are nearer the light.
 *
 * **A swell.** Every twenty to forty seconds the whole room slowly blooms, eight seconds
 * up and a long ease down, the way a wave passing overhead changes the light everywhere
 * beneath it in turn. The same event drives the audio's low swell, so the room brightens
 * as the sound swells.
 *
 * The whole thing is coupled through `RoomModel.coupling` afterwards, because water
 * carries light between neighbouring points; without it the caustics look like six
 * independent lamps rather than one body of water.
 */
class UnderwaterScript : AmbienceScript {

    override val effect = AmbienceEffect.UNDERWATER

    /**
     * Water does not jump. A quarter of a unit a second is a lamp going from dim to
     * moderate over a couple of seconds, which is faster than anything this scene asks
     * for and fast enough never to be the thing that is noticed. The room mean may move
     * at about half that.
     */
    override val comfort = ComfortProfile(risePerS = 0.25f, fallPerS = 0.25f, roomSwingPerS = 0.12f)

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    // One generator per thread: [rng] is the scheduler's alone, [renderRng] the light
    // tick's (it only seeds the per-lamp noise, at bind).
    private val rng = Rng(0x0CEA_11L)
    private val renderRng = Rng(0x0CEA_5EEDL)
    private var nextSwellS = 6.0
    private var nextBeamS = 25.0

    private lateinit var raw: FloatArray
    private lateinit var smooth: FloatArray
    private lateinit var sun: FloatArray
    private lateinit var lampSeed: IntArray

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
        sun = FloatArray(room.count)
        lampSeed = IntArray(room.count) { (renderRng.nextLong() ushr 33).toInt() }
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) {
        if (nextSwellS < fromS) nextSwellS = fromS
        if (nextBeamS < fromS) nextBeamS = fromS
        val intensity = params.intensity.coerceIn(0f, 1f)

        // A swell every 20 to 40 seconds, a little more often when turned up.
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
                    // a splash, which is the wrong side of the surface. Eight seconds up,
                    // a few at the top, a long ease down.
                    env = Envelope(attackS = 8f, holdS = 3f, decayTauS = 4.5f, tailCut = 0.05f),
                    gain = 0.45f + 0.35f * rng.f01(),
                    origin = origin,
                    azimuth = az,
                    colour = CAUSTIC,
                    timbre = 0.4f + 0.6f * rng.f01(),
                    seed = rng.nextLong(),
                ),
            )
            nextSwellS += (SWELL_GAP_MIN_S + (SWELL_GAP_MAX_S - SWELL_GAP_MIN_S) * rng.f01()) /
                (0.8f + 0.4f * intensity)
        }

        // A shaft of sun every minute or so. Silent, so the audio ignores it.
        while (nextBeamS < toS) {
            emit(
                AmbienceEvent(
                    kind = AmbienceEvent.SWEEP,
                    startS = nextBeamS,
                    env = Envelope(attackS = 4f, holdS = 6f, decayTauS = 3f, tailCut = 0.05f),
                    gain = 0.8f + 0.2f * rng.f01(),
                    origin = room.centre(),
                    // Which way along the axis it drifts.
                    azimuth = rng.f01(),
                    colour = SUN,
                    timbre = rng.f01(),
                    seed = rng.nextLong(),
                ),
            )
            nextBeamS += BEAM_GAP_MIN_S + (BEAM_GAP_MAX_S - BEAM_GAP_MIN_S) * rng.f01()
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
        val gain = 0.75f + 0.5f * intensity

        for ((i, id) in room.ids.withIndex()) {
            val p = room.positions[id] ?: room.centre()
            val h = room.heightOf(id)         // 0 at the floor, 1 at the surface
            // Two crossing wavefronts, at different angles and drifting at different
            // rates so the mesh never repeats. 0.08 and 0.115 cycles a second: a ripple
            // passes a given lamp every five to twelve seconds.
            val a = sin(2f * PI.toFloat() * (p.x * 1.7f + p.y * 0.6f - t * 0.080f))
            val b = sin(2f * PI.toFloat() * (p.x * 0.7f - p.y * 1.9f - t * 0.115f))
            // Sharpened towards the bright lines: caustics are mostly dark with bright
            // edges, not a smooth ripple.
            val mesh = ((a * b) * 0.5f + 0.5f).let { it * it }
            // The net is only really visible near the surface; down at the floor the
            // light has been scattered into a faint, even glow.
            val reach = 0.25f + 0.75f * h
            // Slow variation of the water itself, so no two minutes are alike.
            val drift = 1f + 0.10f * SmoothNoise.fractal(lampSeed[i], tS, 0.04f, 2)
            var beam = 0f
            var lvl = (FLOOR_LEVEL + (SURFACE_LEVEL - FLOOR_LEVEL) * h + CAUSTIC_DEPTH * mesh * reach) * drift

            var k = 0
            while (k < n) {
                val e = live[k]
                k++
                if (e == null) continue
                when (e.kind) {
                    AmbienceEvent.SWELL -> {
                        val d = room.distanceTo(id, e.origin)
                        // The swell travels: the far side of the room brightens after the near.
                        lvl += SWELL_DEPTH * e.gain * e.env.at(e.ageAt(tS) - d * SWELL_TRAVEL_S) * (0.5f + 0.5f * reach)
                    }
                    AmbienceEvent.SWEEP -> beam += beamAt(e, tS, id, h)
                }
            }
            raw[i] = lvl
            sun[i] = beam
        }

        // Water spreads light. Without the coupling pass this reads as lamps, not depth.
        for (i in smooth.indices) {
            var acc = 0f
            val row = room.coupling[i]
            for (j in row.indices) acc += row[j] * raw[j]
            smooth[i] = acc
        }

        for ((i, id) in room.ids.withIndex()) {
            // Deep teal near the floor, a brighter green-teal near the surface.
            val c = mix(DEEP, SHALLOW, room.heightOf(id))
            val v = (smooth[i] * gain).coerceIn(0f, 1f)
            val s = sun[i] * gain
            out[id] = Rgb(
                (c.first * v + SUN.first * s).coerceIn(0f, 1f),
                (c.second * v + SUN.second * s).coerceIn(0f, 1f),
                (c.third * v + SUN.third * s).coerceIn(0f, 1f),
            )
        }
    }

    /**
     * How much of the sun's shaft reaches [id]: a soft band that drifts along the room's
     * axis over the event's life, brightest on the high lamps.
     */
    private fun beamAt(e: AmbienceEvent, tS: Double, id: Int, h: Float): Float {
        val age = e.ageAt(tS)
        if (age < 0f) return 0f
        val life = e.env.attackS + e.env.holdS + 3f * e.env.decayTauS
        val travel = (age / life).coerceIn(0f, 1f)
        // Enters off one end and leaves off the other, so the shaft is never a thing that
        // switches on in the middle of the room.
        val head = if (e.azimuth < 0.5f) -0.2f + 1.4f * travel else 1.2f - 1.4f * travel
        val d = ((room.axisPos[id] ?: 0.5f) - head) / BEAM_WIDTH
        return BEAM_LEVEL * e.gain * e.env.at(age) * exp(-d * d) * (0.4f + 0.6f * h)
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

    private companion object {
        /** Seconds per unit of room distance for a swell to travel. Slow on purpose. */
        const val SWELL_TRAVEL_S = 1.6f

        /** Gap between swells, and between sun shafts, in seconds. */
        const val SWELL_GAP_MIN_S = 20f
        const val SWELL_GAP_MAX_S = 40f
        const val BEAM_GAP_MIN_S = 55.0
        const val BEAM_GAP_MAX_S = 110.0

        /**
         * Perceived brightness at the floor and at the surface, the caustics' contribution
         * at the surface, and the most a swell and a shaft add. The floor is close to
         * black on purpose: the depth is most of the effect.
         */
        const val FLOOR_LEVEL = 0.06f
        const val SURFACE_LEVEL = 0.26f
        const val CAUSTIC_DEPTH = 0.16f
        const val SWELL_DEPTH = 0.12f
        const val BEAM_LEVEL = 0.22f
        const val BEAM_WIDTH = 0.18f

        val SHALLOW = Rgb(0.22f, 0.86f, 0.80f)
        val DEEP = Rgb(0.02f, 0.30f, 0.42f)
        val CAUSTIC = Rgb(0.55f, 0.95f, 1.00f)
        val SUN = Rgb(0.95f, 0.97f, 0.78f)
    }
}
