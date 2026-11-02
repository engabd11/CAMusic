package com.engabd.sendpin.hue.ambience.scripts

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceEvent
import com.engabd.sendpin.hue.ambience.AmbienceParams
import com.engabd.sendpin.hue.ambience.AmbienceScript
import com.engabd.sendpin.hue.ambience.Blackbody
import com.engabd.sendpin.hue.ambience.ComfortProfile
import com.engabd.sendpin.hue.ambience.Envelope
import com.engabd.sendpin.hue.ambience.GrainCloud
import com.engabd.sendpin.hue.ambience.OnePole
import com.engabd.sendpin.hue.ambience.PinkNoise
import com.engabd.sendpin.hue.ambience.Rng
import com.engabd.sendpin.hue.ambience.RoomModel
import com.engabd.sendpin.hue.ambience.SmoothNoise
import com.engabd.sendpin.hue.ambience.Svf
import com.engabd.sendpin.hue.ambience.VoicePool
import com.engabd.sendpin.hue.ambience.mix
import com.engabd.sendpin.hue.ambience.smoothstep

/**
 * One to three candles in a dark room. The calmest scene there is.
 *
 * ## What a candlelit room does
 *
 * **The flames are the lamps nearest the middle of the room.** One candle for a small
 * setup, two for a medium one, three for a large one: the lamps closest to the centre
 * of the floor plan, lowest first, so the candles sit where a table would. They burn
 * at 1700 to 1850 K, a little redder and dimmer than the fireplace's coals, and that
 * colour temperature drifts by a few tens of kelvin over minutes rather than flickering.
 *
 * **The flicker is a flame's, and smooth.** Each candle carries three octaves of smooth
 * noise ([SmoothNoise], 0.35 to about 3 Hz). The summed noise only swings about a third
 * of its range, so its depth is large: at full intensity the flame reaches the top of the
 * lamp and dips to about half, which is what a candle does across a dark room. The
 * intensity slider runs from a near-still flame to that, and the old, near-invisible
 * flicker is what the bottom of the slider now looks like.
 *
 * **A draught, now and then.** Every one to four minutes a breath of air crosses one
 * candle: for a few seconds its flicker is more than twice as lively and faster, and
 * its level sags a little as the flame leans. It settles back by itself. That is the
 * scene's only event, and it touches one lamp.
 *
 * **Everything else is reflection.** The lamps that are not candles sit at a warm
 * 1850 to 2000 K, brighter the closer they are to a candle, and drift very slowly. They
 * never flicker on their own, since walls do not, but they carry their nearest candle's
 * flicker: the wall is lit by that flame, so when it dips the wall dips with it.
 *
 * The sound is a quiet room, a barely audible wick tick and, with the draught, a soft
 * breath of air.
 */
class CandlelightScript : AmbienceScript {

    override val effect = AmbienceEffect.CANDLELIGHT

    /** A flame dances, so a lamp may move a few units a second; the room far less. */
    override val comfort = ComfortProfile(risePerS = 3.0f, fallPerS = 3.0f, roomSwingPerS = 1.2f)

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    private val rng = Rng(0xC4_D1_E5L)
    private val renderRng = Rng(0x7A_BB_10L)
    private var nextDraughtS = Double.NaN

    private lateinit var lampSeed: IntArray

    /** Lamp indices that are candles. */
    private var candles = IntArray(0)
    private lateinit var candleOf: IntArray
    private lateinit var nearness: FloatArray
    /** Each lamp's nearest candle, as an index into [candles]. */
    private lateinit var nearestCandle: IntArray
    /** Each candle's flicker this frame, as a multiplier on its resting level. */
    private var flicker = FloatArray(0)

    private val pink = PinkNoise(0xCA_4D)
    private val roomLp = OnePole()
    private lateinit var wick: GrainCloud

    private class Breath {
        val filter = Svf()
        val noise = Rng(0x3B_77)
        fun reset() { filter.reset() }
    }

    private val voices = VoicePool(3) { Breath() }
    private var voiced = false

    override fun bind(room: RoomModel, params: AmbienceParams) {
        this.room = room
        this.params = params
        val n = room.count
        lampSeed = IntArray(n) { (renderRng.nextLong() ushr 33).toInt() }
        val want = when {
            n <= 2 -> 1
            n <= 5 -> 2
            else -> 3
        }.coerceAtMost(n)
        // Nearest the middle, with a little weight on being low: a candle sits on a table.
        val c = room.centre()
        val ranked = room.ids.indices.sortedBy { i ->
            val id = room.ids[i]
            room.distanceTo(id, c) + 0.25f * room.heightOf(id)
        }
        candles = ranked.take(want).toIntArray()
        candleOf = IntArray(n) { -1 }
        for ((k, i) in candles.withIndex()) candleOf[i] = k
        nearness = FloatArray(n) { i ->
            var best = 4f
            for (ci in candles) best = minOf(best, room.gap(room.ids[i], room.ids[ci]))
            1f / (1f + 5f * best * best)
        }
        nearestCandle = IntArray(n) { i ->
            candles.indices.minByOrNull { room.gap(room.ids[i], room.ids[candles[it]]) } ?: 0
        }
        flicker = FloatArray(candles.size) { 1f }
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) {
        if (nextDraughtS.isNaN()) nextDraughtS = fromS + nextGap()
        while (nextDraughtS < toS) {
            val k = rng.nextInt(candles.size.coerceAtLeast(1))
            val id = candles.getOrNull(k)?.let { room.ids[it] }
            emit(
                AmbienceEvent(
                    kind = AmbienceEvent.SWELL,
                    startS = nextDraughtS,
                    // A breath: 1.2 s to build, 2.5 s of unrest, then it eases off.
                    env = Envelope(attackS = 1.2f, holdS = 2.5f, decayTauS = 1.4f, tailCut = 0.05f),
                    gain = 1f,
                    origin = id?.let { room.positions[it] } ?: room.centre(),
                    azimuth = id?.let { room.azimuth[it] } ?: 0.25f,
                    colour = Rgb(1f, 0.7f, 0.4f),
                    timbre = k.toFloat(),
                    seed = rng.nextLong(),
                    soundS = 8f,
                ),
            )
            nextDraughtS += nextGap()
        }
    }

