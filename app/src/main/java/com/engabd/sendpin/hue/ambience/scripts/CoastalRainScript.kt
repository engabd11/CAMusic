package com.engabd.sendpin.hue.ambience.scripts

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.RoomTopology
import com.engabd.sendpin.hue.ambience.AmbienceBedTrack
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceEvent
import com.engabd.sendpin.hue.ambience.AmbienceParams
import com.engabd.sendpin.hue.ambience.AmbienceScript
import com.engabd.sendpin.hue.ambience.BedOnset
import com.engabd.sendpin.hue.ambience.BedSample
import com.engabd.sendpin.hue.ambience.BrownNoise
import com.engabd.sendpin.hue.ambience.ComfortProfile
import com.engabd.sendpin.hue.ambience.Envelope
import com.engabd.sendpin.hue.ambience.OnePole
import com.engabd.sendpin.hue.ambience.PinkNoise
import com.engabd.sendpin.hue.ambience.Rng
import com.engabd.sendpin.hue.ambience.RoomModel
import com.engabd.sendpin.hue.ambience.SlowFollower
import com.engabd.sendpin.hue.ambience.SmoothNoise
import com.engabd.sendpin.hue.ambience.VoicePool
import com.engabd.sendpin.hue.ambience.smoothstep
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/**
 * Cool grey rain on the glass, headlights sweeping the wall now and then.
 *
 * The low steady one — the effect to leave on for an evening. Where the storm is
 * defined by its events, this is defined by its base: a cool, near-neutral wash
 * that breathes with the rain's own slow gusts and never once spikes. The Guide
 * Book's rule that people are sensitive to rapid brightness changes is the design
 * constraint here, not a safety afterthought — the base's per-frame movement is
 * kept an order of magnitude under the flash limiter's threshold.
 *
 * Two kinds of event, both rare:
 *
 * - **Sweeps** ([AmbienceEvent.SWEEP], every 20–60 s): a warm wash whose brightest
 *   lamp walks across the room over a few seconds — headlights passing on a wet
 *   road outside. Warm against the cool base is the whole look; silent, because
 *   the sound of a passing car is not this effect's business.
 * - **Strikes** (every 4 minutes or so): the storm's own vocabulary, borrowed at
 *   a third of the brightness and twice the distance. Cold, dim, and with the
 *   propagation delay intact so the roll still arrives after the flash.
 *
 * The audio is a window: rain hiss high, a low surf-like body underneath, and the
 * same slow gust function shaping both the sound and the light's base level, so a
 * squall arriving is seen and heard together — the trick the storm's rain bed
 * already uses, at a gentler tempo.
 */
class CoastalRainScript : AmbienceScript {

    override val effect = AmbienceEffect.COASTAL_RAIN

    /**
     * The calmest scene after the aurora: a third of a unit a second either way, and the
     * room mean at a sixth. The lighthouse's beam, the headlights and the distant glows
     * are all slower than that by construction, so this is a backstop.
     */
    override val comfort = ComfortProfile(risePerS = 0.35f, fallPerS = 0.30f, roomSwingPerS = 0.25f)

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    /** The scheduler's own stream, advanced only by [schedule], so windows are stable. */
    private val rng = Rng(0x5EED_1101L)

    /** The light tick's own stream; it only seeds the per-lamp cloud, at bind. */
    private val renderRng = Rng(0x5EED_C10DL)
    private var lampSeed = IntArray(0)

    /** The recording's level, eased over seconds. Render thread only. */
    private val moodFollow = SlowFollower(MOOD_TAU_S, 0.5f)
    private var lastT = Double.NaN
    private var nextSweepS = 8.0
    private var nextStrikeS = 90.0

    // Audio bed. Owned by the generator thread alone.
    private val rainHiss = PinkNoise(0x11C0)
    private val surfBody = BrownNoise(0x22C0)
    private val hissHpL = OnePole()
    private val hissHpR = OnePole()
    private val bodyLpL = OnePole()
    private val bodyLpR = OnePole()
    private var voiced = false

    /**
     * The strikes audible in the block being rendered, and their voices.
     *
     * Two parallel lists rather than a list of `Pair`s built per block: this is the
     * generator thread, and `clear()` plus adding an existing reference into a
     * pre-sized list allocates nothing. Sized to [VoicePool]'s capacity, so they
     * never grow either. Generator thread only.
     */
    private val strikeEvents = ArrayList<AmbienceEvent>(STRIKE_VOICES)
    private val strikeVoiceRefs = ArrayList<StrikeVoice>(STRIKE_VOICES)

