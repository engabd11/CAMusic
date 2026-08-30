package com.engabd.sendpin.hue

import kotlin.math.max
import kotlin.math.pow

/**
 * What the light show *is*: the modes, the effects, the colour schemes, and the
 * ~50 numbers behind each intensity rung.
 *
 * Split out of `SyncoEngine.kt` — see the note at the top of that file. This half is
 * pure data with no behaviour at all, and it changes for a completely different
 * reason from the renderer: a preset is retuned by ear, the renderer is changed by
 * argument.
 */

// ── Types ─────────────────────────────────────────────────────────────────

typealias Rgb = Triple<Float, Float, Float>

enum class SyncMode(val wire: String) {
    SUBTLE("subtle"), MEDIUM("medium"), HIGH("high"), INTENSE("intense"), EXTREME("extreme");
    companion object { fun fromWire(s: String?) = entries.firstOrNull { it.wire == s } ?: HIGH }
}

enum class ColorScheme(val wire: String) {
    /**
     * Derived from what is playing rather than fixed. These render the static
     * fallback until something supplies colours — see
     * [SyncoEngine.setAlbumColors].
     */
    ALBUM_ART("album_art"), ALBUM_ART_V2("album_art_v2"), SONG("song"),
    SUNSET("sunset"), OCEAN("ocean"), FOREST("forest"), LAVENDER("lavender"),
    EMBER("ember"), AURORA("aurora"), RAINBOW("rainbow"),
    TROPICAL("tropical"), SAVANNA("savanna"), BLOSSOM("blossom"),
    HONOLULU("honolulu"), GALAXY("galaxy"),
    NEON("neon"), PEACOCK("peacock"), CITRUS("citrus"), ROSEGOLD("rosegold");

    /** True for the schemes that take their colours from what is playing. */
    val isDynamic: Boolean get() = this == ALBUM_ART || this == ALBUM_ART_V2 || this == SONG

    companion object { fun fromWire(s: String?) = entries.firstOrNull { it.wire == s } ?: ALBUM_ART_V2 }
}

// ── Frame-rate normalisation ────────────────────────────────────────────────

/** The nominal render rate the easing coefficients below were tuned against. */
const val TUNING_FPS = 60f

/**
 * Re-express a per-frame easing coefficient tuned at [TUNING_FPS] for the frame
 * time actually observed.
 *
 * Every smoother in the engine used to apply its coefficient once per rendered
 * frame regardless of how long that frame took. Analysis runs at ~50 Hz and the
 * render loop at 60 Hz, and scheduling moves it further still, so the effective
 * envelope tracked loop timing rather than wall time — which is what made the
 * brightness between beats shimmer. The Hue EDK defines every animation in
 * milliseconds for exactly this reason; nothing in it is frame-indexed.
 *
 * The conversion is exact, so `frameAlpha(a, 1/60f) == a` and the tuned presets
 * keep their existing feel at nominal rate.
 */
fun frameAlpha(alpha60: Float, dt: Float): Float =
    if (alpha60 >= 1f) 1f else 1f - (1f - alpha60).pow(dt * TUNING_FPS)

/** [frameAlpha] for a coefficient applied multiplicatively as a decay. */
fun frameDecay(decay60: Float, dt: Float): Float =
    if (decay60 <= 0f) 0f else decay60.pow(dt * TUNING_FPS)

// ── Mode parameters ───────────────────────────────────────────────────────

/**
 * Parameters for one intensity mode. Mirrors syncoV2's `ModeParams` dataclass.
 * Each field controls a specific aspect of the render — see the syncoV2
 * `modes.py` docstring for the full rationale.
 */
