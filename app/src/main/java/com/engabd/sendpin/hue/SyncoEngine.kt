package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
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

enum class SyncEffect(val wire: String) {
    MUSIC("music"), MOVIES("movies"), FIREWORKS("fireworks");
    companion object { fun fromWire(s: String?) = entries.firstOrNull { it.wire == s } ?: MUSIC }
}

enum class ColorScheme(val wire: String) {
    ALBUM_ART("album_art"), ALBUM_ART_V2("album_art_v2"), SONG("song"),
    SUNSET("sunset"), OCEAN("ocean"), FOREST("forest"), LAVENDER("lavender"),
    EMBER("ember"), AURORA("aurora"), RAINBOW("rainbow"),
    TROPICAL("tropical"), SAVANNA("savanna"), BLOSSOM("blossom"),
    HONOLULU("honolulu"), GALAXY("galaxy"),
    NEON("neon"), PEACOCK("peacock"), CITRUS("citrus"), ROSEGOLD("rosegold");
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
    val colourJump: Float = 0.045f,   // palette advance per beat
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
        highlightQuantile = 0f, colourJump = 0.020f, colourSpread = 1f,
        salienceGamma = 1.6f, widthMin = 0.20f,
        melbankGain = 0f, melbankFloor = 0f, colourFlow = 0f, spectralPop = 0f,
        energyGain = 0f,
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
class Palette(val colors: List<Rgb>) {
    init { if (colors.isEmpty()) throw IllegalArgumentException("Palette must have at least one color") }

    fun sample(pos: Float): Rgb {
        if (colors.size == 1) return colors[0]
        val p = ((pos % 1f) + 1f) % 1f  // wrap to 0..1
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
) {
    var palette: Palette = getPalette(FALLBACK_SCHEME)
    var mode: SyncMode = SyncMode.HIGH
    var effect: SyncEffect = SyncEffect.MUSIC
    var brightness: Float = 1.0f
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
    )
    private val cmap: Map<Int, ChannelInfo> = buildChannelMap(channels)

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
            ch.channelId to ChannelInfo(
                xrank = xrank,
                side = 2f * xrank - 1f,
                band = when {
                    n <= 1 -> 0
                    i < n / 3 -> 0       // bass
                    i < 2 * n / 3 -> 1   // mid
                    else -> 2            // high
                },
            )
        }.toMap()
    }

    // ── Public setters ────────────────────────────────────────────────────

    fun setPalette(p: Palette) { palette = p }
    fun setScheme(scheme: ColorScheme) { palette = getPalette(scheme) }
    fun setAlbumColors(colors: List<Rgb>) { if (colors.isNotEmpty()) palette = Palette(colors) }
    fun setMode(m: SyncMode) { mode = m; params = MODE_PARAMS[m]!!; updateRoles() }
    fun setEffect(e: SyncEffect) { effect = e }
    fun setBrightness(b: Float) { brightness = b.coerceIn(0f, 1f) }

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

    private fun beatHighlight(accent: Float, append: Boolean): Boolean {
        if (params.highlightQuantile <= 0f) return true
        if (accents.size < 8) return accent >= 0.3f
        val sorted = accents.sorted()
        val thr = sorted[min(sorted.lastIndex, (params.highlightQuantile * sorted.size).toInt())]
        val ok = accent >= thr
        if (append) accents.addLast(accent)
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
    fun render(frame: AnalysisFrame, dt: Float): Map<Int, Rgb> {
        time += dt
        updateEnv(frame)

        val p = params
        val musicGate = if (SILENCE_GATE > 0f) min(1f, loud / SILENCE_GATE) else 1f

        // Event-salience gates
        val (ampScale, widthGate) = eventGates(frame.salience, frame.onsetWidth)

        // Visible event: detected bass beat
        val visStrength = if (frame.bassBeat) frame.bassStrength * widthGate else 0f
        val visBass = max(frame.bands["sub_bass"] ?: 0f, frame.bands["bass"] ?: 0f)

        // Beat detection
        val accNow = if (visStrength > 0f) min(1f, max(0f, (visStrength - 1f) / 2f)) else 0f
        val beatNow = frame.bassBeat && widthGate > 0f
        val highlight = if (visStrength > 0f) beatHighlight(accNow, beatNow && musicGate > 0.5f) else false

        // Mid onsets (guitar/snare)
        var midStrength = if (frame.midBeat) frame.midStrength * widthGate else 0f

        // Loudness scale
        val loudScale = min(min(1f, max(visBass, energyEnv) / LOUD_REF), ampScale)

        // Colour advance
        colourPhase += p.colourSpeed * dt * musicGate
        if (p.colourFlow > 0f) {
            colourPhase += p.colourFlow * (0.25f + 0.75f * frame.energy) * dt * musicGate
        }
        if (beatNow && p.colourJump > 0f) {
            var step = p.colourJump * (0.55f + 0.45f * accNow) * musicGate
            if (highlight) step *= 1.7f
            colourPhase += step
        }

        // Flash decay
        for (cid in lightFlash.keys) lightFlash[cid] = lightFlash[cid]!! * p.flashDecay

        // Beat flash assignment
        val kick = kickFlash(visStrength, visBass) * musicGate * loudScale
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

            // Continuous melbank layer
            val mel = frame.melbank
            val melLevel = if (mel.isNotEmpty() && mel.size >= MELBANK_BINS) {
                val lo = (info.xrank * MELBANK_BINS).toInt().coerceIn(0, MELBANK_BINS - 1)
                val hi = min(lo + 2, MELBANK_BINS)
                var sum = 0f
                for (i in lo until hi) sum += mel[i]
                sum / (hi - lo)
            } else 0f

            // Target brightness
            var target = p.floor + p.melbankFloor
            target += p.melbankGain * melLevel * musicGate
            target += p.energyGain * energyEnv

            // Role-based brightness
            val bassEnv = max(env["sub_bass"] ?: 0f, env["bass"] ?: 0f)
            when (role) {
                ROLE_BASS -> target += p.bassGain * bassEnv
                ROLE_VOCAL -> target += p.vocalDim
            }
            target = target.coerceIn(0f, 1f)

            // Flash overlay
            val flash = lightFlash[cid] ?: 0f

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

            // Saturation
            val sat = p.colourSat
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
     * Idle glow for paused/stopped state: colours flow gently across the room.
     */
    fun renderIdle(t: Float, level: Float = 0.20f): Map<Int, Rgb> {
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