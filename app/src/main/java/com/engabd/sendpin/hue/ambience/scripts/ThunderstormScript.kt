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
import com.engabd.sendpin.hue.ambience.BrownNoise
import com.engabd.sendpin.hue.ambience.Envelope
import com.engabd.sendpin.hue.ambience.OnePole
import com.engabd.sendpin.hue.ambience.PinkNoise
import com.engabd.sendpin.hue.ambience.Rng
import com.engabd.sendpin.hue.ambience.RoomModel
import com.engabd.sendpin.hue.ambience.Svf
import com.engabd.sendpin.hue.ambience.VoicePool
import com.engabd.sendpin.hue.ambience.azimuthOfPan
import com.engabd.sendpin.hue.ambience.panGains
import kotlin.math.PI
import kotlin.math.abs
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
 *
 * ## The gap has to be bridged, not just be correct
 *
 * Physics gets the delay right and still leaves a bad show, which is what made this
 * effect read as two unrelated things happening in one room. You see a flash; six
 * seconds of nothing follow; thunder arrives while the room sits there having long since
 * forgotten it. Correct, and unwatchable — the eye has no reason to connect the two,
 * because by the time the sound lands the light has nothing left to say.
 *
 * So the roll is given a light of its own. [rollEnvelopeFor] is evaluated by the audio
 * to shape the rumble and by [renderLights] to swell the room, from the same event, at
 * the same instant, leaning towards the lamps on the strike's own side. The flash still
 * comes first by exactly the propagation delay; what changed is that the thunder is now
 * something the room does too, so the ear and the eye are describing one storm.
 *
 * The rain does the same trick an octave down: [gustAt] is a pure function of show time
 * that drives the rain's level in the mix *and* the bed's brightness, so a squall
 * arriving is seen and heard together.
 */
