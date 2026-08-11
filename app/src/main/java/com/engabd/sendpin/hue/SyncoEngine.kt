package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.BeatGrid
import com.engabd.sendpin.audio.StructureState
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

// ── Types ─────────────────────────────────────────────────────────────────

typealias Rgb = Triple<Float, Float, Float>

enum class SyncMode(val wire: String) {
    SUBTLE("subtle"), MEDIUM("medium"), HIGH("high"), INTENSE("intense"), EXTREME("extreme");
    companion object { fun fromWire(s: String?) = entries.firstOrNull { it.wire == s } ?: HIGH }
}

/**
 * Effects the direct engine renders.
 *
 * No Movies. syncoV2 offers it, but the direct path is a music player driving a
 * Hue bridge — there is no video for a calm, brightness-follows-the-soundtrack
 * mode to accompany. A stored `"movies"` value from the Home Assistant path
 * falls back to Music rather than selecting something that does nothing.
 */
enum class SyncEffect(val wire: String) {
    MUSIC("music"), FIREWORKS("fireworks");
    companion object { fun fromWire(s: String?) = entries.firstOrNull { it.wire == s } ?: MUSIC }
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
    val colourLerp: Float = 0.40f,    // per-frame colour easing
    val briAttack: Float = 1.0f,      // per-frame brightness rise rate
    val briDecay: Float = 0.30f,      // per-frame brightness fall rate
    val flashDecay: Float = 0.80f,    // per-frame fade of the beat flash
    val briSlew: Float = 1.0f,        // max brightness RISE per frame (anti-strobe)
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
        highlightQuantile = 0f,
        colourJump = 0.020f,
        colourBeatStep = 0.008f,
        colourSpread = 1.0f,
        salienceGamma = 1.6f, widthMin = 0.20f,
    ),
    SyncMode.MEDIUM to ModeParams(
        base = 0.12f, floor = 0.05f, bassGain = 0.14f, beatGain = 0.9f, beatThreshold = 1.4f,
        colourSpeed = 0.05f, shimmer = 0.10f, colourSat = 0.7f,
        colourLerp = 0.40f, briAttack = 1f, briDecay = 0.30f,
        colourJump = 0.045f, colourSpread = 0.70f, highlightQuantile = 0.30f,
        weakPulse = 0.25f, downbeatPulse = 0.40f,
        melbankGain = 0.45f, melbankFloor = 0.06f, colourFlow = 0.05f, spectralPop = 0.35f,
        energyGain = 0.15f, salienceGamma = 1.3f, widthMin = 0.15f, kickBassFloor = 0.30f,
        predropDepth = 0.30f, phraseBars = 4, phraseColourShift = 0.03f,
        panGain = 0.5f, warmCalm = 0f,
        waveGain = 0.75f, waveSpeed = 2.2f, waveWidth = 0.30f, heightFreq = 0.30f,
        depthWash = 0.08f, anticipationMs = 80f, dropBoost = 0.50f, buildDesat = 0.50f,
    ),
    SyncMode.HIGH to ModeParams(
        base = 0.06f, floor = 0.035f, bassGain = 0.30f, beatGain = 1.6f, beatThreshold = 1.1f,
        colourSpeed = 0.06f, shimmer = 0.50f, colourSat = 0.8f,
        colourLerp = 0.38f, briAttack = 1f, briDecay = 0.38f, flashDecay = 0.80f, briSlew = 0.30f,
        colourJump = 0.09f, colourSpread = 0.55f, highlightQuantile = 0.40f,
        weakPulse = 0.16f, downbeatPulse = 0.45f, fullRoomAccent = 0.94f,
        roleMix = Triple(0.4f, 0.3f, 0.3f), midGain = 1.0f, midThreshold = 1.25f,
        vocalDim = 0.05f, roleRotateBeats = 16, dynamicRoles = true, hardSnap = true,
        melbankGain = 0.44f, melbankFloor = 0.035f, colourFlow = 0.05f, spectralPop = 0.45f,
        energyGain = 0.15f, salienceGamma = 1.0f, widthMin = 0.12f, kickBassFloor = 0.35f,
        predropDepth = 0.45f, phraseBars = 4, phraseColourShift = 0.05f,
        panGain = 0.6f,
        waveGain = 0.55f, waveSpeed = 2.2f, waveWidth = 0.32f, anticipationMs = 80f,
        dropBoost = 0.60f, buildDesat = 0.45f,
    ),
    SyncMode.INTENSE to ModeParams(
        base = 0.05f, floor = 0.10f, bassGain = 0.16f, beatGain = 1.7f, beatThreshold = 1.0f,
        colourSpeed = 0.05f, shimmer = 0f, colourSat = 0.97f,
        colourLerp = 0.55f, briAttack = 1f, briDecay = 0.40f, briSlew = 0.22f, flashDecay = 0.82f,
        colourJump = 0.16f, colourSpread = 0.22f, highlightQuantile = 0.18f,
        weakPulse = 0.42f, downbeatPulse = 0.55f, fullRoomAccent = 0f,
        hardSnap = true,
        melbankGain = 0.42f, melbankFloor = 0.06f, colourFlow = 0.05f, spectralPop = 0.45f,
        energyGain = 0.16f, salienceGamma = 0.8f, widthMin = 0.08f, nobeatFlash = 0.30f,
        fluxGate = 0.5f, predropDepth = 0.60f, phraseBars = 4, phraseColourShift = 0.06f,
        panGain = 0.5f,
        waveGain = 0.55f, waveSpeed = 2.4f, waveWidth = 0.30f, anticipationMs = 90f,
        dropBoost = 0.80f, buildDesat = 0.50f,
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
        colourSpeed = 0.05f, colourFlow = 0.05f, colourSpread = 0.4f, colourLerp = 0.4f,
        colourSat = 0.97f, panGain = 0.6f,
    ),
)

