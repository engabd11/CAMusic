package com.engabd.sendpin.hue.ambience.scripts

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.Vec3
import com.engabd.sendpin.hue.ambience.AmbienceBedTrack
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceEvent
import com.engabd.sendpin.hue.ambience.AmbienceParams
import com.engabd.sendpin.hue.ambience.AmbienceScript
import com.engabd.sendpin.hue.ambience.BedOnset
import com.engabd.sendpin.hue.ambience.BedSample
import com.engabd.sendpin.hue.ambience.Blackbody
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
import com.engabd.sendpin.hue.ambience.panGains
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin

/**
 * A hearth in the corner of a room you are sitting in.
 *
 * The target is a room you notice is lit by a fire and never a light show. Everything
 * below is in service of that, and most of it is restraint.
 *
 * ## What a real fireplace does to a room
 *
 * **It is a warm, low, steady glow that never stops moving.** Each lamp carries a few
 * octaves of smooth noise from about one cycle in twelve seconds up to two and a half a
 * second, weighted roughly 1/f, so the slow octaves set the mood and the fast ones add
 * life without ever being a strobe. The wander is a pure function of time ([SmoothNoise]),
 * so nothing repeats and nothing needs a random generator on the render thread. On top
 * of that sits a shared drift over minutes, a log settling or a draught finding the
 * chimney, that no single lamp shows on its own.
 *
 * **The lamps are one fire seen from different places.** The noise is mixed through
 * `RoomModel.coupling`, so neighbours brighten together, and the hearth lamps and the far
 * lamps are different light entirely. The lamps nearest the fire are the fire: a deep
 * ember at about 1850 K, bright and steady, because from there you see coals and not
 * flames. The far lamps are the fire's bounce off walls and ceiling: softer, warmer-white
 * at about 2200 K, at a quarter to two fifths of the hearth's brightness, and livelier
 * because a wall catches every flame that moves.
 *
 * **Pops are rare and small.** A fire spits a coal every few seconds, and when it does the
 * nearest lamp or two warm and brighten for a moment and settle back like an ember
 * cooling. They do not surge the room. See [react] and [schedule] for the cadence; the
 * lamps never get more than about a tenth of a unit brighter, over a tenth of a second,
 * and take most of a second to fade.
 *
 * **The recording sets the mood, slowly.** A recorded fire's roar scales the whole room
 * by a few percent over several seconds ([ROAR_TAU_S]). It never reaches a lamp as a
 * flicker of its own, and when the recording has nothing to say the last value is held.
 *
 * All brightness here is perceived (see [com.engabd.sendpin.hue.ambience.Perceptual]);
 * `ComfortLimiter` holds the room to [comfort] so that even a pop landing on a flicker
 * cannot be a jolt.
 */
class FireplaceScript : AmbienceScript {

    override val effect = AmbienceEffect.FIREPLACE

    /**
     * Rise 0.7 a second is a pop's 0.1 over its 0.12 s attack with a little room;
     * fall 0.4 lets that pop cool over about a quarter of a second at the very least
     * and in practice over its own 0.6 to 0.9 s. The room mean may never move faster
     * than 0.25 a second, which a fire at this scale does not come near.
     */
    override val comfort = ComfortProfile(
        risePerS = 0.60f, fallPerS = 0.40f, roomSwingPerS = 0.25f,
        burstRisePerS = 1.6f, burstFallPerS = 0.8f, burstRoomSwingPerS = 0.5f,
    )

    private var popping = false

    /** A pop in its first quarter second is the fire's one named event; the rest is calm. */
    override val burstActive: Boolean get() = popping

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    // Three generators, three threads, no sharing. The scheduler thread owns [rng]; the
    // thread that reacts to a recording owns [reactRng]; the render thread owns
    // [renderRng]. They used to be one stream, advanced from the ambience-gen thread
    // and the render thread at once.
    private val rng = Rng(0xF12E_9L)
    private val reactRng = Rng(0x1F1AEL)

    /**
     * The ear-only spits get a stream of their own, apart from the pops'. Both are
     * scheduled in time order, but a window can hold a pop and a spit in either order,
     * so sharing one generator made the whole show depend on where the audio blocks
     * happened to fall.
     */
    private val spitRng = Rng(0x5917_C0DEL)
    private val renderRng = Rng(0x2E_4D_E6L)

    private var nextPopS = 3.0
    private var nextSpitS = 0.4

    /** Seeds for each lamp's noise, fixed at bind. */
    private lateinit var lampSeed: IntArray

    /** Where the fire is: the lamp lowest in the room, and how much each lamp is "hearth". */
    private var hearth = 0
    private lateinit var hearthShare: FloatArray

