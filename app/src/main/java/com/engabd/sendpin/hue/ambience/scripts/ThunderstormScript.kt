package com.engabd.sendpin.hue.ambience.scripts

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.Vec3
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceEvent
import com.engabd.sendpin.hue.ambience.AmbienceParams
import com.engabd.sendpin.hue.ambience.AmbienceScript
import com.engabd.sendpin.hue.ambience.BrownNoise
import com.engabd.sendpin.hue.ambience.Envelope
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
 * Rain, and lightning that arrives before its thunder.
 *
 * **The worked example of the whole design.** A strike is one [AmbienceEvent]. The lights
 * read it at `startS`; the audio reads the same object at `startS + distance / 343 m/s`.
 * Nothing synchronises the flash and the crack, because there is nothing to synchronise —
 * they are two projections of one cause, and the gap between them falls out of the
 * event's own [AmbienceEvent.timbre], which is how far away the strike was in kilometres.
 *
 * Change the intensity mid-storm, reseed it, or re-render the same event a thousand
 * times: a strike two kilometres out is always seen about six seconds before it is heard,
 * because that is arithmetic rather than bookkeeping.
 *
 * The lamps nearest the strike also flash first, by their real distance in the room —
 * which is the same relationship again, one order of magnitude down.
 */
class ThunderstormScript : AmbienceScript {

    override val effect = AmbienceEffect.THUNDERSTORM

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    /** The scheduler's own stream, advanced only by [schedule], so windows are stable. */
    private val rng = Rng(0x57_0A_11_5EL)
    private var nextStrikeS = 0.6

    // Audio bed voices. Owned by the generator thread alone.
    private val rainHiss = PinkNoise(0x1234)
    private val rainRoof = BrownNoise(0x5678)
    private val hissHpL = OnePole()
    private val hissHpR = OnePole()
    private val roofLpL = OnePole()
    private val roofLpR = OnePole()
    /** Per strike, not per storm — two strikes overlap constantly. See [VoicePool]. */
    private class StrikeVoice {
        val crack = Svf()
        val rollLp = OnePole()
        val roll = BrownNoise(0x9ABC)
        val noise = Rng(0xDEF0)
        fun reset() { crack.reset(); rollLp.reset() }
    }

    private val voices = VoicePool(MAX_VOICES) { StrikeVoice() }
    private val pan = FloatArray(2)
    private var voiced = false

