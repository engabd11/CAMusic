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
import com.engabd.sendpin.hue.ambience.GrainCloud
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
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Shells climbing, bursting overhead, and crackling away into the dark.
 *
 * The burst model is the one `FireworksEffect` already uses for the music-reactive
 * effect — an expanding Gaussian shell, `EXPAND` metres a second, fading on its own
 * lifetime — but sourced from scheduled events rather than from beat detection, and in
 * real 3D rather than the (xrank, height) plane the music version works in.
 *
 * ## What the room does
 *
 * **The flash leads the bang.** Light crosses the distance instantly and sound takes a
 * few tenths of a second, so the sky lights up and then it thumps. On the scheduled path
 * that is the sound's own air delay. With a bundled recording the shell is stamped that
 * long before the bang the recording will play, because the plan is read ahead of the
 * playhead (see `AmbienceBedTimeline`).
 *
 * **A burst blooms and then trails.** The ring opens and is gone in about a second, and
 * the shell's embers drift down over a second and a half to three, twinkling slowly
 * (under 2 Hz) so they read as sparks and not as flicker.
 *
 * **Between shells there is smoke.** Each burst leaves a faint warm-grey veil that clears
 * over about ten seconds, so a busy display sits a little brighter and hazier than a lull.
 *
 * **Distance shows.** The furthest shells are about 40 percent dimmer than the nearest.
 *
 * **Not a finale.** Shells come a few seconds apart, never closer than 0.8 s, and the
 * room answers a recording's bangs no more than every 0.7 s. All brightness is perceived
 * (see `Perceptual`); only the bloom is allowed to be sudden (see [comfort]).
 *
 * ## One shell, three moments, both media
 *
 * A display is not a sequence of bangs; it is a sequence of *shells*, and a shell is a
 * climb, a burst and a crackle. One event carries all three, and every one of them is
 * now something the room does as well as something the speaker does:
 *
 * | | seen | heard |
 * |---|---|---|
 * | climb | an ember rising toward where the shell will break | the whistle |
 * | burst | the expanding shell of colour | the boom, [airDelayOf] later |
 * | crackle | the shell's embers twinkling out | the crackle, on the same delay |
 *
 * Both sides read the same `startS`, so the whistle always ends exactly when the sky
 * lights up, and the boom always lags the flash by the time sound takes to cross the
 * distance the shell's own position implies. That lag is what a firework *is* — the
 * flash-then-thump is how you know it was half a kilometre away and not in the garden —
 * and it only reads as a lag rather than as a fault because the eye has the shell in
 * front of it the whole time.
 *
 * ## Why none of this used to be audible
 *
 * The whistle is rendered at negative age, and the crackle outlives the light shell. The
 * timeline handed the audio only events that were alive *as light*, so the climb was
 * never once rendered in the app's life and the tail of every shell was cut. See
 * [AmbienceEvent.leadS] and [AmbienceEvent.soundS], which are how a script now says what
 * its sound needs rather than having it inferred from what its lights need.
 */