class ThunderstormScript(
    override val effect: AmbienceEffect = AmbienceEffect.THUNDERSTORM,
    /**
     * Seeds the strike scheduler. Two storms sharing a seed are the same storm:
     * same strikes, same distances, same times — which is what made the second
     * Fireworks tile a copy of the first before this was parameterised.
     */
    seed: Long = 0x57_0A_11_5EL,
    private val nearBolt: Rgb = NEAR_BOLT,
    private val farBolt: Rgb = FAR_BOLT,
    /** Nearest and furthest a strike may be, in kilometres. See the note at [schedule]. */
    private val minDistKm: Float = 0.15f,
    private val maxDistKm: Float = 2.6f,
    /**
     * How much of the rain is the low thud of drops on a roof over your head versus the
     * wide hiss of rain falling in the open.
     *
     * The one number that separates the two storm tiles' *beds*, the way distance
     * separates their strikes: Thunderstorm II is "more sky than roof", so it gets less
     * of this and its rain sits further away.
     */
    private val roofMix: Float = 0.45f,
    private val rain: Rgb = RAIN,
) : AmbienceScript {

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    /** The scheduler's own stream, advanced only by [schedule], so windows are stable. */
    private val rng = Rng(seed)
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
        /** The tearing hiss just off the front of a close strike; silent for a far one. */
        val sizzle = Svf()
        val rollLp = OnePole()
        /** A second, much lower band. Distant thunder is felt as much as heard. */
        val bodyLp = OnePole()
        val roll = BrownNoise(0x9ABC)
        val noise = Rng(0xDEF0)
        fun reset() { crack.reset(); sizzle.reset(); rollLp.reset(); bodyLp.reset() }
    }

    private val voices = VoicePool(MAX_VOICES) { StrikeVoice() }
    private val pan = FloatArray(2)
    private var voiced = false

    /** Per-lamp light accumulator, so [renderLights] can loop events outside lamps. */
    private var acc = FloatArray(0)

    /** The recording, when one is playing. Null on the synthesised path. */
    @Volatile private var recording: AmbienceBedTrack? = null

    /** Scratch for the light tick's read of the bed. Render thread only. */
    private val bedNow = BedSample()

    /** Reaction state. Analysis thread only. */
    private var lastStrikeS = Double.NaN
    private val reactRng = Rng(seed xor 0x5EED_1E55L)

    override fun bind(room: RoomModel, params: AmbienceParams) {
        this.room = room
        this.params = params
        acc = FloatArray(room.count * 3)
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    // ---- Reacting to a recording -----------------------------------------

    /**
     * A storm *is* its thunder, so when there is a recording of one playing, that
     * recording is the show and inventing a second one alongside it is the bug.
     */
    override val eventsComeFromAudio: Boolean get() = true

    override fun bindBed(bed: AmbienceBedTrack?) { this.recording = bed }

    /**
     * A thunderclap in the recording becomes a strike in the room.
     *
     * The event is stamped at the media position of the audio that caused it and given
     * **no propagation delay at all** — which looks like a betrayal of this script's
     * whole premise and is the opposite. The scripted path had to model the delay
     * because it was inventing a strike that had not happened yet; here the strike
     * already happened, several kilometres away, some seconds ago, and what the room is
     * being asked to do is react to the sound now arriving. Adding a delay on top would
     * hold the flash back from the clap it belongs to for a second time.
     *
     * Everything the scripted path made up is instead read out of the sound:
     *
     * - **how far** — from [BedOnset.lowShare]. Air strips the treble out of a distant
     *   strike, so a clap that arrives with any crack left in it was close and one that
     *   is all rumble was not. The same physics the synth applied on the way out, run
     *   backwards on the way in.
     * - **where** — from the stereo field, so a clap on the left lights the left.
     * - **how hard** — from how far over the storm's own recent level it went, which is
     *   the only honest measure when a recording's absolute level is whatever the
     *   listener set the volume to.
     */
    override fun react(sample: BedSample, onset: BedOnset, emit: (AmbienceEvent) -> Unit) {
        if (!onset.low) return
        if (!lastStrikeS.isNaN() && sample.tS - lastStrikeS < STRIKE_REFRACTORY_S) return
        lastStrikeS = sample.tS

        val far = ((onset.lowShare - NEAR_SHARE) / (FAR_SHARE - NEAR_SHARE)).coerceIn(0f, 1f)
        val distKm = minDistKm + (maxDistKm - minDistKm) * far
        val az = azimuthOfPan(onset.pan)
        val origin = Vec3(
            0.5f + 1.6f * cos(2f * PI.toFloat() * az),
            0.5f + 1.6f * sin(2f * PI.toFloat() * az),
            1f,
        )
        val colour = lerpRgb(nearBolt, farBolt, (distKm / 8f).coerceIn(0f, 1f))
        val gain = (0.35f + 0.65f * onset.lowStrength).coerceIn(0.1f, 1f)
        // A close strike re-strikes its channel; a distant sheet does not. Deterministic
        // in the reaction rng so the same recording lights the room the same way twice.
        val flickers = if (far < 0.35f) 1 + reactRng.nextInt(2) else 1
        var k = 0
        while (k < flickers) {
            emit(
                AmbienceEvent(
                    kind = AmbienceEvent.STRIKE,
                    // Where the clap began, not where it was confirmed — the detector
                    // measures its own delay and hands it back (see BedOnset.atS), so
                    // the flash lands on the attack rather than three hops behind it.
                    startS = onset.atS + k * (0.03 + 0.05 * reactRng.f01()),
                    env = Envelope(attackS = 0.012f, holdS = 0.02f, decayTauS = 0.11f),
                    gain = gain * (1f - 0.25f * k),
                    origin = origin,
                    azimuth = az,
                    colour = colour,
                    timbre = distKm,
                    seed = reactRng.nextLong(),
                    // The recording is the sound. Nothing here has to be synthesised,
                    // and an event claiming otherwise would keep a voice alive for a
                    // strike the synth is never asked to play.
                    soundS = 0f,
                ),
            )
            k++
        }
    }

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
            // Capped at maxDistKm. The flash-to-thunder gap stays physical — that is
            // the whole point of the effect — but a strike eight kilometres out would
            // rumble twenty-three seconds later, by which time three more have flashed
            // and the relationship the effect exists to show is lost on the listener.
            val distKm = minDistKm + (maxDistKm - minDistKm) * rng.pow2()
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
            val colour = lerpRgb(nearBolt, farBolt, (distKm / 8f).coerceIn(0f, 1f))
            // How long this strike is still making sound after its flash: the time the
            // sound takes to get here, plus the whole of the roll. Declared here rather
            // than discovered in renderAudio, because the timeline retires an event on
            // this number — and retiring a strike on its *light* envelope is what left
            // every strike beyond a couple of hundred metres silent. See
            // AmbienceEvent.spanS.
            val soundS = distKm / SPEED_KM_PER_S + rollEnvelopeFor(distKm).lifetimeS
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
                        // Only the first flicker carries the thunder. The others are the
                        // same channel re-striking milliseconds later and are already
                        // inside the first one's crack; giving each its own roll would
                        // treble the rumble of exactly the near strikes that are loudest
                        // to begin with.
                        soundS = if (k == 0) soundS else 0f,
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
        val ids = room.ids
        if (acc.size < ids.size * 3) acc = FloatArray(ids.size * 3)
        val intensity = params.intensity.coerceIn(0f, 1f)

        // 1. The rain bed: a dark, faintly restless blue-grey, breathing with the rain.
        //
        //    With a recording playing, "the rain" means the rain that is audible — the
        //    mid and upper bands of the very samples reaching the speaker — so a squall
        //    gathering in the file brightens the room as it arrives and eases as it
        //    passes. That is the whole of what was being asked for, at the level below
        //    the thunder: the bed is not a loop running alongside the sound, it is the
        //    sound.
        //
        //    Without one it falls back to [gustAt], the synthesised path's own contour,
        //    which drives the rain's level in the mix identically.
        val r = recording
        val haveBed = r != null && r.sampleAt(tS, bedNow)
        val gust = if (haveBed) rainOf(bedNow) else gustAt(tS)
        var i = 0
        while (i < ids.size) {
            val id = ids[i]
            val phase = (tS * 0.37 + id * 0.61).toFloat()
            val shimmer = (0.026f + 0.014f * (0.5f + 0.5f * sin(2f * PI.toFloat() * phase))) *
                (0.75f + 0.5f * gust)
            acc[i * 3] = rain.first * shimmer
            acc[i * 3 + 1] = rain.second * shimmer
            acc[i * 3 + 2] = rain.third * shimmer
            i++
        }

        // 2. The strikes. Events outside, lamps inside — the reverse of the obvious
        //    order, and worth it: everything a strike needs (its nearest lamp, its
        //    propagation delay, its roll shape) is the same answer for every lamp in the
        //    frame, so computing it per lamp did that work `count` times over.
        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e == null || e.kind != AmbienceEvent.STRIKE) continue
            val age = e.ageAt(tS)
            val distKm = e.timbre.coerceAtLeast(0.05f)
            val nearest = nearestDistance(e)

            // 2a. The flash. Lamps nearer the strike light first.
            if (age >= 0f && age < e.env.lifetimeS + room.count * SPREAD_S) {
                var j = 0
                while (j < ids.size) {
                    val id = ids[j]
                    // Measured from the *nearest* lamp, not from the strike. The strike
                    // sits outside the room, so raw distances are large and nearly equal
                    // — a sweep scaled by them would either be imperceptible or, if
                    // scaled up, push the far lamps past the end of the envelope so they
                    // never lit at all. What should set the sweep is the room's own
                    // extent.
                    val d = room.distanceTo(id, e.origin) - nearest
                    // SPREAD_S is tuned so the sweep crosses the room in about 180 ms:
                    // fast enough to read as a direction, slow enough to survive the
                    // 12.5 Hz per-channel ceiling — below that the whole room would
                    // simply flash at once and the direction would be lost. Same
                    // reasoning as SyncoEngine's minimum dwell.
                    val lvl = e.gain * e.env.at(age - d * SPREAD_S) / (1f + 1.4f * d * d)
                    if (lvl > 0f) {
                        // max, not sum: two strikes overlapping should not make the room
                        // brighter than a single close one, they should each still read.
                        val o = j * 3
                        acc[o] = maxOf(acc[o], e.colour.first * lvl)
                        acc[o + 1] = maxOf(acc[o + 1], e.colour.second * lvl)
                        acc[o + 2] = maxOf(acc[o + 2], e.colour.third * lvl)
                    }
                    j++
                }
            }

            // 2b. The roll, seen as well as heard — but only where the roll is being
            //     synthesised. With a recording playing, the rumble the listener can
            //     actually hear is measured rather than modelled, below.
            if (!haveBed) {
                val rollLvl = rollEnvelopeFor(distKm).at(age - distKm / SPEED_KM_PER_S)
                if (rollLvl > 0f) {
                    // The same slow, seeded contour that makes the audible roll wax and
                    // wane rather than decay smoothly — one storm, described twice.
                    val swell = rollLvl * rollContour(e, age) * e.gain * ROLL_GLOW
                    var j = 0
                    while (j < ids.size) {
                        val id = ids[j]
                        val off = turns(room.azimuth[id] ?: 0f, e.azimuth)
                        val side = 0.55f + 0.45f * cos(2f * PI.toFloat() * off)
                        val v = swell * side
                        val o = j * 3
                        acc[o] += e.colour.first * v
                        acc[o + 1] += e.colour.second * v
                        acc[o + 2] += e.colour.third * v
                        j++
                    }
                }
            }
        }

        // 3. The rumble the recording is playing right now, straight off its low end.
        //
        // The flash above answers the *attack* of a clap; this answers the eight seconds
        // of rolling that follow it, which is most of what a storm actually is and what
        // the room used to sit through doing nothing. Measured, not modelled: there is
        // no envelope to get wrong, no distance to guess and nothing to drift, because
        // the number being drawn is the level of the audio in the speaker at the instant
        // it is drawn.
        //
        // Slow and added rather than maxed — a wash under the storm, not a flash
        // competing with one — so it costs nothing against the flash budget. Leaning
        // towards the side the rumble is coming from is what stops it reading as the
        // room simply getting brighter.
        if (haveBed) {
            val swell = ((bedNow.rumble - RUMBLE_FLOOR) / (1f - RUMBLE_FLOOR)).coerceIn(0f, 1f)
            if (swell > 0f) {
                val az = azimuthOfPan(bedNow.pan)
                // Colder as it gets louder: a big roll is a lot of sky lighting up.
                val col = lerpRgb(farBolt, nearBolt, swell)
                val amp = swell * swell * ROLL_GLOW
                var j = 0
                while (j < ids.size) {
                    val off = turns(room.azimuth[ids[j]] ?: 0f, az)
                    val v = amp * (0.6f + 0.4f * cos(2f * PI.toFloat() * off))
                    val o = j * 3
                    acc[o] += col.first * v
                    acc[o + 1] += col.second * v
                    acc[o + 2] += col.third * v
                    j++
                }
            }
        }

        val trim = 0.55f + 0.45f * intensity
        var j = 0
        while (j < ids.size) {
            val o = j * 3
            out[ids[j]] = Rgb(
                (acc[o] * trim).coerceIn(0f, 1f),
                (acc[o + 1] * trim).coerceIn(0f, 1f),
                (acc[o + 2] * trim).coerceIn(0f, 1f),
            )
            j++
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
            roofLpL.setCutoff(300f + 220f * roofMix, sampleRate)
            roofLpR.setCutoff(300f + 220f * roofMix, sampleRate)
            voiced = true
        }
        val intensity = params.intensity.coerceIn(0f, 1f)
        val rainLevel = 0.05f + 0.05f * intensity
        val hissMix = 1f - roofMix

        // 1. The bed. Two independent generators so the rain is not a mono point source
        //    sitting in the middle of the listener's head, and a gust contour on top so
        //    it is weather rather than a noise floor. The contour is a pure function of
        //    absolute show time — never an accumulated phase — so it is identical however
        //    the blocks happen to be cut, and identical to what the lights are reading.
        var i = 0
        while (i < frames) {
            val g = 0.72f + 0.48f * gustAt(startS + i.toDouble() / sampleRate)
            val hissL = hissHpL.hp(rainHiss.next())
            val hissR = hissHpR.hp(rainHiss.next())
            val roofL = roofLpL.lp(rainRoof.next())
            val roofR = roofLpR.lp(rainRoof.next())
            out[i * 2] += (hissL * hissMix + roofL * roofMix) * rainLevel * g
            out[i * 2 + 1] += (hissR * hissMix + roofR * roofMix) * rainLevel * g
            i++
        }

        // 2. The strikes, each delayed by the time its own sound takes to arrive.
        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e == null || e.kind != AmbienceEvent.STRIKE || e.soundS <= 0f) continue
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
        val propS = distKm / SPEED_KM_PER_S      // km at 343 m/s, in seconds
        val heardAt = e.startS + propS

        // All derived, none stored. A near strike is a sharp crack with a short tail; a
        // distant one is all roll and no crack at all.
        val crackEnv = crackEnvelopeFor(distKm)
        val rollEnv = rollEnvelopeFor(distKm)
        val longest = maxOf(crackEnv.lifetimeS, rollEnv.lifetimeS)

        val blockEndS = startS + frames.toDouble() / sampleRate
        if (heardAt + longest < startS || heardAt > blockEndS) return

        panGains(sin(2f * PI.toFloat() * e.azimuth), pan)
        // Air absorbs treble far faster than bass, which is the whole reason distant
        // thunder is a rumble and a near one is a bang. Modelled as two amplitudes and
        // two cutoffs falling at very different rates rather than one gain.
        val crackAmp = e.gain / (1f + 2.2f * distKm * distKm)
        val sizzleAmp = crackAmp * exp(-distKm / 0.5f)
        val rollAmp = e.gain * (0.42f + 0.38f * exp(-distKm / 4f))
        val rollCut = (2200f * exp(-distKm / 1.8f)).coerceAtLeast(70f)
        val bodyCut = (150f * exp(-distKm / 6f)).coerceAtLeast(28f)
        v.rollLp.setCutoff(rollCut, sampleRate)
        v.bodyLp.setCutoff(bodyCut, sampleRate)

        // Envelopes, the filter sweep and the roll contour are stepped per sub-block and
        // interpolated between: 32x fewer exp() calls, and nothing audible on a 12 ms
        // attack.
        var i = 0
        while (i < frames) {
            val chunk = minOf(SUB_BLOCK, frames - i)
            val a0 = (startS + i.toDouble() / sampleRate - heardAt).toFloat()
            val a1 = (startS + (i + chunk).toDouble() / sampleRate - heardAt).toFloat()
            val c0 = crackEnv.at(a0); val c1 = crackEnv.at(a1)
            val r0 = rollEnv.at(a0) * rollContour(e, a0 + propS)
            val r1 = rollEnv.at(a1) * rollContour(e, a1 + propS)
            if (c0 <= 0f && c1 <= 0f && r0 <= 0f && r1 <= 0f) { i += chunk; continue }
            // The crack's band-pass falls from a few kHz to a couple of hundred hertz
            // over the first tens of milliseconds — that downward sweep is what the ear
            // reads as "close and violent" rather than "a burst of noise".
            v.crack.set((3000f * exp(-a0.coerceAtLeast(0f) / 0.04f) + 260f), 1.4f, sampleRate)
            // The leader's tearing hiss, an octave and a half above the crack and gone
            // in a few milliseconds. Only a strike close enough to still have any treble
            // left in it has this at all, which is exactly what makes one sound close.
            v.sizzle.set((7000f * exp(-a0.coerceAtLeast(0f) / 0.012f) + 1800f), 3.0f, sampleRate)
            var j = 0
            while (j < chunk) {
                val f = j.toFloat() / chunk
                val ce = c0 + (c1 - c0) * f
                val re = r0 + (r1 - r0) * f
                val white = v.noise.bipolar()
                var s = v.crack.bp(white) * ce * crackAmp * 0.52f
                if (sizzleAmp > 1e-4f) s += v.sizzle.bp(white) * ce * ce * sizzleAmp * 0.36f
                val brown = v.roll.next()
                s += v.rollLp.lp(brown) * re * rollAmp * 0.40f
                s += v.bodyLp.lp(brown) * re * rollAmp * 0.28f
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
     * Asked once per event per frame rather than once per lamp, which is what hoisting
     * the event loop outside the lamp loop bought: the same answer was being recomputed
     * `count` times a frame, each time walking every lamp. The one-slot cache on top
     * pays for the common case of a single strike in flight across many frames.
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

    /**
     * How hard it is raining, 0..1, from the recording.
     *
     * The upper bands, because that is where rain lives, normalised against how hard it
     * has been raining lately rather than against the analyzer's minute-long AGC — see
     * [BedSample.rain]. Broadband energy would do here instead and should not: it would
     * brighten the room on every thunderclap, which is the flash's job, and count it
     * twice.
     */
    private fun rainOf(s: BedSample): Float = s.rain

    /** Shortest signed separation between two azimuths, in turns: -0.5..0.5. */
    private fun turns(a: Float, b: Float): Float {
        var d = a - b
        while (d > 0.5f) d -= 1f
        while (d < -0.5f) d += 1f
        return d
    }

    private companion object {
        /**
         * Seconds of delay per unit of room distance for the flash sweep.
         *
         * Not physical — light crosses a room instantly. This is a *readable* stand-in:
         * at about 180 ms across the room the eye registers a direction, and it clears
         * the 12.5 Hz per-channel ceiling the bridge imposes. Any faster and the room
         * simply flashes as one.
         */
        const val SPREAD_S = 0.18f

        /** Kilometres a sound covers in a second. The one constant both media divide by. */
        const val SPEED_KM_PER_S = 0.343f

        /**
         * Concurrent strikes with their own voice; beyond this the oldest recycles.
         *
         * Higher than it was, and it had to be: a strike used to be retired from the
         * timeline the moment its flash ended, so at most one or two could ever be in
         * flight. Now that a strike lives until its thunder has finished arriving, a
         * wild storm genuinely has ten of them overlapping — which is what a storm
         * sounds like, and what needs a voice each.
         */
        const val MAX_VOICES = 16

        /** Envelope steps per sub-block. 32 frames is under a millisecond at 48 kHz. */
        const val SUB_BLOCK = 32

        /** How much of the room's brightness the audible roll is allowed to move. */
        const val ROLL_GLOW = 0.16f

        /**
         * How much of a rumble there has to be before the room answers it.
         *
         * Rain has a low end of its own, so without a floor the room would carry a
         * permanent glow that never resolved into anything.
         */
        const val RUMBLE_FLOOR = 0.25f

        /**
         * Shortest gap between two flashes, in seconds.
         *
         * A clap is not one transient — it is an attack and then several seconds of
         * peaks as different parts of the channel arrive — and every one of those peaks
         * is a genuine onset. Flashing on all of them is a strobe, not lightning. The
         * detector's own rising floor does most of the suppression; this catches the
         * rest.
         */
        const val STRIKE_REFRACTORY_S = 0.6


        /**
         * Low-energy share at which a clap reads as overhead, and as far off.
         *
         * A close strike arrives broadband — the crack still has treble in it — so its
         * low share is only a little over half. By a few kilometres the air has taken
         * everything above a few hundred hertz and almost all of what is left is low.
         */
        const val NEAR_SHARE = 0.45f
        const val FAR_SHARE = 0.88f

        val RAIN = Rgb(0.42f, 0.52f, 0.80f)
        val NEAR_BOLT = Rgb(0.70f, 0.80f, 1.00f)
        val FAR_BOLT = Rgb(0.55f, 0.66f, 1.00f)

        /**
         * The crack: what is left of the strike itself by the time it reaches you.
         *
         * Pure and shared, because [renderAudio] and the light side must not be able to
         * disagree about it — the moment one of them keeps its own copy of a shape, the
         * two media are describing different storms again.
         */
        fun crackEnvelopeFor(distKm: Float) =
            Envelope(0.001f, 0.004f, 0.045f + 0.022f * distKm)

        /**
         * The roll, and the reason a strike outlives its flash by so much.
         *
         * A lightning channel is kilometres long, so its sound reaches you over a spread
         * of arrival times rather than at one instant — the near end first, the far end
         * seconds later — and terrain and cloud return more of it after that. The slow
         * attack and the long tail are that spread.
         *
         * `tailCut` is deliberately coarse. The default 1e-3 puts the end of a distant
         * roll seventy seconds out, which is not thunder any more, it is an event the
         * timeline has to carry (and a voice it has to hold) long after it is inaudible
         * under the rain.
         */
        fun rollEnvelopeFor(distKm: Float) = Envelope(
            attackS = 0.22f + 0.45f * distKm,
            holdS = 0.10f,
            decayTauS = 0.75f + 1.15f * distKm,
            tailCut = 0.03f,
        )

        /**
         * The waxing and waning of a roll, in 0..1, as a pure function of the event and
         * its age.
         *
         * Thunder does not decay smoothly; it comes in surges as different parts of the
         * channel and different reflections arrive. Three slow sines at incommensurable
         * rates, phased off the event's own seed, is enough to read as that — and being
         * a pure function of `(seed, age)` it is the same for the audio block and the
         * light tick, which is what lets the room swell on the same surge the ear hears.
         */
        fun rollContour(e: AmbienceEvent, age: Float): Float {
            if (age <= 0f) return 0f
            val p0 = ((e.seed ushr 8) and 0xFFFF).toFloat() / 65536f
            val p1 = ((e.seed ushr 24) and 0xFFFF).toFloat() / 65536f
            val p2 = ((e.seed ushr 40) and 0xFFFF).toFloat() / 65536f
            val w = sin(2f * PI.toFloat() * (age * 0.37f + p0)) * 0.45f +
                sin(2f * PI.toFloat() * (age * 0.83f + p1)) * 0.28f +
                sin(2f * PI.toFloat() * (age * 1.61f + p2)) * 0.17f
            return (0.55f + 0.45f * w).coerceIn(0f, 1f)
        }
    }

    /**
     * The squall contour, 0..1, as a pure function of show time.
     *
     * Rain that never changes is a noise floor; rain that gathers and eases is weather.
     * Read by the audio to set the rain's level and by the lights to set the bed's
     * brightness, from the same `t` — so a gust arriving is one thing the listener both
     * hears and sees, rather than two beds drifting past each other.
     *
     * Absolute time, never an accumulated phase: an accumulator would make the bed a
     * function of how the blocks were cut, which is the bug the whole timeline design
     * exists to avoid.
     */
    private fun gustAt(tS: Double): Float {
        val t = tS.toFloat()
        val w = sin(2f * PI.toFloat() * t * 0.021f) * 0.5f +
            sin(2f * PI.toFloat() * (t * 0.053f + 0.37f)) * 0.3f +
            sin(2f * PI.toFloat() * (t * 0.011f + 0.71f)) * 0.2f
        return (0.5f + 0.5f * w).coerceIn(0f, 1f) * (0.6f + 0.4f * abs(w))
    }
}
