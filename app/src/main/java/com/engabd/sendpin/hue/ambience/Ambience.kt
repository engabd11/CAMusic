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
 * This is deliberately not the same thing as the main reactive engine's music-driven
 * render: that engine has one clock — the analysis frame — and nothing to say when
 * there is no music. Ambience has its own clock, no music, and sound of its own.
 */
enum class AmbienceEffect(
    val wire: String,
    val title: String,
    val blurb: String,
    /**
     * Whether "Default" should generate this effect's sound rather than play a
     * recorded bed for it.
     *
     * Only the synthesised path is an [AudioSink][com.engabd.sendpin.hue.ambience.AudioSink],
     * and only an AudioSink can be the show clock — a recording runs on its own
     * clock and free-wheels against the light script.
     *
     * That is fine for an effect made of *texture*: a fire, the sea, a room tone.
     * Nothing in those has to land on a frame, so a real recording is strictly
     * better than anything synthesis can do and the free-wheeling costs nothing.
     *
     * It is ruinous for an effect made of *events*. A storm and a firework display
     * are nothing but transients, and their whole grammar is a cause you see
     * followed by a consequence you hear: the flash then the crack, the whistle
     * then the burst. Play a recording under that and the room is flashing to one
     * storm while the speaker plays a different one — two shows at once, which is
     * exactly what a listener reports as "they don't feel like one effect".
     * A recorded thunderclap cannot be made to land on a scripted flash, because
     * nothing in the file knows the flash happened.
     *
     * So the rule is the shape of the effect, not the tile: every event-driven
     * effect generates, and every texture-driven one plays its bed. The two
     * Thunderstorm and Fireworks tiles differ by seed, palette and distance —
     * which is what their own blurbs promise — rather than by one being the
     * out-of-sync recording of the other.
     */
    val defaultSoundIsGenerated: Boolean = false,
) {
    FIREWORKS(
        "fireworks", "Fireworks",
        "Shells climb, burst overhead and crackle away into the dark.",
        defaultSoundIsGenerated = true,
    ),
    FIREWORKS_2(
        "fireworks_2", "Fireworks II",
        "The same sky, burning white and gold instead of colour.",
        defaultSoundIsGenerated = true,
    ),
    THUNDERSTORM(
        "thunderstorm", "Thunderstorm",
        "Rain on the roof, and lightning that arrives before its thunder.",
        defaultSoundIsGenerated = true,
    ),
    THUNDERSTORM_2(
        "thunderstorm_2", "Thunderstorm II",
        "A warmer, further-off storm — more sky than roof.",
        defaultSoundIsGenerated = true,
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
        const val SWELL = 5
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

    /** Called once before the first [schedule], with the room this show will run in. */
    fun bind(room: RoomModel, params: AmbienceParams)

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