data class ModeParams(
    val base: Float = 0.12f,          // steady brightness between beats
    val floor: Float = 0.05f,         // minimum brightness (darkness between beats)
    val bassGain: Float = 0.14f,      // continuous brightness from bass envelope
    val beatGain: Float = 0.9f,       // pop of a bass-role light on a kick
    val beatThreshold: Float = 1.4f,  // only kicks this strong pop
    val colourSpeed: Float = 0.05f,   // palette drift per second
    val shimmer: Float = 0.10f,       // sparkle amount (vocal-role lights)
    val colourSat: Float = 0.7f,      // <1 softens colours toward white
    val colourLerp: Float = 0.40f,    // colour easing, per frame at TUNING_FPS
    val briAttack: Float = 1.0f,      // brightness rise easing, per frame at TUNING_FPS
    val briDecay: Float = 0.30f,      // brightness fall easing, per frame at TUNING_FPS
    val flashDecay: Float = 0.80f,    // beat-flash fade, per frame at TUNING_FPS

    /**
     * Ceiling on how fast emitted brightness may rise, in full scale per second.
     *
     * This replaced a per-*frame* cap that limited the rise only and was disabled
     * outright on three of the five rungs — so a beat attack was a single-frame
     * discontinuity, which bulbs show as a hard edge and which the rate limiter
     * downstream then quantised into a staircase.
     *
     * Philips' guidance is that people are far more sensitive to rapid brightness
     * changes than to rapid colour changes, so the brightness transition should be
     * the *slower* of the two. The encoder already slews chromaticity at
     * [XY_SLEW_MAX] (~4.8 full scale/s); these stay under that, and [briFallRate]
     * stays well under [briRiseRate].
     *
     * The rise is deliberately the looser of the two. Its job is only to stop a
     * *single-frame* discontinuity, which is what bulbs render as a hard edge; a
     * full-scale move still spans about three frames, matching the 50 ms intensity
     * ramp of the Hue EDK's own `ExplosionEffect`. Clamping it harder than that
     * would fight `flashDecay` — the beat flash has a ~52 ms half-life, so a slow
     * rise reaches its peak only after the flash has already decayed out from
     * under it, and beats lose their punch. The fall is what buys the smooth
     * dimming between beats.
     */
    val briRiseRate: Float = 24f,

    /** Ceiling on how fast emitted brightness may fall. See [briRiseRate]. */
    val briFallRate: Float = 5f,
    val roleMix: Triple<Float, Float, Float> = Triple(1f, 0f, 0f),  // (bass, mid, vocal) fractions
    val roleRotateBeats: Int = 0,     // swap role assignments every N beats
    val dynamicRoles: Boolean = false,
    val hardSnap: Boolean = false,
    val highlightQuantile: Float = 0.30f,
    val weakPulse: Float = 0.25f,
    val downbeatPulse: Float = 0.40f,
    /** Accents below this barely register, which is what makes a rung selective. */
    val accentFloor: Float = 0.0f,
    val colourJump: Float = 0.045f,   // palette advance per beat
    val colourBeatStep: Float = 0.0f, // tempo-locked rolling colour step
    val colourSpread: Float = 0.70f,  // per-lamp hue variation
    val fullRoomAccent: Float = 2.0f, // accent at/above which ALL roles slam
    val melbankGain: Float = 0.45f,   // continuous brightness from melbank slice
    val melbankFloor: Float = 0.06f,  // ambient lift while music plays
    val colourFlow: Float = 0.05f,    // continuous palette advance
    val spectralPop: Float = 0.35f,  // transient pop per lamp
    val energyGain: Float = 0.15f,   // brightness from broadband loudness
    val salienceGamma: Float = 1.3f,  // flash strictness
    val salienceFloor: Float = 0.05f,
    val widthMin: Float = 0.15f,      // mute narrowband onsets (vocals)
    val widthSoft: Float = 0.10f,
    val kickBassFloor: Float = 0.40f,
    val nobeatFlash: Float = 1.0f,
    val midGain: Float = 0f,
    val midThreshold: Float = 1.3f,
    val vocalDim: Float = 0.08f,
    val warmCalm: Float = 0f,
    val panGain: Float = 0f,
    val graphReactive: Boolean = false,
    val melFluxGain: Float = 0f,
    val melFluxFloor: Float = 0f,
    val rotateRate: Float = 0f,
    val rotateSwing: Float = 0f,
    val flashGamma: Float = 1.0f,
    val flashLoudFloor: Float = 1.0f,
    val bandLoudStrength: Float = 0f,
    /**
     * How much of a lamp's *hottest* melbank bin lifts its glow, against the mean
     * of its whole window.
     *
     * A lamp averages roughly seven of the sixteen bins (see `melbankWindow`), so
     * a pure mean divides a vocal sitting in two of them by seven — which is a
     * large part of why mid content read as too dim to be there. Extreme already
     * did this, as `EXT_GLOW_PEAKINESS`; this is the same idea on the main path,
     * exposed through the `contrast` tunable, which had nothing else to do here.
     */
    val melPeakiness: Float = 0f,

    // ── Sustain bloom ─────────────────────────────────────────────────────
    //
    // The layer that fires where every other one is silent. `eventGates` zeroes
    // its width gate for narrowband onsets, and `melTransient` is measured against
    // a baseline a held note settles into — both deliberate, both correct for
    // percussion, and between them they mean a long vocal over a steady chord
    // produces no flash, no pop and no wave. All that was left was one narrowband
    // melbank term per lamp, which is why a sustained mid read as one lamp barely
    // moving instead of the room glowing.

    /** Brightness a fully-committed sustain adds. Zero disables the layer. */
    val tonalGain: Float = 0f,
    /** `onsetWidth` at or above which nothing counts as tonal. */
    val tonalWidthMax: Float = 0.22f,
    /** Soft knee below [tonalWidthMax], mirroring `eventGates`' own shape. */
    val tonalWidthSoft: Float = 0.10f,
    /** Rise time constant in seconds — it blooms rather than pulses. */
    val tonalAttackS: Float = 0.55f,
    /** Fall time constant in seconds. Longer than the rise, so it lingers. */
    val tonalReleaseS: Float = 1.30f,
    /** How hard live transients suppress the bloom. Higher = more percussive. */
    val tonalDamp: Float = 1.0f,

    /**
     * How far colour follows the tilted room axis instead of left-to-right rank.
     *
     * 0 is the original x-only field, and is the revert. Hue could only ever sweep
     * one way across a room, so two lamps at the same x and different heights were
     * always the same colour however far apart they actually were.
     */
    val colourTilt: Float = 0f,

    /**
     * How much of each lamp's continuous drive comes from its neighbours.
     *
     * 0 is fully independent lamps — the original behaviour, and the revert. See
     * `diffuseDrives` for why this couples the *drive* and never the output.
     */
    val spatialCoupling: Float = 0f,

    // ── Room gestures ─────────────────────────────────────────────────────
    //
    // The layer that moves the *room* rather than a lamp: a sound that sweeps
    // across the stereo field travels across the lamps, and a swell with nothing
    // hitting under it rises through them. Off unless the user asks for it — see
    // `SyncoEngine.spatialGestures` — and rare even then, by design.

    /**
     * Brightness a full-strength room gesture adds. Zero disables the layer.
     *
     * Scaled by the `movement` tunable, whose own description already promises
     * exactly this: "how much the show travels between lamps rather than lighting
     * them together… up for sweeps and chases across the room". An eighth slider
     * for one feature would be worse than reusing the one that already says it.
     */
    val gestureGain: Float = 0f,
    /** Front width along a linear axis, in normalised room units. */
    val gestureWidth: Float = 0.30f,
    /** Front width around a ring, in turns. 0.18 is about 65°. */
    val gestureArcWidth: Float = 0.18f,
    /** Moving-source radius, for a room with no dominant shape. */
    val gestureRadius: Float = 0.45f,

    val roomPunch: Float = 0f,
    val fluxGate: Float = 0f,
    val predropDepth: Float = 0f,
    val phraseBars: Int = 0,
    val phraseColourShift: Float = 0f,
    val buildDesat: Float = 0f,
    val dropBoost: Float = 0f,
    val waveGain: Float = 0f,
    val waveSpeed: Float = 1.8f,
    val waveWidth: Float = 0.33f,
    val heightFreq: Float = 0f,
    val depthWash: Float = 0f,
    val anticipationMs: Float = 0f,
)

