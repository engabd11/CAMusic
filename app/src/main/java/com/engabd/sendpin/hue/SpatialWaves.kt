package com.engabd.sendpin.hue

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 3-D spatial helpers — the thing an LED strip physically cannot do.
 *
 * A Hue entertainment area is real lamps at real positions in a room: every
 * channel carries an (x, y, z) for left/right, back/front and floor/ceiling.
 * Strip-based sync is one-dimensional. Sampling a *field* in three dimensions
 * instead means a kick can send a wavefront sweeping across the room, treble can
 * live up high and bass down low, and colour can drift in two dimensions rather
 * than only left to right.
 *
 * This closes a real gap in the direct path, which parsed `position.y` and
 * `position.z` and then used neither — the channel model was x-only, so several
 * `ModeParams` fields describing 3-D behaviour had nothing behind them.
 *
 * Ported from syncoV2 `effects/spatial.py`. Pure geometry, so it is unit-tested
 * directly.
 */

/** A point in the normalised room cube, each axis 0..1. */
data class Vec3(val x: Float, val y: Float, val z: Float)

/**
 * Map each channel's position to 0..1 over the area's actual extent.
 *
 * Normalising to the real spread rather than the nominal [-1, 1] cube keeps
 * effects well scaled whether the lamps fill the room or cluster in one corner.
 * An axis with no spread — all lamps level, or a collinear area — collapses to
 * 0.5, so effects on that axis do nothing rather than divide by zero.
 */
fun normalizePositions(channels: List<EntertainmentChannel>): Map<Int, Vec3> {
    if (channels.isEmpty()) return emptyMap()

    fun scaler(values: List<Float>): (Float) -> Float {
        val lo = values.min()
        val hi = values.max()
        val span = hi - lo
        return if (span < 1e-6f) { _ -> 0.5f } else { v -> (v - lo) / span }
    }

    val sx = scaler(channels.map { it.position.x })
    val sy = scaler(channels.map { it.position.y })
    val sz = scaler(channels.map { it.position.z })
    return channels.associate { c ->
        c.channelId to Vec3(sx(c.position.x), sy(c.position.y), sz(c.position.z))
    }
}

/**
 * A sensible wave origin: horizontally central, at floor height.
 *
 * For `room` areas a bass thump reads best rising from the centre and low, then
 * expanding outward and upward. For `screen` areas the lamp positions are
 * relative to a screen and the viewer, so the origin shifts to the front centre
 * and effects emanate from the screen direction.
 */
fun floorOrigin(positions: Map<Int, Vec3>, configurationType: String = "room"): Vec3 {
    if (positions.isEmpty()) return Vec3(0.5f, 0.5f, 0f)
    val n = positions.size
    val mx = positions.values.sumOf { it.x.toDouble() }.toFloat() / n
    val my = positions.values.sumOf { it.y.toDouble() }.toFloat() / n
    val mz = positions.values.minOf { it.z }
    return if (configurationType == "screen") Vec3(mx, max(my, 0.8f), mz) else Vec3(mx, my, mz)
}

fun distance(a: Vec3, b: Vec3): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/**
 * Deterministic wave-origin cycle for phrase-level variation.
 *
 * Centre, left, right, centre — all at floor height — so waves sweep the room
 * from a different corner each musical phrase and the classic centred bloom
 * recurs every other phrase. Deterministic, with no seed, so two plays of the
 * same song render identically.
 */
fun phraseOrigins(positions: Map<Int, Vec3>, configurationType: String = "room"): List<Vec3> {
    val centre = floorOrigin(positions, configurationType)
    if (positions.isEmpty()) return listOf(centre)
    var my = positions.values.sumOf { it.y.toDouble() }.toFloat() / positions.size
    val mz = positions.values.minOf { it.z }
    if (configurationType == "screen") my = max(my, 0.8f)
    return listOf(centre, Vec3(0.15f, my, mz), Vec3(0.85f, my, mz), centre)
}