// ── Colour palette ─────────────────────────────────────────────────────────

/**
 * An ordered list of RGB anchor colours (0..1), sampled as a cyclic gradient.
 * Ported from syncoV2's `color/palette.py:Palette`.
 */
/**
 * How much of a full colour cycle a weighted segment spends crossfading.
 * Small, so the palette reads as its colours holding rather than as a gradient.
 */
private const val PALETTE_XFADE = 0.05f

/**
 * A cyclic colour gradient the engine samples by position.
 *
 * Two sampling modes. Unweighted is a plain even interpolation across the
 * anchors, which is what the static schemes want. **Weighted** sizes each
 * segment by its share and *holds* the colour inside it, crossfading only
 * within [PALETTE_XFADE] of each boundary — so a cover that is 90% green and
 * 10% red spends 90% of the cycle green instead of half of it. That dwell-time
 * fidelity is the whole point of album-art v2; an even gradient over the same
 * colours looks nothing like the sleeve.
 */
class Palette(val colors: List<Rgb>, weights: List<Float>? = null) {
    init { if (colors.isEmpty()) throw IllegalArgumentException("Palette must have at least one color") }

    /** Normalised segment sizes, or null for even spacing. */
    private val weights: List<Float>? = weights
        ?.takeIf { it.size == colors.size && it.sum() > 1e-6f }
        ?.let { w -> val total = w.sum(); w.map { (it / total).coerceAtLeast(0f) } }

    fun sample(pos: Float): Rgb {
        if (colors.size == 1) return colors[0]
        val p = ((pos % 1f) + 1f) % 1f  // wrap to 0..1
        return if (weights != null) weightedSample(p) else evenSample(p)
    }

    private fun evenSample(p: Float): Rgb {
        val n = colors.size
        val scaled = p * n
        val i = scaled.toInt() % n
        val j = (i + 1) % n
        val frac = scaled - scaled.toInt()
        val (r1, g1, b1) = colors[i]
        val (r2, g2, b2) = colors[j]
        return Triple(
            r1 + (r2 - r1) * frac,
            g1 + (g2 - g1) * frac,
            b1 + (b2 - b1) * frac,
        )
    }

