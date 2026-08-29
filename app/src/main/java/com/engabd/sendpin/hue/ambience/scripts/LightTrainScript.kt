package com.engabd.sendpin.hue.ambience.scripts

import com.engabd.sendpin.hue.RoomTopology
import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceEvent
import com.engabd.sendpin.hue.ambience.AmbienceParams
import com.engabd.sendpin.hue.ambience.AmbienceScript
import com.engabd.sendpin.hue.ambience.BrownNoise
import com.engabd.sendpin.hue.ambience.Envelope
import com.engabd.sendpin.hue.ambience.OnePole
import com.engabd.sendpin.hue.ambience.RoomModel
import com.engabd.sendpin.hue.ambience.Rng
import com.engabd.sendpin.hue.ambience.Svf
import com.engabd.sendpin.hue.ambience.VoicePool
import com.engabd.sendpin.hue.ambience.panGains
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * A beam that runs the length of the room and away into the distance.
 *
 * The one effect that has to ask what shape the room is, because "run along it" means
 * different things in different rooms — and getting that wrong is the difference between
 * a train and a flicker:
 *
 * - **LINEAR / FIELD** — sweep along `RoomModel.axisPos`, the room's own dominant
 *   direction. This is a lamp bar behind a sofa, or lamps down one wall.
 * - **RING** — sweep around `RoomModel.azimuth` instead, and wrap. In a ring of lamps
 *   the beam should circle the room, not run to one side and stop.
 * - **CLUSTER** — no sweep at all. Three lamps on one table have no length to run along,
 *   so a "sweep" would be a stutter; the whole cluster pulses as one carriage instead.
 *   `SpatialWaves` classifies exactly this case, and honouring it is why the effect does
 *   not look broken in a small room.
 *
 * The audio is bound to the same position. The rumble's pan follows the head of the
 * beam, and rail clacks are scheduled at a rate proportional to speed — so the sound
 * accelerates with the light because both read the same number.
 */
class LightTrainScript : AmbienceScript {

    override val effect = AmbienceEffect.LIGHT_TRAIN

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    private val rng = Rng(0x7A1_1L)
    private var nextClackS = 0.5

    private val rumble = BrownNoise(0x1A1A)
    private val rumbleLp = OnePole()
    /** Per event, so two overlapping ones cannot share filter state. See [VoicePool]. */
    private class ClackVoice {
        val filter = Svf()
        val noise = Rng(0x2B2B)
        fun reset() { filter.reset() }
    }

    private val voices = VoicePool(8) { ClackVoice() }
    private val airLp = OnePole()
    private val air = BrownNoise(0x3C3C)
    private val pan = FloatArray(2)
    private var voiced = false

    override fun bind(room: RoomModel, params: AmbienceParams) {
        this.room = room
        this.params = params
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    /** How long one pass down the room takes, at the current intensity. */
    private fun periodS(): Float = 9f - 6.2f * params.intensity.coerceIn(0f, 1f)

    /** Head of the beam at [tS], as 0..1 along whichever coordinate the room uses. */
    private fun headAt(tS: Double): Float {
        val p = periodS()
        val phase = ((tS % p) / p).toFloat()
        return if (room.topology == RoomTopology.RING) phase else {
            // Out and back rather than a jump-cut restart: a beam that teleported to the
            // far wall every few seconds reads as a glitch.
            if (phase < 0.5f) phase * 2f else 2f - phase * 2f
        }
    }

    override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) {
        if (nextClackS < fromS) nextClackS = fromS
        val intensity = params.intensity.coerceIn(0f, 1f)
        // Faster train, more frequent rail joints. Same number that drives the light.
        val rate = 1.6f + 6.5f * intensity
        while (nextClackS < toS) {
            val head = headAt(nextClackS)
            emit(
                AmbienceEvent(
                    kind = AmbienceEvent.CLACK,
                    startS = nextClackS,
                    env = Envelope(attackS = 0.002f, holdS = 0.004f, decayTauS = 0.055f),
                    gain = 0.45f + 0.4f * rng.f01(),
                    origin = room.centre(),
                    // Pans with the beam, because it is the same train.
                    azimuth = head,
                    colour = BEAM,
                    timbre = head,
                    seed = rng.nextLong(),
                ),
            )
            // Slight jitter: real rail joints are not evenly spaced.
            nextClackS += (1.0 / rate) * (0.8 + 0.4 * rng.f01())
        }
    }

