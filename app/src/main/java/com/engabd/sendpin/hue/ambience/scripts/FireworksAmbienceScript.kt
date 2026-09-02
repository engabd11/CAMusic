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
import com.engabd.sendpin.hue.ambience.GrainCloud
import com.engabd.sendpin.hue.ambience.OnePole
import com.engabd.sendpin.hue.ambience.PinkNoise
import com.engabd.sendpin.hue.ambience.Rng
import com.engabd.sendpin.hue.ambience.RoomModel
import com.engabd.sendpin.hue.ambience.Svf
import com.engabd.sendpin.hue.ambience.VoicePool
import com.engabd.sendpin.hue.ambience.azimuthOfPan
import com.engabd.sendpin.hue.ambience.panGains
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

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    private val rng = Rng(seed)
    private var nextBurstS = 0.8

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
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    // ---- Reacting to a recording -----------------------------------------

    /** A display is its bangs. With a recording of one playing, they are the show. */
    override val eventsComeFromAudio: Boolean get() = true

    override fun bindBed(bed: AmbienceBedTrack?) { this.recording = bed }

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
        emit(
            AmbienceEvent(
                kind = AmbienceEvent.BURST,
                // Where the bang began; see BedOnset.atS.
                startS = onset.atS,
                env = Envelope(attackS = 0.05f, holdS = 0.05f, decayTauS = 0.55f),
                gain = (0.40f + 0.60f * strength).coerceIn(0.15f, 1f),
                origin = origin,
                azimuth = az,
                colour = shellColours[reactRng.nextInt(shellColours.size)],
                timbre = size,
                seed = reactRng.nextLong(),
                // The recording is the sound, so there is nothing to lead into and
                // nothing left to play afterwards. See ThunderstormScript.react.
                leadS = 0f,
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
        val lambda = 0.25f + 1.15f * intensity          // bursts per second
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
                    env = Envelope(attackS = 0.05f, holdS = 0.05f, decayTauS = 0.55f),
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
            nextBurstS += (-ln(rng.f01().coerceAtLeast(1e-6f)) / lambda).toDouble()
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
        val intensity = params.intensity.coerceIn(0f, 1f)

        // Night sky between bursts — not black, or the room looks switched off. Lifted a
        // little by whatever the recording is doing, so the sky over a busy stretch of
        // the display sits brighter than the sky over a lull.
        val r = recording
        val haveBed = r != null && r.sampleAt(tS, bedNow)
        val sky = if (haveBed) 1f + 2.5f * bedNow.level else 1f
        var j = 0
        while (j < ids.size) {
            acc[j * 3] = NIGHT.first * sky
            acc[j * 3 + 1] = NIGHT.second * sky
            acc[j * 3 + 2] = NIGHT.third * sky
            j++
        }

        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e == null || e.kind != AmbienceEvent.BURST) continue
            val age = e.ageAt(tS)

            // 1. The climb. A single ember tracking up towards where the shell will
            //    break, which is the whistle made visible: the sound rises for most of
            //    a second and, until now, nothing in the room acknowledged that
            //    anything was on its way.
            if (age < 0f) {
                val climb = (1f + age / LAUNCH_S.toFloat()).coerceIn(0f, 1f)
                if (climb > 0f) {
                    // Straight up from where it will break, so the ember arrives exactly
                    // where the shell opens rather than sliding across the room.
                    val at = Vec3(e.origin.x, e.origin.y, e.origin.z * climb)
                    // Fades *in* as it climbs and never gets bright: a lifted fuse, not
                    // a second firework.
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

            // 2. The burst: a ring travelling outward, not a flash.
            val shellR = EXPAND * age * e.timbre
            val burst = e.levelAt(tS)
            if (burst > 0f) {
                var i = 0
                while (i < ids.size) {
                    val x = (room.distanceTo(ids[i], e.origin) - shellR) / SHELL_WIDTH
                    val lvl = burst * exp(-x * x)
                    if (lvl > 1e-4f) {
                        val o = i * 3
                        acc[o] += e.colour.first * lvl
                        acc[o + 1] += e.colour.second * lvl
                        acc[o + 2] += e.colour.third * lvl
                    }
                    i++
                }
            }

            // 3. The crackle, seen while it is heard.
            //
            // Held open across the sound's arrival on purpose. The embers of a real
            // shell twinkle for a couple of seconds after the flash and their crackle
            // reaches you late, so the two genuinely do overlap — and that overlap is
            // the whole reason the delayed boom reads as distance rather than as the
            // audio being out of step. Each lamp twinkles on its own seeded phase, so
            // it is a scatter of sparks rather than the room breathing.
            // With a recording playing there is no air delay to wait out — the crackle
            // is audible *now* — and its brightness is the recording's own top end
            // rather than a modelled decay, so the embers die when the crackle does.
            val crackleAge = if (haveBed) age else age - airDelayOf(radiusOf(e))
            if (crackleAge > 0f && crackleAge < CRACKLE_S) {
                val heard = if (haveBed) (0.25f + 0.85f * bedNow.rain).coerceIn(0f, 1f) else 1f
                val fade = exp(-crackleAge / 0.85f) * heard * e.gain * SPARKLE
                var i = 0
                while (i < ids.size) {
                    val ph = (e.seed ushr (i % 32)).toFloat() * 0.61803f + ids[i] * 0.31f
                    val tw = sin(2f * PI.toFloat() * (crackleAge * 5.7f + ph))
                    val lvl = fade * (0.5f + 0.5f * tw) * (0.5f + 0.5f * tw)
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

        val trim = 0.6f + 0.4f * intensity
        var i = 0
        while (i < ids.size) {
            val o = i * 3
            out[ids[i]] = Rgb(
                (acc[o] * trim).coerceIn(0f, 1f),
                (acc[o + 1] * trim).coerceIn(0f, 1f),
                (acc[o + 2] * trim).coerceIn(0f, 1f),
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
         * Shortest gap between two shells, in seconds.
         *
         * Far shorter than the storm's, and it has to be: the finale of a display is
         * three bangs a second and every one of them should light the room. Short
         * enough not to lose any of them, long enough that one bang's own decay does
         * not read as a second shell.
         */
        const val BURST_REFRACTORY_S = 0.12


        /** Brightness the climbing ember is allowed, and how tightly it is focused. */
        const val TRACER = 0.30f
        const val TRACER_WIDTH = 0.42f

        /** Brightness the twinkling embers are allowed once the shell has broken. */
        const val SPARKLE = 0.22f

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

        val NIGHT = Rgb(0.02f, 0.02f, 0.06f)

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