    /** Per-lamp scratch for the coupled noise. Render thread only. */
    private lateinit var wander: FloatArray
    private lateinit var coupled: FloatArray

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

    /** The recording, when one is playing. Null on the synthesised path. */
    @Volatile private var recording: AmbienceBedTrack? = null

    /** Scratch for the light tick's read of the bed. Render thread only. */
    private val bedNow = BedSample()

    /** The recording's roar, slowly. Render thread only. */
    private val roarFollow = SlowFollower(ROAR_TAU_S, 0.5f)
    private var lastT = Double.NaN

    /** Reaction state. Whoever feeds [react] only. */
    private var nextReactPopS = Double.NaN

    override fun bind(room: RoomModel, params: AmbienceParams) {
        this.room = room
        this.params = params
        val n = room.count
        lampSeed = IntArray(n) { (renderRng.nextLong() ushr 33).toInt() }
        wander = FloatArray(n)
        coupled = FloatArray(n)
        // The hearth is the lowest lamp — real height where the room recorded it,
        // synthetic otherwise. See RoomModel.heightOf: without the fallback every lamp
        // would be at z=0 and "the fire is on the floor over there" could not be said.
        hearth = room.ids.minByOrNull { room.heightOf(it) } ?: room.ids.firstOrNull() ?: 0
        // 1 at the fire, falling away over about half the room. Kept off zero so even
        // the farthest lamp is partly lit by direct glow in a small room.
        hearthShare = FloatArray(n) { i ->
            val d = room.gap(room.ids[i], hearth)
            (1f / (1f + 6f * d * d)).coerceIn(0f, 1f)
        }
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    // ---- Reacting to a recording -----------------------------------------

    /**
     * A recorded fire already pops; inventing more on top would be two fires.
     *
     * The recording is the sound and the room answers only its strongest crackles, and
     * only every few seconds (see [react]): the rest of the crackle is heard, not seen.
     */
    override val eventsComeFromAudio: Boolean get() = true

    override fun bindBed(bed: AmbienceBedTrack?) { this.recording = bed }

    override fun onBedReset() {
        nextReactPopS = Double.NaN
    }

    /**
     * A strong spit in the recording warms the nearest lamp or two, now and then.
     *
     * Thinned three ways. Only a crackle well over the fire's usual ([MIN_POP_STRENGTH]);
     * only once a random 2.5 to 8 seconds have passed since the last one; and then only
     * a small, slow glow on the lamps beside the hearth (see [renderLights]). A real fire
     * crackles dozens of times a minute and a room that answered each would flicker like
     * a faulty bulb. The ear gets every one of them from the recording regardless.
     */
    override fun react(sample: BedSample, onset: BedOnset, emit: (AmbienceEvent) -> Unit) {
        if (!onset.high || onset.highStrength < MIN_POP_STRENGTH) return
        if (!nextReactPopS.isNaN() && sample.tS < nextReactPopS) return
        nextReactPopS = sample.tS + POP_GAP_MIN_S + (POP_GAP_MAX_S - POP_GAP_MIN_S) * reactRng.f01()
        emit(popEvent(onset.atS, onset.highStrength, reactRng))
    }

    private fun popEvent(startS: Double, strength: Float, r: Rng): AmbienceEvent {
        val hp = room.positions[hearth] ?: room.centre()
        // Around the hearth rather than on it, so successive pops do not always warm
        // exactly the same lamp.
        val spread = 0.10f
        return AmbienceEvent(
            kind = AmbienceEvent.POP,
            startS = startS,
            // A glow with a visible rise, not a spark: 120 ms up, then an ember cooling
            // over most of a second (three time constants to 5 percent of the peak).
            env = Envelope(attackS = 0.12f, holdS = 0.03f, decayTauS = 0.25f, tailCut = 0.05f),
            gain = POP_GAIN_MIN + (POP_GAIN_MAX - POP_GAIN_MIN) * strength.coerceIn(0f, 1f),
            origin = Vec3(
                hp.x + spread * r.bipolar(),
                hp.y + spread * r.bipolar(),
                hp.z + spread * r.bipolar(),
            ),
            azimuth = room.azimuth[hearth] ?: 0.25f,
            colour = POP_TINT,
            timbre = 0.4f + 0.6f * strength.coerceIn(0f, 1f),
            seed = r.nextLong(),
        )
    }

    // ---- Scheduling ------------------------------------------------------

    override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) {
        if (nextPopS < fromS) nextPopS = fromS
        if (nextSpitS < fromS) nextSpitS = fromS
        val intensity = params.intensity.coerceIn(0f, 1f)

        // The pops the room can see: one every 2.5 to 8 seconds, a little more often when
        // the fire is turned up.
        while (nextPopS < toS) {
            emit(popEvent(nextPopS, 0.3f + 0.7f * rng.pow2(), rng))
            nextPopS += POP_GAP_MIN_S +
                (3.0 + 5.0 * (1.0 - intensity)) * rng.f01()
        }

        // The spits only the ear gets. Without a recording the synthesised fire needs
        // its crackle, and it is the *light* that was too busy, not the sound.
        val lambda = 0.5f + 2.5f * intensity
        while (nextSpitS < toS) {
            emit(
                AmbienceEvent(
                    kind = AmbienceEvent.SPIT,
                    startS = nextSpitS,
                    env = Envelope(attackS = 0.004f, holdS = 0.006f, decayTauS = 0.09f),
                    gain = 0.25f + 0.75f * spitRng.pow2(),
                    origin = room.positions[hearth] ?: room.centre(),
                    azimuth = room.azimuth[hearth] ?: 0.25f,
                    colour = POP_TINT,
                    timbre = 0.4f + 0.6f * spitRng.f01(),
                    seed = spitRng.nextLong(),
                ),
            )
            nextSpitS += (-ln(spitRng.f01().coerceAtLeast(1e-6f)) / lambda).toDouble()
        }
    }

