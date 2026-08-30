package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.BeatGrid
import com.engabd.sendpin.audio.GestureKind
import com.engabd.sendpin.audio.GestureState
import com.engabd.sendpin.audio.StructureState
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.pow

/**
 * The music-reactive effects engine: takes [AnalysisFrame] features and
 * produces per-channel RGB (0..1) for the Hue Entertainment stream.
 *
 * Ported from syncoV2's `effects/engine.py` + `effects/modes.py` +
 * `color/palette.py`, covering the core rendering path:
 * - 5 intensity modes (Subtle/Medium/High/Intense/Extreme) with their ModeParams
 * - 19 colour schemes + album art extraction
 * - Envelope following (asymmetric rise/fall)
 * - Beat flash with highlight selection
 * - Colour drift + per-beat colour jumps
 * - Continuous melbank reactive layer (LedFx-style)
 * - Per-channel smoothing (brightness + colour)
 * - Silence gate (only audio moves lights)
 * - Absolute loudness salience scaling
 * - Event-salience precision gates (width gate)
 *
 * Deferred to a later phase (the show works without them):
 * - Tempo PLL / beat grid (scheduled beats, anticipation)
 * - Song structure detection (builds, drops, phrases, pre-drop)
 * - Spatial beat wavefronts
 * - Extreme graph-reactive renderer (uses the music path instead)
 * - Fireworks effect
 * - Stereo pan
 * - Auto-intensity picker (user selects the mode manually)
 * - Drum-pad / manual beats
 */

// The modes, the presets and the colour palette used to be above this line, and are
// now in `SyncoModes.kt` and `SyncoPalette.kt`. Both are pure data with no behaviour,
// both change for entirely different reasons from the renderer — a preset is retuned
// by ear, the renderer is changed by argument — and the palette is unit-testable
// without a bridge, an audio frame or a room. What is left here is the engine.

// ── Constants ─────────────────────────────────────────────────────────────

private const val SILENCE_GATE = 0.12f
private const val GATE_DECAY = 0.85f
private const val LOUD_REF = 0.30f
private const val ENV_RISE = 0.55f
private const val ENV_FALL = 0.10f
private const val PRESENCE_RISE = 0.04f
private const val PRESENCE_FALL = 0.008f
private const val MELBANK_BINS = 16

/**
 * How many recent beats the highlight quantile ranks against (~12 s at 120 BPM).
 * Matches syncoV2's rolling accent window in `effects/engine.py`.
 */
private const val ACCENT_WINDOW = 24

/** Most wavefronts alive at once. Beyond a handful they read as a wash. */
private const val MAX_WAVES = 6

/** Wave strength half-life, seconds. */
private const val WAVE_DECAY_TAU = 0.45f

/** Per-frame decay of the drop swell. */
private const val SWELL_DECAY = 0.85f

/**
 * Ceiling on what a room gesture may add, as a fraction of full scale.
 *
 * A wash, not a flash. Philips are blunt about the failure mode this guards
 * against: "sudden changes in brightness of lamps in our peripheral vision (to
 * our side) or lamps near to us, can be unpleasant" — and a gesture that crosses
 * the room is peripheral motion by definition.
 */
private const val GESTURE_MAX = 0.35f

/** Shortest a lamp may be lit by a passing front before it reads as a chase. */
private const val MIN_DWELL_S = 0.15f

/** Centroid climb at which a traversal is also rising, not only crossing. */
private const val RISE_FOR_VERTICAL = 0.25f

/**
 * Colour-jump scaling per phrase, so four-phrase cycles don't feel metronomic.
 */
private val PHRASE_JUMP = floatArrayOf(1.0f, 0.85f, 1.15f, 0.95f)

/**
 * Relative weight of each beat in a bar: the downbeat hits hardest, the beats
 * between land softer. This is the difference between a pulse that feels
 * musical and one that feels like a metronome.
 *
 * A table per metre, because the four-beat one is not a waltz's shape with a
 * beat lopped off. In 4/4 the third beat is a secondary strong beat, which is
 * why it sits above beats 2 and 4; in 3/4 there is no secondary strong beat at
 * all and both weak beats are equal. Indexing the 4/4 table with a bar of three
 * would have given beat 3 an accent no waltz has.
 *
 * A scan reports its own [TrackScan.beatsPerBar]; the causal tracker has no
 * metre estimate and always says 4, which is the right guess when guessing is
 * all there is.
 */
private val BAR_WEIGHT_4 = floatArrayOf(1.0f, 0.72f, 0.86f, 0.72f)
private val BAR_WEIGHT_3 = floatArrayOf(1.0f, 0.72f, 0.72f)

/** The bar-weight table for a bar of [beats], falling back to 4/4. */
private fun barWeights(beats: Int): FloatArray = if (beats == 3) BAR_WEIGHT_3 else BAR_WEIGHT_4

/** Floor on a highlighted beat's pulse weight. */
private const val HIGHLIGHT_MIN = 0.55f

/** Rhythm-confidence dynamics: how fast belief in a beat builds and decays. */
private const val RHYTHM_RISE = 0.10f
private const val RHYTHM_EVENT_RISE = 0.35f
private const val RHYTHM_FALL = 0.006f
private const val RHYTHM_W_LO = 0.13f
private const val RHYTHM_W_HI = 0.40f

/** How much of a band's hottest bin is lifted into its glow, versus the mean. */
private const val EXT_GLOW_PEAKINESS = 0.3f

/** Gamma on the whole-room slam, so only genuinely broadband hits punch. */
private const val EXT_ROOM_GAMMA = 2.0f

/**
 * Perceptual compression on the per-band absolute-loudness weight. Below 1, so
 * a much quieter band comes out dimmer but still clearly visible rather than
 * driven to near-black.
 */
private const val BAND_LOUD_COMPRESS = 0.5f

/**
 * Bounds on a *mean-normalised* loudness weight (the main render path's form).
 *
 * Normalising makes the weights redistribute rather than attenuate, but a track
 * whose reference is nearly silent in one band would otherwise hand that band a
 * huge multiplier off a tiny denominator. These keep the spread musical: a quiet
 * band can be pulled down to a quarter and a loud one lifted to two and a half,
 * and no further.
 */
private const val LOUD_WEIGHT_MIN = 0.25f
private const val LOUD_WEIGHT_MAX = 2.5f

// ── Sustain bloom ─────────────────────────────────────────────────────────

/**
 * How much of the spatial coupling the *transient* layer takes, against the glow.
 *
 * Half. Fully diffusing the attack pop would blunt the per-instrument detail it
 * exists to provide — a kick would pop the whole room rather than the low lamps.
 */
private const val POP_COUPLE_RATIO = 0.5f

/** ~200 ms smoothing on chroma total variation, so a single frame can't unlatch it. */
private const val CHROMA_FLUX_SMOOTH = 0.10f

/**
 * Chroma movement that counts as "no longer a held pitch".
 *
 * Total variation between successive sum-normalised 12-bin vectors, so 0 is a
 * perfectly steady chord and 1 is a complete redistribution. A sustained note
 * sits well under this; a busy melodic line crosses it, which is the intent —
 * the bloom is for held material.
 */
private const val CHROMA_FLUX_REF = 0.22f

/** Asymmetric envelope on room-wide transient activity: fast up, slow down. */
private const val TONAL_DAMP_RISE = 0.5f
private const val TONAL_DAMP_FALL = 0.05f

/** Transient level at which the bloom is fully suppressed at `tonalDamp = 1`. */
private const val TONAL_DAMP_REF = 0.35f

/**
 * How much a locked beat grid suppresses the bloom.
 *
 * Mild on purpose. A vocal over a steady groove is most popular music, and
 * damping hard on rhythm would switch this layer off for exactly the material the
 * user asked for it on.
 */
private const val TONAL_RHYTHM_DAMP = 0.35f

/** Asymmetric per-bin baseline the melbank transient is measured against. */
private const val MEL_SLOW_RISE = 0.25f
private const val MEL_SLOW_FALL = 0.06f

/** Pre-drop anticipation constants. Mirror syncoV2 `effects/engine.py`. */
private const val PREDROP_RAMP_S = 1.2f
private const val PREDROP_CONFIRM = 10
private const val PREDROP_HEUR_CAP = 0.6f
private const val PREDROP_TIMEOUT_S = 3.0f
private const val PREDROP_REFRACTORY_S = 8.0f
private const val PREDROP_RISE = 0.10f
private const val PREDROP_FALL = 0.04f

/**
 * Split [bins] melbank bins into [n] contiguous, near-equal `[lo, hi)` spans,
 * low to high — one per lamp. Every band stays covered at every instant, which
 * is what lets the rotating map move without leaving a hole in the spectrum.
 */
private fun spectralBands(n: Int, bins: Int): List<Pair<Int, Int>> {
    if (n <= 0 || bins <= 0) return emptyList()
    return (0 until n).map { i ->
        val lo = (i.toLong() * bins / n).toInt()
        val hi = (((i + 1).toLong() * bins) / n).toInt()
        lo to max(lo + 1, hi)
    }
}
private val ROLE_BASS = 0; private val ROLE_MID = 1; private val ROLE_VOCAL = 2

// ── Engine ─────────────────────────────────────────────────────────────────

/**
 * Renders entertainment frames from audio features and the active mode.
 *
 * Construct with the entertainment area's channel layout, then call [render]
 * each analysis frame (~50 Hz) to get per-channel RGB.
 */