    override fun renderLights(
        tS: Double,
        live: Array<AmbienceEvent?>,
        n: Int,
        out: MutableMap<Int, Rgb>,
    ) {
        val intensity = params.intensity.coerceIn(0f, 1f)
        val head = headAt(tS)
        val width = 0.16f + 0.10f * (1f - intensity)
        val cluster = room.topology == RoomTopology.CLUSTER
        // A whole-room pulse for a cluster, on the same period as the sweep would be.
        val pulse = if (cluster) {
            val p = periodS()
            val ph = ((tS % p) / p).toFloat()
            0.25f + 0.75f * exp(-((ph - 0.5f) * (ph - 0.5f)) / 0.02f)
        } else 0f

        for (id in room.ids) {
            var lvl: Float
            if (cluster) {
                lvl = pulse
            } else {
                val pos = if (room.topology == RoomTopology.RING) {
                    room.azimuth[id] ?: 0f
                } else {
                    room.axisPos[id] ?: 0.5f
                }
                var d = abs(pos - head)
                // On a ring, the short way round is the real distance.
                if (room.topology == RoomTopology.RING) d = minOf(d, 1f - d)
                lvl = exp(-(d * d) / (width * width))
                // A tail behind the head, so it reads as motion rather than a dot.
                val behind = pos - head
                if (behind < 0f) lvl = maxOf(lvl, exp(behind / TAIL) * 0.45f)
            }

            var r = HAZE.first + BEAM.first * lvl
            var g = HAZE.second + BEAM.second * lvl
            var b = HAZE.third + BEAM.third * lvl

            var k = 0
            while (k < n) {
                val e = live[k]
                k++
                if (e == null || e.kind != AmbienceEvent.CLACK) continue
                // The clack sparks the lamp nearest where the wheel was.
                val pos = if (room.topology == RoomTopology.RING) room.azimuth[id] ?: 0f
                else room.axisPos[id] ?: 0.5f
                var d = abs(pos - e.timbre)
                if (room.topology == RoomTopology.RING) d = minOf(d, 1f - d)
                val flash = e.levelAt(tS) * exp(-(d * d) / 0.01f)
                if (flash <= 0f) continue
                r = minOf(1f, r + SPARK.first * flash)
                g = minOf(1f, g + SPARK.second * flash)
                b = minOf(1f, b + SPARK.third * flash)
            }
            val trim = 0.5f + 0.5f * intensity
            out[id] = Rgb(r * trim, g * trim, b * trim)
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
            rumbleLp.setCutoff(150f, sampleRate)
            airLp.setCutoff(2400f, sampleRate)
            voiced = true
        }
        val intensity = params.intensity.coerceIn(0f, 1f)
        val level = 0.06f + 0.07f * intensity

        var i = 0
        while (i < frames) {
            val t = startS + i.toDouble() / sampleRate
            val head = headAt(t)
            // The rumble pans with the beam. One number drives both, so the sound is
            // always where the light is without either being told about the other.
            val panPos = if (room.topology == RoomTopology.RING) {
                sin(2f * PI.toFloat() * head)
            } else head * 2f - 1f
            panGains(panPos, pan)
            // Doppler: a little brighter coming, duller going.
            val approaching = if (head < 0.5f) 1f else -1f
            rumbleLp.setCutoff(150f + 45f * approaching, sampleRate)
            val body = rumbleLp.lp(rumble.next()) * 2.4f
            val hiss = airLp.hp(air.next()) * 0.10f * intensity
            val s = (body + hiss) * level
            out[i * 2] += s * pan[0]
            out[i * 2 + 1] += s * pan[1]
            i++
        }

        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e == null || e.kind != AmbienceEvent.CLACK) continue
            // No block-boundary skip: it would make the shared voices' state depend
            // on the buffer size. Gated per sample below instead.
            val panPos = if (room.topology == RoomTopology.RING) sin(2f * PI.toFloat() * e.azimuth)
            else e.azimuth * 2f - 1f
            panGains(panPos, pan)
            val v = voices.voiceFor(e) { it.reset() }
            v.filter.set(320f + 900f * e.gain, 6f, sampleRate)
            var j = 0
            while (j < frames) {
                val age = (startS + j.toDouble() / sampleRate - e.startS).toFloat()
                if (age >= 0f) {
                    val env = e.env.at(age)
                    if (env > 0f) {
                        val s = v.filter.bp(v.noise.bipolar()) * env * e.gain * 0.35f
                        out[j * 2] += s * pan[0]
                        out[j * 2 + 1] += s * pan[1]
                    }
                }
                j++
            }
        }
    }

    private companion object {
        /** How quickly the beam's tail falls off behind the head, in room units. */
        const val TAIL = 0.10f

        val BEAM = Rgb(0.85f, 0.92f, 1.00f)
        val HAZE = Rgb(0.02f, 0.03f, 0.07f)
        val SPARK = Rgb(1.00f, 0.80f, 0.45f)
    }
}
