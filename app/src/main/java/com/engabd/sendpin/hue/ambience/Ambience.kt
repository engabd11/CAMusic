package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.Vec3
import kotlin.math.exp
import kotlin.math.ln

/**
 * Ambience shows: scripted light effects with their own synthesised sound, driven by
 * nothing but a clock.
 *
 * ## The one idea everything here is built on
 *
 * **An event is a cause, not a light and not a sound.** [AmbienceEvent] says a thing
 * happened, where in the room, how hard, and with what shape. The light tick and the
 * audio block each *project* that cause through a pure function of `t - startS`.
 *
 * That is what makes a thunderclap land with its flash without anything having to keep
 * the two in step. There is no light track and no audio track to drift apart; there is
 * one immutable object read at 60 Hz by one thread and at 48 kHz by another, and the
 * offset between what is seen and what is heard is the propagation delay the event
 * carries in its own [AmbienceEvent.timbre]. Change the intensity, reseed the storm,
 * skip to another effect — the relationship holds, because neither medium stores a copy
 * of anything.
 *
 * ## Where the cause comes from
 *
 * Both, depending on what is making the sound, and the difference is smaller than it
 * looks. When the show plays its bundled recording — which is the normal case, because a
 * real storm recorded by a real microphone beats anything synthesised here — the
 * recording is the cause: [AmbienceBedAnalyser] hears a thunderclap and the script turns
 * it into a STRIKE, stamped at the media position of the audio that produced it. When
 * there is no recording to play, the script invents its own events on its own clock, as
 * it always did.
 *
 * Either way the light tick renders events against the clock the sound is on, and either
 * way an event remains a cause rather than a light or a sound. The reactive path is not
 * a second architecture bolted alongside the scripted one; it is the same architecture
 * with a microphone at the front instead of a random number generator.
 *
 * This is still not the same thing as the main reactive engine's music-driven render.
 * That engine paints straight from an analysis frame; here a frame becomes an *event*,
 * which then has a life of its own — a strike keeps flashing across the room for most of
 * a second after the frame that caused it has gone, and it lights the near lamps before
 * the far ones. A rain shower has no beats to paint.
 */
enum class AmbienceEffect(
    val wire: String,
    val title: String,
    val blurb: String,
) {
    FIREWORKS(
        "fireworks", "Fireworks",
        "Shells climb, burst overhead and crackle away into the dark.",
    ),
    FIREWORKS_2(
        "fireworks_2", "Fireworks II",
        "The same sky, burning white and gold instead of colour.",
    ),
    THUNDERSTORM(
        "thunderstorm", "Thunderstorm",
        "Rain on the roof, and lightning that arrives before its thunder.",
    ),
    THUNDERSTORM_2(
        "thunderstorm_2", "Thunderstorm II",
        "A warmer, further-off storm — more sky than roof.",
    ),
    UNDERWATER(
        "underwater", "Underwater",
        "Caustics drifting across the room, and everything a little muffled.",
    ),
    FIREPLACE(
        "fireplace", "Fireplace",
        "A fire breathing in the corner, popping now and then.",
    ),
    LIGHT_TRAIN(
        "light_train", "Light train",
        "A beam that runs the length of the room and away into the distance.",
    ),
    AURORA(
        "aurora", "Aurora",
        "Slow curtains of colour, folding over each other. Nothing sudden.",
    ),
    COASTAL_RAIN(
        "coastal_rain", "Coastal rain",
        "Cool grey rain on the glass, headlights sweeping the wall now and then.",
    );

    companion object {
        fun fromWire(s: String?) = entries.firstOrNull { it.wire == s }
    }
}

/**
 * One shape, evaluated identically by the light tick and the audio block.
 *
 * Attack is smoothstepped rather than linear — a linear ramp has a corner at both ends,
 * and on a light that reads as a click. Decay is exponential with a hard tail cut, which
 * is what makes [lifetimeS] finite: without it an event would never be collectable and
 * the timeline could not be a fixed-size ring.
 */
data class Envelope(
    val attackS: Float,
    val holdS: Float,
    val decayTauS: Float,
    /** Level below which the tail is treated as over. */
    val tailCut: Float = 1e-3f,
) {
    /** After this long the event contributes nothing and can be forgotten. */
    val lifetimeS: Float =
        attackS + holdS + decayTauS * ln(1f / tailCut.coerceIn(1e-6f, 0.5f))

    fun at(age: Float): Float = when {
        age < 0f || age >= lifetimeS -> 0f
        age < attackS -> {
            val t = if (attackS <= 0f) 1f else age / attackS
            t * t * (3f - 2f * t)          // smoothstep
        }
        age < attackS + holdS -> 1f
        else -> exp(-(age - attackS - holdS) / decayTauS.coerceAtLeast(1e-4f))
    }
}