// ── Mode presets (from syncoV2 modes.py MODE_PARAMS) ──────────────────────

val MODE_PARAMS = mapOf(
    SyncMode.SUBTLE to ModeParams(
        base = 0.80f, floor = 0.80f, bassGain = 0f, beatGain = 0f, beatThreshold = 99f,
        colourSpeed = 0.04f, shimmer = 0f, colourSat = 1f,
        colourLerp = 0.10f, briAttack = 0.12f, briDecay = 0.08f,
        briRiseRate = 4f, briFallRate = 1.5f,
        highlightQuantile = 0f,
        colourJump = 0.020f,
        colourBeatStep = 0.008f,
        colourSpread = 1.0f,
        salienceGamma = 1.6f, widthMin = 0.20f,
        // Subtle sits at base = floor = 0.80 and is already near the clamp through
        // music, so there is little headroom for a peak to use. A light touch of
        // band loudness still shapes *which* lamps sit where in that narrow band.
        melPeakiness = 0.20f, bandLoudStrength = 0.15f,
        // Colour is Subtle's whole identity (colourSpread = 1.0), so it gets the
        // most tilt: the room reads as one slow gradient through all three axes.
        colourTilt = 0.50f,
        // The gentlest gesture of the five. Subtle sits at base = floor = 0.80 and
        // is already near the clamp, so a wide, faint front is all there is room
        // for — which happens to suit the rung: a slow drift across a still room.
        gestureGain = 0.18f, gestureWidth = 0.40f, gestureArcWidth = 0.24f,
    ),
    SyncMode.MEDIUM to ModeParams(
        base = 0.12f, floor = 0.05f, bassGain = 0.14f, beatGain = 0.9f, beatThreshold = 1.4f,
        colourSpeed = 0.05f, shimmer = 0.10f, colourSat = 0.7f,
        colourLerp = 0.40f, briAttack = 1f, briDecay = 0.30f,
        briRiseRate = 16f, briFallRate = 3f,
        colourJump = 0.045f, colourSpread = 0.70f, highlightQuantile = 0.30f,
        weakPulse = 0.25f, downbeatPulse = 0.40f,
        melbankGain = 0.45f, melbankFloor = 0.06f, colourFlow = 0.05f, spectralPop = 0.35f,
        energyGain = 0.15f, salienceGamma = 1.3f, widthMin = 0.15f, kickBassFloor = 0.30f,
        predropDepth = 0.30f, phraseBars = 4, phraseColourShift = 0.03f,
        panGain = 0.5f, warmCalm = 0f,
        melPeakiness = 0.35f, bandLoudStrength = 0.35f,
        tonalGain = 0.22f, tonalAttackS = 0.65f, tonalReleaseS = 1.5f, colourTilt = 0.45f,
        spatialCoupling = 0.45f,
        waveGain = 0.75f, waveSpeed = 2.2f, waveWidth = 0.30f, heightFreq = 0.30f,
        depthWash = 0.08f, anticipationMs = 80f, dropBoost = 0.50f, buildDesat = 0.50f,
        gestureGain = 0.30f,
    ),
    SyncMode.HIGH to ModeParams(
        base = 0.06f, floor = 0.035f, bassGain = 0.30f, beatGain = 1.6f, beatThreshold = 1.1f,
        colourSpeed = 0.06f, shimmer = 0.50f, colourSat = 0.8f,
        colourLerp = 0.38f, briAttack = 1f, briDecay = 0.38f, flashDecay = 0.80f,
        briRiseRate = 20f, briFallRate = 4f,
        colourJump = 0.09f, colourSpread = 0.55f, highlightQuantile = 0.40f,
        weakPulse = 0.16f, downbeatPulse = 0.45f, fullRoomAccent = 0.94f,
        roleMix = Triple(0.4f, 0.3f, 0.3f), midGain = 1.0f, midThreshold = 1.25f,
        vocalDim = 0.05f, roleRotateBeats = 16, dynamicRoles = true, hardSnap = true,
        melbankGain = 0.44f, melbankFloor = 0.035f, colourFlow = 0.05f, spectralPop = 0.45f,
        energyGain = 0.15f, salienceGamma = 1.0f, widthMin = 0.12f, kickBassFloor = 0.35f,
        predropDepth = 0.45f, phraseBars = 4, phraseColourShift = 0.05f,
        panGain = 0.6f,
        melPeakiness = 0.40f, bandLoudStrength = 0.45f,
        // The darkest resting level of the reactive rungs (base = 0.06), so it has
        // the most headroom for a bloom and the most obvious gap without one.
        // Moderate: High's role split is deliberate separation, so binding the room
        // too tightly would undo the thing the rung is for.
        tonalGain = 0.26f, colourTilt = 0.35f, spatialCoupling = 0.35f,
        waveGain = 0.55f, waveSpeed = 2.2f, waveWidth = 0.32f, anticipationMs = 80f,
        dropBoost = 0.60f, buildDesat = 0.45f,
        gestureGain = 0.30f,
    ),
    SyncMode.INTENSE to ModeParams(
        base = 0.05f, floor = 0.10f, bassGain = 0.16f, beatGain = 1.7f, beatThreshold = 1.0f,
        colourSpeed = 0.05f, shimmer = 0f, colourSat = 0.97f,
        colourLerp = 0.55f, briAttack = 1f, briDecay = 0.40f, flashDecay = 0.82f,
        briRiseRate = 24f, briFallRate = 5f,
        colourJump = 0.16f, colourSpread = 0.22f, highlightQuantile = 0.18f,
        weakPulse = 0.42f, downbeatPulse = 0.55f, fullRoomAccent = 0f,
        hardSnap = true,
        melbankGain = 0.42f, melbankFloor = 0.06f, colourFlow = 0.05f, spectralPop = 0.45f,
        energyGain = 0.16f, salienceGamma = 0.8f, widthMin = 0.08f, nobeatFlash = 0.30f,
        fluxGate = 0.5f, predropDepth = 0.60f, phraseBars = 4, phraseColourShift = 0.06f,
        panGain = 0.5f,
        melPeakiness = 0.40f, bandLoudStrength = 0.40f,
        // Percussive-forward rung: damp harder so the bloom cannot soften a drop.
        // colourSpread is only 0.22 here — near unison already — so a large tilt
        // would have little to spread and mostly just shift the whole room.
        tonalGain = 0.20f, tonalDamp = 1.4f, colourTilt = 0.25f, spatialCoupling = 0.40f,
        waveGain = 0.55f, waveSpeed = 2.4f, waveWidth = 0.30f, anticipationMs = 90f,
        dropBoost = 0.80f, buildDesat = 0.50f,
        // Narrower than the other rungs: Intense is percussive-forward, so a
        // tighter front reads as a sweep rather than as the room breathing.
        gestureGain = 0.25f, gestureWidth = 0.24f, gestureArcWidth = 0.15f,
    ),
    SyncMode.EXTREME to ModeParams(
        graphReactive = true,
        base = 0f, floor = 0f, bassGain = 0f, beatGain = 0f, beatThreshold = 99f,
        melbankGain = 0.60f, melbankFloor = 0.02f, spectralPop = 1.6f,
        flashGamma = 1.5f, flashLoudFloor = 0.30f,
        melFluxGain = 1.25f, melFluxFloor = 0.12f,
        rotateRate = 0.36f, rotateSwing = 0.85f, bandLoudStrength = 0.8f,
        roomPunch = 1.5f, energyGain = 0.06f, flashDecay = 0.70f,
        briAttack = 0.5f, briDecay = 0.4f,
        briRiseRate = 26f, briFallRate = 6f,
        colourSpeed = 0.05f, colourFlow = 0.05f, colourSpread = 0.4f, colourLerp = 0.4f,
        colourSat = 0.97f, panGain = 0.6f, colourTilt = 0.30f,
        // No gestureGain, and it would do nothing if there were one: `renderExtreme`
        // is a separate renderer that returns before the flash overlay the gesture
        // layer joins. Extreme is a different show, not a louder one.
    ),
)