    private fun nextGap() = DRAUGHT_MIN_S + (DRAUGHT_MAX_S - DRAUGHT_MIN_S) * rng.f01()

    override fun renderLights(tS: Double, live: Array<AmbienceEvent?>, n: Int, out: MutableMap<Int, Rgb>) {
        val intensity = params.intensity.coerceIn(0f, 1f)
        // How much the draught is unsettling each candle right now, 0..1.
        var unrest0 = 0f
        var unrest1 = 0f
        var unrest2 = 0f
        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e == null || e.kind != AmbienceEvent.SWELL) continue
            val u = e.levelAt(tS)
            when (e.timbre.toInt()) { 0 -> unrest0 += u; 1 -> unrest1 += u; else -> unrest2 += u }
        }

        // The flames first, so the walls can follow them.
        for ((ci, i) in candles.withIndex()) {
            val s = lampSeed[i]
            val unrest = when (ci) { 0 -> unrest0; 1 -> unrest1; else -> unrest2 }.coerceIn(0f, 1f)
            // A draught adds a faster layer on top rather than speeding the noise up,
            // because scaling time would move the whole pattern at once.
            val wander = SmoothNoise.fractal(s, tS, FLAME_BASE_HZ, 3, 2.9f, 0.6f) +
                0.7f * unrest * SmoothNoise.fractal(s + 5, tS, 0.6f, 2, 2f, 0.6f)
            val depth = (FLAME_DEPTH + FLAME_DEPTH_GAIN * intensity) * (1f + 0.5f * unrest)
            // The flame leans away from the draught: a small dip, not a flash.
            val lean = 1f - 0.10f * unrest
            flicker[ci] = ((1f + depth * wander) * lean).coerceIn(0.25f, 1.6f)
        }

        for ((i, id) in room.ids.withIndex()) {
            val s = lampSeed[i]
            val ci = candleOf[i]
            if (ci >= 0) {
                val base = CANDLE_LEVEL
                val slow = 1f + 0.03f * SmoothNoise.value(s + 17, tS / 60.0)
                val level = (base * flicker[ci] * slow).coerceIn(0f, 1f)
                val kelvin = 1775f + 75f * SmoothNoise.value(s + 53, tS / 90.0)
                val c = Blackbody.rgb(kelvin)
                out[id] = Rgb(c.first * level, c.second * level, c.third * level)
            } else {
                val glow = ROOM_LEVEL
                val lift = 0.55f + 0.9f * nearness[i]
                val drift = 1f + 0.08f * SmoothNoise.fractal(s, tS, 1f / 70f, 2, 2.2f)
                val lit = flicker.getOrElse(nearestCandle[i]) { 1f }
                val level = (glow * lift * drift * lit).coerceIn(0f, 1f)
                val c = mix(Blackbody.rgb(2000f), Blackbody.rgb(1850f), nearness[i])
                out[id] = Rgb(c.first * level, c.second * level, c.third * level)
            }
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
            roomLp.setCutoff(260f, sampleRate)
            wick = GrainCloud(0x51_C7, sampleRate)
            voiced = true
        }
        val intensity = params.intensity.coerceIn(0f, 1f)
        val tone = 0.020f + 0.015f * intensity
        var i = 0
        while (i < frames) {
            // A quiet room: a low hum that is barely there, and a wick that ticks now and then.
            val hum = roomLp.lp(pink.next()) * tone
            val tick = wick.next(0.5f + 0.7f * intensity, 3400f, 3f) * 0.025f
            val s = hum + tick
            out[i * 2] += s
            out[i * 2 + 1] += s
            i++
        }

        var j = 0
        while (j < n) {
            val e = live[j]
            j++
            if (e == null || e.kind != AmbienceEvent.SWELL) continue
            val v = voices.voiceFor(e) { it.reset() }
            val entry = voices.entryGain(v, frames)
            var f = 0
            while (f < frames) {
                val age = (startS + f.toDouble() / sampleRate - e.startS).toFloat()
                if (age >= 0f && age < e.soundS) {
                    // A breath: band-limited air whose centre rises and falls with the gust.
                    val env = e.env.at(age)
                    val a = env * 0.03f * entry
                    if (a > 0f) {
                        v.filter.set(500f + 700f * smoothstep(0f, 2f, age) * env, 1.2f, sampleRate)
                        val s = v.filter.bp(v.noise.bipolar()) * a
                        out[f * 2] += s * 0.8f
                        out[f * 2 + 1] += s * 1.2f
                    }
                }
                f++
            }
        }
    }

    private companion object {
        const val DRAUGHT_MIN_S = 60.0
        const val DRAUGHT_MAX_S = 240.0

        /** Perceived brightness of a candle lamp. Intensity moves the flame, never this. */
        const val CANDLE_LEVEL = 0.82f

        /** And of the reflected light, before it is lifted by nearness to a candle. */
        const val ROOM_LEVEL = 0.30f

        /** Flame flicker: slowest octave in Hz, and depth as a fraction of the level. */
        const val FLAME_BASE_HZ = 0.35f
        const val FLAME_DEPTH = 0.25f
        const val FLAME_DEPTH_GAIN = 0.85f
    }
}