/**
 * An expanding spherical pulse launched on a beat.
 *
 * @param originIdx which phrase origin this launched from. Indexes the
 *   per-channel precomputed distances, so [amplitudeAt] needs no per-frame sqrt.
 */
class Wave(
    val origin: Vec3,
    var strength: Float,
    private val speed: Float,
    private val width: Float,
    var age: Float = 0f,
    val originIdx: Int = 0,
) {
    fun advance(dt: Float, decayTau: Float) {
        age += dt
        strength *= exp(-dt / decayTau)
    }

    val radius: Float get() = age * speed

    /** Brightness this wave contributes to a point [d] away from its origin. */
    fun amplitudeAt(d: Float): Float {
        val shell = (d - radius) / width
        return strength * exp(-shell * shell)
    }

    fun dead(maxDistance: Float = 1.8f): Boolean =
        strength < 0.02f || radius > maxDistance + 3f * width
}

/**
 * Half-open melbank bin range `[lo, hi)` a lamp should average.
 *
 * Maps the room's spatial axis onto the spectrum the way LedFx's "Wavelength"
 * spreads a melbank along a strip: a lamp at [spectralPos] 0 rides the lowest
 * frequencies, one at 1 the highest. Each lamp averages a window — [span] of the
 * bins, at least one — so neighbours overlap into a smooth field instead of
 * hard-edged bands.
 */
fun melbankWindow(spectralPos: Float, nBins: Int, span: Float = 0.20f): Pair<Int, Int> {
    if (nBins <= 0) return 0 to 0
    val pos = spectralPos.coerceIn(0f, 1f)
    val center = pos * (nBins - 1)
    val half = max(0.5f, span * nBins)
    val lo = max(0, floor(center - half).toInt())
    val hi = min(nBins, ceil(center + half).toInt() + 1)
    return lo to max(lo + 1, hi)
}

/** The five analyzer bands, floor to ceiling. */
internal val HEIGHT_BANDS = arrayOf("sub_bass", "bass", "low_mid", "mid", "high")

/**
 * Map a lamp's height to the frequency band it should favour: bass on the floor,
 * treble at the ceiling, the way a room's energy naturally stacks.
 *
 * The nearest band, with no blending — kept for callers that want a single name.
 * Anything rendering a field should use [heightBandLower] and [heightBandFrac]
 * instead: this is a five-way step, so two lamps a centimetre apart either side of
 * a boundary follow completely different parts of the spectrum, which is part of
 * why a room of lamps reads as separate instruments rather than one field.
 */
fun heightBand(nz: Float): String =
    HEIGHT_BANDS[min(HEIGHT_BANDS.size - 1, max(0, (nz * HEIGHT_BANDS.size).toInt()))]

/**
 * The lower of the two bands a lamp at height [nz] sits between.
 *
 * Works in band-*centre* space: a lamp at the exact centre of a band is purely
 * that band, and one between two centres is a mix. So `nz = 0` is pure `sub_bass`,
 * `nz = 1` is pure `high`, and nothing in between jumps.
 *
 * Two scalar functions rather than a `Pair`, because this runs per lamp per frame
 * and boxing it would allocate sixty times a second per lamp.
 */
fun heightBandLower(nz: Float): Int {
    val c = nz.coerceIn(0f, 1f) * HEIGHT_BANDS.size - 0.5f
    return floor(c).toInt().coerceIn(0, HEIGHT_BANDS.size - 1)
}

/** How far towards the *next* band up a lamp at [nz] sits, smoothstepped. */
fun heightBandFrac(nz: Float): Float {
    val c = nz.coerceIn(0f, 1f) * HEIGHT_BANDS.size - 0.5f
    val lower = floor(c).toInt().coerceIn(0, HEIGHT_BANDS.size - 1)
    // Above the topmost band centre there is no band to blend towards, so the mix
    // is zero rather than "half-way to a band that does not exist". The same holds
    // at the bottom by the coerce below. Both ends therefore land on a pure band,
    // and the response stays continuous because the frac reaches 1 just as `lower`
    // steps up.
    if (lower >= HEIGHT_BANDS.size - 1) return 0f
    val t = (c - lower).coerceIn(0f, 1f)
    // Smoothstep rather than linear: the derivative is zero at both ends, so a
    // lamp drifting past a band centre has no visible kink in its response.
    return t * t * (3f - 2f * t)
}

