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
private val HEIGHT_BANDS = arrayOf("sub_bass", "bass", "low_mid", "mid", "high")

/**
 * Map a lamp's height to the frequency band it should favour: bass on the floor,
 * treble at the ceiling, the way a room's energy naturally stacks. Continuous
 * blending between bands is left to the caller.
 */
fun heightBand(nz: Float): String =
    HEIGHT_BANDS[min(HEIGHT_BANDS.size - 1, max(0, (nz * HEIGHT_BANDS.size).toInt()))]
