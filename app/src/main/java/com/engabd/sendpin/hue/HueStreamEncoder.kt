package com.engabd.sendpin.hue

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.pow

/**
 * Encodes HueStream v2 datagrams for the Hue Entertainment API.
 *
 * The frame layout (from the Entertainment API spec):
 *
 * ```
 * "HueStream"          9 bytes, protocol name
 * 0x02 0x00             streaming API version 2.0
 * <seq>                 1 byte sequence id (informational)
 * 0x00 0x00             reserved
 * <colorspace>          0x00 RGB | 0x01 xy+brightness
 * 0x00                  reserved
 * <config uuid>         36 ASCII bytes
 * then per channel:     <channel id> + 3× uint16 big-endian
 * ```
 *
 * Maximum 20 channels per datagram. Larger areas are split across multiple datagrams
 * — each carries explicit channel IDs so the bridge applies each subset correctly.
 *
 * Ported from syncoV2's `hue/stream.py:HueStreamEncoder`, preserving the gamut
 * clamping, xy slew-limiting, and brightness-separated dimming that make native
 * Hue dim smoothly.
 */

// ── Colour space constants ───────────────────────────────────────────────

const val COLOR_SPACE_RGB: Byte = 0x00
const val COLOR_SPACE_XYB: Byte = 0x01

/**
 * Philips Hue Gamut C (current colour bulbs): the triangle of xy chromaticities
 * the bulbs can actually reproduce. Points outside get snapped by the bridge
 * unpredictably, so we clamp to the triangle ourselves for deterministic colour.
 */
val GAMUT_C = listOf(
    0.6915f to 0.3038f,  // red
    0.1700f to 0.7000f,  // green
    0.1532f to 0.0475f,  // blue
)

/**
 * Max xy chromaticity movement per emitted frame. The bridge does not interpolate
 * between frames, so a big palette/album-art jump would "pop"; capping the step
 * turns it into a smooth slewed move.
 */
const val XY_SLEW_MAX = 0.08f

// ── Colour math ───────────────────────────────────────────────────────────

/** sRGB gamma expansion. */
private fun gamExpand(c: Float): Float =
    if (c > 0.04045f) ((c + 0.055f) / 1.055f).pow(2.4f) else c / 12.92f

/**
 * Hue RGB → xy chromaticity, gamut-clamped.
 *
 * The matrix is the sRGB → XYZ D65 transform given on Philips' current Colour
 * Conversion page. It is deliberately *not* the "Wide-RGB D65" matrix
 * (0.649926, 0.103455, 0.197109, …) that circulates in Hue community code and
 * that this app, and syncoV2, previously used — that came from the original 2013
 * developer docs and has since been superseded. The two disagree most on
 * saturated greens and blues.
 *
 * Gamma expansion and the gamut clamp are unchanged; only the matrix moved.
 *
 * Chromaticity is scale-invariant, so callers pass a full-brightness colour and
 * carry brightness separately for stable dimming.
 */
fun rgbToXy(r: Float, g: Float, b: Float, gamut: List<Pair<Float, Float>> = GAMUT_C): Pair<Float, Float> {
    val rr = gamExpand(r)
    val gg = gamExpand(g)
    val bb = gamExpand(b)
    val x = rr * 0.4124f + gg * 0.3576f + bb * 0.1805f
    val y = rr * 0.2126f + gg * 0.7152f + bb * 0.0722f
    val z = rr * 0.0193f + gg * 0.1192f + bb * 0.9505f
    val total = x + y + z
    if (total <= 0f) return 0f to 0f
    return clampToGamut(x / total, y / total, gamut)
}

/**
 * Snap an xy chromaticity into the bulb's reproducible colour triangle.
 * Sign of the cross products tells which side of each edge the point is on.
 */
fun clampToGamut(x: Float, y: Float, gamut: List<Pair<Float, Float>>): Pair<Float, Float> {
    val (rx, ry) = gamut[0]
    val (gx, gy) = gamut[1]
    val (bx, by) = gamut[2]
    fun cross(ox: Float, oy: Float, ax: Float, ay: Float, px: Float, py: Float): Float =
        (ax - ox) * (py - oy) - (ay - oy) * (px - ox)
    val d1 = cross(rx, ry, gx, gy, x, y)
    val d2 = cross(gx, gy, bx, by, x, y)
    val d3 = cross(bx, by, rx, ry, x, y)
    val hasNeg = d1 < 0 || d2 < 0 || d3 < 0
    val hasPos = d1 > 0 || d2 > 0 || d3 > 0
    if (!hasNeg || !hasPos) return x to y  // inside the triangle

    // Project to the nearest edge.
    var best: Triple<Float, Float, Float>? = null  // (dist², x, y)
    data class Edge(val ax: Float, val ay: Float, val bx: Float, val by: Float)
    val edges = listOf(
        Edge(rx, ry, gx, gy),
        Edge(gx, gy, bx, by),
        Edge(bx, by, rx, ry),
    )
    for (edge in edges) {
        val abx = edge.bx - edge.ax
        val aby = edge.by - edge.ay
        val denom = abx * abx + aby * aby
        val t = if (denom <= 0f) 0f else ((x - edge.ax) * abx + (y - edge.ay) * aby) / denom
        val tc = t.coerceIn(0f, 1f)
        val cx = edge.ax + abx * tc
        val cy = edge.ay + aby * tc
        val d = (cx - x) * (cx - x) + (cy - y) * (cy - y)
        if (best == null || d < best.first) best = Triple(d, cx, cy)
    }
    return best!!.second to best!!.third
}

/** Scale a normalised 0.0–1.0 value to a 16-bit channel value. */
private fun floatTo16(v: Float): Int {
    if (v <= 0f) return 0
    if (v >= 1f) return 65535
    return (v * 65535f + 0.5f).toInt()
}