/**
 * A thing that happened, on the show clock.
 *
 * Deeply immutable, and that is load-bearing rather than tidy: every derived value is a
 * pure function of `(t - startS)` and the fields below, so two threads at two different
 * rates can read the same instance with no lock and reach answers that agree exactly.
 *
 * @param kind script-private tag — STRIKE, POP, BURST, CLACK.
 * @param startS absolute, in show-clock seconds.
 * @param env the *light* side's shape. Audio derives its own from this event's fields;
 *   see `ThunderstormScript` for the worked case.
 * @param origin where it happened, in the normalised room cube.
 * @param azimuth turns around the room, 0 = room-right, matching `SpatialWaves.azimuthOf`.
 *   Also the audio pan, so a flash on the left cracks from the left.
 * @param timbre one script-specific scalar. Thunder uses it for distance in km, which is
 *   what both the light falloff and the audio's propagation delay are computed from —
 *   the single number that keeps the two media telling the same story.
 * @param seed deterministic per-event noise, so a re-render of the same event is
 *   identical and the scripts are testable.
 * @param leadS how long **before** [startS] this event is already audible. A shell
 *   whistles on the way up, so its sound begins nearly a second before the sky lights
 *   up; without this the timeline would not hand the event to the audio block until
 *   the burst, and the whistle could never be rendered at all.
 * @param soundS how long **after** [startS] this event is still audible. Almost always
 *   longer than the light — a strike is over as light in under a second and still
 *   rolling as sound twenty seconds later, because the flash and the thunder are
 *   separated by the propagation delay the event exists to express.
 */
class AmbienceEvent(
    val kind: Int,
    val startS: Double,
    val env: Envelope,
    val gain: Float,
    val origin: Vec3,
    val azimuth: Float,
    val colour: Rgb,
    val timbre: Float = 0f,
    val seed: Long = 0L,
    val leadS: Float = 0f,
    val soundS: Float = 0f,
) {
    /**
     * How long after [startS] this event still matters to *either* medium.
     *
     * The light envelope alone is not it, and assuming it was is what silenced the
     * storm. [AmbienceTimeline] collects an event as soon as nothing can still be
     * reading it, and the audio reads far later than the lights do: a strike 2 km out
     * flashes for 0.8 s and thunders at 5.8 s, so an event retired on its light
     * envelope was already gone by the time its own sound came due. Every strike
     * beyond a couple of hundred metres was simply never heard.
     *
     * So the span is the union of what both media need, and the script that knows the
     * delay is the one that declares it.
     */
    val spanS: Float = maxOf(env.lifetimeS, soundS)

    fun ageAt(t: Double): Float = (t - startS).toFloat()
    fun levelAt(t: Double): Float = gain * env.at(ageAt(t))

    /** True while anything — light or sound — can still be reading this event. */
    fun aliveAt(t: Double): Boolean {
        val age = ageAt(t)
        return age >= -leadS && age < spanS
    }

    /**
     * True if the event is alive anywhere in `[fromS, toS)`.
     *
     * The audio path asks this rather than [aliveAt], because a block is a span and an
     * event that begins inside one is not alive at its first sample. Gating a block on
     * its start instant threw away the leading edge of every event that did not happen
     * to begin on a block boundary — which is precisely the attack transient, the part
     * that carries the crack.
     */
    fun aliveOver(fromS: Double, toS: Double): Boolean =
        toS > startS - leadS && fromS < startS + spanS

    companion object {
        // Shared kind tags. Scripts only ever see their own, but keeping them in one
        // place stops two scripts quietly meaning different things by the same number.
        const val STRIKE = 1
        const val BURST = 2
        const val POP = 3
        const val CLACK = 4
        /** A slow travelling wash — headlights on a rainy window, not a moment. */
        const val SWEEP = 5
        const val SWELL = 6
    }
}

/** Room-independent knobs every script honours. */
data class AmbienceParams(
    /** 0..1. Drives event rate and, more gently, level. */
    val intensity: Float = 0.5f,
    /** Ceiling on any lamp's output, so an effect cannot outshine the room's setting. */
    val brightness: Float = 1f,
)

/**
 * One ambience effect: what happens, what it looks like, what it sounds like.
 *
 * Every method is pure given the script's own state, the window and the seed, which is
 * what lets the whole set be tested off-device with no bridge and no speaker.
 */
