package com.engabd.sendpin.hue.ambience.scripts

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.RoomTopology
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceEvent
import com.engabd.sendpin.hue.ambience.AmbienceParams
import com.engabd.sendpin.hue.ambience.AmbienceScript
import com.engabd.sendpin.hue.ambience.Blackbody
import com.engabd.sendpin.hue.ambience.BrownNoise
import com.engabd.sendpin.hue.ambience.ComfortProfile
import com.engabd.sendpin.hue.ambience.Envelope
import com.engabd.sendpin.hue.ambience.OnePole
import com.engabd.sendpin.hue.ambience.RoomModel
import com.engabd.sendpin.hue.ambience.Rng
import com.engabd.sendpin.hue.ambience.Svf
import com.engabd.sendpin.hue.ambience.VoicePool
import com.engabd.sendpin.hue.ambience.mix
import com.engabd.sendpin.hue.ambience.panGains
import com.engabd.sendpin.hue.ambience.smoothstep
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * A night train going by, seen as warm windows.
 *
 * Not a beam that sweeps the room: a *train*, which is a row of carriages with gaps
 * between them, passing a window at night. Every part of it is a pure function of the
 * show clock, so the lights and the clacks agree without anything telling either.
 *
 * ## One cycle
 *
 * - **Approach.** About six seconds before the train arrives a cool station glow rises at
 *   the end it is coming from, the way a platform lights up when a train is due.
 * - **The pass.** Four lit carriages (two on a ring, where the room has no far end)
 *   cross the room in twenty to thirty-four seconds, faster with intensity. A lamp sees a
 *   carriage's warm windows (2700 K) for a couple of seconds, then a dark gap, then the
 *   next. The edges are soft, because a carriage passing a window is not a strobe. The
 *   windows are the only bright thing in the scene; the first carriage carries a faint
 *   headlight ahead of it.
 * - **The quiet.** Twenty seconds of night track with a dim signal at the far end,
 *   red while the train is in the block and green after, and nothing else but the dark
 *   blue of the cutting.
 *
 * Then the next train, coming from the other end. On a ring it always goes the same way
 * round. In a **cluster** (a few lamps on a table) there is no length to run along, so the
 * whole cluster plays the view from one spot: carriages and gaps pass over it in turn.
 *
 * The rail clacks only sound while the train is passing, at a rate that follows its
 * speed, and each leaves a faint soft glow by the head of the train rather than a spark.
 */
class LightTrainScript : AmbienceScript {

    override val effect = AmbienceEffect.LIGHT_TRAIN

    /**
     * About two thirds of a unit a second takes a lamp from dark to its window level in
     * well under a second, which is as fast as a carriage's soft edge ever asks for (its
     * steepest moment is half again its average). The room mean may move as fast, since
     * only the lamps under the train are moving.
     */
    override val comfort = ComfortProfile(risePerS = 0.65f, fallPerS = 0.65f, roomSwingPerS = 0.65f)

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    // One generator per thread: the scheduler's, and the light tick's (which only seeds
    // the carriages' individual brightness, at bind).
    private val rng = Rng(0x7A1_1L)
    private val renderRng = Rng(0x7A1_5EEDL)
    private var carriageSeed = 0
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
        carriageSeed = (renderRng.nextLong() ushr 33).toInt()
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    // ---- The timetable -----------------------------------------------------

    private val ring: Boolean get() = room.topology == RoomTopology.RING
    private val cluster: Boolean get() = room.topology == RoomTopology.CLUSTER

    /** How long one pass takes at the current intensity, in seconds. */
    private fun passS(): Float = 34f - 14f * params.intensity.coerceIn(0f, 1f)

    private fun carriages(): Int = if (ring) 2 else 4

    /** Train length in room units. */
    private fun trainLength(): Float = carriages() * CAR_LEN

    private fun cycleS(): Double = (APPROACH_S + passS() + QUIET_S).toDouble()

    /** Which way this cycle's train goes: alternating, or always one way round a ring. */
    private fun forward(cycle: Long): Boolean = ring || cycle % 2L == 0L

    /**
     * Head of the train at [tS], in the train's own travel coordinate: 0 at the end it
     * enters and 1 at the end it leaves, running a little either side while it is off
     * the room. Meaningful only while [passFraction] is inside 0..1.
     */
    private fun headTravel(passFraction: Float): Float {
        val span = 1f + trainLength() + 2f * MARGIN
        return -MARGIN + passFraction * span
    }

    /** 0 as the train enters the picture, 1 as its tail leaves, over the pass. */
    private fun passFraction(tau: Float): Float = (tau - APPROACH_S) / passS()

    /**
     * Head of the beam at [tS] as a 0..1 position for the audio to pan with, and the
     * clacks to sit at. Centre while no train is passing.
     */
    private fun headAt(tS: Double): Float {
        val cycle = floor(tS / cycleS()).toLong()
        val tau = (tS - cycle * cycleS()).toFloat()
        val pf = passFraction(tau)
        if (pf < 0f || pf > 1f) return 0.5f
        val travel = headTravel(pf).coerceIn(0f, 1f)
        return if (forward(cycle)) travel else 1f - travel
    }

