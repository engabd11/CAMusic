package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.EntertainmentChannel
import com.engabd.sendpin.hue.RoomTopology
import com.engabd.sendpin.hue.Vec3
import com.engabd.sendpin.hue.axisPosition
import com.engabd.sendpin.hue.azimuthOf
import com.engabd.sendpin.hue.classifyTopology
import com.engabd.sendpin.hue.couplingKernel
import com.engabd.sendpin.hue.distance
import com.engabd.sendpin.hue.fitRing
import com.engabd.sendpin.hue.hasHeight
import com.engabd.sendpin.hue.normalizePositions
import com.engabd.sendpin.hue.principalAxis

/**
 * The room an ambience show runs in, worked out once.
 *
 * Every value here comes from `SpatialWaves`; nothing is recomputed. A show ticks at
 * 60 Hz and its scripts read positions, distances and neighbour weights on every frame,
 * and a k-means-free geometry pass is still not something to do sixty times a second.
 *
 * ## The flat-room problem
 *
 * The Hue app records an entertainment area as a **floor plan**: the setup flow asks
 * where each lamp is in the room, not how high, and the API's own example has `z: 0`
 * throughout. So [hasHeight] is false in most real rooms, and any effect that wants a
 * vertical dimension — thunder from the sky, a fire low in the corner, an aurora
 * overhead — would silently do nothing at all.
 *
 * [heightOf] is the answer: real z where there is any, and otherwise a *synthetic*
 * height derived from distance to the room's centre. A lamp far out at the edge of the
 * floor plan reads as high, one near the middle reads as low. It is not true, but it is
 * stable, it is different per lamp, and it makes "up" mean something in a room that
 * never recorded it — which is the difference between an effect that reads as a storm
 * and one that reads as the lights flickering.
 */
class RoomModel(channels: List<EntertainmentChannel>) {

    /** Channel ids, in a fixed order every script can index by. */
    val ids: List<Int> = channels.map { it.channelId }

    /** Per-axis normalised to 0..1 — good for "where along the room", bad for distance. */
    val positions: Map<Int, Vec3> = normalizePositions(channels)

    /** Centroid-centred with one scale factor, so real proportions survive. Use for distance. */
    val shape: Map<Int, Vec3> = com.engabd.sendpin.hue.roomShape(channels)

    val topology: RoomTopology = classifyTopology(channels)

    /** True when the setup actually recorded lamp heights, which is uncommon. */
    val realHeight: Boolean = hasHeight(channels)

    val count: Int get() = ids.size

    private val posList: List<Vec3> = ids.map { positions[it] ?: Vec3(.5f, .5f, .5f) }

    /** Row-stochastic Gaussian neighbour weights — how much lamp i shares with lamp j. */
    val coupling: Array<FloatArray> = couplingKernel(posList)

    private val ring = fitRing(posList)

    /** Dominant direction through the lamps, and how much of the spread it explains. */
    private val axis = principalAxis(posList)
    val axisDir: Vec3 = axis.first
    val axisExplained: Float = axis.second

    private val centroid: Vec3 = run {
        if (posList.isEmpty()) Vec3(.5f, .5f, .5f)
        else Vec3(
            posList.sumOf { it.x.toDouble() }.toFloat() / posList.size,
            posList.sumOf { it.y.toDouble() }.toFloat() / posList.size,
            posList.sumOf { it.z.toDouble() }.toFloat() / posList.size,
        )
    }

    /** 0..1 along the room's dominant axis. The light train runs on this. */
    val axisPos: Map<Int, Float> = run {
        val raw = ids.associateWith { axisPosition(positions[it] ?: centroid, axisDir, centroid) }
        val lo = raw.values.minOrNull() ?: 0f
        val hi = raw.values.maxOrNull() ?: 1f
        val span = (hi - lo).takeIf { it > 1e-4f } ?: 1f
        raw.mapValues { (_, v) -> ((v - lo) / span).coerceIn(0f, 1f) }
    }

    /** Turns around the room, 0 = room-right. Falls back to angle about the centroid. */
    val azimuth: Map<Int, Float> = ids.associateWith { id ->
        val p = positions[id] ?: centroid
        val r = ring
        if (r != null) azimuthOf(p, r) else {
            var t = (Math.atan2((p.y - centroid.y).toDouble(), (p.x - centroid.x).toDouble())
                / (2.0 * Math.PI)).toFloat()
            if (t < 0f) t += 1f
            t
        }
    }

    /**
     * A usable 0..1 height for [id] — see the note on the class about flat rooms.
     *
     * Where the setup recorded heights, that is what this returns. Where it did not,
     * distance from the centre of the floor plan stands in: lamps at the edges read as
     * high, lamps in the middle as low. Deliberately not random, so a lamp keeps the
     * same "height" for the whole session and effects that stack read consistently.
     */
    fun heightOf(id: Int): Float = syntheticHeight[id] ?: 0.5f

    /**
     * The 0..1 height each lamp is treated as having.
     *
     * Real z where the setup recorded it. Otherwise **rank** of distance from the centre
     * of the floor plan, not the distance itself — and the difference matters: in a ring,
     * which is a perfectly ordinary Hue layout, every lamp is the same distance from the
     * centre, so raw distance gives all of them an identical height and every effect with
     * a vertical dimension silently collapses. Ranking guarantees a full spread in any
     * layout while keeping the same ordering wherever distances do differ.
     *
     * Ties are broken by position so the result is stable for the whole session: a lamp
     * that changed height between frames would make a fire flicker for the wrong reason.
     */
    private val syntheticHeight: Map<Int, Float> = run {
        if (realHeight) {
            ids.associateWith { (positions[it]?.z ?: 0.5f).coerceIn(0f, 1f) }
        } else {
            val scored = ids.map { id ->
                val p = positions[id] ?: centroid
                val d = distance(Vec3(p.x, p.y, 0f), Vec3(centroid.x, centroid.y, 0f))
                // The tiebreak is tiny — it only orders lamps that are genuinely equal.
                id to (d + 1e-4f * (p.x * 7f + p.y * 13f))
            }.sortedBy { it.second }
            val last = (scored.size - 1).coerceAtLeast(1)
            scored.mapIndexed { i, (id, _) -> id to i.toFloat() / last }.toMap()
        }
    }

    /** Straight-line distance between two lamps, in shape-preserving units. */
    fun gap(a: Int, b: Int): Float =
        distance(shape[a] ?: Vec3(0f, 0f, 0f), shape[b] ?: Vec3(0f, 0f, 0f))

    /** Distance from a lamp to an arbitrary point in normalised room space. */
    fun distanceTo(id: Int, point: Vec3): Float =
        distance(positions[id] ?: centroid, point)

    /** Where the middle of the room is, for effects that need somewhere to start. */
    fun centre(): Vec3 = centroid
}