class FireworksAmbienceScript(
    override val effect: AmbienceEffect = AmbienceEffect.FIREWORKS,
    /** See the note on `ThunderstormScript`'s seed: same seed, same show. */
    seed: Long = 0xF1_2E_00_2AL,
    private val shellColours: Array<Rgb> = SHELL_COLOURS,
) : AmbienceScript {

    /**
     * Calm limits for the sky, the haze and the trailing sparks; burst limits for the
     * shell breaking, which is the one sudden thing in the scene. Sparks twinkling at
     * 2 Hz stay well inside the calm rate, so they read as embers rather than flicker.
     */
    override val comfort = ComfortProfile(
        risePerS = 1.0f, fallPerS = 0.8f, roomSwingPerS = 0.6f,
        burstRisePerS = 30f, burstFallPerS = 6f, burstRoomSwingPerS = 40f,
    )

    private var bursting = false
    override val burstActive: Boolean get() = bursting

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    // One generator per thread: the scheduler's, the reaction's and the light tick's.
    private val rng = Rng(seed)
    private val renderRng = Rng(seed xor 0x0516_47L)
    private var nextBurstS = 0.8

    /** Seeds for each lamp's faint sky shimmer. Set at bind from [renderRng]. */
    private var lampSeed = IntArray(0)

    /** Smoke hanging in the room after shells, 0..1, decaying over seconds. Render thread only. */
    private var smoke = 0f
    private var hazeMarkS = Double.NEGATIVE_INFINITY
    private var lastT = Double.NaN
    private val skyFollow = SlowFollower(SKY_TAU_S, 0.3f)

    /**
     * Everything one shell needs to make its own sound.
     *
     * Per shell, not per script: two shells overlapping is the normal case, and shared
     * filters would make the render order-dependent — see [VoicePool].
     */
    private class ShellVoice(sampleRate: Int) {
        val whistle = Svf()
        val boom = BrownNoise(0x3131)
        val boomLp = OnePole()
        /** The chest-thump under the boom, an octave below what the boom filter passes. */
        val thumpLp = OnePole()
        val crackle = Rng(0x4242)
        val sparkle = GrainCloud(0x5A5A, sampleRate)
        val mortar = OnePole()
        fun reset() { whistle.reset(); boomLp.reset(); thumpLp.reset(); mortar.reset() }
    }

    private var voices: VoicePool<ShellVoice>? = null
    private val pan = FloatArray(2)

    /**
     * The night the display happens in: a distant crowd and the low roll of a town.
     *
     * Not decoration. Between shells the synthesised effect was pure digital silence,
     * and silence is the one thing a real display never has — the gap read as the sound
     * having stopped rather than as a pause, which makes each boom arrive from nowhere
     * instead of out of an evening.
     */
    private val airHiss = PinkNoise(0x2718)
    private val airLpL = OnePole()
    private val airLpR = OnePole()

    /** Per-lamp light accumulator, so [renderLights] can loop events outside lamps. */
    private var acc = FloatArray(0)
    private var voiced = false

    /** The recording, when one is playing. Null on the synthesised path. */
    @Volatile private var recording: AmbienceBedTrack? = null

    /** Scratch for the light tick's read of the bed. Render thread only. */
    private val bedNow = BedSample()

    /** Reaction state. Analysis thread only. */
    private var lastBurstS = Double.NaN
    private val reactRng = Rng(seed xor 0x5EED_B005L)

    override fun bind(room: RoomModel, params: AmbienceParams) {
        this.room = room
        this.params = params
        acc = FloatArray(room.count * 3)
        lampSeed = IntArray(room.count) { (renderRng.nextLong() ushr 33).toInt() }
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    // ---- Reacting to a recording -----------------------------------------

    /** A display is its bangs. With a recording of one playing, they are the show. */
    override val eventsComeFromAudio: Boolean get() = true

    override fun bindBed(bed: AmbienceBedTrack?) { this.recording = bed }

    /** The bed jumped: forget the previous pass's last burst. */
    override fun onBedReset() {
        lastBurstS = Double.NaN
    }

    /**
     * A bang in the recording becomes a shell breaking over the room.
     *
     * Read out of the sound rather than invented: **where** from the stereo field,
     * **how hard** from how far the bang went over the display's own recent level, and
     * **how big** from how much of it is bottom end — a large shell is felt as much as
     * heard, and that is a spectral fact about the recording rather than a guess.
     *
     * No climb. The whistle is genuinely there in a good recording and genuinely not
     * findable: a rising tone under two other shells still crackling is beyond what a
     * broadband onset detector can honestly claim, and a climbing ember that fired at
     * the wrong moments would be worse than none. It stays on the synthesised path,
     * which knows when its own shells were launched because it launched them.
     */
    override fun react(sample: BedSample, onset: BedOnset, emit: (AmbienceEvent) -> Unit) {
        if (!onset.broadband && !onset.low) return
        if (!lastBurstS.isNaN() && sample.tS - lastBurstS < BURST_REFRACTORY_S) return
        lastBurstS = sample.tS

        val strength = maxOf(onset.broadbandStrength, onset.lowStrength * 0.9f)
        val az = azimuthOfPan(onset.pan)
        // Bigger shells sit further out and higher; small ones are closer in. Keeps a
        // barrage of small bangs from all landing on the same lamps.
        val size = (0.35f + 0.75f * onset.lowShare).coerceIn(0.3f, 1f)
        val radius = 0.55f + 0.40f * size
        val origin = Vec3(
            0.5f + radius * kotlin.math.cos(2f * PI.toFloat() * az),
            0.5f + radius * sin(2f * PI.toFloat() * az),
            0.75f + 0.25f * size,
        )
        // Light first. The flash is stamped the time the bang would take to cross the
        // distance the shell implies *before* the bang, as far as the analysis lets it
        // (BedOnset.aheadS is zero for a live clip, so the flash lands on the bang then).
        // The 0.25 s margin is so the event reaches the timeline before it starts.
        val lead = airDelayOf(radius).coerceAtMost((onset.aheadS - 0.25f).coerceAtLeast(0f))
        // A rising ember only when there is room to show the climb as well.
        val climb = if (onset.aheadS > lead + LAUNCH_S + 0.25) LAUNCH_S.toFloat() else 0f
        emit(
            AmbienceEvent(
                kind = AmbienceEvent.BURST,
                // Where the bang began, less the lead; see BedOnset.atS.
                startS = onset.atS - lead,
                env = BURST_ENV,
                gain = (0.40f + 0.60f * strength).coerceIn(0.15f, 1f),
                origin = origin,
                azimuth = az,
                colour = shellColours[reactRng.nextInt(shellColours.size)],
                timbre = size,
                seed = reactRng.nextLong(),
                // The recording is the sound, so nothing is synthesised, and nothing is
                // left to play afterwards. The lead is only what the lights draw.
                leadS = climb,
                soundS = 0f,
            ),
        )
    }

    /**
     * A shell has to exist before it starts whistling.
     *
     * The whole climb happens at negative age, so scheduling a burst when the block
     * containing it comes up is nearly a second too late — the blocks that should have
     * carried the whistle have already been written. A margin on top of the climb
     * itself, so a block boundary landing awkwardly cannot shave the first grain off
     * the launch.
     */
    override val lookaheadS: Float get() = LAUNCH_S.toFloat() + 0.25f

    override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) {
        if (nextBurstS < fromS) nextBurstS = fromS
        val intensity = params.intensity.coerceIn(0f, 1f)
        // Bursts per second. A real display fires a shell every few seconds with the odd
        // salvo; the old one-a-second-or-more was a finale, and a finale is not a room
        // you can sit in.
        val lambda = 0.05f + 0.30f * intensity
        while (nextBurstS < toS) {
            val az = rng.f01()
            // Overhead and out: a firework is not in the room with you.
            val radius = 0.55f + 0.45f * rng.f01()
            val origin = Vec3(
                0.5f + radius * kotlin.math.cos(2f * PI.toFloat() * az),
                0.5f + radius * sin(2f * PI.toFloat() * az),
                0.75f + 0.25f * rng.f01(),
            )
            val size = 0.5f + 0.5f * rng.f01()
            val airS = airDelayOf(radius)
            emit(
                AmbienceEvent(
                    kind = AmbienceEvent.BURST,
                    startS = nextBurstS,
                    // Long enough to expand and fade; matches FireworksEffect's BURST_LIFE.
                    env = BURST_ENV,
                    gain = 0.45f + 0.55f * rng.f01(),
                    origin = origin,
                    azimuth = az,
                    colour = shellColours[rng.nextInt(shellColours.size)],
                    // Shell size: drives both the light shell's radius and the boom's depth.
                    timbre = size,
                    seed = rng.nextLong(),
                    // The shell is already whistling this long before it breaks, so the
                    // timeline has to hand it to the audio block that early. Without
                    // this the climb was scheduled, rendered by nothing, and silent.
                    leadS = LAUNCH_S.toFloat(),
                    // ...and still crackling this long after, on top of the time its
                    // sound takes to reach the room at all.
                    soundS = airS + CRACKLE_S.toFloat(),
                ),
            )
            nextBurstS += (-ln(rng.f01().coerceAtLeast(1e-6f)) / lambda).coerceAtLeast(MIN_GAP_S).toDouble()
        }
    }

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
        // How bright a shell is allowed to be follows the intensity, so a gentle setting
        // is a gentler sky and not merely a quieter one.
        val trim = 0.65f + 0.35f * intensity

        // 1. The night, and the smoke the shells leave in it.
        //
        //    Not black, or the room looks switched off, and not static: each lamp's sky
        //    wanders faintly. A recording's level lifts it a little over seconds (never
        //    per frame; with no reading the last value is held).
        val r = recording
        val haveBed = r != null && r.sampleAt(tS, bedNow)
        val busy = skyFollow.step(haveBed || r == null, if (haveBed) bedNow.level else 0.3f, dt)
        smoke *= exp(-dt / SMOKE_TAU_S)
        var newest = hazeMarkS
        var s = 0
        while (s < n) {
            val e = live[s]
            s++
            // A shell adds its smoke once, the moment it breaks. Pre-fed events that
            // have not started yet (age < 0) wait their turn.
            if (e == null || e.kind != AmbienceEvent.BURST || e.startS <= hazeMarkS || e.ageAt(tS) < 0f) continue
            smoke = minOf(1f, smoke + SMOKE_PER_SHELL * e.gain)
            if (e.startS > newest) newest = e.startS
        }
        hazeMarkS = newest

        var j = 0
        while (j < ids.size) {
            val shimmer = 0.85f + 0.30f * (0.5f + 0.5f * SmoothNoise.fractal(lampSeed[j], tS, 0.05f, 2))
            val sky = SKY_LEVEL * (0.85f + 0.30f * busy) * shimmer
            val haze = HAZE_LEVEL * smoke
            acc[j * 3] = NIGHT.first * sky + HAZE.first * haze
            acc[j * 3 + 1] = NIGHT.second * sky + HAZE.second * haze
            acc[j * 3 + 2] = NIGHT.third * sky + HAZE.third * haze
            j++
        }

        // 2. The shells.
        var anyBloom = false
        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e == null || e.kind != AmbienceEvent.BURST) continue
            val age = e.ageAt(tS)

            // 2a. The climb. A single ember tracking up towards where the shell will
            //     break, which is the whistle made visible. Dim: a lifted fuse, not a
            //     second firework.
            if (age < 0f) {
                val climb = (1f + age / LAUNCH_S.toFloat()).coerceIn(0f, 1f)
                if (climb > 0f) {
                    // Straight up from where it will break, so the ember arrives exactly
                    // where the shell opens rather than sliding across the room.
                    val at = Vec3(e.origin.x, e.origin.y, e.origin.z * climb)
                    val amp = TRACER * climb * climb * e.gain
                    var i = 0
                    while (i < ids.size) {
                        val d = room.distanceTo(ids[i], at) / TRACER_WIDTH
                        val lvl = amp * exp(-d * d)
                        if (lvl > 1e-4f) {
                            val o = i * 3
                            acc[o] += EMBER.first * lvl
                            acc[o + 1] += EMBER.second * lvl
                            acc[o + 2] += EMBER.third * lvl
                        }
                        i++
                    }
                }
                continue        // nothing has burst yet
            }

            // Distant shells are dimmer, and read as lower: the ring is fainter the
            // further out the shell broke, and not so tall.
            val far = ((radiusOf(e) - 0.55f) / 0.45f).coerceIn(0f, 1f)
            val proximity = 1f - FAR_DIM * far

            // 2b. The bloom: a ring travelling outward and fading, not a flash. Quick
            //     to open and gone in a second or so.
            val bloom = e.levelAt(tS)
            if (bloom > 0f) {
                if (age < BLOOM_WINDOW_S) anyBloom = true
                val shellR = EXPAND * age * e.timbre
                var i = 0
                while (i < ids.size) {
                    val x = (room.distanceTo(ids[i], e.origin) - shellR) / SHELL_WIDTH
                    val lvl = trim * proximity * BURST_PEAK * bloom * exp(-x * x)
                    if (lvl > 1e-4f) {
                        val o = i * 3
                        acc[o] += e.colour.first * lvl
                        acc[o + 1] += e.colour.second * lvl
                        acc[o + 2] += e.colour.third * lvl
                    }
                    i++
                }
            }

            // 2c. The trail: the shell's embers drifting down and twinkling out over one
            //     and a half to three seconds, bigger shells lasting longer. Each lamp
            //     twinkles on its own seeded phase, slowly, so it is a scatter of sparks
            //     and not the room breathing.
            val trailS = TRAIL_MIN_S + (TRAIL_MAX_S - TRAIL_MIN_S) * e.timbre
            val trailAge = age - TRAIL_DELAY_S
            if (trailAge > 0f && trailAge < trailS) {
                val fade = exp(-trailAge / (trailS * 0.38f)) * (1f - smoothstep(0.8f, 1f, trailAge / trailS))
                val heard = if (haveBed) (0.7f + 0.5f * bedNow.rain).coerceIn(0f, 1.2f) else 1f
                var i = 0
                while (i < ids.size) {
                    val ph = (e.seed ushr (i % 32)).toFloat() * 0.61803f + ids[i] * 0.31f
                    val tw = 0.5f + 0.5f * sin(2f * PI.toFloat() * (trailAge * TWINKLE_HZ + ph))
                    val lvl = trim * proximity * SPARKLE * fade * heard * e.gain * tw * tw
                    if (lvl > 1e-4f) {
                        val o = i * 3
                        acc[o] += e.colour.first * lvl
                        acc[o + 1] += e.colour.second * lvl
                        acc[o + 2] += e.colour.third * lvl
                    }
                    i++
                }
            }
        }
        bursting = anyBloom

        var i = 0
        while (i < ids.size) {
            val o = i * 3
            out[ids[i]] = Rgb(
                acc[o].coerceIn(0f, 1f),
                acc[o + 1].coerceIn(0f, 1f),
                acc[o + 2].coerceIn(0f, 1f),
            )
            i++
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
        // Built on the first block, when the sample rate is finally known.
        val pool = voices ?: VoicePool(MAX_VOICES) { ShellVoice(sampleRate) }.also { voices = it }
        if (!voiced) {
            airLpL.setCutoff(900f, sampleRate)
            airLpR.setCutoff(760f, sampleRate)
            voiced = true
        }
        val intensity = params.intensity.coerceIn(0f, 1f)

        // 0. The night the display is happening in. Very quiet and very dull — it is
        //    there to stop the gaps between shells being digital silence, not to be
        //    listened to.
        val airLevel = 0.014f + 0.010f * intensity
        var i = 0
        while (i < frames) {
            out[i * 2] += airLpL.lp(airHiss.next()) * airLevel
            out[i * 2 + 1] += airLpR.lp(airHiss.next()) * airLevel
            i++
        }

        // Deliberately no block-boundary skip here.
        //
        // The obvious optimisation — bail out when a shell's whole span falls outside
        // this block — makes *which* events are rendered depend on where the block
        // boundaries happen to fall. The voices below are shared between shells and
        // carry state, so a shell skipped in one block layout and rendered in another
        // leaves the filters in a different place, and the output stops being a function
        // of the show alone: it becomes a function of the audio buffer size. Every
        // sample is gated on absolute time inside [renderShell] instead, which costs a
        // few comparisons and makes the result reproducible.
        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e == null || e.kind != AmbienceEvent.BURST) continue
            panGains(sin(2f * PI.toFloat() * e.azimuth), pan)
            renderShell(e, pool.voiceFor(e) { it.reset() }, out, frames, startS, sampleRate)
        }
    }

    private fun renderShell(
        e: AmbienceEvent,
        v: ShellVoice,
        out: FloatArray,
        frames: Int,
        startS: Double,
        sampleRate: Int,
    ) {
        // How long this shell's sound takes to reach the room. Everything audible is
        // shifted by it; nothing visible is. That difference is the effect.
        val airS = airDelayOf(radiusOf(e))
        val boomEnv = Envelope(0.006f, 0.02f, 0.28f + 0.35f * e.timbre)
        val thumpEnv = Envelope(0.010f, 0.03f, 0.42f + 0.50f * e.timbre)
        v.boomLp.setCutoff(160f + 240f * (1f - e.timbre), sampleRate)
        // The part you feel. A big shell has more of it, which is most of what makes one
        // shell read as bigger than another once both are just noise bursts.
        v.thumpLp.setCutoff(48f + 34f * (1f - e.timbre), sampleRate)
        v.mortar.setCutoff(120f, sampleRate)
        val mortarEnv = Envelope(0.004f, 0.01f, 0.11f)
        // Air takes the treble out of a boom over distance exactly as it does thunder's,
        // so a far shell is a thud and a near one has an edge to it.
        val far = (airS / MAX_AIR_S).coerceIn(0f, 1f)
        val boomAmp = e.gain * (0.55f + 0.25f * (1f - far))

        var i = 0
        while (i < frames) {
            val t = startS + i.toDouble() / sampleRate
            // Sound time, not light time. One subtraction, applied once, and every
            // audible part of the shell inherits it.
            val rel = (t - e.startS - airS).toFloat()
            var s = 0f

            // 1. The mortar, at the bottom of the climb: the flat thud of the shell
            //    being thrown, before there is anything to see at all.
            val mortarAge = rel + LAUNCH_S.toFloat()
            if (mortarAge > 0f && mortarAge < mortarEnv.lifetimeS) {
                s += v.mortar.lp(v.crackle.bipolar()) * mortarEnv.at(mortarAge) * e.gain * 0.24f
            }

            // 2. Whistle on the way up: a rising band-pass, ending exactly at the burst.
            if (rel < 0f && rel > -LAUNCH_S.toFloat()) {
                val climb = 1f + rel / LAUNCH_S.toFloat()   // 0 at launch, 1 at burst
                v.whistle.set(500f + 1500f * climb, 9f, sampleRate)
                val fade = climb * climb
                s += v.whistle.bp(v.crackle.bipolar()) * fade * 0.075f * e.gain
            }

            // 3. The burst itself: a band the ear hears and a thump it does not.
            if (rel >= 0f) {
                val be = boomEnv.at(rel)
                if (be > 0f) s += v.boomLp.lp(v.boom.next()) * be * boomAmp * 0.44f
                val te = thumpEnv.at(rel)
                if (te > 0f) s += v.thumpLp.lp(v.boom.next()) * te * e.gain * 0.28f
            }

            // 4. The crackle that falls out of it, thinning as it goes.
            if (rel > 0.05f && rel < CRACKLE_S.toFloat()) {
                val decay = exp(-(rel - 0.05f) / 0.7f)
                s += v.sparkle.next(70f * decay + 4f, 4200f, 3.5f) * decay * e.gain * 0.18f
            }

            if (s != 0f) {
                out[i * 2] += s * pan[0]
                out[i * 2 + 1] += s * pan[1]
            }
            i++
        }
    }

    /** How far out from the middle of the room this shell broke, 0..1. */
    private fun radiusOf(e: AmbienceEvent): Float {
        val dx = e.origin.x - 0.5f
        val dy = e.origin.y - 0.5f
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    companion object {
        /**
     * Concurrent shells that can each keep their own voice. Beyond this the oldest
     * recycles — which now means a click, because a recycled voice is reset while its
     * shell is still sounding. A shell used to leave the timeline when its light shell
     * faded; it now stays until its crackle has finished arriving, so a dense display
     * genuinely has fifteen in flight where it used to have six.
     */
        const val MAX_VOICES = 20

        /** Shell radius per second, in normalised room units. Matches FireworksEffect. */
        const val EXPAND = 0.9f
        const val SHELL_WIDTH = 0.34f

        /** How long a shell whistles before it bursts. */
        const val LAUNCH_S = 0.9

        /** How long the crackle carries on after. */
        const val CRACKLE_S = 2.4

        /**
         * Shortest gap between two bursts the room shows, for a recording's bangs.
         *
         * The old 0.12 s lit the room on every bang of a finale, three a second, which is
         * a strobe with extra steps. Seven tenths of a second thins a barrage to the
         * shells you can tell apart, and keeps a flash from landing inside the last
         * one's bloom. The ear still gets every bang from the recording.
         */
        const val BURST_REFRACTORY_S = 0.7

        /** Shortest gap the scheduler leaves between two shells, in seconds. */
        const val MIN_GAP_S = 0.8f

        /**
         * A shell's light: a quick bloom (the ring), then gone. The sparks that follow
         * are drawn separately, so the envelope is short; 0.4 s of decay is a bloom that
         * has faded to a tenth in about a second.
         */
        val BURST_ENV = Envelope(attackS = 0.05f, holdS = 0.04f, decayTauS = 0.40f)

        /** How long after the burst the bloom still counts as the named event, in seconds. */
        const val BLOOM_WINDOW_S = 0.5f

        /** Perceived brightness of a shell's ring at its peak, before distance and intensity. */
        const val BURST_PEAK = 1.0f

        /** How much dimmer the furthest shells are than the nearest. */
        const val FAR_DIM = 0.40f

        /** Brightness the climbing ember is allowed, and how tightly it is focused. */
        const val TRACER = 0.16f
        const val TRACER_WIDTH = 0.42f

        /** Brightness the twinkling embers are allowed once the shell has broken. */
        const val SPARKLE = 0.20f

        /** The trail of sparks: starts just after the bloom, lasts 1.5 to 3 s by shell size. */
        const val TRAIL_DELAY_S = 0.2f
        const val TRAIL_MIN_S = 1.5f
        const val TRAIL_MAX_S = 3.0f
        const val TWINKLE_HZ = 1.8f

        /**
         * The night and its smoke, perceived. The sky is dim enough that a shell is the
         * brightest thing in the room by far; the smoke is a faint warm-grey veil that
         * builds with each shell and clears over about ten seconds.
         */
        const val SKY_LEVEL = 0.07f
        const val SKY_TAU_S = 3f
        const val HAZE_LEVEL = 0.07f
        const val SMOKE_TAU_S = 9f
        const val SMOKE_PER_SHELL = 0.30f

        /**
         * Seconds of sound delay for a shell at the far edge of the display.
         *
         * A real display is set back a few hundred metres, which puts about a second
         * between the flash and the thump — and that gap is most of what makes fireworks
         * read as *outdoors and enormous* rather than as a speaker in the corner.
         *
         * Kept to two thirds of a second at the very furthest, though, rather than the
         * literal figure. Past about a second the eye stops crediting the burst it just
         * saw with the bang it hears, especially when shells are going up two a second
         * and the previous one is still crackling — it stops being distance and starts
         * being a fault. This is the one place the effect knowingly under-states the
         * physics, and it does it in the direction of the relationship staying legible.
         */
        const val MAX_AIR_S = 0.66f
        const val MIN_AIR_S = 0.24f

        /** The delay for a shell at normalised distance [radius] from the room's middle. */
        fun airDelayOf(radius: Float): Float =
            MIN_AIR_S + (MAX_AIR_S - MIN_AIR_S) * (radius / 0.95f).coerceIn(0f, 1f)

        val NIGHT = Rgb(0.30f, 0.34f, 0.70f)

        /** The smoke hanging between shells: warm grey, lit by the last burst. */
        val HAZE = Rgb(0.85f, 0.75f, 0.70f)

        /** The lifted fuse: burning magnesium, before the shell has any colour of its own. */
        val EMBER = Rgb(1.00f, 0.52f, 0.14f)

        /**
         * The saturated show. The Hue light-effects guide describes two explosion
         * archetypes — "reds and oranges with smoke" or "white hot and intense" —
         * and [WHITE_HOT_SHELLS] is the other one.
         */
        val SHELL_COLOURS = arrayOf(
            Rgb(1.00f, 0.30f, 0.25f),
            Rgb(1.00f, 0.78f, 0.25f),
            Rgb(0.35f, 0.85f, 1.00f),
            Rgb(0.65f, 0.40f, 1.00f),
            Rgb(0.40f, 1.00f, 0.55f),
            Rgb(1.00f, 0.55f, 0.85f),
        )

        /** Magnesium and gold: the "white hot and intense" archetype. */
        val WHITE_HOT_SHELLS = arrayOf(
            Rgb(1.00f, 0.97f, 0.90f),
            Rgb(1.00f, 0.86f, 0.55f),
            Rgb(1.00f, 0.72f, 0.30f),
            Rgb(0.95f, 0.95f, 1.00f),
            Rgb(1.00f, 0.60f, 0.18f),
        )
    }
}