    /** Per-lamp light accumulator, so [renderLights] can loop events outside lamps. */
    private var acc = FloatArray(0)

    /** The recording, when one is playing. Null on the synthesised path. */
    @Volatile private var recording: AmbienceBedTrack? = null

    /** Scratch for the light tick's read of the bed. Render thread only. */
    private val bedNow = BedSample()

    /** Reaction state. Analysis thread only. */
    private var lastStrikeS = Double.NaN
    private val reactRng = Rng(0x5EED_1E55L)

    override fun bind(room: RoomModel, params: AmbienceParams) {
        this.room = room
        this.params = params
        acc = FloatArray(room.count * 3)
        lampSeed = IntArray(room.count) { (renderRng.nextLong() ushr 33).toInt() }
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    // ---- Reacting to a recording -----------------------------------------

    /**
     * A recording of rain on glass *is* the sound; the room still contributes the
     * sweeps, which are the one thing a recording cannot time — they belong to the
     * world outside the window, not to the rain itself.
     */
    override val eventsComeFromAudio: Boolean get() = false

    override fun bindBed(bed: AmbienceBedTrack?) { this.recording = bed }

    /** A rain bed loops; nothing here keeps cross-loop state worth forgetting. */
    override fun onBedReset() = Unit

    /**
     * The recording supplies the room's *level*, not events: the base breathes with
     * what is actually audible rather than with the scripted gusts. Sweeps keep
     * coming from [schedule] — they are on their own clock.
     */
    override fun react(sample: BedSample, onset: BedOnset, emit: (AmbienceEvent) -> Unit) {
        // The bed's level is read per frame in [renderLights]; nothing to do here.
        // A genuine thunderclap in a user's own clip still deserves its moment:
        if (onset.low && onset.lowStrength > 0.55f) {
            val last = lastStrikeS
            if (!last.isNaN() && sample.tS - last < STRIKE_REFRACTORY_S) return
            lastStrikeS = sample.tS
            emit(
                AmbienceEvent(
                    kind = AmbienceEvent.STRIKE,
                    startS = onset.atS,
                    env = STRIKE_GLOW,
                    gain = (0.15f + 0.2f * onset.lowStrength).coerceIn(0.12f, 0.35f),
                    origin = room.centre(),
                    azimuth = reactRng.f01(),
                    colour = COLD_BOLT,
                    timbre = 2.5f + reactRng.f01() * 2f,
                    seed = reactRng.nextLong(),
                    soundS = 4f,
                ),
            )
        }
    }

    // ---- Scheduling ------------------------------------------------------

    override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) {
        if (nextSweepS < fromS) nextSweepS = fromS
        if (nextStrikeS < fromS) nextStrikeS = fromS
        val intensity = params.intensity.coerceIn(0f, 1f)

        // Sweeps: 20-60 s apart, faster when the show is turned up.
        var sweepSweep = nextSweepS
        val sweepLambda = 1.0 / (SWEEP_MIN_GAP_S + (SWEEP_MAX_GAP_S - SWEEP_MIN_GAP_S) * (1.0 - intensity))
        while (sweepSweep < toS) {
            emitSweep(sweepSweep, emit)
            sweepSweep += (-ln(rng.f01().coerceAtLeast(1e-6f)) / sweepLambda).coerceIn(1.0, 120.0)
        }
        nextSweepS = sweepSweep

        // Strikes: rare — about one every four minutes at a typical intensity. The
        // premise is distant weather seen from a window, not a storm overhead.
        var strikeCursor = nextStrikeS
        val strikeLambda = 0.0015 + 0.0038 * intensity
        while (strikeCursor < toS) {
            emitStrike(strikeCursor, emit)
            strikeCursor += (-ln(rng.f01().coerceAtLeast(1e-6f)) / strikeLambda).coerceIn(120.0, 900.0)
        }
        nextStrikeS = strikeCursor
    }