class SyncoEngine(
    channels: List<EntertainmentChannel>,
    /**
     * `"room"` or `"screen"`, from the entertainment configuration. Screen areas
     * place their lamps relative to a display, so wavefronts should emanate from
     * the screen rather than the middle of the floor.
     */
    private val configurationType: String = "room",
) {
    companion object {
        /**
         * Advanced live tunables: each is a multiplier on the active mode's params.
         *
         * `cohesion` is **direct-path only**. The Home Assistant path posts its own
         * tunable map straight to syncoV2's `hue_music_sync.set_options` service, and
         * that integration has no such option — so the two lists are deliberately
         * separate and must stay that way. See `LightSyncRepository.TUNABLE_DEFS`.
         */
        val TUNABLE_KEYS = listOf(
            "reactivity",    // flash punch
            "glow",          // continuous room brightness
            "movement",      // spatial motion
            "contrast",      // small-vs-big peak spread
            "colour_speed",  // colour drift speed
            "loudness",      // per-band absolute-loudness follow
            "cohesion",      // how much neighbouring lamps share a drive
        )

        /** The direct path's tunables, with labels, for its own settings screen. */
        val TUNABLE_DEFS = listOf(
            "reactivity" to "Reactivity",
            "glow" to "Glow",
            "movement" to "Movement",
            "contrast" to "Contrast",
            "colour_speed" to "Colour speed",
            "loudness" to "Loudness",
            "cohesion" to "Cohesion",
        )

        /**
         * What each tunable does, in the terms a listener can check against the room.
         *
         * Seven unlabelled percentage sliders is a control panel you can only learn by
         * moving one and watching, which is a slow way to find out that two of them
         * sound similar and one of them is the reason the room strobes. Each line says
         * what the factor multiplies and what turning it *both ways* does, because
         * "more reactivity" is only half an answer when the useful direction is often
         * down.
         *
         * Deliberately written against the effect rather than the implementation — the
         * multiplier lands on a different parameter in each case, and naming the
         * parameter would be true and useless.
         */
        val TUNABLE_BLURBS = mapOf(
            "reactivity" to
                "How hard a beat hits. Up for sharper, punchier flashes on drums; " +
                    "down when the room feels twitchy or the flashes arrive as strobing.",
            "glow" to
                "The steady light under the show, how bright the room stays between " +
                    "beats. Up to keep the space usable and lit; down for a darker room " +
                    "where only the peaks show.",
            "movement" to
                "How much the show travels between lamps rather than lighting them " +
                    "together. Up for sweeps and chases across the room; down to hold " +
                    "everything still and let brightness do the work.",
            "contrast" to
                "The gap between quiet moments and loud ones. Up to make big peaks tower " +
                    "over small ones; down to even the show out so soft passages still " +
                    "register.",
            "colour_speed" to
                "How quickly the room drifts between the album's colours. Up for colour " +
                    "that changes on the beat; down to hold one colour and let it settle.",
            "loudness" to
                "How much each lamp follows its own frequency band's actual level, rather " +
                    "than the mix as a whole. Up to separate bass, mids and vocals across " +
                    "the room; down for a room that reacts as one.",
            "cohesion" to
                "How much neighbouring lamps share a drive. Up so the room moves as a " +
                    "single wash; down so each lamp is more clearly its own instrument.",
        )
    }

    var palette: Palette = getPalette(FALLBACK_SCHEME)
    var mode: SyncMode = SyncMode.HIGH
        set(value) {
            field = value
            baseParams = MODE_PARAMS[value]!!
            params = withTunables(baseParams)
            updateRoles()
        }
    var brightness: Float = 1.0f
        set(value) { field = value.coerceIn(0f, 1f) }
    var time: Float = 0.0f
    var colourPhase: Float = 0.0f

    /** The active mode's params after tunables are applied. */
    private var params: ModeParams = MODE_PARAMS[mode]!!
    /** The mode's coded params before tunables. */
    private var baseParams: ModeParams = params
    /** Current tunable factors (1.0 = unchanged). */
    private val tunables = mutableMapOf<String, Float>()

    /**
     * Apply advanced live tunables. Each key scales the params the active mode
     * actually uses; a key missing from [values] is treated as 1.0.
     */
    fun setTunables(values: Map<String, Float>?) {
        tunables.clear()
        values?.let { tunables.putAll(it) }
        params = withTunables(baseParams)
    }

    private fun withTunables(base: ModeParams): ModeParams {
        if (tunables.isEmpty()) return base
        fun fac(name: String) = tunables[name]?.coerceAtLeast(0f) ?: 1f
        val r = fac("reactivity")
        val g = fac("glow")
        val m = fac("movement")
        val c = fac("contrast")
        val cs = fac("colour_speed")
        val loud = fac("loudness")
        return base.copy(
            spectralPop = base.spectralPop * r,
            melFluxGain = base.melFluxGain * r,
            beatGain = base.beatGain * r,
            melbankGain = base.melbankGain * g,
            // `glow` is documented as "continuous room brightness", which is exactly
            // what the sustain bloom is. Turning it up used to be the only way to
            // make a long vocal show at all — a global lift standing in for a
            // missing layer — and now it lifts the layer that was missing.
            tonalGain = base.tonalGain * g,
            rotateRate = base.rotateRate * m,
            rotateSwing = base.rotateSwing * m,
            waveSpeed = base.waveSpeed * m,
            // A room gesture is the most literal reading of `movement` there is —
            // the blurb promises "sweeps and chases across the room" and until now
            // nothing in the engine did that. An amount rather than a rate, which
            // is why it multiplies the gain and not the duration: the duration is
            // measured from the sound and must not be a preference.
            gestureGain = base.gestureGain * m,
            flashGamma = max(0.2f, base.flashGamma * c),
            // `contrast` is "small-vs-big peak spread", which is exactly what this
            // does within a lamp's slice of the spectrum. Until now it only reached
            // `flashGamma`, which `renderExtreme` alone reads — so on four of the
            // five rungs the slider did nothing at all.
            melPeakiness = min(1f, base.melPeakiness * c),
            colourSpeed = base.colourSpeed * cs,
            colourFlow = base.colourFlow * cs,
            bandLoudStrength = min(1f, base.bandLoudStrength * loud),
            // Its own key rather than folded into `movement`: that one scales *rates*
            // (rotation, wave speed), and cohesion is not a rate — turning movement
            // down would then fragment the room, which the label does not promise.
            spatialCoupling = min(1f, base.spatialCoupling * fac("cohesion")),
        )
    }

    // Channel layout (sorted left→right by x position)
    private val rankIds: List<Int> = channels.sortedBy { it.position.x }.map { it.channelId }
    private val nChannels: Int = rankIds.size

    // Per-channel info
    private data class ChannelInfo(
        val xrank: Float,    // 0..1 left→right
        val side: Float,     // -1 (left) .. +1 (right)
        val band: Int,       // frequency band assignment (0=bass, 1=mid, 2=high)
        /** Normalised position in the room, each axis 0..1. */
        val pos: Vec3,
        /**
         * Distance from each of the four phrase origins, precomputed so the
         * wave sum needs no square root per lamp per frame.
         */
        val distToOrigin: FloatArray,
        /** Half-open melbank window this lamp averages. */
        val melLo: Int,
        val melHi: Int,
        /**
         * Position along a tilted room axis, 0..1 — the colour field's own
         * coordinate. Uses all three axes rather than only x, so hue drifts through
         * the room's depth and height as well as its width. Falls back to [xrank]
         * when the lamps have no spread to project onto.
         */
        val spatialPos: Float,
        /**
         * Position along the room's principal axis, 0..1, oriented so that
         * increasing is room-right. The coordinate a linear gesture travels
         * along; [xrank] when there is no axis to speak of.
         */
        val axisPos: Float,
        /**
         * Where this lamp sits around the room, in turns counter-clockwise from
         * room-right. Meaningless unless [topology] is [RoomTopology.RING].
         */
        val azimuth: Float,
    )

    /** Normalised lamp positions, and the phrase-cycle wave origins over them. */
    private val positions: Map<Int, Vec3> = normalizePositions(channels)
    private val origins: List<Vec3> = phraseOrigins(positions, configurationType)

    /**
     * What shape the lamps are actually in, and the geometry that follows from
     * it. Classified once: lamps do not move while a stream is running, which is
     * the same argument [coupling] below already makes.
     */
    private val topology: RoomTopology = classifyTopology(channels)
    private val ring: Ring? =
        if (topology == RoomTopology.RING) fitRing(positions.values.toList()) else null

    /**
     * Whether the room has enough vertical spread to render a rising gesture on.
     * Usually false — the Hue app places lamps on a floor plan — in which case a
     * swell lifts the whole room together instead of sweeping upward through
     * lamps that are all at the same height.
     */
    private val roomHasHeight: Boolean = hasHeight(channels)

    private val cmap: Map<Int, ChannelInfo> = buildChannelMap(channels)

    /**
     * How much each lamp hears of each other lamp. Built once — the geometry does
     * not change while a session is running, and only the *amount* mixed in is
     * tunable. Rows sum to 1; see [couplingKernel].
     */
    private val coupling: Array<FloatArray> =
        couplingKernel(rankIds.map { cmap[it]?.pos ?: Vec3(0.5f, 0.5f, 0.5f) })

    // The two continuous per-lamp drives, before and after spatial coupling.
    // Preallocated and reused every frame, like melSlow/melTransient/melFlux below:
    // this runs sixty times a second and must not allocate.
    private val melLevelBuf = FloatArray(nChannels)
    private val popBuf = FloatArray(nChannels)
    private val melLevelSm = FloatArray(nChannels)
    private val popSm = FloatArray(nChannels)

    /**
     * Blend each lamp's continuous drive with its neighbours'.
     *
     * **Pre-smoothing, on the drive — never post-smoothing, on the output.** The
     * distinction is the whole design:
     *
     *  - Smoothing the *output* would smear `lightFlash` across lamps, which is
     *    what the bass/mid/vocal role split exists to keep apart, and would widen
     *    the travelling wavefront until the near-versus-far peak that makes a kick
     *    read as sweeping the room flattened out. It would also sit between
     *    `briAttack/briDecay` and the per-channel slew, coupling the smoothers to
     *    each other rather than the signal.
     *  - Smoothing the *drive* touches only the two terms that made each lamp an
     *    independent visualiser, and leaves every transient layer — flash, wave,
     *    swell, roles, shimmer — exactly as it was. The punch is untouched.
     *
     * **A spatial kernel has no temporal memory.** It is zero-phase and cannot
     * delay anything by construction, which is why it is safe to add to a render
     * whose timing is already right.
     *
     * The pop is coupled at half strength: fully diffusing the transient layer
     * would blunt the per-instrument detail it exists to provide.
     */
    private fun diffuseDrives(p: ModeParams) {
        val k = p.spatialCoupling
        if (k <= 0f || nChannels <= 1) {
            melLevelBuf.copyInto(melLevelSm)
            popBuf.copyInto(popSm)
            return
        }
        val kPop = k * POP_COUPLE_RATIO
        for (i in 0 until nChannels) {
            val row = coupling[i]
            var mixMel = 0f
            var mixPop = 0f
            for (j in 0 until nChannels) {
                val w = row[j]
                mixMel += w * melLevelBuf[j]
                mixPop += w * popBuf[j]
            }
            melLevelSm[i] = (1f - k) * melLevelBuf[i] + k * mixMel
            popSm[i] = (1f - kPop) * popBuf[i] + kPop * mixPop
        }
    }

    /**
     * Live wavefronts. Bounded at [MAX_WAVES]: each one costs a distance lookup
     * per lamp per frame, and more than a handful overlapping reads as a wash
     * rather than as motion.
     */
    private val waves = ArrayList<Wave>(MAX_WAVES)

    /** Which phrase origin the next wave launches from. */
    private var originIdx = 0

    /**
     * Contiguous, near-equal melbank spans — one per lamp, low to high. Extreme
     * assigns these across the room so instruments separate in space.
     */
    private val extBands: List<Pair<Int, Int>> = spectralBands(rankIds.size, MELBANK_BINS)

    /** Slow per-bin baseline; the melbank above it is a fresh attack. */
    private val melSlow = FloatArray(MELBANK_BINS)

    /** Per-bin novelty transient: the big, surprising peaks. */
    private val melTransient = FloatArray(MELBANK_BINS)

    /** Per-bin frame-to-frame flux: the groove, re-firing on every hit. */
    private val melFlux = FloatArray(MELBANK_BINS)

    private val melPrev = FloatArray(MELBANK_BINS)

    /** Position of the rotating lamp-to-band map. */
    private var spectralRot = 0f

    // State
    private val env = mutableMapOf<String, Float>()      // band envelopes
    private val presence = mutableMapOf<String, Float>() // per-band presence
    private var energyEnv = 0f                            // smoothed room loudness
    private var loud = 0f                                 // fast peak-hold for silence gate
    private val lightFlash = mutableMapOf<Int, Float>()  // per-light beat flash
    private val state = mutableMapOf<Int, Pair<Rgb, Float>>()  // (color, brightness)
    private val emitB = mutableMapOf<Int, Float>()        // slew-limited emitted brightness
    private val accents = ArrayDeque<Float>()             // recent beat accents for ranking
    private var barCount = 0
    private var phrase = 0
    private var beatsSeen = 0

    /** Full-field swell released by a drop, decaying back over ~half a second. */
    private var swell = 0f

    /**
     * Depth held the moment a drop landed. A deeper pre-drop pull-back earns a
     * bigger detonation; mirrors syncoV2 `predrop_released`.
     */
    private var predropReleased = 0f

    /** Pre-drop state machine: rendered envelope 0..1. */
    private var predrop = 0f
    private var predropCommit = false
    private var predropStreak = 0
    private var predropCommitT = 0f
    private var predropBlockUntil = 0f

    /** Evidence that the song currently has an actual beat. See [updateRhythmConf]. */
    private var rhythmConf = 0f

    /**
     * True once a wave has been launched for the upcoming beat, cleared when the
     * beat arrives. Without it the anticipation window fires a wave on every
     * frame it is open, which is a burst rather than a pulse.
     */
    private var waveArmed = false
    private var roleOffset = 0
    var roles: Map<Int, Int> = emptyMap()                 // channel → ROLE_BASS/MID/VOCAL
    private var roleMixEff: Triple<Float, Float, Float>? = null

    init {
        // Initialize per-channel state
        for (ch in channels) {
            state[ch.channelId] = Triple(0f, 0f, 0f) to 0f
            emitB[ch.channelId] = 0f
            lightFlash[ch.channelId] = 0f
        }
        updateRoles()
    }

    private fun buildChannelMap(channels: List<EntertainmentChannel>): Map<Int, ChannelInfo> {
        val sorted = channels.sortedBy { it.position.x }
        val n = sorted.size

        // The colour axis, min-max normalised across the area so the palette always
        // spans the room whatever shape it is. A collapsed projection — every lamp
        // at the same point on the axis — falls back to rank, which is what a
        // single-lamp or perfectly-stacked area needs.
        val projections = sorted.map { ch ->
            colourAxisProjection(positions[ch.channelId] ?: Vec3(0.5f, 0.5f, 0.5f))
        }
        val projLo = projections.minOrNull() ?: 0f
        val projHi = projections.maxOrNull() ?: 0f
        val projSpan = projHi - projLo

        // The gesture axis, from the room's *shape* rather than from the per-axis
        // normalisation above: five lamps in a line with a few centimetres of
        // wobble have that wobble stretched to the full width of the unit cube by
        // a per-axis fit, and stop reading as a line at all. See [roomShape].
        val shape = roomShape(channels)
        val shapePts = sorted.map { shape[it.channelId] ?: Vec3(0f, 0f, 0f) }
        val (rawDir, _) = principalAxis(shapePts)
        // Point it room-right, so a left-to-right sweep travels left to right
        // whichever way the eigenvector happened to come out. A vertical line has
        // no left-right component to orient by and keeps its own direction.
        val dir = if (rawDir.x < 0f) Vec3(-rawDir.x, -rawDir.y, -rawDir.z) else rawDir
        val axisRaw = shapePts.map { axisPosition(it, dir, Vec3(0f, 0f, 0f)) }
        val axisLo = axisRaw.minOrNull() ?: 0f
        val axisSpan = (axisRaw.maxOrNull() ?: 0f) - axisLo

        return sorted.mapIndexed { i, ch ->
            val xrank = if (n <= 1) 0.5f else i.toFloat() / (n - 1)
            val pos = positions[ch.channelId] ?: Vec3(xrank, 0.5f, 0.5f)
            val (melLo, melHi) = melbankWindow(xrank, MELBANK_BINS)
            val spatialPos =
                if (projSpan < 1e-6f) xrank else (projections[i] - projLo) / projSpan
            ch.channelId to ChannelInfo(
                xrank = xrank,
                side = 2f * xrank - 1f,
                band = when {
                    n <= 1 -> 0
                    i < n / 3 -> 0       // bass
                    i < 2 * n / 3 -> 1   // mid
                    else -> 2            // high
                },
                pos = pos,
                distToOrigin = FloatArray(origins.size) { k -> distance(pos, origins[k]) },
                melLo = melLo,
                melHi = melHi,
                spatialPos = spatialPos,
                axisPos = if (axisSpan < 1e-6f) xrank else (axisRaw[i] - axisLo) / axisSpan,
                azimuth = ring?.let { azimuthOf(pos, it) } ?: xrank,
            )
        }.toMap()
    }

    /**
     * Launch a wavefront from the current phrase origin.
     *
     * Fired [ModeParams.anticipationMs] *before* the predicted beat so the shell
     * is crossing the room as the kick lands, rather than starting from the
     * origin once it already has. That anticipation is the whole reason the beat
     * grid exists — a purely reactive wave always arrives late.
     */
    private fun spawnWave(strength: Float, p: ModeParams) {
        if (p.waveGain <= 0f || strength <= 0f) return
        if (waves.size >= MAX_WAVES) waves.removeAt(0)
        waves.add(
            Wave(
                origin = origins[originIdx % origins.size],
                strength = strength,
                speed = p.waveSpeed,
                width = p.waveWidth,
                originIdx = originIdx % origins.size,
            )
        )
    }

    /** Total wave amplitude at one lamp, using the precomputed distances. */
    private fun waveAmplitude(info: ChannelInfo): Float {
        if (waves.isEmpty()) return 0f
        var sum = 0f
        for (w in waves) sum += w.amplitudeAt(info.distToOrigin[w.originIdx])
        return sum
    }

    // ── Room gestures ─────────────────────────────────────────────────────

    /**
     * Whether the room is allowed to move with the music at all.
     *
     * Off by default and behind a user setting, because there is no way to judge
     * this from a unit test: the tests can prove the detector fires on a
     * synthetic sweep and not on a synthetic wobble, which is necessary and
     * nowhere near sufficient.
     */
    var spatialGestures: Boolean = false

    /**
     * The room's geometry in one line, for the session log.
     *
     * Whether the classifier got this particular room right is the first thing to
     * check on a device and the hardest to infer by watching lights: a ring
     * misread as a field looks like a gesture that simply did not work.
     */
    val roomSummary: String
        get() = buildString {
            append("$topology, $nChannels lamps")
            ring?.let { append(", ring r=${"%.2f".format(it.radius)} residual=${"%.2f".format(it.residual)}") }
            append(if (roomHasHeight) ", has height" else ", flat")
        }

    /** The id of the last gesture launched, so one can never fire twice. */
    private var lastGestureId = 0L

    /** At most one of these is non-null: a front along a coordinate, or a source. */
    private var front: TravellingFront? = null
    private var source: MovingSource? = null

    /** Which coordinate [front] is travelling along, while one is live. */
    private var frontAxis = GestureAxis.NONE

    private enum class GestureAxis { NONE, LINEAR, RING, HEIGHT, UNIFORM }

    /**
     * Turn a detected gesture into something this particular room can show.
     *
     * The same musical event renders four ways, and one of those ways is to
     * refuse. A [RoomTopology.CLUSTER] gets no traversal at all — two lamps on a
     * shelf cannot express "across the room", and flickering between them reads
     * as a fault. Philips make the same call for their own `AreaEffect`: better
     * to show nothing than to show it in the wrong place.
     */
    private fun launchGesture(g: GestureState, p: ModeParams) {
        front = null
        source = null
        frontAxis = GestureAxis.NONE

        // Strength drives brightness, duration comes from the sound. A slow pad
        // and a fast riser must not look the same, which is the whole reason the
        // detector measures a duration rather than assuming one.
        val strength = (g.strength * p.gestureGain).coerceIn(0f, GESTURE_MAX)
        if (strength <= 1e-4f || g.durationS <= 0f) return

        val rising = g.kind == GestureKind.SWELL || g.rise > RISE_FOR_VERTICAL

        // A swell in a room with real height rises through it. Most rooms have
        // none, and there the swell lifts everything together rather than picking
        // an arbitrary order among lamps at the same height.
        if (g.kind == GestureKind.SWELL) {
            if (roomHasHeight && rising) {
                front = TravellingFront(0f, 1f, g.durationS, strength, p.gestureWidth)
                frontAxis = GestureAxis.HEIGHT
            } else {
                front = TravellingFront(0f, 0f, g.durationS, strength, 1e6f)
                frontAxis = GestureAxis.UNIFORM
            }
            return
        }

        when (topology) {
            RoomTopology.CLUSTER -> return
            RoomTopology.LINEAR -> {
                front = TravellingFront(
                    from = (g.fromPan + 1f) / 2f,
                    to = (g.toPan + 1f) / 2f,
                    durationS = g.durationS,
                    strength = strength,
                    width = widthFor(p.gestureWidth, g.durationS),
                )
                frontAxis = GestureAxis.LINEAR
            }
            RoomTopology.RING -> {
                front = TravellingFront(
                    from = azimuthForPan(g.fromPan),
                    to = azimuthForPan(g.toPan),
                    durationS = g.durationS,
                    strength = strength,
                    width = widthFor(p.gestureArcWidth, g.durationS),
                    circular = true,
                )
                frontAxis = GestureAxis.RING
            }
            RoomTopology.FIELD -> {
                val my = positions.values.map { it.y }.average().toFloat()
                val mz = positions.values.map { it.z }.average().toFloat()
                source = MovingSource(
                    from = Vec3((g.fromPan + 1f) / 2f, my, mz),
                    to = Vec3((g.toPan + 1f) / 2f, my, mz),
                    durationS = g.durationS,
                    strength = strength,
                    radius = p.gestureRadius,
                )
            }
        }
    }

    /**
     * Widen a front rather than let it sharpen when the gesture is fast.
     *
     * If a lamp's dwell would fall under [MIN_DWELL_S] the room reads as a chase
     * rather than as a sweep — and the bridge relays at 25 Hz over Zigbee, so
     * nothing that quick survives the trip anyway. Philips' own swirl effect puts
     * a comfortable per-lamp dwell at about half a second.
     */
    private fun widthFor(base: Float, durationS: Float): Float {
        if (nChannels <= 1) return base
        val dwell = durationS * base
        if (dwell >= MIN_DWELL_S) return base
        return min(1f, base * MIN_DWELL_S / max(dwell, 1e-4f))
    }

    private fun advanceGestures(dt: Float) {
        front?.let { if (it.done) { front = null; frontAxis = GestureAxis.NONE } else it.advance(dt) }
        source?.let { if (it.done) source = null else it.advance(dt) }
    }

    /** What the live gesture, if any, adds at one lamp. */
    private fun gestureAmplitude(info: ChannelInfo): Float {
        source?.let { return it.amplitudeAt(info.pos) }
        val f = front ?: return 0f
        return when (frontAxis) {
            GestureAxis.LINEAR -> f.amplitudeAt(info.axisPos)
            GestureAxis.RING -> f.amplitudeAt(info.azimuth)
            GestureAxis.HEIGHT -> f.amplitudeAt(info.pos.z)
            // A width of a million makes the Gaussian flat, so every lamp gets the
            // same value: the whole room blooming together, which is what a swell
            // in a room with no height to travel through has to be.
            GestureAxis.UNIFORM -> f.amplitudeAt(0f)
            GestureAxis.NONE -> 0f
        }
    }

    // ── Public setters ────────────────────────────────────────────────────

    /** The scheme the user picked, so album art can be applied only when wanted. */
    private var scheme: ColorScheme = FALLBACK_SCHEME

    /** Fresh random colours on each beat. Only built while SONG is selected. */
    private val songPalette = SongPalette()

    fun setScheme(scheme: ColorScheme) {
        this.scheme = scheme
        palette = when {
            scheme == ColorScheme.SONG -> {
                songPalette.reset()
                Palette(songPalette.colors())
            }
            // Album art keeps whatever was last extracted, so switching to it
            // mid-track uses the cover already on screen.
            scheme.isDynamic -> albumPalette ?: getPalette(scheme)
            else -> getPalette(scheme)
        }
    }

    /** Latest album-art palette, retained across scheme changes. */
    private var albumPalette: Palette? = null

    /**
     * Hand the engine the colours pulled from the current track's artwork.
     *
     * [weights] are population shares: what fraction of the cover each colour
     * actually occupies. With them the palette holds each colour for its share
     * of the cycle rather than giving all of them equal time, which is what
     * makes the room look like the sleeve instead of like a rainbow of its
     * colours.
     *
     * Stored either way, so switching to an album-art scheme mid-track picks up
     * the artwork already on screen rather than waiting for the next song.
     */
    fun setAlbumColors(colors: List<Rgb>, weights: List<Float>? = null) {
        albumPalette = if (colors.isEmpty()) null else Palette(colors, weights)
        // Song draws its own colours, so artwork must not overwrite it.
        if (scheme.isDynamic && scheme != ColorScheme.SONG) {
            palette = albumPalette ?: getPalette(scheme)
        }
    }

    // ── Role assignment ───────────────────────────────────────────────────

    private fun updateRoles() {
        val mix = roleMixEff ?: params.roleMix
        val (bassF, midF, vocalF) = mix
        val n = nChannels
        if (n == 0) { roles = emptyMap(); return }
        val nBass = max(1, (n * bassF).toInt().coerceAtMost(n))
        val nMid = max(0, (n * midF).toInt().coerceAtMost(n - nBass))
        val nVocal = n - nBass - nMid
        val assigned = IntArray(n)
        var idx = 0
        repeat(nBass) { assigned[idx++ % n] = ROLE_BASS }
        repeat(nMid) { assigned[idx++ % n] = ROLE_MID }
        repeat(nVocal) { assigned[idx++ % n] = ROLE_VOCAL }
        // Rotate by roleOffset
        val rotated = IntArray(n) { assigned[(it + roleOffset) % n] }
        roles = rankIds.zip(rotated.toList()).toMap()
    }

    // ── Envelope update ───────────────────────────────────────────────────

    private fun updateEnv(frame: AnalysisFrame, dt: Float) {
        val rise = frameAlpha(ENV_RISE, dt)
        val fall = frameAlpha(ENV_FALL, dt)
        val pRise = frameAlpha(PRESENCE_RISE, dt)
        val pFall = frameAlpha(PRESENCE_FALL, dt)
        for ((name, value) in frame.bands) {
            val prev = env[name] ?: 0f
            val a = if (value > prev) rise else fall
            env[name] = prev + (value - prev) * a
            val pp = presence[name] ?: 0f
            val pa = if (value > pp) pRise else pFall
            presence[name] = pp + (value - pp) * pa
        }
        val a = if (frame.energy > energyEnv) rise else fall
        energyEnv += (frame.energy - energyEnv) * a
        loud = max(frame.energy, loud * frameDecay(GATE_DECAY, dt))
    }

    // ── Highlight ranking ─────────────────────────────────────────────────

    /**
     * Advance the pre-drop pull-down envelope and return its 0..1 value.
     *
     * Ported from syncoV2 `effects/engine.py:_update_predrop`. A scheduled drop
     * (known ETA) ramps to full depth; a heuristic build is treated with
     * suspicion: it must persist [PREDROP_CONFIRM] frames, is capped at
     * [PREDROP_HEUR_CAP], times out after [PREDROP_TIMEOUT_S], and then blocks
     * re-commit for [PREDROP_REFRACTORY_S].
     */
    private fun updatePredrop(structure: StructureState?, dt: Float): Float {
        predropReleased = 0f
        if (structure == null) {
            predrop = 0f
            predropCommit = false
            predropStreak = 0
            return 0f
        }

        if (structure.dropNow) {
            if (predrop > 0.05f) predropReleased = predrop
            predrop = 0f
            predropCommit = false
            predropStreak = 0
            return 0f
        }

        val scheduled = structure.dropEtaS >= 0f
        val target: Float
        if (scheduled) {
            predropCommit = true
            predropCommitT = time
            target = (1f - structure.dropEtaS / PREDROP_RAMP_S).coerceIn(0f, 1f)
        } else if (predropCommit) {
            if (time - predropCommitT > PREDROP_TIMEOUT_S) {
                predropCommit = false
                predropStreak = 0
                predropBlockUntil = time + PREDROP_REFRACTORY_S
                target = 0f
            } else {
                target = PREDROP_HEUR_CAP
            }
        } else if (structure.dropImminentHeuristic && time >= predropBlockUntil) {
            predropStreak++
            if (predropStreak >= PREDROP_CONFIRM) {
                predropCommit = true
                predropCommitT = time
            }
            target = 0f
        } else {
            predropStreak = 0
            target = 0f
        }

        val step = dt * TUNING_FPS
        predrop = if (target > predrop)
            min(target, predrop + PREDROP_RISE * step)
        else
            max(target, predrop - PREDROP_FALL * step)
        return predrop
    }

    /**
     * Is this beat strong enough to earn a full-brightness flash?
     *
     * Ranked against the most recent [ACCENT_WINDOW] beats, not against the whole
     * song. The window has to be bounded on both counts: an unbounded deque grows
     * for the life of a session and is re-sorted on every beat, and — the part
     * that shows — the threshold drifts toward an all-time percentile, so
     * highlights get steadily less selective the longer the music plays. A
     * rolling window keeps "loud for this passage" meaning what it says.
     */
    private fun beatHighlight(accent: Float, append: Boolean): Boolean {
        if (params.highlightQuantile <= 0f) return true
        if (accents.size < 8) return accent >= 0.3f
        // Ranked before joining the window, matching syncoV2: a beat competes
        // against its predecessors, not against itself.
        val sorted = accents.sorted()
        val thr = sorted[min(sorted.lastIndex, (params.highlightQuantile * sorted.size).toInt())]
        val ok = accent >= thr
        if (append) {
            accents.addLast(accent)
            while (accents.size > ACCENT_WINDOW) accents.removeFirst()
        }
        return ok
    }

    // ── Event gates ───────────────────────────────────────────────────────

    private data class EventGates(val ampScale: Float, val widthGate: Float)

    private fun eventGates(salience: Float, onsetWidth: Float): EventGates {
        val amp = params.salienceFloor + (1f - params.salienceFloor) * salience.pow(1f / params.salienceGamma)
        val wMin = params.widthMin
        val wSoft = params.widthSoft
        val widthGate = if (onsetWidth <= wMin) 0f
            else if (onsetWidth >= wMin + wSoft) 1f
            else (onsetWidth - wMin) / wSoft
        return EventGates(amp, widthGate)
    }

    // ── Beat flash ────────────────────────────────────────────────────────

    /**
     * Fast per-channel sparkle in 0..1.
     *
     * Two detuned sines multiplied, offset per channel, so neighbouring lamps
     * shimmer out of step with each other and the room glitters rather than
     * pulsing in unison. Deterministic, so a given lamp behaves the same way
     * every time rather than looking like noise.
     */
    private fun shimmerAt(t: Float, cid: Int): Float =
        0.5f + 0.5f * sin(t * 23.0f + cid * 2.7f) * sin(t * 8.0f + cid * 1.3f)

    /**
     * Track evidence that the song currently has an actual beat.
     *
     * A locked tempo grid is proof — dense mixes with buried kicks still lock.
     * While unlocked, only clearly broadband onsets count, per the onset-width
     * calibration, so tonal material cannot build confidence and then earn
     * flashes it should not have.
     */
    private fun updateRhythmConf(frame: AnalysisFrame, beatgrid: BeatGrid?) {
        var c = rhythmConf
        if (beatgrid != null && beatgrid.locked) {
            c += (1f - c) * RHYTHM_RISE
        } else if (frame.bassBeat) {
            val ev = ((frame.onsetWidth - RHYTHM_W_LO) / (RHYTHM_W_HI - RHYTHM_W_LO)).coerceIn(0f, 1f)
            if (ev > c) c += (ev - c) * RHYTHM_EVENT_RISE
        }
        rhythmConf = c * (1f - RHYTHM_FALL)
    }

    /**
     * How hard a *scheduled* beat should pulse, from its accent and its place in
     * the bar.
     *
     * Highlights — the rank-selected top accents of the recent passage — pulse at
     * full musical size, and selective rungs guarantee at least [HIGHLIGHT_MIN]
     * so a ranked beat never lands limp. Everything else gets only
     * [ModeParams.weakPulse], the quiet metronome between hits, which is zero on
     * Extreme so ordinary beats stay dark. [ModeParams.accentFloor] shapes the
     * response *within* highlights, and [ModeParams.downbeatPulse] guarantees the
     * bar's "one" lands either way, so the room never loses the pulse.
     */
    private fun pulseWeight(
        p: ModeParams,
        accent: Float,
        beatInBar: Int,
        highlight: Boolean,
        beatsPerBar: Int = 4,
    ): Float {
        var w: Float
        if (highlight) {
            var a = (accent - p.accentFloor) / max(1e-6f, 1f - p.accentFloor)
            a = a.coerceIn(0f, 1f)
            if (p.highlightQuantile > 0f) a = max(a, HIGHLIGHT_MIN)
            w = p.weakPulse + (1f - p.weakPulse) * a
        } else {
            w = p.weakPulse
        }
        if (beatInBar == 0) w = max(w, p.downbeatPulse)
        val weights = barWeights(beatsPerBar)
        return w * weights[beatInBar.mod(weights.size)]
    }

    private fun kickFlash(visStrength: Float, visBass: Float): Float {
        if (visStrength <= 0f) return 0f
        val knee = if (visStrength > params.beatThreshold) 1f else visStrength / params.beatThreshold
        return params.beatGain * knee * (params.kickBassFloor + (1f - params.kickBassFloor) * visBass)
    }

    // ── Render ─────────────────────────────────────────────────────────────

    /**
     * Advance time and produce per-channel RGB (0..1) for the active effect.
     * Call this at the analysis frame rate (~50 Hz).
     *
     * [dt] is seconds since the last frame. [frame] is the current audio
     * analysis frame. [beatgrid] and [structure] are deferred (null) in the
     * MVP — the engine renders purely reactively.
     */
    fun render(
        frame: AnalysisFrame,
        dt: Float,
        beatgrid: BeatGrid? = null,
        structure: StructureState? = null,
        gesture: GestureState? = null,
    ): Map<Int, Rgb> {
        time += dt
        updateEnv(frame, dt)

        val p = params

        // Per-bin transients feed the attack-pop layer below. Updated once per
        // frame here rather than inside the Extreme renderer, which is where it
        // used to live and why the music path had no pop at all.
        updateMelTransient(frame)

        // The rung chooses a renderer. Extreme is a different renderer rather than
        // a louder music one, which is why `graphReactive` exists as a flag on the
        // params.
        if (p.graphReactive) return renderExtreme(frame, dt)

        val musicGate = if (SILENCE_GATE > 0f) min(1f, loud / SILENCE_GATE) else 1f

        // Event-salience gates
        val (ampScale, widthGate) = eventGates(frame.salience, frame.onsetWidth)

        // Visible event: reactive first, with the scheduled beat folded in.
        // Detection still carries the show when the grid is unlocked or wrong;
        // the grid adds a beat the analyzer may have missed and, more
        // importantly, lets effects fire *before* the kick rather than after it.
        val locked = beatgrid?.locked == true
        var visStrength = if (frame.bassBeat) frame.bassStrength * widthGate else 0f
        val visBass = max(frame.bands["sub_bass"] ?: 0f, frame.bands["bass"] ?: 0f)

        // Flux gate: the phantom-beat killer. A beat arriving with no actual
        // spectral movement behind it is scaled away, so a grid that has drifted
        // cannot keep flashing an empty bar. Applied here rather than later so a
        // phantom never enters the highlight window and skews the ranking.
        // Only Intense enables it, which is what lets it afford so permissive a
        // width gate.
        if (p.fluxGate > 0f) {
            val lo = 0.35f * p.fluxGate
            val span = max(1e-6f, p.fluxGate - lo)
            val bassG = ((frame.bassFlux - lo) / span).coerceIn(0f, 1f)
            val midG = ((frame.midFlux - lo) / span).coerceIn(0f, 1f) * widthGate
            visStrength *= max(bassG, midG)
        }

        // Beat detection
        val accNow = if (visStrength > 0f) min(1f, max(0f, (visStrength - 1f) / 2f)) else 0f
        val beatNow = frame.bassBeat && widthGate > 0f
        val highlight = if (visStrength > 0f) beatHighlight(accNow, beatNow && musicGate > 0.5f) else false

        // Mid onsets (guitar/snare)
        var midStrength = if (frame.midBeat) frame.midStrength * widthGate else 0f

        // Does the song actually have a beat right now? A locked grid is proof;
        // while unlocked only clearly broadband onsets count, so sung vowels and
        // tonal swells that slip past a permissive width gate can never build
        // confidence. Rungs with nobeatFlash below 1 soften their flashes when
        // this is low, which is what stops Intense strobing through an ambient
        // passage.
        updateRhythmConf(frame, beatgrid)
        val rhythmGate = p.nobeatFlash + (1f - p.nobeatFlash) * rhythmConf

        // The sustain bloom, computed once for the room.
        updateTonalEnv(frame, dt, env, musicGate)

        // Loudness scale
        val loudScale = min(min(1f, max(visBass, energyEnv) / LOUD_REF), ampScale)

        // ── Musical structure ────────────────────────────────────────────
        // Tension through a build desaturates and tightens; the drop releases it
        // as a full-field swell. Without this every bar of a track gets the same
        // treatment however the song is shaped.
        val build = structure?.buildProgress ?: 0f
        val sectionLevel = structure?.sectionLevel ?: 1f
        val predrop = updatePredrop(structure, dt) * p.predropDepth * musicGate
        if (structure?.dropNow == true && p.dropBoost > 0f) {
            swell = max(swell, p.dropBoost * (1f + 0.35f * predropReleased) * musicGate)
            // A drop is a new section: reshuffle the room so the same lamps
            // aren't carrying the same roles through the whole track.
            if (p.dynamicRoles) { roleOffset++; updateRoles() }
        }
        swell *= frameDecay(SWELL_DECAY, dt)
        val satMul = 1f - max(p.buildDesat * build, 0.6f * predrop)

        // ── Phrase ───────────────────────────────────────────────────────
        // Bars are counted off the grid's downbeats, so the colour shift lands
        // on a musical boundary rather than every N detected onsets.
        if (locked && beatgrid!!.predictedBeat && beatgrid.beatInBar == 0) {
            barCount++
            if (p.phraseBars > 0 && barCount % p.phraseBars == 0) {
                phrase++
                originIdx = phrase % origins.size
                colourPhase += p.phraseColourShift * musicGate
            }
        }

        // Song: turn the palette over on the beat. Gated by musicGate for the
        // same reason everything else is — a grid ticking through silence must
        // not keep generating colours for a room nobody is listening in.
        if (scheme == ColorScheme.SONG && beatNow && musicGate > 0.5f) {
            songPalette.onBeat(peak = highlight, chroma = frame.chroma)
            palette = Palette(songPalette.colors())
        }

        // Colour advance
        colourPhase += p.colourSpeed * dt * musicGate
        if (p.colourFlow > 0f) {
            colourPhase += p.colourFlow * (0.25f + 0.75f * frame.energy) * dt * musicGate
        }
        val rolling = locked && beatgrid!!.periodS > 0.05f && p.colourBeatStep > 0f
        val sectionMul = 0.6f + 0.4f * sectionLevel
        if (beatNow && p.colourJump > 0f) {
            var step = p.colourJump * (0.55f + 0.45f * accNow) * musicGate
            if (highlight) step *= 1.7f
            step *= PHRASE_JUMP[phrase % PHRASE_JUMP.size]
            step *= sectionMul
            step *= (1f - 0.5f * predrop)  // hold still as the drop approaches
            colourPhase += step
            if (rolling) {
                colourPhase += p.colourBeatStep * 0.2f * (dt / beatgrid.periodS) * sectionMul
            }
        } else if (rolling) {
            colourPhase += p.colourBeatStep * 0.7f * (dt / beatgrid.periodS) * sectionMul
        }

        // ── Room gestures ────────────────────────────────────────────────
        // Launched on an *id change* rather than on a threshold, so a detection
        // that persists across frames cannot re-fire — the problem `waveArmed`
        // exists to solve for wavefronts, solved in the detector instead. Once
        // launched a gesture runs to completion on its own clock, so the visible
        // sweep is smooth however the detector's score jitters underneath it.
        if (spatialGestures && p.gestureGain > 0f) {
            if (gesture != null && gesture.kind != GestureKind.NONE && gesture.id != lastGestureId) {
                lastGestureId = gesture.id
                launchGesture(gesture, p)
            }
            advanceGestures(dt)
        } else if (front != null || source != null) {
            front = null
            source = null
            frontAxis = GestureAxis.NONE
        }

        // ── Wavefronts ───────────────────────────────────────────────────
        // Advance and retire first, then launch. Locked, a wave is fired
        // `anticipationMs` before the predicted beat so its shell is crossing
        // the room as the kick lands; unlocked, it fires on detection and is
        // inevitably a little late.
        if (waves.isNotEmpty()) {
            for (w in waves) w.advance(dt, WAVE_DECAY_TAU)
            waves.removeAll { it.dead() }
        }
        if (p.waveGain > 0f) {
            val gate = musicGate * ampScale * (1f - 0.8f * predrop)
            if (locked) {
                val g = beatgrid!!
                val antic = p.anticipationMs / 1000f
                if (!waveArmed && g.timeToNextBeat <= antic) {
                    waveArmed = true
                    val size = (0.45f + 1.05f * g.accent) * g.scheduleStrength
                    spawnWave(size * gate, p)
                }
                if (g.predictedBeat) waveArmed = false
            } else if (visStrength > 0f) {
                spawnWave(min(1.5f, 0.5f + visStrength) * gate, p)
            }
        }

        // Flash decay
        val flashFade = frameDecay(p.flashDecay, dt)
        for (cid in lightFlash.keys) lightFlash[cid] = lightFlash[cid]!! * flashFade

        // Beat flash assignment. Reactive detection is taken as the baseline and
        // the scheduled pulse folded in with max(), so an unlocked or mistaken
        // grid can only ever add to the show, never subtract from it.
        val scheduled = if (locked && beatgrid!!.predictedBeat) {
            p.beatGain *
                pulseWeight(
                    p, beatgrid.accentNow, beatgrid.beatInBar, highlight, beatgrid.beatsPerBar,
                ) *
                (0.6f + 0.4f * visBass) *
                beatgrid.scheduleStrength
        } else 0f
        // hardSnap rungs keep the full flash alongside the wave; the others give
        // the wave its share so the room doesn't double-hit on every beat.
        val flashScale = if (p.hardSnap) 1f else 1f - p.waveGain
        val kick = max(kickFlash(visStrength, visBass), scheduled) *
            flashScale * musicGate * loudScale * rhythmGate * (1f - 0.8f * predrop)
        val midf = if (midStrength > 0f) params.midGain * min(3f, midStrength / params.midThreshold) * musicGate * loudScale else 0f

        if (kick > 0f || midf > 0f) {
            val fullRoom = kick > 0f && highlight && accNow >= p.fullRoomAccent
            for ((cid, role) in roles) {
                if (fullRoom) {
                    val f = kick * (if (role == ROLE_VOCAL) 0.85f else 1f)
                    val fFinal = if (role == ROLE_MID) max(f, midf) else f
                    lightFlash[cid] = max(lightFlash[cid] ?: 0f, fFinal)
                } else if (role == ROLE_MID && midf > 0f) {
                    lightFlash[cid] = max(lightFlash[cid] ?: 0f, midf)
                } else if (role == ROLE_BASS && kick > 0f) {
                    lightFlash[cid] = max(lightFlash[cid] ?: 0f, kick)
                }
            }
        }

        // Role rotation on beats
        if (beatNow && p.roleRotateBeats > 0) {
            beatsSeen++
            if (beatsSeen >= p.roleRotateBeats) {
                beatsSeen = 0
                roleOffset++
                updateRoles()
            }
        }

        // Per-bin absolute-loudness weights, mean-normalised so they redistribute
        // rather than attenuate — see [melLoudWeights]. Computed once for the frame:
        // this used to be reached only by `renderExtreme`, which is why the
        // `loudness` tunable did nothing on any other rung.
        val melWeight = melLoudWeights(frame, p, normalise = true)

        // ── Pre-pass: the two per-lamp continuous drives ─────────────────
        //
        // Hoisted out of the render loop so they can be spatially coupled before
        // anything reads them. These two — the melbank glow and the attack pop —
        // are precisely the terms that made each lamp an independent visualiser:
        // both are computed from that lamp's own narrow slice of the spectrum and
        // from nothing else.
        val mel = frame.melbank
        val haveMel = mel.isNotEmpty() && mel.size >= MELBANK_BINS
        for ((rank, cid) in rankIds.withIndex()) {
            val info = cmap[cid]
            if (info == null) { melLevelBuf[rank] = 0f; popBuf[rank] = 0f; continue }

            melLevelBuf[rank] = if (haveMel) {
                val pan = frame.pan
                val usePan = p.panGain > 0f && pan.size >= info.melHi
                var sum = 0f
                var peak = 0f
                for (i in info.melLo until info.melHi) {
                    var v = mel[i] * (melWeight?.get(i) ?: 1f)
                    if (usePan) v *= (1f + p.panGain * pan[i] * info.side).coerceIn(0f, 2f)
                    sum += v
                    if (v > peak) peak = v
                }
                // Mean *and* peak. A held vocal occupies two or three of this
                // lamp's ~seven bins, so a pure mean delivered it at roughly a
                // third of its real height — audible as a mid that is present in
                // the music and absent from the room.
                val mean = sum / (info.melHi - info.melLo)
                (1f - p.melPeakiness) * mean + p.melPeakiness * peak
            } else 0f

            popBuf[rank] = if (p.spectralPop > 0f && mel.isNotEmpty()) {
                // Pan-weighted when the source is stereo: a hit panned left pops
                // the left of the room harder than the right. The weight is
                // clamped to 0..2 and divided by the bin count rather than by the
                // weight sum, so a centred mix comes out exactly as the unweighted
                // mean and nothing changes for mono material.
                val pan = frame.pan
                val usePan = p.panGain > 0f && pan.size >= info.melHi
                var sum = 0f
                var peak = 0f
                for (i in info.melLo until min(info.melHi, MELBANK_BINS)) {
                    var v = melTransient[i] * (melWeight?.get(i) ?: 1f)
                    if (usePan) v *= (1f + p.panGain * pan[i] * info.side).coerceIn(0f, 2f)
                    sum += v
                    if (v > peak) peak = v
                }
                // Same mean-and-peak blend as the glow above, for the same reason:
                // a single-bin transient averaged over seven bins arrives at a
                // seventh of its size.
                val mean = sum / (info.melHi - info.melLo)
                (1f - p.melPeakiness) * mean + p.melPeakiness * peak
            } else 0f
        }

        diffuseDrives(p)

        // Render each channel
        val out = HashMap<Int, Rgb>()
        for ((rank, cid) in rankIds.withIndex()) {
            val info = cmap[cid] ?: continue
            val role = roles[cid] ?: ROLE_BASS
            val melLevel = melLevelSm[rank]

            // Target brightness. The melbank floor is the ambient lift that
            // rides *under* the spectral layer, so it belongs inside the music
            // gate along with it — syncoV2 applies both as
            // `(melbank_floor + melbank_gain * drive) * music`. Hoisting the
            // floor out, as this did, leaves the room glowing through silence
            // and between tracks.
            var target = p.base
            target += (p.melbankFloor + p.melbankGain * melLevel) * musicGate
            target += p.energyGain * energyEnv

            // Attack pop: this lamp brightens on a *fresh* transient anywhere in
            // its own slice of the spectrum — a kick pops the low lamps, a snare
            // the low-mids, a guitar the mids, a cymbal the highs.
            //
            // This is the layer that makes a room feel alive between beats, and
            // it was missing from the music path entirely: every rung sets
            // spectralPop, but only the Extreme renderer read it, so Subtle
            // through Intense had no per-instrument detail at all — just the
            // beat flash and a smooth melbank glow. It is measured against a
            // slow per-bin baseline, so a held note settles and stops popping
            // while a repeated hit keeps firing.
            if (p.spectralPop > 0f) {
                target += p.spectralPop * popSm[rank] * musicGate
            }

            // Role-based brightness
            val bassEnv = max(env["sub_bass"] ?: 0f, env["bass"] ?: 0f)
            when (role) {
                ROLE_BASS -> target += p.bassGain * bassEnv
                ROLE_VOCAL -> {
                    target += p.vocalDim
                    // The human flavour: a vocal lamp still reacts to the music
                    // like any other, then shimmers with the singing on top. The
                    // 0.75 keeps it from simply adding brightness — the sparkle
                    // replaces part of the steady level rather than piling onto
                    // it, so a vocal lamp reads as *moving* rather than brighter.
                    if (p.shimmer > 0f) {
                        val treble = max(env["mid"] ?: 0f, env["high"] ?: 0f)
                        val vocalDrive = max(treble, 0.6f * (env["low_mid"] ?: 0f))
                        target = 0.75f * target + p.shimmer * vocalDrive * shimmerAt(time, cid)
                    }
                }
            }
            // Rungs with no role split keep the classic everywhere-sparkle.
            if (p.shimmer > 0f && roles.isEmpty()) {
                val treble = max(env["mid"] ?: 0f, env["high"] ?: 0f)
                target += p.shimmer * treble * shimmerAt(time, cid)
            }

            // Height: bass sits on the floor and treble at the ceiling, the way
            // a room's energy naturally stacks.
            // Blended across the two bands the lamp sits between, not snapped to the
            // nearer one. `heightBand` is a five-way step, so two lamps a hand's
            // width apart either side of a boundary followed entirely different
            // parts of the spectrum — one of the reasons a room read as separate
            // instruments rather than one field.
            if (p.heightFreq > 0f) {
                val lower = heightBandLower(info.pos.z)
                val frac = heightBandFrac(info.pos.z)
                val lo = env[HEIGHT_BANDS[lower]] ?: 0f
                val hi = env[HEIGHT_BANDS[min(lower + 1, HEIGHT_BANDS.size - 1)]] ?: 0f
                target += p.heightFreq * (lo + (hi - lo) * frac) * musicGate
            }
            // Depth: lamps further back carry a broader wash and less detail.
            if (p.depthWash > 0f) {
                target += p.depthWash * energyEnv * (1f - info.pos.y)
            }

            // Sustain bloom: one room-wide value, so a long vocal lifts every lamp
            // together into a single glow rather than nudging whichever one happens
            // to own that part of the spectrum.
            //
            // A `target` addend, deliberately not part of `flash` below. That puts
            // it through briAttack/briDecay and the brightness slew, so its
            // per-frame change stays roughly two orders under FLASH_DELTA — well
            // inside what the 12.5 Hz effect ceiling permits as a smooth gradation,
            // and invisible to both the WCAG limiter and the rate limiter.
            if (p.tonalGain > 0f) target += p.tonalGain * tonalEnv

            // Pre-drop compresses the headroom toward the floor, so the drop has
            // somewhere to go.
            if (predrop > 0f) target = p.floor + (target - p.floor) * (1f - predrop)
            target = target.coerceIn(0f, 1f)

            // Flash overlay: the per-light beat flash, the wavefront sweeping
            // past, any drop swell still ringing, and a room gesture travelling
            // through.
            //
            // The gesture belongs here rather than in `target`, and for the
            // opposite reason to the sustain bloom above. The bloom is room-wide
            // and wants briAttack/briDecay under it; a *travelling* front must not
            // have them, because briDecay would smear it as it moves off each lamp
            // and flatten the near-versus-far difference that is the only reason
            // it reads as motion at all. It is still slew-limited on the rise, and
            // it is smooth by construction anyway.
            val wave = if (p.waveGain > 0f) p.waveGain * waveAmplitude(info) * musicGate else 0f
            val gest = if (front != null || source != null) gestureAmplitude(info) * musicGate else 0f
            val flash = (lightFlash[cid] ?: 0f) + wave + swell + gest

            // Smooth brightness
            val (prevColor, prevB) = state[cid] ?: (Triple(0f, 0f, 0f) to 0f)
            val alpha = frameAlpha(if (target >= prevB) p.briAttack else p.briDecay, dt)
            var newB = prevB + (target - prevB) * alpha

            // Colour, on a tilted room axis rather than left-to-right alone.
            // `colourTilt = 0` is byte-identical to the x-only behaviour, and a room
            // with no depth or height spread is identical either way — see
            // [colourAxisProjection].
            val cbase = info.xrank + (info.spatialPos - info.xrank) * p.colourTilt
            val cpos = cbase * p.colourSpread + colourPhase
            val tgtColor = palette.sample(cpos)
            val (tr, tg, tb) = tgtColor
            val tm = max(tr, max(tg, tb))
            val nrTgt = if (tm > 1e-6f) Triple(tr / tm, tg / tm, tb / tm) else Triple(0f, 0f, 0f)
            val (nr, ng, nb) = nrTgt
            val (pr, pg, pb) = prevColor
            val cLerp = frameAlpha(p.colourLerp, dt)
            var nc = Triple(
                pr + (nr - pr) * cLerp,
                pg + (ng - pg) * cLerp,
                pb + (nb - pb) * cLerp,
            )

            // Saturation. Tension through a build pulls the room toward white,
            // which reads as strain and makes the drop's return of colour land.
            val sat = (p.colourSat * satMul).coerceIn(0f, 1f)
            if (sat < 1f) {
                val (cr, cg, cb) = nc
                nc = Triple(
                    cr * sat + (1 - sat),
                    cg * sat + (1 - sat),
                    cb * sat + (1 - sat),
                )
            }

            // Flash + brightness, slew-limited
            // Brightness moves no faster than the rung's rise/fall ceiling, in
            // full scale per second. A single-frame jump is what bulbs render as
            // a hard edge, and what the downstream rate limiter used to quantise
            // into a staircase; capping the *rate* removes both. The fall is
            // always the slower of the two, per Philips' guidance that brightness
            // should transition more slowly than colour.
            var b = min(1f, newB + flash)
            val prevEmit = emitB[cid] ?: 0f
            b = b.coerceIn(prevEmit - p.briFallRate * dt, prevEmit + p.briRiseRate * dt)
            emitB[cid] = b
            b *= brightness

            // Renormalize colour and apply brightness
            val (cr, cg, cb) = nc
            val cm = max(cr, max(cg, cb))
            val finalC = if (cm > 1e-6f) Triple(cr / cm, cg / cm, cb / cm) else Triple(0f, 0f, 0f)
            val (fr, fg, fb) = finalC
            out[cid] = Triple(fr * b, fg * b, fb * b)

            // Save state
            state[cid] = nc to newB
        }

        return out
    }

    /**
     * Update the per-bin attack measures Extreme runs on.
     *
     * Two of them, deliberately. The *transient* is the melbank above a slow
     * asymmetric baseline, which catches big surprising peaks like a drop but
     * absorbs a steady pattern once the baseline catches up. The *flux* is the
     * frame-to-frame rise, which keeps re-firing on every hit of that same
     * pattern. Taking the larger of the two is what lets the room read a song's
     * whole detail rather than only its biggest moments.
     */
    private fun updateMelTransient(frame: AnalysisFrame) {
        val mel = frame.melbank
        if (mel.isEmpty()) return
        val n = min(MELBANK_BINS, mel.size)
        for (i in 0 until n) {
            val v = mel[i]
            val a = if (v > melSlow[i]) MEL_SLOW_RISE else MEL_SLOW_FALL
            melSlow[i] += (v - melSlow[i]) * a
            melTransient[i] = max(0f, v - melSlow[i])
            melFlux[i] = max(0f, v - melPrev[i])
            melPrev[i] = v
        }
    }

    /**
     * Extreme: **the song is a graph**, and each lamp reflects a slice of it.
     *
     * The melbank is split into contiguous bands spanning low to high and
     * assigned across the room left to right, so instruments separate in space —
     * a kick lights the low lamps, a snare or guitar the mids, a cymbal the
     * highs. Each lamp's brightness is two parts: a smooth glow from its band's
     * loudness, with the hottest bin lifted in so one loud instrument reads
     * instead of being averaged into a wash; and a peak flash proportional to a
     * fresh attack in that band.
     *
     * The lamp-to-band map rotates slowly, grid-free and loudness-scaled, so
     * every lamp takes turns on every instrument while all bands stay covered at
     * each instant.
     *
     * There is deliberately no beat grid here, no scheduled beat and no onset
     * gating: a held tone has no fresh attack so it only glows and never
     * strobes, a tail fades as its loudness does, and every real hit at any
     * tempo flashes as big as it actually is.
     *
     * This is the rung the direct path was most wrong about. `graphReactive` was
     * declared and never read, so Extreme fell through to the ordinary music
     * path — where it sets `beatThreshold = 99` and zeroes its beat and bass
     * gains, leaving it the least reactive rung after Subtle.
     *
     * Ported from syncoV2 `effects/engine.py::_render_extreme`. The
     * absolute-loudness melbank reference comes from a track scan when the track
     * has one and from the analyzer's own live estimate otherwise — see
     * [melLoudWeights]. (This used to say no live estimate was possible, on the
     * reasoning that a per-bin AGC divides out what it measures. That holds for
     * the published melbank and not for the raw means it is computed from.)
     */
    // ── Sustain bloom ─────────────────────────────────────────────────────

    private var tonalEnv = 0f
    private val chromaPrev = FloatArray(12)
    private var chromaFlux = 0f
    private var roomTransient = 0f

    /**
     * A slow, room-wide glow that rises on sustained, pitched, mid-heavy material.
     *
     * Deliberately the mirror image of [eventGates]: that returns zero for a
     * narrowband onset, and this is near one there. Between them the two cover the
     * whole of `onsetWidth` rather than leaving the tonal half of it dark.
     *
     * Four things have to agree before it commits, because "narrowband" alone is
     * not enough — a cymbal wash and a held note are both narrowband by inverse
     * participation ratio, and only one of them should bloom:
     *
     *  - **tonality**: `onsetWidth` low, on the same soft knee `eventGates` uses.
     *  - **mid presence**: the 250–2500 Hz bands, where voices and strings live.
     *  - **chroma stability**: a held *pitch*, not a wash. This is what separates
     *    a vocal from room tone.
     *  - **transient quiet**: mutually damped against the layers that already
     *    handle percussion, so a beat and a bloom cannot both claim the same
     *    moment. Only mildly damped against the beat grid, though — a vocal over a
     *    steady groove is most music, and must still glow.
     *
     * Scaled by `salience`, which is AGC-immune, so a quiet passage blooms less
     * rather than being normalised up to a chorus.
     */
    private fun updateTonalEnv(
        frame: AnalysisFrame,
        dt: Float,
        env: Map<String, Float>,
        musicGate: Float,
    ) {
        val p = params
        if (p.tonalGain <= 0f) { tonalEnv = 0f; return }

        val tonality =
            ((p.tonalWidthMax - frame.onsetWidth) / max(1e-3f, p.tonalWidthSoft)).coerceIn(0f, 1f)

        val midPresence = max(env["low_mid"] ?: 0f, env["mid"] ?: 0f)

        // Total variation between successive chroma vectors, each normalised to
        // sum 1 so loudness cannot masquerade as harmonic movement.
        val chroma = frame.chroma
        val chromaStable = if (chroma.size == chromaPrev.size) {
            var total = 0f
            for (v in chroma) total += v
            if (total > 1e-9f) {
                var diff = 0f
                for (i in chroma.indices) {
                    val n = chroma[i] / total
                    diff += abs(n - chromaPrev[i])
                    chromaPrev[i] = n
                }
                chromaFlux += (0.5f * diff - chromaFlux) * CHROMA_FLUX_SMOOTH
            }
            1f - (chromaFlux / CHROMA_FLUX_REF).coerceIn(0f, 1f)
        } else {
            1f  // no chroma this frame: neutral rather than blocking
        }

        var transientNow = 0f
        if (melTransient.isNotEmpty()) {
            var sum = 0f
            for (i in melTransient.indices) sum += melTransient[i] + melFlux[i]
            transientNow = sum / melTransient.size
        }
        // Fast up, slow down: a hit should suppress the bloom immediately and let
        // it back only once the hits have actually stopped.
        val tAlpha = if (transientNow > roomTransient) TONAL_DAMP_RISE else TONAL_DAMP_FALL
        roomTransient += (transientNow - roomTransient) * tAlpha
        val transientQuiet =
            1f - (p.tonalDamp * roomTransient / TONAL_DAMP_REF).coerceIn(0f, 1f)

        val drive = tonality * midPresence * chromaStable * transientQuiet *
            (1f - TONAL_RHYTHM_DAMP * rhythmConf) * frame.salience * musicGate

        // dt-correct, so a stalled or jittery frame interval cannot make the bloom
        // jump. dt = 0 gives alpha = 0, which is a no-op rather than a NaN.
        val tau = if (drive > tonalEnv) p.tonalAttackS else p.tonalReleaseS
        val alpha = if (tau > 1e-4f) 1f - exp(-dt / tau) else 1f
        tonalEnv += (drive - tonalEnv) * alpha
    }

    // ── Per-bin absolute-loudness weights ─────────────────────────────────

    /** Cache key: the reference array's identity, the strength, and the mode. */
    private var loudWeightRef: FloatArray? = null
    private var loudWeightStrength = -1f
    private var loudWeightNormalised = false
    private var loudWeightCache: FloatArray? = null

    /**
     * Per-bin weights that put absolute band loudness back into the melbank.
     *
     * The melbank is per-bin AGC'd, so a hi-hat tick and a kick arrive the same
     * height and every lamp reads equally bright. These weights restore the
     * difference, perceptually compressed and strength-blended so a quiet
     * instrument dims rather than disappearing.
     *
     * The reference comes from an offline track scan where there is one, and from
     * the analyzer's own long-horizon pre-AGC average where there is not.
     *
     * [normalise] decides what shape the result has, and the two are not
     * interchangeable. The raw form is `(1-s) + s*ref^0.5` with `ref` in 0..1, so
     * it is always **≤ 1** — a pure attenuator. `renderExtreme`'s gains are tuned
     * against that and must keep it. The main path must not: `melbankRef` peaks in
     * the bass on most material, so an attenuator would dim the mids specifically,
     * which is the opposite of what it is being added for. Mean-normalising makes
     * it redistribute instead, leaving the room's overall level alone.
     *
     * Cached on the reference's identity — `DirectLightSync` attaches the same
     * array for a whole track — which also takes 16 `pow` calls per frame out of
     * Extreme's render.
     */
    private fun melLoudWeights(
        frame: AnalysisFrame,
        p: ModeParams,
        normalise: Boolean,
    ): FloatArray? {
        if (p.bandLoudStrength <= 0f) return null
        val mel = frame.melbank
        if (mel.isEmpty()) return null
        // The scan's reference is authoritative; the live estimate is the fallback.
        val ref = frame.melbankRef.takeIf { it.size == mel.size }
            ?: frame.melbankRefLive.takeIf { it.size == mel.size }
            ?: return null

        if (ref === loudWeightRef &&
            p.bandLoudStrength == loudWeightStrength &&
            normalise == loudWeightNormalised
        ) {
            return loudWeightCache
        }

        val w = FloatArray(ref.size) { i ->
            (1f - p.bandLoudStrength) +
                p.bandLoudStrength * ref[i].coerceIn(0f, 1f).pow(BAND_LOUD_COMPRESS)
        }
        if (normalise) {
            var sum = 0f
            for (v in w) sum += v
            val mean = sum / w.size
            if (mean > 1e-6f) {
                for (i in w.indices) w[i] = (w[i] / mean).coerceIn(LOUD_WEIGHT_MIN, LOUD_WEIGHT_MAX)
            }
        }

        loudWeightRef = ref
        loudWeightStrength = p.bandLoudStrength
        loudWeightNormalised = normalise
        loudWeightCache = w
        return w
    }

    private fun renderExtreme(frame: AnalysisFrame, dt: Float): Map<Int, Rgb> {
        val p = params
        val musicGate = if (SILENCE_GATE > 0f) min(1f, loud / SILENCE_GATE) else 1f

        // Colour only drifts, never jumps on a beat: brightness carries the song
        // so the colour field stays coherent underneath it.
        colourPhase += (p.colourSpeed + p.colourFlow * frame.energy) * dt * musicGate

        val mel = frame.melbank
        val n = rankIds.size
        val haveBands = mel.isNotEmpty() && extBands.isNotEmpty()

        val melWeight = if (haveBands) melLoudWeights(frame, p, normalise = false) else null

        // Grid-free rotation: time-driven, faster through busy passages, frozen
        // in silence by the music gate, so it can add no phantom beats.
        val rotRate = p.rotateRate + p.rotateSwing * frame.energy
        if (n > 1 && rotRate > 0f) {
            spectralRot = (spectralRot + rotRate * dt * musicGate).mod(n.toFloat())
        }

        fun bandGlow(lo: Int, hi: Int): Float {
            if (mel.isEmpty() || hi <= lo || hi > mel.size) return 0f
            var tot = 0f
            var mx = 0f
            for (k in lo until hi) {
                val v = mel[k] * (melWeight?.get(k) ?: 1f)
                tot += v
                if (v > mx) mx = v
            }
            val mean = tot / (hi - lo)
            return (1f - EXT_GLOW_PEAKINESS) * mean + EXT_GLOW_PEAKINESS * mx
        }

        fun bandPeak(lo: Int, hi: Int): Float {
            if (hi <= lo) return 0f
            var mx = 0f
            for (k in lo until min(hi, MELBANK_BINS)) {
                val w = melWeight?.get(k) ?: 1f
                var r = melTransient[k] * w
                // Only the part of the groove flux above the ambient floor, so
                // room tone and reverb wash never flash — real hits do.
                val fxk = p.melFluxGain * (melFlux[k] - p.melFluxFloor) * w
                if (fxk > r) r = fxk
                if (r > mx) mx = r
            }
            return mx
        }

        val flashFade = frameDecay(p.flashDecay, dt)
        for (cid in lightFlash.keys) lightFlash[cid] = lightFlash[cid]!! * flashFade

        // A hit in a quiet passage cannot flash as bright as one in a drop; the
        // melbank is AGC-relative, so absolute loudness has to come from salience.
        val loudScale = p.flashLoudFloor + (1f - p.flashLoudFloor) * frame.salience

        // Whole-room slam: a broadband transient spiking many bands at once
        // punches the entire room in unison, while a single-band tick barely
        // registers, so the per-band detail survives.
        var roomSlam = 0f
        if (p.roomPunch > 0f && haveBands) {
            var tot = 0f
            var mx = 0f
            for (v in melFlux) {
                val d = v - p.melFluxFloor
                if (d > 0f) { tot += d; if (d > mx) mx = d }
            }
            val roomFx = 0.8f * (tot / melFlux.size) + 0.2f * mx
            roomSlam = p.roomPunch * roomFx.pow(EXT_ROOM_GAMMA) * p.spectralPop * musicGate * loudScale
        }

        val out = HashMap<Int, Rgb>(n)
        for ((rankI, cid) in rankIds.withIndex()) {
            val info = cmap[cid] ?: continue
            var level: Float
            var peak: Float
            if (haveBands) {
                // Rotating assignment with a smoothstep crossfade between the two
                // bands a lamp straddles, so it sits crisply on one band then
                // moves quickly to the next and separation stays sharp.
                val pos = (rankI + spectralRot).mod(n.toFloat())
                val b0 = pos.toInt() % n
                var frac = pos - pos.toInt()
                frac = frac * frac * (3f - 2f * frac)
                val b1 = (b0 + 1) % n
                val (lo0, hi0) = extBands[b0]
                val (lo1, hi1) = extBands[b1]
                level = (1f - frac) * bandGlow(lo0, hi0) + frac * bandGlow(lo1, hi1)
                peak = (1f - frac) * bandPeak(lo0, hi0) + frac * bandPeak(lo1, hi1)
            } else {
                // No melbank at all (minimal frames): fall back to the coarse
                // band envelope so the room still lives, with no flash.
                level = max(env["sub_bass"] ?: 0f, env["bass"] ?: 0f)
                peak = 0f
            }

            // Expand the contrast so small ticks stay dim and big hits slam, then
            // scale by absolute loudness so peaks read relatively rather than all
            // saturating to full.
            var flash = peak.pow(p.flashGamma) * p.spectralPop * musicGate * loudScale
            if (roomSlam > flash) flash = roomSlam
            if (flash > (lightFlash[cid] ?: 0f)) lightFlash[cid] = flash

            // Clamped, as the music path clamps its own target. A real analyzer
            // melbank is AGC-normalised to 0..1, but the engine writes straight
            // to the wire: a malformed frame in must not become a malformed
            // frame out, and the encoder maps these onto xy plus brightness
            // where a negative is not a wrong colour but a broken packet.
            val target = (
                p.melbankFloor +
                    p.melbankGain * level * musicGate +
                    p.energyGain * energyEnv
                ).coerceIn(0f, 1f)

            val (prevColor, prevB) = state[cid] ?: (Triple(0f, 0f, 0f) to 0f)
            val alpha = frameAlpha(if (target >= prevB) p.briAttack else p.briDecay, dt)
            val newB = prevB + (target - prevB) * alpha

            // Colour stays keyed to the lamp's fixed position plus the drift
            // phase: only the instrument activity rotates, so the room keeps a
            // coherent colour field.
            val cbase = info.xrank + (info.spatialPos - info.xrank) * p.colourTilt
            val tgt = palette.sample(cbase * p.colourSpread + colourPhase)
            val m = max(tgt.first, max(tgt.second, tgt.third))
            val nt = if (m > 1e-6f) Triple(tgt.first / m, tgt.second / m, tgt.third / m)
            else Triple(0f, 0f, 0f)
            val cLerp = frameAlpha(p.colourLerp, dt)
            var nc = Triple(
                prevColor.first + (nt.first - prevColor.first) * cLerp,
                prevColor.second + (nt.second - prevColor.second) * cLerp,
                prevColor.third + (nt.third - prevColor.third) * cLerp,
            )
            state[cid] = nc to newB

            if (p.colourSat < 1f) {
                val s = p.colourSat
                nc = Triple(nc.first * s + (1 - s), nc.second * s + (1 - s), nc.third * s + (1 - s))
            }
            val cval = max(nc.first, max(nc.second, nc.third))
            // Extreme bypasses FieldSafety at the user's request, so the rate
            // ceiling is the only thing shaping its edges. Slewed before the
            // colour-value term and the user's ceiling, matching the music path.
            val prevEmit = emitB[cid] ?: 0f
            val slewed = min(1f, newB + (lightFlash[cid] ?: 0f))
                .coerceIn(prevEmit - p.briFallRate * dt, prevEmit + p.briRiseRate * dt)
                .coerceIn(0f, 1f)
            emitB[cid] = slewed
            val b = slewed * (0.35f + 0.65f * cval) * brightness
            out[cid] = Triple(
                (nc.first * b).coerceIn(0f, 1f),
                (nc.second * b).coerceIn(0f, 1f),
                (nc.third * b).coerceIn(0f, 1f),
            )
        }
        return out
    }

    /**
     * Idle glow for paused/stopped state: colours flow gently across the room.
     */
    fun renderIdle(t: Float, level: Float = 0.20f): Map<Int, Rgb> {
        predrop = 0f
        predropCommit = false
        predropStreak = 0
        predropReleased = 0f
        val dim = level * brightness
        val out = HashMap<Int, Rgb>()
        for (cid in rankIds) {
            val info = cmap[cid] ?: continue
            val c = palette.sample(info.xrank + t * 0.045f)
            val (r, g, b) = c
            val m = max(r, max(g, b))
            val nc = if (m > 1e-6f) Triple(r / m, g / m, b / m) else Triple(0f, 0f, 0f)
            val d = dim * (0.5f + 0.5f * m)
            val (nr, ng, nb) = nc
            out[cid] = Triple(nr * d, ng * d, nb * d)
        }
        return out
    }

    /**
     * Slow ambient "show" for a genuinely idle/paused room.
     *
     * Colours drift across the room and through the palette while two slow,
     * out-of-phase spatial waves make the light seem to wander. [t] is elapsed
     * seconds; [intensity] (0..1) fades the movement in, so at 0 this matches
     * [renderIdle] and at 1 the full wandering show is active.
     */
    fun renderIdleShow(t: Float, dt: Float, intensity: Float = 1f): Map<Int, Rgb> {
        predrop = 0f
        predropCommit = false
        predropStreak = 0
        predropReleased = 0f
        val p = params
        val inten = intensity.coerceIn(0f, 1f)
        val base = 0.22f
        val amp = 0.30f * inten
        val breath = 0.88f + 0.12f * sin(2f * PI.toFloat() * 0.045f * t)
        val out = HashMap<Int, Rgb>()
        for (cid in rankIds) {
            val info = cmap[cid] ?: continue
            val c = palette.sample(info.xrank * 0.6f + t * 0.045f)
            val (r, g, b) = c
            val m = max(r, max(g, b))
            val nc = if (m > 1e-6f) Triple(r / m, g / m, b / m) else Triple(0f, 0f, 0f)
            val w1 = 0.5f + 0.5f * sin(2f * PI.toFloat() * (info.pos.x * 0.9f - t * 0.10f))
            val w2 = 0.5f + 0.5f * sin(2f * PI.toFloat() * (info.pos.z * 0.7f + t * 0.06f) + 1.7f)
            val wave = 0.6f * w1 + 0.4f * w2
            // Rate-limited and recorded exactly as the music path does, because
            // `emitB` means "what this engine last put on the wire" and the slew
            // clamps the next frame against it. Rendering straight to the output
            // here left that memory holding a value from before the pause, so the
            // first frame back on the music path clamped against *that* and the
            // room flashed to roughly its pre-pause brightness before sliding
            // down. It also makes dropping into the idle show a fade rather than
            // a step.
            val prevEmit = emitB[cid] ?: 0f
            val level = ((base + amp * wave) * breath * (0.5f + 0.5f * m))
                .coerceIn(prevEmit - p.briFallRate * dt, prevEmit + p.briRiseRate * dt)
                .coerceIn(0f, 1f)
            emitB[cid] = level
            val d = brightness * level
            val (nr, ng, nb) = nc
            out[cid] = Triple(nr * d, ng * d, nb * d)
        }
        return out
    }
}
