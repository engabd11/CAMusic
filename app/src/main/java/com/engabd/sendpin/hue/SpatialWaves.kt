package com.engabd.sendpin.hue

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
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

// ── Room topology ─────────────────────────────────────────────────────────
//
// The question none of the above can answer: what *shape* are the lamps in?
//
// It matters because the same musical gesture has to render differently
// depending on the room. A sound sweeping left to right should travel along a
// line of lamps, go round a room whose lamps sit in the corners, and — on two
// bulbs on a shelf — not be attempted at all. Philips make the same point about
// their own `AreaEffect`: if a setup has no light in the area an effect covers,
// the effect simply is not shown, because "it is better to not show an effect at
// all, than to show it on the wrong location".

/**
 * What shape an entertainment area's lamps are in. Classified once per session
 * by [classifyTopology] — the lamps do not move while a stream is running.
 */
enum class RoomTopology {
    /** Lamps essentially on a line. A gesture travels along it. */
    LINEAR,

    /** Lamps around the listener. A gesture goes round. */
    RING,

    /** Scattered in the room with no dominant shape. A gesture is a moving source. */
    FIELD,

    /**
     * Too few lamps, or all of them within arm's reach of each other. **No
     * spatial gesture is rendered at all** — two lamps on a shelf cannot express
     * "around the room", and a left-right flicker between them reads as a fault
     * rather than as motion.
     */
    CLUSTER,
}

/** A least-squares circle through the lamps, in the room's floor plane. */
data class Ring(
    val centre: Vec3,
    val radius: Float,
    /** RMS radial error as a fraction of [radius], so it compares across rooms. */
    val residual: Float,
)

/** Fewer lamps than this can never describe a ring, however well they fit one. */
private const val RING_MIN_LAMPS = 4

/**
 * Largest raw extent, on any axis, below which the area is a [RoomTopology.CLUSTER].
 *
 * The Hue API reports channel positions over roughly −1..1 across the room, so
 * this is a fifth of the room's width. It also behaves sanely if a bridge ever
 * reported metres instead: a real room would then span several units and never
 * be called a cluster, while a genuine huddle of bulbs is under 0.4 m anyway.
 */
private const val CLUSTER_EXTENT = 0.40f

/** Variance the principal axis must explain for the area to count as a line. */
private const val LINEAR_VARIANCE = 0.80f

/** Circle-fit error, relative to the radius, above which it is not a ring. */
private const val RING_RESIDUAL_MAX = 0.25f

/**
 * Largest permitted gap in azimuth, in turns.
 *
 * The test that separates "round the room" from "four lamps in an arc along one
 * wall". Both fit a circle beautifully; only one of them can be swept around.
 */
private const val RING_GAP_MAX = 0.40f

/** Raw z spread below which the room has no usable height. See [hasHeight]. */
private const val HEIGHT_MIN_SPREAD = 0.25f

/**
 * Positions centred on the room's centroid and scaled by **one** factor for all
 * three axes, so the area's real proportions survive.
 *
 * Deliberately not [normalizePositions], which scales each axis independently to
 * fill 0..1. That is right for effects that should span whatever room they are
 * given, and wrong for asking what shape the room is: five lamps in a line with a
 * few centimetres of wobble have their wobble stretched to the full width of the
 * unit cube by a per-axis fit, and stop looking like a line at all.
 */
fun roomShape(channels: List<EntertainmentChannel>): Map<Int, Vec3> {
    if (channels.isEmpty()) return emptyMap()
    val n = channels.size
    val cx = channels.sumOf { it.position.x.toDouble() }.toFloat() / n
    val cy = channels.sumOf { it.position.y.toDouble() }.toFloat() / n
    val cz = channels.sumOf { it.position.z.toDouble() }.toFloat() / n
    val extent = max(
        channels.maxOf { it.position.x } - channels.minOf { it.position.x },
        max(
            channels.maxOf { it.position.y } - channels.minOf { it.position.y },
            channels.maxOf { it.position.z } - channels.minOf { it.position.z },
        ),
    )
    val scale = if (extent < 1e-6f) 1f else 1f / extent
    return channels.associate { c ->
        c.channelId to Vec3(
            (c.position.x - cx) * scale,
            (c.position.y - cy) * scale,
            (c.position.z - cz) * scale,
        )
    }
}

/**
 * Does this area have enough vertical spread to render a rising gesture on?
 *
 * Usually **no**, and that is the point of asking. The Hue app places lamps on a
 * two-dimensional floor plan and the Entertainment API's own worked example
 * reports `z: 0.0` for every channel, so most real areas have no height at all.
 * Sweeping "bottom to top" across lamps that are all at the same height would
 * pick an arbitrary order and read as a flicker, so the swell falls back to
 * lifting the whole room together instead.
 */