// ── Frame encoder ─────────────────────────────────────────────────────────

/**
 * Builds HueStream v2 datagrams for an entertainment configuration.
 *
 * Uses xy+brightness colourspace by default: brightness is sent on its own
 * 16-bit channel while chromaticity is computed from the full-bright colour
 * so it stays constant as brightness changes. This is how native Hue dims
 * smoothly — the bridge maps the dedicated brightness through the bulb's
 * own dimming curve instead of us shrinking RGB magnitudes.
 */
class HueStreamEncoder(
    private val configId: String,
    private val colorspace: Byte = COLOR_SPACE_XYB,
    /**
     * Per-channel colour triangles, resolved from the bridge. A channel missing
     * here falls back to [GAMUT_C], which is right for current bulbs and only
     * approximate for older ones — clamping a Gamut A strip to C pushes
     * saturated greens and blues to points it cannot actually produce, and the
     * bridge then snaps them somewhere unpredictable.
     */
    private val gamuts: Map<Int, List<Pair<Float, Float>>> = emptyMap(),
) {
    private var seq = 0
    /** Per-channel previous xy for slew-limiting. */
    private val prevXy = mutableMapOf<Int, Pair<Float, Float>>()

    init {
        val raw = configId.toByteArray(Charsets.US_ASCII)
        require(raw.size == 36) { "Entertainment config id must be 36 bytes, got ${raw.size}" }
    }

    /**
     * Encode one frame as one or more datagrams from normalised 0.0–1.0 RGB
     * tuples keyed by channel id. Areas with >20 channels are split.
     */
    fun buildPackets(channels: Map<Int, Triple<Float, Float, Float>>): List<ByteArray> {
        val items = channels.entries.toList()
        if (items.size <= MAX_CHANNELS) return listOf(build(channels))
        return items.chunked(MAX_CHANNELS).map { chunk ->
            build(chunk.associate { it.key to it.value })
        }
    }

    private fun build(channels: Map<Int, Triple<Float, Float, Float>>): ByteArray {
        val buf = ByteBuffer.allocate(16 + 36 + channels.size * 7).order(ByteOrder.BIG_ENDIAN)
        // Header
        buf.put("HueStream".toByteArray(Charsets.US_ASCII))  // 9 bytes
        buf.put(0x02)   // version major
        buf.put(0x00)   // version minor
        seq = (seq + 1) and 0xFF
        buf.put(seq.toByte())  // sequence id
        buf.put(0x00); buf.put(0x00)  // reserved
        buf.put(colorspace)
        buf.put(0x00)  // reserved
        buf.put(configId.toByteArray(Charsets.US_ASCII))  // 36 bytes

        when (colorspace) {
            COLOR_SPACE_XYB -> {
                for ((cid, rgb) in channels) {
                    val (r, g, b) = rgb
                    val bri = max(r, max(g, b))
                    buf.put(cid.toByte())
                    if (bri <= 1e-6f) {
                        // Black: hold the last chromaticity, send brightness 0.
                        val prev = prevXy[cid] ?: gamutCentroid(gamuts[cid] ?: GAMUT_C)
                        prevXy[cid] = prev
                        buf.putShort(floatTo16(prev.first).toShort())
                        buf.putShort(floatTo16(prev.second).toShort())
                        buf.putShort(0)
                    } else {
                        val (x, y) = rgbToXy(r / bri, g / bri, b / bri, gamuts[cid] ?: GAMUT_C)
                        val (sx, sy) = slewXy(cid, x, y)
                        buf.putShort(floatTo16(sx).toShort())
                        buf.putShort(floatTo16(sy).toShort())
                        buf.putShort(floatTo16(bri).toShort())
                    }
                }
            }
            COLOR_SPACE_RGB -> {
                for ((cid, rgb) in channels) {
                    val (r, g, b) = rgb
                    buf.put(cid.toByte())
                    buf.putShort(floatTo16(r).toShort())
                    buf.putShort(floatTo16(g).toShort())
                    buf.putShort(floatTo16(b).toShort())
                }
            }
        }
        return buf.array().copyOfRange(0, buf.position())
    }

    private fun slewXy(cid: Int, x: Float, y: Float): Pair<Float, Float> {
        val prev = prevXy[cid]
        if (prev != null) {
            val dx = x - prev.first
            val dy = y - prev.second
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > XY_SLEW_MAX) {
                val scale = XY_SLEW_MAX / dist
                val result = (prev.first + dx * scale) to (prev.second + dy * scale)
                prevXy[cid] = result
                return result
            }
        }
        prevXy[cid] = x to y
        return x to y
    }

    /**
     * Copy of the per-channel chromaticity state, for surviving a reconnect.
     *
     * `reconnect()` rebuilds the encoder because its sequence numbers belong to
     * the old session — and until this existed, rebuilding also dropped
     * [prevXy], so every channel's colour jumped to the engine's current state
     * in one frame, exactly the pop the slew limiter exists to prevent. The
     * sequence counter deliberately does not cross: it belongs to the session.
     */
    fun snapshotXy(): Map<Int, Pair<Float, Float>> = prevXy.toMap()

    /** Restore chromaticity state from a previous encoder's [snapshotXy]. */
    fun restoreXy(state: Map<Int, Pair<Float, Float>>) {
        prevXy.clear()
        prevXy.putAll(state)
    }

    private fun gamutCentroid(gamut: List<Pair<Float, Float>>): Pair<Float, Float> =
        (gamut[0].first + gamut[1].first + gamut[2].first) / 3f to
        (gamut[0].second + gamut[1].second + gamut[2].second) / 3f

    companion object {
        const val MAX_CHANNELS = 20
    }
}