    /** Hold-and-crossfade over weight-sized segments. */
    private fun weightedSample(pos: Float): Rgb {
        val w = weights!!
        val n = colors.size
        var start = 0f
        var i = n - 1  // float-sum slack lands in the last segment
        for (k in w.indices) {
            if (pos < start + w[k] || k == n - 1) { i = k; break }
            start += w[k]
        }
        val end = start + w[i]
        val prevI = (i - 1 + n) % n
        val nextI = (i + 1) % n
        // Crossfade half-widths, clamped so a narrow segment cannot be swallowed
        // by its neighbours' fades.
        val xfIn = minOf(PALETTE_XFADE, 0.5f * w[i], 0.5f * w[prevI])
        val xfOut = minOf(PALETTE_XFADE, 0.5f * w[i], 0.5f * w[nextI])
        val cur = colors[i]
        val t: Float
        val other: Rgb
        if (xfIn > 0f && pos < start + xfIn) {
            // Finishing the fade from the previous colour; 0.5 at the boundary.
            t = 0.5f + 0.5f * (pos - start) / xfIn
            other = colors[prevI]
        } else if (xfOut > 0f && pos > end - xfOut) {
            // Starting the fade toward the next colour; 0.5 at the boundary.
            t = 1f - 0.5f * (1f - (end - pos) / xfOut)
            other = colors[nextI]
        } else {
            return cur
        }
        return Triple(
            other.first + (cur.first - other.first) * t,
            other.second + (cur.second - other.second) * t,
            other.third + (cur.third - other.third) * t,
        )
    }
}

/** HSV to RGB. */
private fun hsvToRgb(h: Float, s: Float, v: Float): Rgb {
    val i = (h * 6).toInt()
    val f = h * 6 - i
    val p = v * (1 - s)
    val q = v * (1 - f * s)
    val t = v * (1 - (1 - f) * s)
    return when (i % 6) {
        0 -> Triple(v, t, p)
        1 -> Triple(q, v, p)
        2 -> Triple(p, v, t)
        3 -> Triple(p, q, v)
        4 -> Triple(t, p, v)
        else -> Triple(v, p, q)
    }
}