    private fun emitSweep(t: Double, emit: (AmbienceEvent) -> Unit) {
        emit(
            AmbienceEvent(
                kind = AmbienceEvent.SWEEP,
                startS = t,
                // A sweep is a wash arriving and leaving, never a moment: the attack
                // alone spans a second, and the whole event lasts several more.
                env = Envelope(
                    attackS = 1.2f + 0.8f * rng.f01(),
                    holdS = 2.0f + 2.5f * rng.f01(),
                    decayTauS = 1.8f,
                ),
                gain = (0.16f + 0.10f * rng.f01()) * (0.7f + 0.6f * params.intensity.coerceIn(0f, 1f)),
                origin = room.centre(),
                azimuth = rng.f01(),          // which way the car is going; see renderSweep
                colour = HEADLIGHTS,
                seed = rng.nextLong(),
                leadS = 0f,
                soundS = 0f,                  // headlights are silent
            ),
        )
    }

    private fun emitStrike(t: Double, emit: (AmbienceEvent) -> Unit) {
        val az = rng.f01()
        val distKm = 2.5f + 2.0f * rng.f01()
        emit(
            AmbienceEvent(
                kind = AmbienceEvent.STRIKE,
                startS = t,
                env = STRIKE_GLOW,
                gain = (0.16f + 0.14f * rng.f01()) * (0.7f + 0.6f * params.intensity.coerceIn(0f, 1f)),
                origin = room.centre(),
                azimuth = az,
                colour = COLD_BOLT,
                timbre = distKm,
                seed = rng.nextLong(),
                // The roll arrives after the propagation delay, as the storm's own
                // strikes do — the relationship this script borrows intact. The event
                // has to outlive the whole roll, not a guessed 3.5 s: `spanS` is what
                // the timeline collects on, and a roll still sounding when its event
                // is collected stops mid-rumble. Same construction as ThunderstormScript.
                soundS = distKm / SPEED_KM_PER_S + rollEnvelopeFor(distKm).lifetimeS,
            ),
        )
    }

    // ---- Lights ----------------------------------------------------------