fun hasHeight(channels: List<EntertainmentChannel>): Boolean {
    if (channels.size < 2) return false
    return (channels.maxOf { it.position.z } - channels.minOf { it.position.z }) > HEIGHT_MIN_SPREAD
}

/**
 * The dominant direction through a set of points, and the fraction of their
 * total variance it explains.
 *
 * Principal component analysis over three axes: build the covariance, then power
 * iterate for its largest eigenvector. Sixty-four iterations of a 3×3 multiply
 * costs nothing and this runs once per session.
 */
fun principalAxis(positions: List<Vec3>): Pair<Vec3, Float> {
    val n = positions.size
    if (n < 2) return Vec3(1f, 0f, 0f) to 0f

    val mx = positions.sumOf { it.x.toDouble() }.toFloat() / n
    val my = positions.sumOf { it.y.toDouble() }.toFloat() / n
    val mz = positions.sumOf { it.z.toDouble() }.toFloat() / n

    var cxx = 0f; var cyy = 0f; var czz = 0f
    var cxy = 0f; var cxz = 0f; var cyz = 0f
    for (p in positions) {
        val dx = p.x - mx; val dy = p.y - my; val dz = p.z - mz
        cxx += dx * dx; cyy += dy * dy; czz += dz * dz
        cxy += dx * dy; cxz += dx * dz; cyz += dy * dz
    }
    val trace = cxx + cyy + czz
    if (trace < 1e-12f) return Vec3(1f, 0f, 0f) to 0f

    // Start from whichever basis vector the covariance responds to most strongly.
    // A fixed start can be exactly orthogonal to the dominant axis — a line along
    // (1, −1, 0) is the easy example — and power iteration then never leaves zero.
    val cols = arrayOf(
        floatArrayOf(cxx, cxy, cxz),
        floatArrayOf(cxy, cyy, cyz),
        floatArrayOf(cxz, cyz, czz),
    )
    var best = cols[0]
    for (c in cols) if (norm3(c) > norm3(best)) best = c
    var vx = best[0]; var vy = best[1]; var vz = best[2]
    var m = norm3(vx, vy, vz)
    if (m < 1e-12f) return Vec3(1f, 0f, 0f) to 0f
    vx /= m; vy /= m; vz /= m

    repeat(64) {
        val nx = cxx * vx + cxy * vy + cxz * vz
        val ny = cxy * vx + cyy * vy + cyz * vz
        val nz = cxz * vx + cyz * vy + czz * vz
        m = norm3(nx, ny, nz)
        if (m > 1e-12f) { vx = nx / m; vy = ny / m; vz = nz / m }
    }

    // λ₁ = vᵀCv, and the axis explains λ₁ of the trace.
    val lambda = vx * (cxx * vx + cxy * vy + cxz * vz) +
        vy * (cxy * vx + cyy * vy + cyz * vz) +
        vz * (cxz * vx + cyz * vy + czz * vz)
    return Vec3(vx, vy, vz) to (lambda / trace).coerceIn(0f, 1f)
}