interface AmbienceScript {
    val effect: AmbienceEffect

    /**
     * How far past the block being rendered this script needs its events scheduled.
     *
     * Zero for a script whose events are silent until they start, which is most of
     * them: the generator already schedules a block immediately before rendering it,
     * so an event is always published before anything asks about it.
     *
     * Not zero for a script with [AmbienceEvent.leadS]. A shell's whistle is rendered
     * at *negative* age, and an event is only scheduled when the block containing its
     * `startS` comes up — by which time the block that should have carried the whistle
     * was rendered and written nearly a second ago. Declaring the lead here moves the
     * scheduling horizon out far enough that the event exists before its own sound
     * begins, which is the difference between the climb being audible and it being
     * dead code, as it was.
     */
    val lookaheadS: Float get() = 0f

    /**
     * Whether a recording, when one is playing, replaces this script's own scheduler.
     *
     * True for the effects that *are* their events — a storm is thunder, a display is
     * bangs — where running the scheduler alongside an analysed recording would put two
     * unrelated storms in one room, which is precisely the complaint the reactive path
     * exists to answer.
     *
     * False for the effects that are texture with decoration on top. An underwater
     * show's swells and a light train's clacks are not in the recording in any form a
     * detector could find, and suppressing them would leave the effect poorer rather
     * than more honest. Those scripts keep scheduling, and merely read the bed for how
     * bright the room should be.
     */
    val eventsComeFromAudio: Boolean get() = false

    /** Called once before the first [schedule], with the room this show will run in. */
    fun bind(room: RoomModel, params: AmbienceParams)

    /**
     * The recording this show is running over, or null when it is generating its own.
     *
     * Held rather than passed to [renderLights] so the 60 Hz signature stays the shape
     * it is. A script that ignores the bed — most of them — needs no code at all.
     */
    fun bindBed(bed: AmbienceBedTrack?) {}

    /**
     * The recording did something; turn it into this effect's events.
     *
     * Called on the analysis thread once per hop, with [sample] describing that moment
     * and [onset] saying what, if anything, crossed a threshold in it. Both are reused
     * between calls: read them, emit, and keep neither.
     *
     * Detection is shared and generic (see [AmbienceBedAnalyser]); interpretation is
     * not, and lives here. Only this script knows that a loud, dull, low onset is a
     * strike three kilometres out that should wash the room in dim blue rather than
     * crack it white.
     */
    fun react(sample: BedSample, onset: BedOnset, emit: (AmbienceEvent) -> Unit) {}

    /**
     * The recording jumped (a loop point or a seek): forget whatever reaction
     * state described the stretch that is no longer playing.
     *
     * The session already drops the timeline and resets the shared detector on
     * this event; what it could not reach was the script's own refractory
     * bookkeeping, because that is private. The consequence was specific: the
     * bed loops (REPEAT_MODE_ONE), media time leaps back by the whole file, and
     * `sample.tS - lastStrikeS` goes hugely *negative* - which passes every
     * refractory test rather than failing it, so nothing is suppressed... until
     * the playhead climbs back past the last strike of the previous pass. From
     * that moment to the end of the loop, every real onset sits inside the
     * previous pass's refractory window and is silently swallowed. A looping
     * storm flashed on its first pass and then went dark for a full loop. This
     * hook is where each script forgets.
     */
    fun onBedReset() {}

    /** Live parameter change. Takes effect on the next scheduled window. */
    fun retune(params: AmbienceParams)

    /**
     * Emit every event starting in `[fromS, toS)`.
     *
     * Called with **contiguous** windows and never re-called for one already covered, so
     * an implementation may carry state across calls. `schedule(0,1)` then
     * `schedule(1,2)` must produce exactly what `schedule(0,2)` would — the block
     * boundary is an implementation detail of the audio buffer, not something the show
     * is allowed to notice.
     */
    fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit)

    /**
     * The light frame at [tS].
     *
     * [live] is a scratch array owned by the caller holding [n] events alive at [tS];
     * writing into [out] rather than returning a map keeps the 60 Hz path allocation-free.
     */
    fun renderLights(tS: Double, live: Array<AmbienceEvent?>, n: Int, out: MutableMap<Int, Rgb>)

    /**
     * Interleaved stereo float for [frames] frames beginning at show time [startS].
     *
     * Additive into [out], which the caller has zeroed. Must not allocate.
     */
    fun renderAudio(
        out: FloatArray,
        frames: Int,
        startS: Double,
        sampleRate: Int,
        live: Array<AmbienceEvent?>,
        n: Int,
    )
}
