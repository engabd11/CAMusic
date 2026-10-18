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
import com.engabd.sendpin.hue.ambience.ComfortProfile
import com.engabd.sendpin.hue.ambience.Envelope
import com.engabd.sendpin.hue.ambience.OnePole
import com.engabd.sendpin.hue.ambience.PinkNoise
import com.engabd.sendpin.hue.ambience.Rng
import com.engabd.sendpin.hue.ambience.RoomModel
import com.engabd.sendpin.hue.ambience.SlowFollower
import com.engabd.sendpin.hue.ambience.SmoothNoise
import com.engabd.sendpin.hue.ambience.Svf
import com.engabd.sendpin.hue.ambience.VoicePool
import com.engabd.sendpin.hue.ambience.azimuthOfPan
import com.engabd.sendpin.hue.ambience.panGains
import com.engabd.sendpin.hue.ambience.smoothstep
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Rain, and lightning that arrives before its thunder.
 *
 * **The worked example of the whole design.** A strike is [AmbienceEvent]s. The lights
 * read them at `startS`; the audio reads the same object at `startS + distance / 343 m/s`.
 * Nothing synchronises the flash and the crack, because there is nothing to synchronise —
 * they are two projections of one cause, and the gap between them falls out of the
 * event's own [AmbienceEvent.timbre], which is how far away the strike was in kilometres.
 *
 * ## The room, as a storm outside a window
 *
 * **Between strikes** the room is dim blue-grey, the colour of rain light, with a slow
 * drift of cloud passing along the room's long axis over tens of seconds. It is
 * deliberately close to dark: the lightning is the only bright thing in the scene, and
 * it can only be that if the base is not.
 *
 * **A near strike is white, sharp and directional.** Two to four return strokes of 40 to
 * 120 ms with dark gaps between, brightest on the lamps nearest the side it came from.
 * Near strikes are the rare, startling ones, and they are only allowed at higher
 * intensities (see [nearChance]); at the default the storm is a distant one.
 *
 * **A far strike is a soft lavender glow "through cloud".** No stroke, no direction: the
 * whole sky brightens over a quarter of a second and eases away over a second or two.
 * This is most of what a storm seen from indoors actually is, and it is comfortable
 * enough to leave on.
 *
 * ## The light leads the thunder
 *
 * Physics puts lightning ahead of its thunder by about three seconds a kilometre. With a
 * recorded bed the session hands this script each clap a few seconds before it is heard
 * (see [BedOnset.aheadS]) and the strike is stamped one to three seconds early, by the
 * distance the sound implies. A user's own clip is analysed live and can only flash level
 * with its thunder, as before. The scheduled (synthesised) path always led it by exactly
 * the propagation delay.
 *
 * ## The gap has to be bridged, not just be correct
 *
 * Physics gets the delay right and still leaves a bad show, which is what made this
 * effect read as two unrelated things happening in one room. So the roll is given a light
 * of its own: a faint, slow swell, leaning towards the strike's side, that follows the
 * rumble. It is a hint, a few units of brightness at most, not a second flash.
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

    /**
     * Calm limits for the rain, the cloud and the far glows; burst limits for a near
     * strike's strokes, which are the one thing in the scene allowed to be sudden.
     * 0.8 a second takes a far glow's 0.3 peak over about 0.4 s however abruptly the
     * script asks for it. The burst fall (20 a second) is what lets the dark gap between
     * two strokes actually be dark.
     */
    override val comfort = ComfortProfile(
        risePerS = 0.80f, fallPerS = 0.50f, roomSwingPerS = 0.50f,
        burstRisePerS = 40f, burstFallPerS = 20f, burstRoomSwingPerS = 80f,
    )

    private var burst = false
    override val burstActive: Boolean get() = burst

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    // One generator per thread. The scheduler thread owns [rng], whoever feeds [react]
    // owns [reactRng], and the light tick owns [renderRng]; they were one stream before.
    private val rng = Rng(seed)
    private val reactRng = Rng(seed xor 0x5EED_1E55L)
    private val renderRng = Rng(seed xor 0x0C10_0D5L)
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

    /** Seeds for the cloud noise, fixed at bind from [renderRng]. */
    private var cloudSeed = 0
    private var lampSeed = IntArray(0)

    /** The recording, when one is playing. Null on the synthesised path. */
    @Volatile private var recording: AmbienceBedTrack? = null

    /** Scratch for the light tick's read of the bed. Render thread only. */
    private val bedNow = BedSample()

    /** The rain's and the rumble's levels from the recording, eased over seconds. */
    private val rainFollow = SlowFollower(RAIN_TAU_S, 0.5f)
    private val rumbleFollow = SlowFollower(RUMBLE_TAU_S, 0f)
    private var lastT = Double.NaN

    /** Reaction state. Whoever feeds [react] only. */
    private var lastStrikeS = Double.NaN

    override fun bind(room: RoomModel, params: AmbienceParams) {
        this.room = room
        this.params = params
        acc = FloatArray(room.count * 3)
        cloudSeed = (renderRng.nextLong() ushr 33).toInt()
        lampSeed = IntArray(room.count) { (renderRng.nextLong() ushr 33).toInt() }
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    // ---- Reacting to a recording -----------------------------------------

    /**
     * A storm *is* its thunder, so when there is a recording of one playing, that
     * recording is the show and inventing a second one alongside it is the bug.
     */
    override val eventsComeFromAudio: Boolean get() = true

    override fun bindBed(bed: AmbienceBedTrack?) { this.recording = bed }

    /** The bed jumped: forget the previous pass's last strike, or its refractory swallows this loop's onsets. */
    override fun onBedReset() {
        lastStrikeS = Double.NaN
    }

    /**
     * A thunderclap in the recording becomes a strike in the room, ahead of the clap
     * when the recording has been analysed in advance.
     *
     * Everything the scripted path made up is instead read out of the sound:
     *
     * - **how far** — from [BedOnset.lowShare]. Air strips the treble out of a distant
     *   strike, so a clap that arrives with any crack left in it was close and one that
     *   is all rumble was not.
     * - **where** — from the stereo field, so a clap on the left lights the left.
     * - **how hard** — from how far over the storm's own recent level it went.
     *
     * The flash is stamped `lead` seconds before the clap: about three seconds a
     * kilometre, clamped to one to three, and never earlier than the analysis lets it
     * ([BedOnset.aheadS]). With live analysis `aheadS` is zero, so the flash lands on the
     * clap's attack, as it always did.
     */
    override fun react(sample: BedSample, onset: BedOnset, emit: (AmbienceEvent) -> Unit) {
        if (!onset.low) return
        if (!lastStrikeS.isNaN() && sample.tS - lastStrikeS < STRIKE_REFRACTORY_S) return
        lastStrikeS = sample.tS

        val far = ((onset.lowShare - NEAR_SHARE) / (FAR_SHARE - NEAR_SHARE)).coerceIn(0f, 1f)
        val distKm = minDistKm + (maxDistKm - minDistKm) * far
        // Near strikes are for high intensities; otherwise a close clap is drawn as the
        // soft glow of a far one. Its distance is not changed: that is what the thunder
        // says, and the lead below still follows it.
        val soft = distKm >= NEAR_KM || reactRng.f01() > nearChance(params.intensity)
        val lead = leadFor(distKm, onset.aheadS)
        emitStrike(
            groundS = onset.atS - lead,
            distKm = distKm,
            az = azimuthOfPan(onset.pan),
            strength = onset.lowStrength,
            withSound = false,
            soft = soft,
            r = reactRng,
            emit = emit,
        )
    }

    /** One to three seconds before the thunder, by distance, and never before the plan allows. */
    private fun leadFor(distKm: Float, aheadS: Float): Double {
        val want = (distKm * LEAD_S_PER_KM).coerceIn(LEAD_MIN_S, LEAD_MAX_S)
        // 0.25 s of margin: the event has to reach the timeline before it starts.
        return want.coerceAtMost((aheadS - 0.25f).coerceAtLeast(0f)).toDouble()
    }

    // ---- Scheduling ------------------------------------------------------

    override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) {
        if (nextStrikeS < fromS) nextStrikeS = fromS
        val intensity = params.intensity.coerceIn(0f, 1f)
        // Strikes per second. A quiet storm is one every fifty seconds; the default is
        // one every fourteen or so; a wild one is one every eight. Real storms seen from
        // indoors are far slower than the old one every four seconds.
        val lambda = 0.02f + 0.10f * intensity
        while (nextStrikeS < toS) {
            val az = rng.f01()
            // pow2 biases towards zero, so *near* strikes are the rare ones — which is
            // what makes the occasional close one feel like an event.
            // Capped at maxDistKm. The flash-to-thunder gap stays physical — that is
            // the whole point of the effect — but a strike eight kilometres out would
            // rumble twenty-three seconds later, by which time three more have flashed
            // and the relationship the effect exists to show is lost on the listener.
            var distKm = minDistKm + (maxDistKm - minDistKm) * rng.pow2()
            if (distKm < NEAR_KM && rng.f01() > nearChance(intensity)) {
                distKm = NEAR_KM + (maxDistKm.coerceAtLeast(NEAR_KM + 0.5f) - NEAR_KM) * rng.f01()
            }
            emitStrike(nextStrikeS, distKm, az, strength = 1f, withSound = true, soft = distKm >= NEAR_KM, r = rng, emit = emit)
            // Poisson gaps, with a floor. A fixed cadence reads as a machine, not weather,
            // and two strikes inside a few seconds read as one very busy one.
            nextStrikeS += (-ln(rng.f01().coerceAtLeast(1e-6f)) / lambda).coerceAtLeast(MIN_GAP_S.toFloat()).toDouble()
        }
    }

    /**
     * How likely a strike drawn near is to *stay* near: never below 0.55 intensity,
     * always above 0.85. Near strikes are sudden by nature, so they are the ones the
     * listener's intensity setting has to opt in to.
     */
    private fun nearChance(intensity: Float): Float = smoothstep(0.55f, 0.85f, intensity)

    /**
     * One strike, as light (and, on the scheduled path, as the sound that will follow).
     *
     * A near strike is two to four return strokes, each its own event with a dark gap
     * after it; only the first carries the thunder, because the others are the same
     * channel re-striking milliseconds later and are already inside the first one's
     * crack. A far strike is one soft glow, occasionally two.
     *
     * [groundS] is when the first light appears. The thunder follows by
     * `distKm / 0.343` seconds, which the audio computes from [AmbienceEvent.timbre].
     */
    private fun emitStrike(
        groundS: Double,
        distKm: Float,
        az: Float,
        strength: Float,
        withSound: Boolean,
        soft: Boolean,
        r: Rng,
        emit: (AmbienceEvent) -> Unit,
    ) {
        val origin = Vec3(
            0.5f + 1.6f * cos(2f * PI.toFloat() * az),
            0.5f + 1.6f * sin(2f * PI.toFloat() * az),
            1f,
        )
        val near = !soft
        val colour = if (near) nearBolt else farBolt
        val gain = (1.15f / (1f + distKm)).coerceIn(0.08f, 1f) * (0.55f + 0.45f * strength.coerceIn(0f, 1f))
        // How long this strike is still making sound after its flash: the time the
        // sound takes to get here, plus the whole of the roll. Declared here rather than
        // discovered in renderAudio, because the timeline retires an event on this
        // number — and retiring a strike on its *light* envelope is what left every
        // strike beyond a couple of hundred metres silent. See AmbienceEvent.spanS.
        val soundS = if (withSound) distKm / SPEED_KM_PER_S + rollEnvelopeFor(distKm).lifetimeS else 0f

        if (near) {
            val strokes = if (r.f01() < 0.2f) 4 else if (r.f01() < 0.5f) 3 else 2
            var at = groundS
            var k = 0
            while (k < strokes) {
                val hold = 0.015f + 0.035f * r.f01()
                val env = Envelope(attackS = 0.006f, holdS = hold, decayTauS = 0.010f + 0.010f * r.f01(), tailCut = 0.03f)
                emit(
                    AmbienceEvent(
                        kind = AmbienceEvent.STRIKE,
                        startS = at,
                        env = env,
                        // Successive return strokes are weaker, as they are in a real
                        // channel; the first carries the thunder's loudness too.
                        gain = gain * (1f - 0.22f * k),
                        origin = origin,
                        azimuth = az,
                        colour = colour,
                        timbre = distKm,
                        seed = r.nextLong(),
                        soundS = if (k == 0) soundS else 0f,
                        soft = soft,
                    ),
                )
                // The next stroke starts after this one has finished and a dark gap has
                // passed: 60 to 180 ms, so a stroke group never flickers faster than
                // about 5 Hz and each stroke reads as a stroke.
                at += env.lifetimeS + STROKE_GAP_MIN_S + (STROKE_GAP_MAX_S - STROKE_GAP_MIN_S) * r.f01()
                k++
            }
        } else {
            val glows = if (r.f01() < 0.3f) 2 else 1
            var at = groundS
            var k = 0
            while (k < glows) {
                emit(
                    AmbienceEvent(
                        kind = AmbienceEvent.STRIKE,
                        startS = at,
                        // Through cloud: nothing sudden about it. A quarter of a second
                        // up and a second or two of easing away.
                        env = Envelope(attackS = 0.22f + 0.12f * r.f01(), holdS = 0.05f, decayTauS = 0.55f + 0.35f * r.f01(), tailCut = 0.03f),
                        gain = gain * (1f - 0.35f * k),
                        origin = origin,
                        azimuth = az,
                        colour = colour,
                        timbre = distKm,
                        seed = r.nextLong(),
                        soundS = if (k == 0) soundS else 0f,
                        soft = soft,
                    ),
                )
                at += 0.5 + 0.4 * r.f01()
                k++
            }
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
        val dt = if (lastT.isNaN()) 0f else (tS - lastT).coerceIn(0.0, 0.25).toFloat()
        lastT = tS
        val intensity = params.intensity.coerceIn(0f, 1f)

        // How hard the storm hits follows the intensity: a gentle setting is a gentler
        // sky, not merely a rarer one. Applied to everything but the dim base.
        val trim = 0.6f + 0.4f * intensity

        // 1. The rain and the cloud.
        //
        //    Dim blue-grey with cloud drifting along the room's long axis, so a bright
        //    patch of sky passes from one end of the room to the other over a minute or
        //    two. A recorded bed's rain level, followed over seconds, lifts and lowers
        //    the whole thing a little; with no reading the last value is held.
        val r = recording
        val haveBed = r != null && r.sampleAt(tS, bedNow)
        val rainNow = rainFollow.step(haveBed || r == null, if (haveBed) bedNow.rain else gustAt(tS), dt)
        val rumbleNow = rumbleFollow.step(haveBed || r == null, if (haveBed) bedNow.rumble else 0f, dt)
        val rainMod = 0.85f + 0.30f * rainNow
        val base = (BASE_MIN + (BASE_MAX - BASE_MIN) * (0.7f + 0.3f * intensity)) * rainMod
        var i = 0
        while (i < ids.size) {
            val axis = room.axisPos[ids[i]] ?: 0.5f
            // The patch: noise sampled at a point that slides along the axis with time.
            val patch = 0.5f + 0.5f * SmoothNoise.value(cloudSeed, tS * CLOUD_SPEED - axis * CLOUD_SPAN)
            val own = 0.5f + 0.5f * SmoothNoise.fractal(lampSeed[i], tS, 0.03f, 2)
            val v = base * (0.45f + 0.55f * (0.6f * patch + 0.4f * own))
            acc[i * 3] = rain.first * v
            acc[i * 3 + 1] = rain.second * v
            acc[i * 3 + 2] = rain.third * v
            i++
        }

        // 2. The strikes. Events outside, lamps inside — the reverse of the obvious
        //    order, and worth it: everything a strike needs (its nearest lamp, its
        //    propagation delay, its roll shape) is the same answer for every lamp in the
        //    frame, so computing it per lamp did that work `count` times over.
        var bursting = false
        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e == null || e.kind != AmbienceEvent.STRIKE) continue
            val age = e.ageAt(tS)
            val distKm = e.timbre.coerceAtLeast(0.05f)
            val isNear = !e.soft

            // 2a. The flash.
            if (age >= 0f && age < e.env.lifetimeS + room.count * SPREAD_S) {
                if (isNear) {
                    bursting = true
                    val nearest = nearestDistance(e)
                    var j = 0
                    while (j < ids.size) {
                        // Measured from the *nearest* lamp, not from the strike. The strike
                        // sits outside the room, so raw distances are large and nearly equal
                        // — what should set the sweep and the falloff is the room's own
                        // extent. Lamps on the strike's side flash first and brightest.
                        val d = room.distanceTo(ids[j], e.origin) - nearest
                        val lvl = trim * NEAR_PEAK * e.gain * e.env.at(age - d * SPREAD_S) / (1f + 1.4f * d * d)
                        if (lvl > 0f) {
                            // max, not sum: two strokes overlapping should not make the room
                            // brighter than a single close one, they should each still read.
                            val o = j * 3
                            acc[o] = maxOf(acc[o], e.colour.first * lvl)
                            acc[o + 1] = maxOf(acc[o + 1], e.colour.second * lvl)
                            acc[o + 2] = maxOf(acc[o + 2], e.colour.third * lvl)
                        }
                        j++
                    }
                } else {
                    // Through cloud: the whole sky, leaning very slightly towards its
                    // own side. No stroke, no direction to speak of.
                    val g = trim * FAR_PEAK * (0.5f + 0.5f * e.gain / 0.6f).coerceAtMost(1f) * e.env.at(age)
                    if (g > 0f) {
                        var j = 0
                        while (j < ids.size) {
                            val off = turns(room.azimuth[ids[j]] ?: 0f, e.azimuth)
                            val side = 0.82f + 0.18f * cos(2f * PI.toFloat() * off)
                            val v = g * side
                            val o = j * 3
                            acc[o] += e.colour.first * v
                            acc[o + 1] += e.colour.second * v
                            acc[o + 2] += e.colour.third * v
                            j++
                        }
                    }
                }
            }

            // 2b. The roll, seen as well as heard — but only where it is synthesised, and
            //     only as a faint swell. With a recording playing the rumble that is
            //     actually audible is followed instead (below).
            if (recording == null) {
                val rollLvl = rollEnvelopeFor(distKm).at(age - distKm / SPEED_KM_PER_S)
                if (rollLvl > 0f) {
                    val swell = trim * rollLvl * rollContour(e, age) * e.gain * ROLL_GLOW * (0.6f + 0.4f * intensity)
                    var j = 0
                    while (j < ids.size) {
                        val off = turns(room.azimuth[ids[j]] ?: 0f, e.azimuth)
                        val side = 0.55f + 0.45f * cos(2f * PI.toFloat() * off)
                        val v = swell * side
                        val o = j * 3
                        acc[o] += farBolt.first * v
                        acc[o + 1] += farBolt.second * v
                        acc[o + 2] += farBolt.third * v
                        j++
                    }
                }
            }
        }
        burst = bursting

        // 3. The rumble the recording is playing, followed over seconds: a hint that the
        //    sky is busy, not a flash. Measured rather than modelled, but nowhere near
        //    fast enough to be mistaken for a stroke.
        if (recording != null) {
            val swell = ((rumbleNow - RUMBLE_FLOOR) / (1f - RUMBLE_FLOOR)).coerceIn(0f, 1f)
            if (swell > 0f) {
                val az = azimuthOfPan(bedNow.pan)
                val amp = trim * swell * swell * ROLL_GLOW * (0.6f + 0.4f * intensity)
                var j = 0
                while (j < ids.size) {
                    val off = turns(room.azimuth[ids[j]] ?: 0f, az)
                    val v = amp * (0.6f + 0.4f * cos(2f * PI.toFloat() * off))
                    val o = j * 3
                    acc[o] += farBolt.first * v
                    acc[o + 1] += farBolt.second * v
                    acc[o + 2] += farBolt.third * v
                    j++
                }
            }
        }

        var j = 0
        while (j < ids.size) {
            val o = j * 3
            out[ids[j]] = Rgb(
                acc[o].coerceIn(0f, 1f),
                acc[o + 1].coerceIn(0f, 1f),
                acc[o + 2].coerceIn(0f, 1f),
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
         * at about 40 ms a unit the near lamp leads the far one by a stroke's width or
         * two, which reads as a direction without smearing a 60 ms stroke across the room.
         */
        const val SPREAD_S = 0.04f

        /** Kilometres a sound covers in a second. The one constant both media divide by. */
        const val SPEED_KM_PER_S = 0.343f

        /** Seconds the light leads the thunder per kilometre, and the limits on that lead. */
        const val LEAD_S_PER_KM = 3f
        const val LEAD_MIN_S = 1f
        const val LEAD_MAX_S = 3f

        /** Under this a strike is near: white, sharp, directional, and gated by intensity. */
        const val NEAR_KM = 1.0f

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

        /**
         * How much of the room's brightness the audible roll is allowed to move, in
         * perceived units. A hint of sky, never a second flash.
         */
        const val ROLL_GLOW = 0.10f

        /**
         * How much of a rumble there has to be before the room answers it.
         *
         * Rain has a low end of its own, so without a floor the room would carry a
         * permanent glow that never resolved into anything.
         */
        const val RUMBLE_FLOOR = 0.25f

        /** Time constants the recording's rain and rumble are followed at. */
        const val RAIN_TAU_S = 3f
        const val RUMBLE_TAU_S = 1.5f

        /**
         * Shortest gap between two strikes the room shows, in seconds.
         *
         * A clap is not one transient — it is an attack and then several seconds of
         * peaks as different parts of the channel arrive — and every one of those peaks
         * is a genuine onset. Flashing on all of them is a strobe, not lightning. The
         * detector's own rising floor does most of the suppression; this catches the
         * rest, and spaces the storm out to something you could sit in.
         */
        const val STRIKE_REFRACTORY_S = 4.0
        const val MIN_GAP_S = 4.0

        /** The dark between two return strokes, after the previous one has finished. */
        const val STROKE_GAP_MIN_S = 0.06f
        const val STROKE_GAP_MAX_S = 0.18f

        /**
         * Low-energy share at which a clap reads as overhead, and as far off.
         *
         * A close strike arrives broadband — the crack still has treble in it — so its
         * low share is only a little over half. By a few kilometres the air has taken
         * everything above a few hundred hertz and almost all of what is left is low.
         */
        const val NEAR_SHARE = 0.45f
        const val FAR_SHARE = 0.88f

        /** Perceived peaks: a near stroke on the nearest lamp, and a far glow everywhere. */
        const val NEAR_PEAK = 0.95f
        const val FAR_PEAK = 0.30f

        /** The dim base: perceived brightness of the rain light, at intensity 0 and 1. */
        const val BASE_MIN = 0.13f
        const val BASE_MAX = 0.19f

        /** How fast cloud moves along the room's axis, and how many cloud cells span it. */
        const val CLOUD_SPEED = 0.025
        const val CLOUD_SPAN = 1.1f

        val RAIN = Rgb(0.50f, 0.58f, 0.80f)
        val NEAR_BOLT = Rgb(0.88f, 0.92f, 1.00f)
        val FAR_BOLT = Rgb(0.68f, 0.60f, 0.95f)

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