    // ---- Lights ----------------------------------------------------------

    override fun renderLights(
        tS: Double,
        live: Array<AmbienceEvent?>,
        n: Int,
        out: MutableMap<Int, Rgb>,
    ) {
        val dt = if (lastT.isNaN()) 0f else (tS - lastT).coerceIn(0.0, 0.25).toFloat()
        lastT = tS
        val intensity = params.intensity.coerceIn(0f, 1f)
        val ids = room.ids

        // 1. The roar: how hard the fire is burning, a few percent either way, slowly.
        //    With a recording it is the recording's level; held, never snapped, when
        //    the recording has nothing for this instant. Without one, the fire's own
        //    slow breathing stands in.
        val r = recording
        val haveBed = r != null && r.sampleAt(tS, bedNow)
        val target = if (haveBed) bedNow.level else 0.5f + 0.5f * SmoothNoise.fractal(ROAR_SEED, tS, 0.03f, 2)
        val roarLevel = ROAR_MIN + (ROAR_MAX - ROAR_MIN) * roarFollow.step(haveBed || r == null, target, dt)

        // 2. The settling: a drift over minutes shared by the whole room.
        val settle = 1f + SETTLE_DEPTH * SmoothNoise.fractal(SETTLE_SEED, tS, 1f / 110f, 2, ratio = 2.2f)

        // 3. Each lamp's own wander, then mixed through the neighbour kernel so the room
        //    breathes as one fire seen from different angles.
        for (i in wander.indices) {
            wander[i] = SmoothNoise.fractal(lampSeed[i], tS, FLICKER_BASE_HZ, FLICKER_OCTAVES, FLICKER_RATIO, FLICKER_ROLLOFF)
        }
        for (i in coupled.indices) {
            var acc = 0f
            val row = room.coupling[i]
            for (j in row.indices) acc += row[j] * wander[j]
            // Mostly the shared wander, a little of the lamp's own, so neighbours move
            // together without being identical.
            coupled[i] = 0.7f * acc + 0.3f * wander[i]
        }

        var nowPopping = false
        val hearthLevel = HEARTH_BASE + HEARTH_GAIN * intensity
        val farLevel = FAR_BASE + FAR_GAIN * intensity
        val depth = 0.8f + 0.4f * intensity

        for ((i, id) in ids.withIndex()) {
            val h = hearthShare[i]
            // Hearth lamps are coals: bright, deep, steady. Far lamps are bounce: soft,
            // paler, and they show every flame that moves.
            val base = farLevel + (hearthLevel - farLevel) * h
            val amp = (FLICKER_FAR + (FLICKER_HEARTH - FLICKER_FAR) * h) * depth
            val slow = 1f + LAMP_DRIFT * SmoothNoise.value(lampSeed[i] + 91, tS / 70.0)
            val level = base * (1f + amp * coupled[i]) * slow * settle * roarLevel

            val colour = Blackbody.rgb(FAR_KELVIN + (HEARTH_KELVIN - FAR_KELVIN) * h)
            var rr = colour.first * level
            var gg = colour.second * level
            var bb = colour.third * level

            // 4. Pops: a small glow on the one or two lamps nearest where the coal spat.
            var pop = 0f
            var k = 0
            while (k < n) {
                val e = live[k]
                k++
                if (e == null || e.kind != AmbienceEvent.POP) continue
                if (e.ageAt(tS) in 0f..0.3f) nowPopping = true
                pop += e.levelAt(tS) * popShare(i, e)
            }
            if (pop > 0f) {
                val p = pop * (0.85f + 0.3f * intensity)
                rr += POP_TINT.first * p
                gg += POP_TINT.second * p
                bb += POP_TINT.third * p
            }
            out[id] = Rgb(rr.coerceIn(0f, 1f), gg.coerceIn(0f, 1f), bb.coerceIn(0f, 1f))
        }
        popping = nowPopping
    }