    override fun renderLights(
        tS: Double,
        live: Array<AmbienceEvent?>,
        n: Int,
        out: MutableMap<Int, Rgb>,
    ) {
        val ids = room.ids
        if (acc.size < ids.size * 3) acc = FloatArray(ids.size * 3)
        java.util.Arrays.fill(acc, 0f)
        val dt = if (lastT.isNaN()) 0f else (tS - lastT).coerceIn(0.0, 0.25).toFloat()
        lastT = tS
        val intensity = params.intensity.coerceIn(0f, 1f)

        // 1. The overcast: grey-blue, breathing with the rain over tens of seconds.
        //    With a recording playing, the rain that is audible lifts and lowers it a
        //    few percent, eased over seconds and held when the recording has no reading
        //    (never snapped to a default); without one the scripted gusts stand in.
        val r = recording
        val haveBed = r != null && r.sampleAt(tS, bedNow)
        val mood = moodFollow.step(haveBed || r == null, if (haveBed) bedNow.level else gustAt(tS), dt)
        val level = 0.85f + 0.30f * mood
        val base = (BASE_MIN + (BASE_MAX - BASE_MIN) * intensity) * level

        for ((i, id) in ids.withIndex()) {
            val h = room.heightOf(id)
            // Cloud, slowly: each lamp's patch of sky wanders on its own noise, so the
            // overcast has depth and is never perfectly even. Sky through a tall window
            // reads a touch brighter on the higher lamps.
            val cloud = 0.88f + 0.24f * (0.5f + 0.5f * SmoothNoise.fractal(lampSeed[i], tS, 0.02f, 2))
            val v = base * cloud * (0.88f + 0.24f * h)
            out[id] = Rgb(OVERCAST.first * v, OVERCAST.second * v, OVERCAST.third * v)
        }

        // 2. The lighthouse: a beam that crosses the room every 15 to 20 seconds.
        lighthouse(tS, intensity)

        // 3. Events on top: headlights as a travelling warm wash, strikes as a cold glow.
        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e == null) continue
            when (e.kind) {
                AmbienceEvent.SWEEP -> renderSweep(e, tS, acc)
                AmbienceEvent.STRIKE -> renderStrike(e, tS, acc)
            }
        }

        // 4. Fold the accumulators in.
        for ((i, id) in ids.withIndex()) {
            val c = out.getValue(id)
            out[id] = Rgb(
                (c.first + acc[i * 3]).coerceIn(0f, 1f),
                (c.second + acc[i * 3 + 1]).coerceIn(0f, 1f),
                (c.third + acc[i * 3 + 2]).coerceIn(0f, 1f),
            )
        }
    }

    /**
     * A lighthouse across the water, seen on the wall: a soft warm-white beam that
     * crosses the room once per rotation, then is gone until the lamp comes round again.
     *
     * One rotation takes 20 seconds at the lowest intensity and 15 at the highest. In a line
     * the beam occupies the first half of each rotation and the rest is the dark side of the
     * lantern; on a ring it simply goes round. A pure function of the clock, so it needs no events and the
     * audio (which has nothing to say about it) cannot disagree.
     *
     * On a **ring** the beam goes round the room by azimuth, wrapping; in a **line** or a
     * field it runs end to end along the main axis, entering and leaving beyond the ends
     * so no lamp ever sees it appear; in a **cluster** there is no length to cross, so
     * the whole group swells and fades together as the beam passes.
     */
    private fun lighthouse(tS: Double, intensity: Float) {
        val period = LIGHTHOUSE_SLOW_S - (LIGHTHOUSE_SLOW_S - LIGHTHOUSE_FAST_S) * intensity
        val phase = ((tS / period) % 1.0).toFloat()
        val amp = LIGHTHOUSE_MIN + (LIGHTHOUSE_MAX - LIGHTHOUSE_MIN) * intensity
        val ring = room.topology == RoomTopology.RING
        val cluster = room.topology == RoomTopology.CLUSTER
        // Progress through the lit part of the rotation, 0..1, or -1 while dark.
        val lit = if (ring) phase else if (phase < LIGHTHOUSE_LIT) phase / LIGHTHOUSE_LIT else -1f
        if (lit < 0f) return
        for ((i, id) in room.ids.withIndex()) {
            val w = if (cluster) {
                // A swell across the whole group, peaking mid-sweep.
                smoothstep(0f, 0.5f, lit) * (1f - smoothstep(0.5f, 1f, lit))
            } else if (ring) {
                var d = (room.azimuth[id] ?: 0f) - lit
                d -= floor(d + 0.5f)                      // shortest way round
                exp(-(d * d) / (LIGHTHOUSE_WIDTH * LIGHTHOUSE_WIDTH))
            } else {
                // Off both ends: every lamp is out of reach at lit = 0 and lit = 1.
                val head = -LIGHTHOUSE_REACH + lit * (1f + 2f * LIGHTHOUSE_REACH)
                val d = ((room.axisPos[id] ?: 0.5f) - head) / LIGHTHOUSE_WIDTH
                exp(-d * d)
            }
            val v = amp * w
            acc[i * 3] += LIGHTHOUSE_COLOUR.first * v
            acc[i * 3 + 1] += LIGHTHOUSE_COLOUR.second * v
            acc[i * 3 + 2] += LIGHTHOUSE_COLOUR.third * v
        }
    }

    /**
     * A sweep's brightest lamp walks across the room: the wash's centre enters off
     * one side, crosses, and leaves off the other. Distance falloff from the moving
     * centre, warm additive colour.
     *
     * The head must **never wrap**. It used to be `(azimuth + travel) % 1f`, which
     * put the bright spot on the opposite wall in a single frame — `positions` is
     * min-max normalised, so there is always a lamp at x=0 and one at x=1 to catch
     * it. So the travel now runs from [SWEEP_REACH] outside one edge to [SWEEP_REACH]
     * outside the other, where every lamp is already out of range, and the azimuth
     * picks the direction instead of the start — which is what a passing car actually
     * varies.
     */
    private fun renderSweep(e: AmbienceEvent, tS: Double, acc: FloatArray) {
        val age = e.ageAt(tS)
        if (age < 0f || age >= e.env.lifetimeS) return
        val lvl = e.levelAt(tS)
        if (lvl <= 0f) return
        val travel = (age / (e.env.attackS + e.env.holdS + 0.5f)).coerceIn(0f, 1f)
        val span = 1f + 2f * SWEEP_REACH
        val headX = if (e.azimuth < 0.5f) {
            -SWEEP_REACH + travel * span
        } else {
            1f + SWEEP_REACH - travel * span
        }
        for ((i, id) in room.ids.withIndex()) {
            val p = room.positions[id] ?: room.centre()
            val d = kotlin.math.abs(p.x - headX)
            val influence = (1f - (d / SWEEP_REACH).coerceIn(0f, 1f)) * lvl
            if (influence <= 0f) continue
            acc[i * 3] += e.colour.first * influence
            acc[i * 3 + 1] += e.colour.second * influence
            acc[i * 3 + 2] += e.colour.third * influence
        }
    }

    /** A distant strike: a cold, dim wash leaning toward the strike's side of the room. */
    private fun renderStrike(e: AmbienceEvent, tS: Double, acc: FloatArray) {
        val age = e.ageAt(tS)
        if (age < 0f || age >= e.env.lifetimeS) return
        val lvl = e.levelAt(tS)
        if (lvl <= 0f) return
        val cosAz = cos(2f * PI.toFloat() * e.azimuth)
        for ((i, id) in room.ids.withIndex()) {
            val p = room.positions[id] ?: room.centre()
            // Lean toward the strike's side, not a point: distant lightning lights
            // the sky, and the sky has no position in the room.
            val lean = 0.65f + 0.35f * (p.x * cosAz).coerceIn(-1f, 1f)
            acc[i * 3] += e.colour.first * lvl * lean
            acc[i * 3 + 1] += e.colour.second * lvl * lean
            acc[i * 3 + 2] += e.colour.third * lvl * lean
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
            hissHpL.setCutoff(1100f, sampleRate)
            hissHpR.setCutoff(1100f, sampleRate)
            bodyLpL.setCutoff(220f, sampleRate)
            bodyLpR.setCutoff(220f, sampleRate)
            voiced = true
        }
        val intensity = params.intensity.coerceIn(0f, 1f)
        val hissLevel = 0.030f + 0.025f * intensity
        val bodyLevel = 0.020f + 0.020f * intensity

        // The rain sound is steady; the gusts shape its level, the same function the
        // light's base follows, so a squall is seen and heard together.
        val gust0 = gustAt(startS).toDouble()
        val gust1 = gustAt(startS + frames.toDouble() / sampleRate).toDouble()

        // Distant strikes from the schedule get their roll, arriving after the
        // propagation delay exactly as the storm's own do. Collected into two
        // pre-sized scratch lists rather than a fresh list of Pairs: this runs on the
        // generator thread every block, and neither clear() nor adding an existing
        // reference allocates.
        strikeEvents.clear()
        strikeVoiceRefs.clear()
        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e != null && e.kind == AmbienceEvent.STRIKE && e.soundS > 0f) {
                val v = voices.voiceFor(e) { it.reset() }
                // Air absorbs treble long before bass, which is the whole reason
                // distant thunder is a rumble. Without this the roll ran on OnePole's
                // default coefficient — around 5 kHz, essentially unfiltered.
                v.rollLp.setCutoff(rollCutFor(e.timbre), sampleRate)
                // Derived once per block, never per sample: Envelope is a data class
                // and this loop is about to run at the sample rate.
                v.rollEnv = rollEnvelopeFor(e.timbre)
                strikeEvents.add(e)
                strikeVoiceRefs.add(v)
            }
        }

        var i = 0
        while (i < frames) {
            val frac = i.toDouble() / frames
            val gust = (gust0 + (gust1 - gust0) * frac).toFloat()
            val hl = hissLevel * (0.6f + 0.8f * gust)
            val bl = bodyLevel * (0.5f + 1.0f * gust)
            val hissL = hissHpL.hp(rainHiss.next()) * hl
            val hissR = hissHpR.hp(rainHiss.next()) * hl
            val bodyL = bodyLpL.lp(surfBody.next()) * bl
            val bodyR = bodyLpR.lp(surfBody.next()) * bl
            var l = hissL + bodyL
            var r = hissR + bodyR
            var vi = 0
            while (vi < strikeEvents.size) {
                val e = strikeEvents[vi]
                val v = strikeVoiceRefs[vi]
                vi++
                val age = (startS + i.toDouble() / sampleRate - e.startS).toFloat()
                val heard = age - e.timbre / SPEED_KM_PER_S  // the light comes first
                if (heard < 0f) continue
                // Enveloped, not gated. It used to switch on at full amplitude the
                // moment the delay elapsed and stop dead when the timeline collected
                // the event — a click at each end of every roll, on an effect meant to
                // run all evening.
                val re = v.rollEnv.at(heard)
                if (re <= 0f) continue
                val roll = v.rollLp.lp(v.roll.next()) * 1.4f
                val s = roll * re * 0.10f * e.gain
                l += s * (1f - e.azimuth)
                r += s * e.azimuth
            }
            out[i * 2] += l
            out[i * 2 + 1] += r
            i++
        }
    }

    /**
     * Slow gusts over the show, a pure function of time — no state, so the light
     * tick and the audio block agree without synchronising.
     */
    private fun gustAt(tS: Double): Float {
        val t = tS.toFloat()
        return (
            0.5f + 0.25f * sin(2f * PI.toFloat() * t * 0.021f) +
                0.25f * sin(2f * PI.toFloat() * t * 0.007f + 1.3f)
            ).coerceIn(0f, 1f)
    }

    /** Per strike, so two overlapping ones cannot share filter state. See [VoicePool]. */
    private class StrikeVoice {
        val rollLp = OnePole()
        val roll = BrownNoise(0x9ABC)
        /** Set per block from the event's distance; see [rollEnvelopeFor]. */
        var rollEnv: Envelope = SILENT_ROLL
        fun reset() { rollLp.reset() }
    }

    private val voices = VoicePool(STRIKE_VOICES) { StrikeVoice() }

    private companion object {
        const val SPEED_KM_PER_S = 0.343f
        const val STRIKE_REFRACTORY_S = 20.0
        const val STRIKE_VOICES = 4

        /** Sweeps land between 20 and 60 s apart at full intensity. */
        const val SWEEP_MIN_GAP_S = 20.0
        const val SWEEP_MAX_GAP_S = 60.0

        /**
         * How far outside the room a sweep's head starts and ends, in normalised x.
         *
         * Doubles as the falloff radius, which is the point: at the ends of the
         * travel every lamp is at least this far from the head, so the influence is
         * already zero and the head can appear and disappear without a step.
         */
        const val SWEEP_REACH = 0.40f

        /** Until a voice is given a real one. Zero-length, so it sounds nothing. */
        val SILENT_ROLL = Envelope(attackS = 0f, holdS = 0f, decayTauS = 1e-4f)

        /**
         * The roll's shape, sized to fit inside the window [emitStrike] then buys it.
         *
         * `soundS` is set from this envelope's own [Envelope.lifetimeS], so the roll
         * always fades to its tail before the timeline collects the event — the two
         * cannot drift apart. Slow in and slow out: a lightning channel kilometres
         * long is heard over a spread of arrival times, not at one instant.
         */
        fun rollEnvelopeFor(distKm: Float) = Envelope(
            attackS = 0.55f + 0.22f * distKm,
            holdS = 0.20f,
            decayTauS = 0.60f + 0.18f * distKm,
            tailCut = 0.02f,
        )

        /**
         * Where the roll's treble is gone by, for a strike [distKm] away.
         *
         * At this effect's 2.5–4.5 km that lands between roughly 550 and 180 Hz —
         * a rumble, which is the only kind of thunder a distant storm has left.
         */
        fun rollCutFor(distKm: Float): Float =
            (2200f * exp(-distKm.coerceAtLeast(0f) / 1.8f)).coerceAtLeast(70f)

        /** The base's cool lean, and the two event colours against it. */
        val COLD_BOLT = Rgb(0.74f, 0.82f, 1.00f)

        /**
         * A distant strike through cloud: a quarter of a second up and a long ease away.
         * Sudden enough to notice, slow enough never to be a flash.
         */
        val STRIKE_GLOW = Envelope(attackS = 0.25f, holdS = 0.10f, decayTauS = 0.60f, tailCut = 0.03f)

        /** Time constant the recording's level is followed at. */
        const val MOOD_TAU_S = 3f

        /** Perceived brightness of the overcast, at intensity 0 and 1. */
        const val BASE_MIN = 0.12f
        const val BASE_MAX = 0.20f

        /** The lighthouse: seconds per rotation at intensity 0 and 1, and the share of it that is lit. */
        const val LIGHTHOUSE_SLOW_S = 20f
        const val LIGHTHOUSE_FAST_S = 15f
        const val LIGHTHOUSE_LIT = 0.5f
        const val LIGHTHOUSE_WIDTH = 0.20f
        const val LIGHTHOUSE_REACH = 0.40f
        const val LIGHTHOUSE_MIN = 0.14f
        const val LIGHTHOUSE_MAX = 0.22f

        /** Grey-blue overcast: cool, barely saturated. */
        val OVERCAST = Rgb(0.66f, 0.76f, 0.90f)
        val LIGHTHOUSE_COLOUR = Rgb(1.00f, 0.94f, 0.78f)
        val HEADLIGHTS = Rgb(1.00f, 0.72f, 0.45f)
    }
}