/**
 * Project a position onto a tilted room axis, for colour.
 *
 * Colour has always been a function of the x axis alone — `xrank` — so a room's
 * hue could only ever sweep left to right, and two lamps at the same x but
 * different heights or depths were always the same colour however far apart they
 * were. This gives the field a second and third dimension to drift in, which is
 * what [SpatialWaves]'s own preamble has described since it was written.
 *
 * Dominantly left-right, so the result still reads as the same effect rather than
 * a new one. An axis with no spread collapses to 0.5 in [normalizePositions] and
 * contributes a constant, which the caller's min-max normalisation then removes
 * entirely — so a flat room renders exactly as it did before.
 */
fun colourAxisProjection(pos: Vec3): Float =
    COLOUR_AXIS_X * pos.x + COLOUR_AXIS_Y * pos.y + COLOUR_AXIS_Z * pos.z

private const val COLOUR_AXIS_X = 0.845f
private const val COLOUR_AXIS_Y = 0.296f
private const val COLOUR_AXIS_Z = 0.465f

/**
 * A row-stochastic Gaussian kernel over lamp positions: how much each lamp should
 * hear of each other lamp.
 *
 * The thing that makes a room read as one field rather than as N independent
 * visualisers. Every lamp's continuous drive was computed from its own slice of
 * the spectrum and nothing else, so nearby lamps could be doing completely
 * unrelated things — which is exactly "the beams don't feel connected".
 *
 * **Rows sum to 1.** That is the property that matters: a row-stochastic matrix
 * applied to a set of values is a weighted average, so the room's total energy is
 * preserved and the coupling can neither brighten nor dim it, only redistribute.
 * It also means a *constant* field comes back unchanged, so coupling has no effect
 * at all on a moment when every lamp already agrees.
 *
 * σ is derived from the area's own geometry — the mean nearest-neighbour distance
 * — so a tight cluster of four bulbs and a room spanning fifteen metres both get a
 * neighbourhood that means the same thing relative to their own spacing.
 *
 * Computed once, at construction: the shape never changes, only how much of it is
 * mixed in.
 */
fun couplingKernel(positions: List<Vec3>): Array<FloatArray> {
    val n = positions.size
    if (n <= 1) return Array(n) { FloatArray(n) { 1f } }

    // Mean nearest-neighbour distance, the natural scale of this particular room.
    var nearSum = 0f
    var nearCount = 0
    for (i in 0 until n) {
        var best = Float.MAX_VALUE
        for (j in 0 until n) {
            if (i == j) continue
            val d = distance(positions[i], positions[j])
            if (d < best) best = d
        }
        if (best < Float.MAX_VALUE) { nearSum += best; nearCount++ }
    }
    val meanNear = if (nearCount > 0) nearSum / nearCount else 0.3f
    // Scaled so a lamp's nearest neighbour lands around weight 0.6 — close enough
    // to bind them, far from making the room a single average.
    val sigma = (meanNear / SIGMA_NEIGHBOUR_SCALE).coerceIn(SIGMA_MIN, SIGMA_MAX)

    return Array(n) { i ->
        val row = FloatArray(n)
        var sum = 0f
        for (j in 0 until n) {
            val d = distance(positions[i], positions[j]) / sigma
            val w = exp(-d * d)
            row[j] = w
            sum += w
        }
        // Row-normalise. `sum` includes the self term, which is always 1, so it can
        // never be zero and this needs no guard.
        for (j in 0 until n) row[j] /= sum
        row
    }
}

/** `exp(-x²) = 0.6` at `x ≈ 0.715`, so dividing by this puts a neighbour there. */
private const val SIGMA_NEIGHBOUR_SCALE = 0.715f

/** Bounds on σ as a fraction of the unit room cube. */
private const val SIGMA_MIN = 0.15f
private const val SIGMA_MAX = 0.60f