/** The 16 static colour schemes (dynamic ones use album art). */
private val STATIC_SCHEMES = mapOf(
    ColorScheme.SUNSET to Palette(listOf(
        hsvToRgb(0.80f, 0.80f, 0.85f), hsvToRgb(0.92f, 0.85f, 1.0f), hsvToRgb(0.99f, 0.85f, 1.0f),
        hsvToRgb(0.045f, 0.85f, 1.0f), hsvToRgb(0.09f, 0.80f, 1.0f), hsvToRgb(0.12f, 0.70f, 1.0f))),
    ColorScheme.OCEAN to Palette(listOf(
        hsvToRgb(0.46f, 0.75f, 1.0f), hsvToRgb(0.50f, 0.85f, 1.0f), hsvToRgb(0.55f, 0.90f, 1.0f),
        hsvToRgb(0.60f, 0.90f, 1.0f), hsvToRgb(0.64f, 0.85f, 0.95f))),
    ColorScheme.FOREST to Palette(listOf(
        hsvToRgb(0.27f, 0.70f, 1.0f), hsvToRgb(0.33f, 0.80f, 1.0f),
        hsvToRgb(0.38f, 0.85f, 0.95f), hsvToRgb(0.44f, 0.80f, 0.95f))),
    ColorScheme.LAVENDER to Palette(listOf(
        hsvToRgb(0.72f, 0.55f, 1.0f), hsvToRgb(0.77f, 0.65f, 1.0f),
        hsvToRgb(0.83f, 0.60f, 1.0f), hsvToRgb(0.90f, 0.55f, 1.0f))),
    ColorScheme.EMBER to Palette(listOf(
        hsvToRgb(0.99f, 0.90f, 1.0f), hsvToRgb(0.02f, 0.90f, 1.0f), hsvToRgb(0.05f, 0.90f, 1.0f),
        hsvToRgb(0.09f, 0.85f, 1.0f), hsvToRgb(0.12f, 0.80f, 1.0f))),
    ColorScheme.AURORA to Palette(listOf(
        hsvToRgb(0.45f, 0.80f, 1.0f), hsvToRgb(0.36f, 0.75f, 1.0f), hsvToRgb(0.55f, 0.80f, 1.0f),
        hsvToRgb(0.72f, 0.75f, 1.0f), hsvToRgb(0.88f, 0.65f, 1.0f))),
    ColorScheme.RAINBOW to Palette(listOf(
        hsvToRgb(0.00f, 0.90f, 1.0f), hsvToRgb(0.083f, 0.90f, 1.0f), hsvToRgb(0.167f, 0.90f, 1.0f),
        hsvToRgb(0.333f, 0.85f, 1.0f), hsvToRgb(0.500f, 0.85f, 1.0f),
        hsvToRgb(0.667f, 0.85f, 1.0f), hsvToRgb(0.833f, 0.85f, 1.0f))),
    ColorScheme.TROPICAL to Palette(listOf(
        hsvToRgb(0.92f, 0.80f, 1.0f), hsvToRgb(0.85f, 0.72f, 1.0f), hsvToRgb(0.98f, 0.80f, 1.0f),
        hsvToRgb(0.04f, 0.82f, 1.0f), hsvToRgb(0.10f, 0.70f, 1.0f))),
    ColorScheme.SAVANNA to Palette(listOf(
        hsvToRgb(0.01f, 0.85f, 1.0f), hsvToRgb(0.05f, 0.85f, 1.0f), hsvToRgb(0.08f, 0.85f, 1.0f),
        hsvToRgb(0.11f, 0.80f, 1.0f), hsvToRgb(0.13f, 0.68f, 1.0f))),
    ColorScheme.BLOSSOM to Palette(listOf(
        hsvToRgb(0.95f, 0.42f, 1.0f), hsvToRgb(0.04f, 0.38f, 1.0f), hsvToRgb(0.13f, 0.33f, 1.0f),
        hsvToRgb(0.78f, 0.34f, 1.0f), hsvToRgb(0.55f, 0.28f, 1.0f))),
    ColorScheme.HONOLULU to Palette(listOf(
        hsvToRgb(0.95f, 0.85f, 1.0f), hsvToRgb(0.04f, 0.85f, 1.0f),
        hsvToRgb(0.80f, 0.80f, 1.0f), hsvToRgb(0.50f, 0.80f, 1.0f))),
    ColorScheme.GALAXY to Palette(listOf(
        hsvToRgb(0.66f, 0.90f, 1.0f), hsvToRgb(0.72f, 0.85f, 1.0f),
        hsvToRgb(0.78f, 0.85f, 1.0f), hsvToRgb(0.86f, 0.78f, 1.0f))),
    ColorScheme.NEON to Palette(listOf(
        hsvToRgb(0.505f, 0.94f, 0.99f), hsvToRgb(0.845f, 0.82f, 1.0f),
        hsvToRgb(0.32f, 0.92f, 1.0f), hsvToRgb(0.945f, 0.82f, 1.0f))),
    ColorScheme.PEACOCK to Palette(listOf(
        hsvToRgb(0.515f, 0.87f, 0.90f), hsvToRgb(0.60f, 0.88f, 1.0f),
        hsvToRgb(0.44f, 0.90f, 0.85f), hsvToRgb(0.12f, 0.80f, 1.0f))),
    ColorScheme.CITRUS to Palette(listOf(
        hsvToRgb(0.15f, 0.76f, 1.0f), hsvToRgb(0.24f, 0.80f, 0.92f),
        hsvToRgb(0.09f, 0.89f, 1.0f), hsvToRgb(0.005f, 0.64f, 1.0f))),
    ColorScheme.ROSEGOLD to Palette(listOf(
        hsvToRgb(0.04f, 0.24f, 1.0f), hsvToRgb(0.02f, 0.45f, 1.0f),
        hsvToRgb(0.04f, 0.58f, 0.88f), hsvToRgb(0.12f, 0.62f, 0.96f))),
)

private val FALLBACK_SCHEME = ColorScheme.SUNSET

fun getPalette(scheme: ColorScheme): Palette =
    if (scheme in setOf(ColorScheme.ALBUM_ART, ColorScheme.ALBUM_ART_V2, ColorScheme.SONG))
        STATIC_SCHEMES[FALLBACK_SCHEME]!!
    else
        STATIC_SCHEMES[scheme] ?: STATIC_SCHEMES[FALLBACK_SCHEME]!!

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
 * Colour-jump scaling per phrase, so four-phrase cycles don't feel metronomic.
 */