    override fun bind(room: RoomModel, params: AmbienceParams) {
        this.room = room
        this.params = params
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    // ---- Scheduling ------------------------------------------------------

    override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) {
        if (nextStrikeS < fromS) nextStrikeS = fromS
        val intensity = params.intensity.coerceIn(0f, 1f)
        // Strikes per second. A quiet storm is one every twenty-five seconds; a wild one
        // is about one every two and a half.
        val lambda = 0.04f + 0.36f * intensity
        while (nextStrikeS < toS) {
            val az = rng.f01()
            // pow2 biases towards zero, so *near* strikes are the rare ones — which is
            // what makes the occasional close one feel like an event.
            // Capped at MAX_DIST_KM. The flash-to-thunder gap stays physical — that is
            // the whole point of the effect — but a strike eight kilometres out would
            // rumble twenty-three seconds later, by which time three more have flashed
            // and the relationship the effect exists to show is lost on the listener.
            val distKm = MIN_DIST_KM + (MAX_DIST_KM - MIN_DIST_KM) * rng.pow2()
            // Outside the room and above it: the flash comes from beyond the walls.
            val origin = Vec3(
                0.5f + 1.6f * cos(2f * PI.toFloat() * az),
                0.5f + 1.6f * sin(2f * PI.toFloat() * az),
                1f,
            )
            val gain = (1.15f / (1f + distKm)).coerceIn(0.08f, 1f)
            // A near strike flickers two or three times; a distant one is a single
            // sheet. Deliberately few: the 12.5 Hz rate limiter would swallow a longer
            // flicker group anyway, and a strike that survives the limiter reads better
            // than one that gets averaged into a smear.
            val flickers = if (distKm < 1.5f) 1 + rng.nextInt(2) else 1
            val colour = lerpRgb(NEAR_BOLT, FAR_BOLT, (distKm / 8f).coerceIn(0f, 1f))
            var k = 0
            while (k < flickers) {
                emit(
                    AmbienceEvent(
                        kind = AmbienceEvent.STRIKE,
                        startS = nextStrikeS + k * (0.03 + 0.06 * rng.f01()),
                        env = Envelope(attackS = 0.012f, holdS = 0.02f, decayTauS = 0.11f),
                        gain = gain * (1f - 0.25f * k),
                        origin = origin,
                        azimuth = az,
                        colour = colour,
                        // The one number both media read.
                        timbre = distKm,
                        seed = rng.nextLong(),
                    ),
                )
                k++
            }
            // Poisson gaps. A fixed cadence reads as a machine, not weather.
            nextStrikeS += (-ln(rng.f01().coerceAtLeast(1e-6f)) / lambda).toDouble()
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
        for (id in room.ids) {
            // The rain bed: a dark, faintly restless blue-grey. Per-lamp phase so the
            // room shimmers rather than pulsing as one panel.
            val phase = (tS * 0.37 + id * 0.61).toFloat()
            val shimmer = 0.030f + 0.016f * (0.5f + 0.5f * sin(2f * PI.toFloat() * phase))
            var r = RAIN.first * shimmer
            var g = RAIN.second * shimmer
            var b = RAIN.third * shimmer

            var i = 0
            while (i < n) {
                val e = live[i]
                i++
                if (e == null || e.kind != AmbienceEvent.STRIKE) continue
                // Measured from the *nearest* lamp, not from the strike. The strike sits
                // outside the room, so raw distances are large and nearly equal — a sweep
                // scaled by them would either be imperceptible or, if scaled up, push the
                // far lamps past the end of the envelope so they never lit at all. What
                // should set the sweep is the room's own extent.
                val d = room.distanceTo(id, e.origin) - nearestDistance(e)
                // Lamps nearer the strike light first. SPREAD_S is tuned so the sweep
                // crosses the room in about 180 ms: fast enough to read as a direction,
                // slow enough to survive the 12.5 Hz per-channel ceiling — below that
                // the whole room would simply flash at once and the direction would be
                // lost. Same reasoning as SyncoEngine's minimum dwell.
                val lvl = e.gain * e.env.at(e.ageAt(tS) - d * SPREAD_S) / (1f + 1.4f * d * d)
                if (lvl <= 0f) continue
                // max, not sum: two strikes overlapping should not make the room
                // brighter than a single close one, they should each still read.
                r = maxOf(r, e.colour.first * lvl)
                g = maxOf(g, e.colour.second * lvl)
                b = maxOf(b, e.colour.third * lvl)
            }
            val trim = 0.55f + 0.45f * intensity
            out[id] = Rgb(r * trim, g * trim, b * trim)
        }
    }

    // ---- Audio -----------------------------------------------------------

    override fun renderAudio(
        out: FloatArray,
        frames: Int,
        startS: Double,
        sampleRate: Int,
        live: Array<AmbienceEvent?>,
        n: Int,
    ) {
        if (!voiced) {
            hissHpL.setCutoff(1200f, sampleRate)
            hissHpR.setCutoff(1200f, sampleRate)
            roofLpL.setCutoff(400f, sampleRate)
            roofLpR.setCutoff(400f, sampleRate)
            voiced = true
        }
        val intensity = params.intensity.coerceIn(0f, 1f)
        val rainLevel = 0.05f + 0.05f * intensity

        // 1. The bed. Two independent generators so the rain is not a mono point source
        //    sitting in the middle of the listener's head.
        var i = 0
        while (i < frames) {
            val hissL = hissHpL.hp(rainHiss.next())
            val hissR = hissHpR.hp(rainHiss.next())
            val roofL = roofLpL.lp(rainRoof.next())
            val roofR = roofLpR.lp(rainRoof.next())
            out[i * 2] += (hissL * 0.55f + roofL * 0.45f) * rainLevel
            out[i * 2 + 1] += (hissR * 0.55f + roofR * 0.45f) * rainLevel
            i++
        }

        // 2. The strikes, each delayed by the time its own sound takes to arrive.
        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e == null || e.kind != AmbienceEvent.STRIKE) continue
            renderStrike(e, voices.voiceFor(e) { it.reset() }, out, frames, startS, sampleRate)
        }
    }

    /**
     * One strike's sound.
     *
     * The event stored a light envelope and a distance. Everything audible is derived
     * from those two: the crack's brightness and length, the roll's slow build and long
     * tail, and above all the delay — `distance / 343 m/s` — that puts the thunder after
     * the flash by exactly as much as the physics says.
     */
    private fun renderStrike(
        e: AmbienceEvent,
        v: StrikeVoice,
        out: FloatArray,
        frames: Int,
        startS: Double,
        sampleRate: Int,
    ) {
        val distKm = e.timbre.coerceAtLeast(0.05f)
        val propS = distKm / 0.343f              // km at 343 m/s, in seconds
        val heardAt = e.startS + propS

        // Both derived, neither stored. A near strike is a sharp crack with a short
        // tail; a distant one is all roll and no crack at all.
        val crackTau = 0.05f + 0.02f * distKm
        val crackEnv = Envelope(0.001f, 0.004f, crackTau)
        val rollEnv = Envelope(0.30f + 0.9f * distKm, 0.15f, 1.1f + 3.4f * distKm)
        val longest = maxOf(crackEnv.lifetimeS, rollEnv.lifetimeS)

        val blockEndS = startS + frames.toDouble() / sampleRate
        if (heardAt + longest < startS || heardAt > blockEndS) return

        panGains(sin(2f * PI.toFloat() * e.azimuth), pan)
        val crackAmp = e.gain / (1f + distKm)
        val rollAmp = e.gain * (0.5f + 0.5f * exp(-distKm / 6f))
        val rollCut = 2000f * exp(-distKm / 2f)
        v.rollLp.setCutoff(rollCut.coerceAtLeast(60f), sampleRate)

        // Envelopes and the filter sweep are stepped per sub-block and interpolated
        // between: 32x fewer exp() calls, and nothing audible on a 12 ms attack.
        var i = 0
        while (i < frames) {
            val chunk = minOf(SUB_BLOCK, frames - i)
            val a0 = (startS + i.toDouble() / sampleRate - heardAt).toFloat()
            val a1 = (startS + (i + chunk).toDouble() / sampleRate - heardAt).toFloat()
            val c0 = crackEnv.at(a0); val c1 = crackEnv.at(a1)
            val r0 = rollEnv.at(a0); val r1 = rollEnv.at(a1)
            if (c0 <= 0f && c1 <= 0f && r0 <= 0f && r1 <= 0f) { i += chunk; continue }
            // The crack's band-pass falls from a few kHz to a couple of hundred hertz
            // over the first tens of milliseconds — that downward sweep is what the ear
            // reads as "close and violent" rather than "a burst of noise".
            v.crack.set((3000f * exp(-a0.coerceAtLeast(0f) / 0.04f) + 260f), 1.4f, sampleRate)
            var j = 0
            while (j < chunk) {
                val f = j.toFloat() / chunk
                val ce = c0 + (c1 - c0) * f
                val re = r0 + (r1 - r0) * f
                val crack = v.crack.bp(v.noise.bipolar()) * ce * crackAmp
                val roll = v.rollLp.lp(v.roll.next()) * re * rollAmp
                val s = crack * 0.7f + roll * 0.9f
                val idx = (i + j) * 2
                out[idx] += s * pan[0]
                out[idx + 1] += s * pan[1]
                j++
            }
            i += chunk
        }
    }

    /**
     * How far the closest lamp is from [e]'s origin.
     *
     * Cached per event because it is the same answer for every lamp in the frame, and
     * the light tick asks once per lamp per event.
     */
    private fun nearestDistance(e: AmbienceEvent): Float {
        if (e === nearestFor) return nearestValue
        var best = Float.MAX_VALUE
        for (id in room.ids) {
            val d = room.distanceTo(id, e.origin)
            if (d < best) best = d
        }
        nearestFor = e
        nearestValue = best
        return best
    }

    private var nearestFor: AmbienceEvent? = null
    private var nearestValue = 0f

    private fun lerpRgb(a: Rgb, b: Rgb, t: Float) = Rgb(
        a.first + (b.first - a.first) * t,
        a.second + (b.second - a.second) * t,
        a.third + (b.third - a.third) * t,
    )

    private companion object {
        /**
         * Seconds of delay per unit of room distance for the flash sweep.
         *
         * Not physical — light crosses a room instantly. This is a *readable* stand-in:
         * at about 180 ms across the room the eye registers a direction, and it clears
         * the 12.5 Hz per-channel ceiling the bridge imposes. Any faster and the room
         * simply flashes as one.
         */
        /** Concurrent strikes with their own voice; beyond this the oldest recycles. */
        const val MAX_VOICES = 10

        const val SPREAD_S = 0.18f

        /** Nearest and furthest a strike may be, in kilometres. See the note at [schedule]. */
        const val MIN_DIST_KM = 0.15f
        const val MAX_DIST_KM = 2.6f

        /** Envelope steps per sub-block. 32 frames is under a millisecond at 48 kHz. */
        const val SUB_BLOCK = 32

        val RAIN = Rgb(0.42f, 0.52f, 0.80f)
        val NEAR_BOLT = Rgb(0.70f, 0.80f, 1.00f)
        val FAR_BOLT = Rgb(0.55f, 0.66f, 1.00f)
    }
}
