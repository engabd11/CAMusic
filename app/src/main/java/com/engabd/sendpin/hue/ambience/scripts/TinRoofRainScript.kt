package com.engabd.sendpin.hue.ambience.scripts

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.ambience.AmbienceBedTrack
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceEvent
import com.engabd.sendpin.hue.ambience.AmbienceParams
import com.engabd.sendpin.hue.ambience.AmbienceScript
import com.engabd.sendpin.hue.ambience.BedSample
import com.engabd.sendpin.hue.ambience.Blackbody
import com.engabd.sendpin.hue.ambience.BrownNoise
import com.engabd.sendpin.hue.ambience.ComfortProfile
import com.engabd.sendpin.hue.ambience.Envelope
import com.engabd.sendpin.hue.ambience.GrainCloud
import com.engabd.sendpin.hue.ambience.OnePole
import com.engabd.sendpin.hue.ambience.PinkNoise
import com.engabd.sendpin.hue.ambience.Rng
import com.engabd.sendpin.hue.ambience.RoomModel
import com.engabd.sendpin.hue.ambience.SlowFollower
import com.engabd.sendpin.hue.ambience.SmoothNoise
import com.engabd.sendpin.hue.ambience.Svf
import com.engabd.sendpin.hue.ambience.VoicePool
import com.engabd.sendpin.hue.ambience.mix
import com.engabd.sendpin.hue.ambience.panGains
import com.engabd.sendpin.hue.ambience.smoothstep
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * A cabin at night with rain on a tin roof.
 *
 * ## What the room does
 *
 * **One warm lamp, and the weather outside.** The lamp lowest in the room is a reading
 * lamp: 2700 K, steady, with a drift of a couple of percent over a minute. Every other
 * lamp is window light, a cool blue-grey that is brighter towards the window end of the
 * room's axis and picks up a little of the reading lamp's warmth when it is close to it.
 *
 * **The glass shimmers with the rain.** Two layers of smooth noise (about a third of a
 * hertz and a little under one) move each window lamp by a few percent, more when the
 * rain is heavy. It is a rippling of the light through running water and never a flicker.
 *
 * **The rain comes from the recording, slowly.** With a recording playing, its level,
 * followed over about three seconds ([SlowFollower]), is the rain's intensity. With none,
 * a slow wander over minutes stands in. Either way the intensity changes over many
 * seconds and never between frames.
 *
 * **Lightning is rare and far away.** Only when the synthesised rain is heavy (which the
 * intensity setting must allow; at the default it never is) a distant flash lights the
 * window side as a soft glow, rising over half a second, and the thunder follows three to
 * eight seconds later as a low rumble. One every one to four minutes at most, in the
 * minutes when it qualifies. The glow is gentle enough to need no burst allowance.
 *
 * The synthesised rain is a dense scatter of bright ticks over a pink wash, a low drum of
 * the roof's resonance, and bigger drops now and then.
 */
class TinRoofRainScript : AmbienceScript {

    override val effect = AmbienceEffect.TIN_ROOF_RAIN

    override val comfort = ComfortProfile(risePerS = 0.20f, fallPerS = 0.20f, roomSwingPerS = 0.08f)

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    private val rng = Rng(0x71_A0_0FL)
    private val renderRng = Rng(0xCA_B1_44L)
    private var nextFlashS = Double.NaN

    private lateinit var lampSeed: IntArray
    private var reading = 0
    private lateinit var warmth: FloatArray
    private var rainSeed = 0

    @Volatile private var recording: AmbienceBedTrack? = null
    private val bedNow = BedSample()
    private val rainFollow = SlowFollower(RAIN_TAU_S, 0.4f)
    private var lastT = Double.NaN

    private val wash = PinkNoise(0x7A_11)
    private val washLp = OnePole()
    private val drum = PinkNoise(0xD2_0A)
    private val drumSvf = Svf()
    private lateinit var ticks: GrainCloud
    private lateinit var drops: GrainCloud
    private val pan = FloatArray(2)
    private var voiced = false

    private class Rumble {
        val noise = BrownNoise(0x81_7C)
        val lp = OnePole()
        var primed = false
        fun reset() { primed = false }
    }

    private val voices = VoicePool(3) { Rumble() }

    override fun bind(room: RoomModel, params: AmbienceParams) {
        this.room = room
        this.params = params
        val n = room.count
        lampSeed = IntArray(n) { (renderRng.nextLong() ushr 33).toInt() }
        rainSeed = (renderRng.nextLong() ushr 33).toInt()
        reading = room.ids.indices.minByOrNull { room.heightOf(room.ids[it]) } ?: 0
        val rid = room.ids.getOrNull(reading)
        warmth = FloatArray(n) { i ->
            if (rid == null || i == reading) 0f else {
                val d = room.gap(room.ids[i], rid)
                1f / (1f + 6f * d * d)
            }
        }
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    override fun bindBed(bed: AmbienceBedTrack?) { this.recording = bed }

    /**
     * How hard it is raining with no recording, 0..1: a wander over a few minutes, scaled
     * by the intensity setting. A pure function of the clock so the scheduler and the
     * sound agree on it without sharing state.
     */
    private fun syntheticRain(tS: Double): Float {
        val intensity = params.intensity.coerceIn(0f, 1f)
        val wander = 0.5f + 0.5f * SmoothNoise.fractal(rainSeed, tS, 1f / 150f, 2, 2.5f)
        return (0.15f + 0.85f * intensity * (0.30f + 0.70f * wander)).coerceIn(0f, 1f)
    }

    override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) {
        if (nextFlashS.isNaN()) nextFlashS = fromS + nextGap()
        while (nextFlashS < toS) {
            // Only in the heaviest rain; otherwise the moment simply passes.
            if (syntheticRain(nextFlashS) >= LIGHTNING_RAIN) {
                val delay = 3f + 5f * rng.f01()
                emit(
                    AmbienceEvent(
                        kind = AmbienceEvent.STRIKE,
                        startS = nextFlashS,
                        env = Envelope(attackS = 0.8f, holdS = 0.1f, decayTauS = 1.1f, tailCut = 0.05f),
                        gain = FLASH_GAIN_MIN + (FLASH_GAIN_MAX - FLASH_GAIN_MIN) * rng.f01(),
                        origin = room.centre(),
                        azimuth = rng.f01(),
                        colour = Rgb(0.80f, 0.86f, 1f),
                        timbre = delay,
                        seed = rng.nextLong(),
                        soundS = delay + 14f,
                        soft = true,
                    ),
                )
            }
            nextFlashS += nextGap()
        }
    }