    /**
     * How much of event [e]'s glow lamp [i] gets: all of it for the lamp nearest the
     * spit, 60 percent for the second nearest, none for anything else.
     */
    private fun popShare(i: Int, e: AmbienceEvent): Float {
        val ids = room.ids
        val mine = room.distanceTo(ids[i], e.origin)
        var closer = 0
        for (j in ids.indices) {
            if (j != i && room.distanceTo(ids[j], e.origin) < mine) closer++
            if (closer >= 2) return 0f
        }
        return if (closer == 0) 1f else 0.6f
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

        // Pops and spits: one impulse each. The pops are also what the lamps glow on, but
        // the audio plays them at full strength regardless of how little the room shows.
        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e == null || (e.kind != AmbienceEvent.POP && e.kind != AmbienceEvent.SPIT)) continue
            // No block-boundary skip: it would make the shared voices' state depend
            // on the buffer size. Gated per sample below instead.
            val v = voices.voiceFor(e) { it.reset() }
            val entry = voices.entryGain(v, frames)
            v.filter.set(700f + 2200f * e.timbre, 4.5f, sampleRate)
            // The light envelope of a pop is a slow glow; the sound is a short tick.
            val env = if (e.kind == AmbienceEvent.POP) POP_SOUND else e.env
            val gain = if (e.kind == AmbienceEvent.POP) 0.25f + 0.75f * e.timbre else e.gain
            var j = 0
            while (j < frames) {
                val age = (startS + j.toDouble() / sampleRate - e.startS).toFloat()
                if (age >= 0f) {
                    val a = env.at(age)
                    if (a > 0f) {
                        // entry: the de-click ramp on a freshly recycled voice.
                        val s = v.filter.bp(v.noise.bipolar()) * a * gain * entry * 0.5f
                        out[j * 2] += s * pan[0]
                        out[j * 2 + 1] += s * pan[1]
                    }
                }
                j++
            }
        }
    }

    private companion object {
        /** Shortest and longest gap between two pops the room shows, in seconds. */
        const val POP_GAP_MIN_S = 2.5
        const val POP_GAP_MAX_S = 8.0

        /** A crackle must be this far over the fire's usual to be worth a glow. */
        const val MIN_POP_STRENGTH = 0.45f

        /** How much brighter, in perceived units, the nearest lamp gets at a pop's peak. */
        const val POP_GAIN_MIN = 0.06f
        const val POP_GAIN_MAX = 0.10f

        /** The sound of a pop is a short tick, however slowly the lamp glows. */
        val POP_SOUND = Envelope(attackS = 0.004f, holdS = 0.006f, decayTauS = 0.09f)

        /**
         * Time constant the recording's roar is followed at. Long on purpose: the fire's
         * mood changes over several seconds, and the per-frame brightness is the noise's
         * business, not the recording's.
         */
        const val ROAR_TAU_S = 3f
        const val ROAR_MIN = 0.94f
        const val ROAR_MAX = 1.06f
        const val ROAR_SEED = 0x6A11
        const val SETTLE_SEED = 0x5E77
        const val SETTLE_DEPTH = 0.05f
        const val LAMP_DRIFT = 0.04f

        /**
         * The flicker's four octaves: about one cycle in twelve seconds, then roughly
         * three times faster each, up to about two and a half a second. Each octave is
         * 0.6 of the last, which is close to 1/f amplitude for a ratio of three.
         */
        const val FLICKER_BASE_HZ = 0.08f
        const val FLICKER_OCTAVES = 4
        const val FLICKER_RATIO = 3.1f
        const val FLICKER_ROLLOFF = 0.75f

        /** Flicker depth, as a fraction of a lamp's own level. Coals are steadier. */
        const val FLICKER_HEARTH = 0.20f
        const val FLICKER_FAR = 0.42f

        /** Perceived brightness of the hearth lamps and the far lamps at intensity 0 and 1. */
        const val HEARTH_BASE = 0.36f
        const val HEARTH_GAIN = 0.26f
        const val FAR_BASE = 0.20f
        const val FAR_GAIN = 0.14f

        /** Colour temperature of the coals and of the bounce, in kelvin. */
        const val HEARTH_KELVIN = 1850f
        const val FAR_KELVIN = 2200f

        /** The glow of a pop: orange-gold, a little paler than the coals. */
        val POP_TINT = Blackbody.rgb(2500f)
    }
}