    /** 1 while a train is passing, easing to 0 either side, for the rumble's level. */
    private fun presence(tS: Double): Float {
        val cycle = floor(tS / cycleS()).toLong()
        val tau = (tS - cycle * cycleS()).toFloat()
        val pf = passFraction(tau)
        return smoothstep(-0.15f, 0.1f, pf) * (1f - smoothstep(0.9f, 1.15f, pf))
    }

    override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) {
        if (nextClackS < fromS) nextClackS = fromS
        val intensity = params.intensity.coerceIn(0f, 1f)
        // Faster train, more frequent rail joints. Same number that drives the light.
        val rate = 1.4f + 4.6f * intensity
        val cycle = cycleS()
        while (nextClackS < toS) {
            val c = floor(nextClackS / cycle).toLong()
            val tau = (nextClackS - c * cycle).toFloat()
            val pf = passFraction(tau)
            if (pf < 0f || pf > 1f) {
                // No train: nothing clacks. Skip to the start of the next pass.
                nextClackS = if (pf < 0f) c * cycle + APPROACH_S else (c + 1) * cycle + APPROACH_S
                continue
            }
            val head = headAt(nextClackS)
            emit(
                AmbienceEvent(
                    kind = AmbienceEvent.CLACK,
                    startS = nextClackS,
                    env = Envelope(attackS = 0.002f, holdS = 0.004f, decayTauS = 0.055f),
                    gain = 0.45f + 0.4f * rng.f01(),
                    origin = room.centre(),
                    // Pans with the train, because it is the same train.
                    azimuth = head,
                    colour = SPARK,
                    timbre = head,
                    seed = rng.nextLong(),
                ),
            )
            // Slight jitter: real rail joints are not evenly spaced.
            nextClackS += (1.0 / rate) * (0.8 + 0.4 * rng.f01())
        }
    }

    // ---- Lights ----------------------------------------------------------

    override fun renderLights(
        tS: Double,
        live: Array<AmbienceEvent?>,
        n: Int,
        out: MutableMap<Int, Rgb>,
    ) {
        val intensity = params.intensity.coerceIn(0f, 1f)
        val cycleLen = cycleS()
        val cycle = floor(tS / cycleLen).toLong()
        val tau = (tS - cycle * cycleLen).toFloat()
        val pf = passFraction(tau)
        val fwd = forward(cycle)
        val head = headTravel(pf.coerceIn(0f, 1f))
        val onPass = pf in 0f..1f
        val windowLevel = WINDOW_MIN + (WINDOW_MAX - WINDOW_MIN) * intensity
        val length = trainLength()

        // The approach: a platform lighting up at the end the train comes from, easing
        // away once the train has pulled in past it.
        val station = smoothstep(0f, APPROACH_S - 0.5f, tau) *
            (1f - smoothstep(0.02f, 0.18f, pf.coerceAtLeast(0f))) * (if (pf > 1f) 0f else 1f)

        // The signal at the far end: red while the train is in the block, green after.
        val inBlock = smoothstep(-0.05f, 0.05f, pf) * (1f - smoothstep(1.0f, 1.15f, pf))
        val signal = mix(SIGNAL_GREEN, SIGNAL_RED, inBlock)

        for (id in room.ids) {
            // Where this lamp is along the train's way, 0 at the end the train enters.
            val pos = when {
                cluster -> 0.5f              // one spot: the whole group sees the same train
                ring -> room.azimuth[id] ?: 0f
                else -> room.axisPos[id] ?: 0.5f
            }
            val u = if (fwd) pos else 1f - pos

            var r = NIGHT.first * NIGHT_LEVEL
            var g = NIGHT.second * NIGHT_LEVEL
            var b = NIGHT.third * NIGHT_LEVEL

            // The windows.
            if (onPass || pf in -0.1f..1.2f) {
                var behind = head - u
                if (ring) behind -= floor(behind)        // the short way round the ring
                if (behind in 0f..length) {
                    val q = behind / CAR_LEN
                    val car = floor(q).toInt()
                    val frac = q - floor(q)
                    // Soft at both ends, and dark between carriages. Each carriage has
                    // its own brightness: some windows are curtained.
                    val lit = smoothstep(0f, 0.25f, frac) * (1f - smoothstep(0.5f, 0.72f, frac))
                    val own = 0.75f + 0.25f * hash01(carriageSeed, car + 31 * cycle.toInt())
                    val w = windowLevel * lit * own
                    r += WINDOW.first * w
                    g += WINDOW.second * w
                    b += WINDOW.third * w
                }
                // A faint headlight just ahead of the first carriage.
                val ahead = (u - head) / HEADLIGHT_WIDTH
                if (ahead > -3f) {
                    val hl = HEADLIGHT_LEVEL * exp(-ahead * ahead) * (if (onPass) 1f else 0f)
                    r += HEADLIGHT.first * hl
                    g += HEADLIGHT.second * hl
                    b += HEADLIGHT.third * hl
                }
            }

            // The station, at the entry end.
            if (station > 0f) {
                val d = u / STATION_WIDTH
                val s = STATION_LEVEL * station * exp(-d * d)
                r += STATION.first * s
                g += STATION.second * s
                b += STATION.third * s
            }

            // The signal, at the exit end.
            val sd = (1f - u) / SIGNAL_WIDTH
            val sv = SIGNAL_LEVEL * exp(-sd * sd)
            r += signal.first * sv
            g += signal.second * sv
            b += signal.third * sv

            // The clacks: a soft warm glow by the head of the train, not a spark.
            var k = 0
            while (k < n) {
                val e = live[k]
                k++
                if (e == null || e.kind != AmbienceEvent.CLACK) continue
                val clackPos = if (fwd) e.timbre else 1f - e.timbre
                val d = (u - clackPos) / CLACK_WIDTH
                val glow = CLACK_LEVEL * e.gain * CLACK_GLOW.at(e.ageAt(tS)) * exp(-d * d)
                if (glow <= 0f) continue
                r += SPARK.first * glow
                g += SPARK.second * glow
                b += SPARK.third * glow
            }
            out[id] = Rgb(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        }
    }

    /** Stable 0..1 from a seed and an index; a carriage's own brightness. */
    private fun hash01(seed: Int, i: Int): Float {
        var h = seed * 0x2c1b3c6d + i * -0x61c88647
        h = h xor (h ushr 15)
        h *= 0x2c1b3c6d
        h = h xor (h ushr 12)
        return (h and 0xFFFF) / 65535f
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
            // The rumble pans with the train and rises and falls with its passing. One
            // number drives both, so the sound is always where the light is without
            // either being told about the other.
            val panPos = if (ring) sin(2f * PI.toFloat() * head) else head * 2f - 1f
            panGains(panPos, pan)
            // Doppler: a little brighter coming, duller going. A continuous function
            // of the head's position, not a step - the step fired the low-pass
            // coefficient across the divide in one sample, and a jump in a filter's
            // cutoff is audible as a click exactly at the room's centre.
            val doppler = cos(2f * PI.toFloat() * head)      // +1 approaching, -1 receding
            rumbleLp.setCutoff(150f + 45f * doppler, sampleRate)
            val presence = 0.35f + 0.65f * presence(t)
            val body = rumbleLp.lp(rumble.next()) * 2.4f
            val hiss = airLp.hp(air.next()) * 0.10f * intensity
            val s = (body + hiss) * level * presence
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
            val panPos = if (ring) sin(2f * PI.toFloat() * e.azimuth) else e.azimuth * 2f - 1f
            panGains(panPos, pan)
            val v = voices.voiceFor(e) { it.reset() }
            val entry = voices.entryGain(v, frames)
            v.filter.set(320f + 900f * e.gain, 6f, sampleRate)
            var j = 0
            while (j < frames) {
                val age = (startS + j.toDouble() / sampleRate - e.startS).toFloat()
                if (age >= 0f) {
                    val env = e.env.at(age)
                    if (env > 0f) {
                        val s = v.filter.bp(v.noise.bipolar()) * env * e.gain * entry * 0.35f
                        out[j * 2] += s * pan[0]
                        out[j * 2 + 1] += s * pan[1]
                    }
                }
                j++
            }
        }
    }

    private companion object {
        /** Length of one carriage, in room units. */
        const val CAR_LEN = 0.30f

        /** How far off each end of the room the train starts and finishes. */
        const val MARGIN = 0.15f

        /** Seconds of station glow before the train arrives, and of quiet after it leaves. */
        const val APPROACH_S = 6f
        const val QUIET_S = 20f

        /** Perceived brightness of a lit window, at intensity 0 and 1. */
        const val WINDOW_MIN = 0.22f
        const val WINDOW_MAX = 0.40f

        const val NIGHT_LEVEL = 0.035f
        const val HEADLIGHT_LEVEL = 0.14f
        const val HEADLIGHT_WIDTH = 0.10f
        const val STATION_LEVEL = 0.16f
        const val STATION_WIDTH = 0.28f
        const val SIGNAL_LEVEL = 0.07f
        const val SIGNAL_WIDTH = 0.16f
        const val CLACK_LEVEL = 0.05f
        const val CLACK_WIDTH = 0.16f

        /** A clack's glow: a soft rise and a short fall, so it is a pulse and not a spark. */
        val CLACK_GLOW = Envelope(attackS = 0.07f, holdS = 0f, decayTauS = 0.16f)

        /** The cutting at night: dark blue. */
        val NIGHT = Rgb(0.25f, 0.35f, 0.80f)
        val WINDOW = Blackbody.rgb(2700f)
        val HEADLIGHT = Blackbody.rgb(3800f)
        /** The platform: cool, a little greenish, like fluorescent tubes. */
        val STATION = Rgb(0.78f, 0.95f, 1.00f)
        val SIGNAL_RED = Rgb(1.00f, 0.10f, 0.05f)
        val SIGNAL_GREEN = Rgb(0.10f, 1.00f, 0.30f)
        val SPARK = Blackbody.rgb(2400f)
    }
}