private val PHRASE_JUMP = floatArrayOf(1.0f, 0.85f, 1.15f, 0.95f)

/**
 * Relative weight of each beat in a 4/4 bar: the downbeat hits hardest, beats 2
 * and 4 land softer. This is the difference between a pulse that feels musical
 * and one that feels like a metronome.
 */
private val BAR_WEIGHT = floatArrayOf(1.0f, 0.72f, 0.86f, 0.72f)

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
    var palette: Palette = getPalette(FALLBACK_SCHEME)
    var mode: SyncMode = SyncMode.HIGH
        set(value) { field = value; params = MODE_PARAMS[value]!!; updateRoles() }
    var effect: SyncEffect = SyncEffect.MUSIC
    var brightness: Float = 1.0f
        set(value) { field = value.coerceIn(0f, 1f) }
    var time: Float = 0.0f
    var colourPhase: Float = 0.0f

    private var params: ModeParams = MODE_PARAMS[mode]!!

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
    )

    /** Normalised lamp positions, and the phrase-cycle wave origins over them. */
    private val positions: Map<Int, Vec3> = normalizePositions(channels)
    private val origins: List<Vec3> = phraseOrigins(positions, configurationType)

    private val cmap: Map<Int, ChannelInfo> = buildChannelMap(channels)

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
        return sorted.mapIndexed { i, ch ->
            val xrank = if (n <= 1) 0.5f else i.toFloat() / (n - 1)
            val pos = positions[ch.channelId] ?: Vec3(xrank, 0.5f, 0.5f)
            val (melLo, melHi) = melbankWindow(xrank, MELBANK_BINS)
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

    // ── Accessors for the alternate renderers ─────────────────────────────

    /** Fireworks owns its own burst state and decay, so it renders separately. */
    private val fireworks = FireworksEffect()

    /**
     * Each lamp's `(xrank, height)` — the two axes Fireworks places bursts on.
     * Exposed rather than handing over the whole channel map, so the effect
     * depends on a shape it actually needs.
     */
    internal val lampLayout: Map<Int, Pair<Float, Float>>
        get() = cmap.mapValues { (_, info) -> info.xrank to info.pos.z }

    /** Smoothed room loudness, for effects that breathe with the music. */
    internal val energyEnvValue: Float get() = energyEnv

    /** The active rung's event gates, so alternate renderers share the tuning. */
    internal fun gatesFor(frame: AnalysisFrame): Pair<Float, Float> {
        val g = eventGates(frame.salience, frame.onsetWidth)
        return g.ampScale to g.widthGate
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

    private fun updateEnv(frame: AnalysisFrame) {
        for ((name, value) in frame.bands) {
            val prev = env[name] ?: 0f
            val a = if (value > prev) ENV_RISE else ENV_FALL
            env[name] = prev + (value - prev) * a
            val pp = presence[name] ?: 0f
            val pa = if (value > pp) PRESENCE_RISE else PRESENCE_FALL
            presence[name] = pp + (value - pp) * pa
        }
        val a = if (frame.energy > energyEnv) ENV_RISE else ENV_FALL
        energyEnv += (frame.energy - energyEnv) * a
        loud = max(frame.energy, loud * GATE_DECAY)
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
    private fun updatePredrop(structure: StructureState?): Float {
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

        predrop = if (target > predrop)
            min(target, predrop + PREDROP_RISE)
        else
            max(target, predrop - PREDROP_FALL)
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
    private fun pulseWeight(p: ModeParams, accent: Float, beatInBar: Int, highlight: Boolean): Float {
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
        return w * BAR_WEIGHT[beatInBar.mod(BAR_WEIGHT.size)]
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
    ): Map<Int, Rgb> {
        time += dt
        updateEnv(frame)

        val p = params

        // Per-bin transients feed the attack-pop layer below. Updated once per
        // frame here rather than inside the Extreme renderer, which is where it
        // used to live and why the music path had no pop at all.
        updateMelTransient(frame)

        // Effect and rung both choose a renderer. Fireworks replaces the whole
        // path; Extreme is a different renderer rather than a louder music one,
        // which is why `graphReactive` exists as a flag on the params.
        if (effect == SyncEffect.FIREWORKS) return fireworks.render(this, frame, dt, p)
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

        // Loudness scale
        val loudScale = min(min(1f, max(visBass, energyEnv) / LOUD_REF), ampScale)

        // ── Musical structure ────────────────────────────────────────────
        // Tension through a build desaturates and tightens; the drop releases it
        // as a full-field swell. Without this every bar of a track gets the same
        // treatment however the song is shaped.
        val build = structure?.buildProgress ?: 0f
        val sectionLevel = structure?.sectionLevel ?: 1f
        val predrop = updatePredrop(structure) * p.predropDepth * musicGate
        if (structure?.dropNow == true && p.dropBoost > 0f) {
            swell = max(swell, p.dropBoost * (1f + 0.35f * predropReleased) * musicGate)
            // A drop is a new section: reshuffle the room so the same lamps
            // aren't carrying the same roles through the whole track.
            if (p.dynamicRoles) { roleOffset++; updateRoles() }
        }
        swell *= SWELL_DECAY
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
        for (cid in lightFlash.keys) lightFlash[cid] = lightFlash[cid]!! * p.flashDecay

        // Beat flash assignment. Reactive detection is taken as the baseline and
        // the scheduled pulse folded in with max(), so an unlocked or mistaken
        // grid can only ever add to the show, never subtract from it.
        val scheduled = if (locked && beatgrid!!.predictedBeat) {
            p.beatGain *
                pulseWeight(p, beatgrid.accentNow, beatgrid.beatInBar, highlight) *
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

        // Render each channel
        val out = HashMap<Int, Rgb>()
        for (cid in rankIds) {
            val info = cmap[cid] ?: continue
            val role = roles[cid] ?: ROLE_BASS

            // Continuous melbank layer. The window overlaps its neighbours, so
            // the room reads as a smooth spectral field rather than as hard-edged
            // frequency bands.
            val mel = frame.melbank
            val melLevel = if (mel.isNotEmpty() && mel.size >= MELBANK_BINS) {
                val pan = frame.pan
                val usePan = p.panGain > 0f && pan.size >= info.melHi
                var sum = 0f
                for (i in info.melLo until info.melHi) {
                    var v = mel[i]
                    if (usePan) v *= (1f + p.panGain * pan[i] * info.side).coerceIn(0f, 2f)
                    sum += v
                }
                sum / (info.melHi - info.melLo)
            } else 0f

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
            if (p.spectralPop > 0f && mel.isNotEmpty()) {
                // Pan-weighted when the source is stereo: a hit panned left pops
                // the left of the room harder than the right. The weight is
                // clamped to 0..2 and divided by the bin count rather than by the
                // weight sum, so a centred mix comes out exactly as the unweighted
                // mean and nothing changes for mono material.
                val pan = frame.pan
                val usePan = p.panGain > 0f && pan.size >= info.melHi
                var pop = 0f
                for (i in info.melLo until min(info.melHi, MELBANK_BINS)) {
                    var v = melTransient[i]
                    if (usePan) v *= (1f + p.panGain * pan[i] * info.side).coerceIn(0f, 2f)
                    pop += v
                }
                pop /= (info.melHi - info.melLo)
                target += p.spectralPop * pop * musicGate
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
            if (p.heightFreq > 0f) {
                val band = heightBand(info.pos.z)
                target += p.heightFreq * (env[band] ?: 0f) * musicGate
            }
            // Depth: lamps further back carry a broader wash and less detail.
            if (p.depthWash > 0f) {
                target += p.depthWash * energyEnv * (1f - info.pos.y)
            }

            // Pre-drop compresses the headroom toward the floor, so the drop has
            // somewhere to go.
            if (predrop > 0f) target = p.floor + (target - p.floor) * (1f - predrop)
            target = target.coerceIn(0f, 1f)

            // Flash overlay: the per-light beat flash, the wavefront sweeping
            // past, and any drop swell still ringing.
            val wave = if (p.waveGain > 0f) p.waveGain * waveAmplitude(info) * musicGate else 0f
            val flash = (lightFlash[cid] ?: 0f) + wave + swell

            // Smooth brightness
            val (prevColor, prevB) = state[cid] ?: (Triple(0f, 0f, 0f) to 0f)
            val alpha = if (target >= prevB) p.briAttack else p.briDecay
            var newB = prevB + (target - prevB) * alpha

            // Colour
            val cpos = info.xrank * p.colourSpread + colourPhase
            val tgtColor = palette.sample(cpos)
            val (tr, tg, tb) = tgtColor
            val tm = max(tr, max(tg, tb))
            val nrTgt = if (tm > 1e-6f) Triple(tr / tm, tg / tm, tb / tm) else Triple(0f, 0f, 0f)
            val (nr, ng, nb) = nrTgt
            val (pr, pg, pb) = prevColor
            var nc = Triple(
                pr + (nr - pr) * p.colourLerp,
                pg + (ng - pg) * p.colourLerp,
                pb + (nb - pb) * p.colourLerp,
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
            var b = min(1f, newB + flash)
            val prevEmit = emitB[cid] ?: 0f
            if (p.briSlew < 1f && b > prevEmit + p.briSlew) b = prevEmit + p.briSlew
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
     * has one — a per-bin AGC divides out the very thing it measures, so there
     * is no live estimate of it to be had — and degrades to uniform weighting
     * when it does not, which is what syncoV2 does too.
     */
    private fun renderExtreme(frame: AnalysisFrame, dt: Float): Map<Int, Rgb> {
        val p = params
        val musicGate = if (SILENCE_GATE > 0f) min(1f, loud / SILENCE_GATE) else 1f

        // Colour only drifts, never jumps on a beat: brightness carries the song
        // so the colour field stays coherent underneath it.
        colourPhase += (p.colourSpeed + p.colourFlow * frame.energy) * dt * musicGate

        val mel = frame.melbank
        val n = rankIds.size
        val haveBands = mel.isNotEmpty() && extBands.isNotEmpty()

        // Per-bin absolute-loudness weight, from a track scan. The melbank is
        // per-bin normalised, so a hi-hat tick and a kick arrive the same height
        // and every lamp reads equally bright; this puts the difference back, so
        // a loud band lights its lamp harder than a quiet one. Perceptually
        // compressed and strength-blended so quiet instruments stay visible
        // rather than disappearing. No scan (or no melbank) means uniform
        // weighting, which is exactly the behaviour that came before.
        val ref = frame.melbankRef
        val melWeight: FloatArray? =
            if (p.bandLoudStrength > 0f && haveBands && ref.size == mel.size) {
                FloatArray(ref.size) { i ->
                    (1f - p.bandLoudStrength) +
                        p.bandLoudStrength * ref[i].coerceIn(0f, 1f).pow(BAND_LOUD_COMPRESS)
                }
            } else null

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

        for (cid in lightFlash.keys) lightFlash[cid] = lightFlash[cid]!! * p.flashDecay

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
            val alpha = if (target >= prevB) p.briAttack else p.briDecay
            val newB = prevB + (target - prevB) * alpha

            // Colour stays keyed to the lamp's fixed position plus the drift
            // phase: only the instrument activity rotates, so the room keeps a
            // coherent colour field.
            val tgt = palette.sample(info.xrank * p.colourSpread + colourPhase)
            val m = max(tgt.first, max(tgt.second, tgt.third))
            val nt = if (m > 1e-6f) Triple(tgt.first / m, tgt.second / m, tgt.third / m)
            else Triple(0f, 0f, 0f)
            var nc = Triple(
                prevColor.first + (nt.first - prevColor.first) * p.colourLerp,
                prevColor.second + (nt.second - prevColor.second) * p.colourLerp,
                prevColor.third + (nt.third - prevColor.third) * p.colourLerp,
            )
            state[cid] = nc to newB

            if (p.colourSat < 1f) {
                val s = p.colourSat
                nc = Triple(nc.first * s + (1 - s), nc.second * s + (1 - s), nc.third * s + (1 - s))
            }
            val cval = max(nc.first, max(nc.second, nc.third))
            val b = min(1f, newB + (lightFlash[cid] ?: 0f)).coerceIn(0f, 1f) *
                (0.35f + 0.65f * cval) * brightness
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
}