private fun norm3(v: FloatArray): Float = norm3(v[0], v[1], v[2])
private fun norm3(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

/** How far along [dir] from [origin] a point sits. */
fun axisPosition(pos: Vec3, dir: Vec3, origin: Vec3): Float =
    (pos.x - origin.x) * dir.x + (pos.y - origin.y) * dir.y + (pos.z - origin.z) * dir.z

/**
 * Least-squares circle through the lamps, **in the x–y plane**.
 *
 * The plane is fixed rather than fitted because the Hue app is a floor plan: `z`
 * is identically zero in most real areas, and a plane fitted through points with
 * no vertical spread is degenerate. The floor is the only plane there is.
 *
 * Kåsa's linear formulation — minimise `Σ(x² + y² − 2ax − 2by − c)²`, which is
 * linear in `(a, b, c)` and solves in closed form, unlike the true geometric fit.
 * It biases slightly toward larger circles when the points cover only a short
 * arc, which does not matter here because [largestAzimuthGap] rejects short arcs
 * outright.
 *
 * Run this on [normalizePositions] output rather than [roomShape]: per-axis
 * normalisation turns lamps round a rectangular room into a circle, which is
 * exactly the case "lights in the corners" describes.
 */
fun fitRing(positions: List<Vec3>): Ring? {
    val n = positions.size
    if (n < 3) return null

    var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
    var sxs = 0.0; var sys = 0.0; var ss = 0.0
    for (p in positions) {
        val x = p.x.toDouble(); val y = p.y.toDouble(); val s = x * x + y * y
        sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y
        sxs += x * s; sys += y * s; ss += s
    }

    val m = arrayOf(
        doubleArrayOf(sxx, sxy, sx),
        doubleArrayOf(sxy, syy, sy),
        doubleArrayOf(sx, sy, n.toDouble()),
    )
    val rhs = doubleArrayOf(sxs, sys, ss)
    val det = det3(m)
    if (abs(det) < 1e-12) return null

    // The system solves for (2a, 2b, c), so only the centre terms are halved.
    // Halving the constant as well shrinks every fitted radius by a factor that
    // depends on the room, which reads as a circle that fits badly rather than as
    // an obviously wrong answer.
    val a = 0.5 * det3(withColumn(m, 0, rhs)) / det
    val b = 0.5 * det3(withColumn(m, 1, rhs)) / det
    val c = det3(withColumn(m, 2, rhs)) / det
    val rSq = a * a + b * b + c
    if (rSq <= 1e-12) return null
    val r = sqrt(rSq)

    var acc = 0.0
    var mz = 0.0
    for (p in positions) {
        val dx = p.x - a; val dy = p.y - b
        val e = sqrt(dx * dx + dy * dy) - r
        acc += e * e
        mz += p.z
    }
    return Ring(
        centre = Vec3(a.toFloat(), b.toFloat(), (mz / n).toFloat()),
        radius = r.toFloat(),
        residual = (sqrt(acc / n) / r).toFloat(),
    )
}

private fun det3(m: Array<DoubleArray>): Double =
    m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
        m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
        m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

private fun withColumn(m: Array<DoubleArray>, col: Int, v: DoubleArray): Array<DoubleArray> =
    Array(3) { r -> DoubleArray(3) { c -> if (c == col) v[r] else m[r][c] } }

/**
 * Where a lamp sits around [ring], in **turns**: 0..1 counter-clockwise from the
 * +x direction.
 *
 * So room-right is 0, the front of the room is 0.25, room-left is 0.5 and behind
 * the listener is 0.75. [azimuthForPan] depends on exactly this convention.
 */
fun azimuthOf(pos: Vec3, ring: Ring): Float {
    val a = atan2((pos.y - ring.centre.y).toDouble(), (pos.x - ring.centre.x).toDouble())
    var t = (a / (2.0 * PI)).toFloat()
    if (t < 0f) t += 1f
    return t
}

/**
 * Where a sound at stereo position [pan] belongs on the ring, in turns.
 *
 * Hard left is half a turn and hard right is zero, so interpolating between two
 * of these passes through 0.25 — the **front** of the room. That is the whole
 * point: a sound crossing in front of the listener must appear to cross in front
 * of them, and the other arc would send it round the back.
 */
fun azimuthForPan(pan: Float): Float = (1f - (pan.coerceIn(-1f, 1f) + 1f) / 2f) * 0.5f

/**
 * The widest stretch of the ring with no lamp on it, in turns.
 *
 * Four lamps evenly spread give 0.25. Four lamps bunched along one wall give
 * something near 0.75, and are not a ring however well they fit a circle.
 */
fun largestAzimuthGap(azimuths: List<Float>): Float {
    if (azimuths.size < 2) return 1f
    val s = azimuths.sorted()
    // The wrap-around gap, from the last lamp forward to the first.
    var gap = 1f - (s.last() - s.first())
    for (i in 1 until s.size) gap = max(gap, s[i] - s[i - 1])
    return gap
}

/**
 * What shape this entertainment area's lamps are in.
 *
 * The extent test runs on **raw** positions, deliberately. [normalizePositions]
 * stretches any area to fill the unit cube, so two bulbs on a shelf and a room
 * spanning fifteen metres are indistinguishable after it — and telling those two
 * apart is the entire job of the [RoomTopology.CLUSTER] case.
 */
fun classifyTopology(channels: List<EntertainmentChannel>): RoomTopology {
    if (channels.size < 3) return RoomTopology.CLUSTER

    val ex = channels.maxOf { it.position.x } - channels.minOf { it.position.x }
    val ey = channels.maxOf { it.position.y } - channels.minOf { it.position.y }
    val ez = channels.maxOf { it.position.z } - channels.minOf { it.position.z }
    if (max(ex, max(ey, ez)) < CLUSTER_EXTENT) return RoomTopology.CLUSTER

    // Shape-preserving for the line test, per-axis for the ring test. Each
    // normalisation is right for its own question; see [roomShape] and [fitRing].
    val shape = roomShape(channels).values.toList()
    val (_, explained) = principalAxis(shape)
    if (explained > LINEAR_VARIANCE) return RoomTopology.LINEAR

    if (channels.size >= RING_MIN_LAMPS) {
        val spread = normalizePositions(channels).values.toList()
        val ring = fitRing(spread)
        if (ring != null && ring.residual < RING_RESIDUAL_MAX) {
            val gap = largestAzimuthGap(spread.map { azimuthOf(it, ring) })
            if (gap < RING_GAP_MAX) return RoomTopology.RING
        }
    }

    return RoomTopology.FIELD
}

// ── Gestures ──────────────────────────────────────────────────────────────
//
// Both of these fade in and out rather than appearing, because Philips' own
// guidance is blunt about it: "sudden changes in brightness of lamps in our
// peripheral vision (to our side) or lamps near to us, can be unpleasant" — and
// a gesture that crosses the room is peripheral motion by definition.

/** Fraction of a gesture spent fading in, and fading back out. */
private const val GESTURE_FADE_IN = 0.15f
private const val GESTURE_FADE_OUT = 0.25f

private fun smoothstep01(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * A soft front travelling along one room coordinate.
 *
 * The coordinate is whatever the topology says it is — position along the
 * principal axis for a line, azimuth in turns for a ring, height for a rise —
 * so one class covers three of the four cases and only the units change.
 *
 * @param circular measure distance the short way round, for a ring. Widths are
 *   in the coordinate's own units: normalised axis units for a line, turns for a
 *   ring, where 0.18 turns is about 65°.
 */
class TravellingFront(
    private val from: Float,
    private val to: Float,
    val durationS: Float,
    val strength: Float,
    private val width: Float,
    private val circular: Boolean = false,
) {
    var age: Float = 0f
        private set

    fun advance(dt: Float) { age += dt }

    val progress: Float get() = if (durationS <= 0f) 1f else (age / durationS).coerceIn(0f, 1f)

    val done: Boolean get() = progress >= 1f

    /** Where the front is now. Smoothstepped, so it eases off both ends. */
    val front: Float get() = from + (to - from) * smoothstep01(progress)

    val envelope: Float get() {
        val t = progress
        return when {
            t < GESTURE_FADE_IN -> smoothstep01(t / GESTURE_FADE_IN)
            t > 1f - GESTURE_FADE_OUT -> smoothstep01((1f - t) / GESTURE_FADE_OUT)
            else -> 1f
        }
    }

    /** Brightness this front contributes to a lamp sitting at [coord]. */
    fun amplitudeAt(coord: Float): Float {
        var d = coord - front
        // Wrap to −0.5..0.5 turns: a lamp a tenth of a turn *behind* the start of
        // a sweep is a tenth of a turn away, not nine tenths.
        if (circular) d -= floor(d + 0.5f)
        val s = d / width
        return strength * envelope * exp(-s * s)
    }
}

/**
 * A virtual light source that moves through the room — Hue's own
 * `LightSourceEffect`, whose worked example is "a fireball with a fixed radius
 * which moves from the front to the back of the room".
 *
 * Deliberately not a [Wave] with a moving origin. `Wave` precomputes each lamp's
 * distance to a *fixed* origin so its [Wave.amplitudeAt] needs no square root,
 * and a moving origin would take that optimisation away from every beat pulse in
 * the show to serve a gesture that fires a few times a minute. This costs one
 * root per lamp per frame and only while a gesture is live.
 */
class MovingSource(
    private val from: Vec3,
    private val to: Vec3,
    val durationS: Float,
    val strength: Float,
    private val radius: Float,
) {
    var age: Float = 0f
        private set

    /** Where the source is now. Recomputed on [advance], not per lamp. */
    var at: Vec3 = from
        private set

    fun advance(dt: Float) {
        age += dt
        val e = smoothstep01(progress)
        at = Vec3(
            from.x + (to.x - from.x) * e,
            from.y + (to.y - from.y) * e,
            from.z + (to.z - from.z) * e,
        )
    }

    val progress: Float get() = if (durationS <= 0f) 1f else (age / durationS).coerceIn(0f, 1f)

    val done: Boolean get() = progress >= 1f

    val envelope: Float get() {
        val t = progress
        return when {
            t < GESTURE_FADE_IN -> smoothstep01(t / GESTURE_FADE_IN)
            t > 1f - GESTURE_FADE_OUT -> smoothstep01((1f - t) / GESTURE_FADE_OUT)
            else -> 1f
        }
    }

    fun amplitudeAt(pos: Vec3): Float {
        val d = distance(pos, at) / radius
        return strength * envelope * exp(-d * d)
    }
}