    private fun nextGap() = FLASH_GAP_MIN_S + (FLASH_GAP_MAX_S - FLASH_GAP_MIN_S) * rng.f01()

    override fun renderLights(tS: Double, live: Array<AmbienceEvent?>, n: Int, out: MutableMap<Int, Rgb>) {
        val dt = if (lastT.isNaN()) 0f else (tS - lastT).coerceIn(0.0, 0.25).toFloat()
        lastT = tS
        val r = recording
        val haveBed = r != null && r.sampleAt(tS, bedNow)
        val target = if (haveBed) bedNow.level else syntheticRain(tS)
        val rain = rainFollow.step(haveBed || r == null, target, dt)

        var flash = 0f
        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e == null || e.kind != AmbienceEvent.STRIKE) continue
            flash += e.levelAt(tS)
        }

        val warm = Blackbody.rgb(2700f)
        for ((i, id) in room.ids.withIndex()) {
            val s = lampSeed[i]
            if (i == reading) {
                val level = READING_LEVEL * (1f + 0.025f * SmoothNoise.fractal(s, tS, 1f / 55f, 2, 2.3f))
                out[id] = Rgb(warm.first * level, warm.second * level, warm.third * level)
                continue
            }
            val axis = room.axisPos[id] ?: 0.5f
            val windowSide = 0.55f + 0.45f * axis
            val shimmer = 0.6f * SmoothNoise.value(s, tS * 0.33) + 0.4f * SmoothNoise.value(s + 9, tS * 0.85)
            val depth = 0.03f + 0.07f * rain
            var level = (WINDOW_BASE + WINDOW_GAIN * rain) * windowSide * (1f + depth * shimmer)
            level += flash * windowSide
            level = level.coerceIn(0f, 1f)
            val colour = mix(WINDOW, warm, 0.40f * warmth[i])
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
            washLp.setCutoff(3200f, sampleRate)
            ticks = GrainCloud(0x71_C4, sampleRate)
            drops = GrainCloud(0xD8_09, sampleRate)
            voiced = true
        }
        val rain = syntheticRain(startS)
        val bodyLevel = 0.02f + 0.07f * rain
        drumSvf.set(210f, 2.5f, sampleRate)
        var i = 0
        while (i < frames) {
            val w = washLp.lp(wash.next()) * bodyLevel
            val hum = drumSvf.bp(drum.next()) * bodyLevel * 0.9f
            // Thousands of small drops on metal: a dense, bright scatter. A tin roof is
            // hard and ringing, which is why the ticks sit high and are so short.
            val t1 = ticks.next(70f + 380f * rain, 3900f, 2.2f) * (0.06f + 0.10f * rain)
            val t2 = drops.next(2f + 9f * rain, 1500f, 7f) * (0.05f + 0.07f * rain)
            val s = w + hum + t1 + t2
            out[i * 2] += s * 0.95f
            out[i * 2 + 1] += s * 1.05f
            i++
        }

        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e == null || e.kind != AmbienceEvent.STRIKE) continue
            val v = voices.voiceFor(e) { it.reset() }
            val entry = voices.entryGain(v, frames)
            if (!v.primed) { v.lp.setCutoff(130f, sampleRate); v.primed = true }
            panGains(sin(2f * PI.toFloat() * e.azimuth), pan)
            var f = 0
            while (f < frames) {
                val age = (startS + f.toDouble() / sampleRate - e.startS).toFloat() - e.timbre
                if (age >= 0f) {
                    // Distant thunder: a long swell of low rumble that rolls away.
                    val env = smoothstep(0f, 2.2f, age) * exp(-age / 4.5f)
                    val s = v.lp.lp(v.noise.next()) * env * 0.35f * entry
                    out[f * 2] += s * pan[0]
                    out[f * 2 + 1] += s * pan[1]
                }
                f++
            }
        }
    }

    private companion object {
        /** Slow, so the recording shapes the mood over seconds and never a frame. */
        const val RAIN_TAU_S = 3f

        /** The synthesised rain must be this heavy before any lightning is allowed. */
        const val LIGHTNING_RAIN = 0.75f
        const val FLASH_GAP_MIN_S = 60.0
        const val FLASH_GAP_MAX_S = 240.0

        /** Perceived lift of the window side at the glow's peak. */
        const val FLASH_GAIN_MIN = 0.06f
        const val FLASH_GAIN_MAX = 0.10f

        const val READING_LEVEL = 0.42f
        const val WINDOW_BASE = 0.10f
        const val WINDOW_GAIN = 0.08f

        /** Cool blue-grey light from outside. */
        val WINDOW = Rgb(0.55f, 0.68f, 0.88f)
    }
}
