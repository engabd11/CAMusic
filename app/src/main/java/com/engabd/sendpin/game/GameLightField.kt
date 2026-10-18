package com.engabd.sendpin.game

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.RoomTopology
import com.engabd.sendpin.hue.ambience.RoomModel
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Lane colours as 0xRRGGBB, shared by the highway on the glass and the lamps in the room. */
val LANE_RGB = intArrayOf(0xFF3B4E, 0x3D8BFF, 0xFFC01E, 0x5BE85B)

/**
 * The room as the rhythm game paints it: taps are the only light.
 *
 * ## Why this owns the frame
 *
 * The first version of game mode kept the music show running and gated its output, so
 * a tap let the show through. The show was already moving to the music, so the taps
 * only added to a reaction that would have happened anyway. Here the music engine does
 * not render at all in game mode (the analysis still runs, because the chart needs
 * the beats). The room is a dim album-colour glow, and everything brighter is
 * something the player did.
 *
 * ## Lanes and lamps
 *
 * The four lanes (kick, snare, hat, melody) each own a zone of the room, split by
 * azimuth sectors when the lamps ring the listener and by position along the room's
 * main axis otherwise. A hit flashes its lane's lamps in the lane's colour with a
 * little spill to neighbouring lamps, so the answer has a place as well as a colour.
 *
 * ## Threading
 *
 * Taps arrive on the main thread and the frame is built on the light render thread.
 * Commands go through a queue and are applied at the top of [render], so every piece
 * of state below is only ever touched by the render thread. [bindRoom] and [render]
 * must be called from that one thread.
 *
 * ## Time
 *
 * Everything is on one clock, `SystemClock.elapsedRealtime()`, passed in by the
 * caller. A hit may start in the future (an early tap is held so that the light and
 * the beat arrive together); until its start time it contributes nothing.
 */
class GameLightField {

    private sealed interface Command {
        class Hit(val lane: Int, val judgement: Judgement, val combo: Int, val startMs: Long) : Command
        class Miss(val lane: Int, val atMs: Long) : Command
        class Break(val atMs: Long) : Command
    }

    private class Flash(val lane: Int, val startMs: Long, val peak: Float, val decayMs: Float)
    private class Ember(val lane: Int, val startMs: Long)
    private class Bloom(val startMs: Long, val rgb: FloatArray)

    private val commands = ConcurrentLinkedQueue<Command>()

    // ── Render-thread state ────────────────────────────────────────────────

    private var ids = IntArray(0)
    private var laneIdx: List<IntArray> = List(LANE_COUNT) { IntArray(0) }
    private var spill: List<FloatArray> = List(LANE_COUNT) { FloatArray(0) }

    /** The lamp ids each lane lights, for tests and logs. */
    var laneLamps: List<List<Int>> = List(LANE_COUNT) { emptyList() }
        private set

    private val flashes = ArrayList<Flash>()
    private val embers = ArrayList<Ember>()
    private val blooms = ArrayList<Bloom>()
    private var comboNorm = 0f
    private var breakAtMs = -1L
    private var breakFrom = 0f
    private var boundChannels: Any? = null

    /** Bind the lamps this field paints. Idempotent for the same [channels] list. */
    fun bindChannels(channels: List<com.engabd.sendpin.hue.EntertainmentChannel>) {
        if (channels === boundChannels) return
        boundChannels = channels
        bindRoom(RoomModel(channels))
    }

    fun bindRoom(room: RoomModel) {
        ids = room.ids.toIntArray()
        val map = laneMap(room)
        laneLamps = map
        val indexOf = HashMap<Int, Int>(ids.size)
        ids.forEachIndexed { i, id -> indexOf[id] = i }
        laneIdx = map.map { lamps -> lamps.map { indexOf.getValue(it) }.toIntArray() }
        spill = laneIdx.map { lamps -> spillFor(room, lamps) }
        flashes.clear()
        embers.clear()
        blooms.clear()
    }

    // ── Commands, from any thread ──────────────────────────────────────────

    /** A scored tap on [lane]; the flash starts at [startMs] (real time, may be ahead of now). */
    fun postHit(lane: Int, judgement: Judgement, combo: Int, startMs: Long) {
        if (judgement == Judgement.MISS || lane !in 0 until LANE_COUNT) return
        commands.add(Command.Hit(lane, judgement, combo, startMs))
    }

    /** A note on [lane] fell past the line untouched. */
    fun postMiss(lane: Int, atMs: Long) {
        if (lane !in 0 until LANE_COUNT) return
        commands.add(Command.Miss(lane, atMs))
    }

    /** The combo just broke. */
    fun postComboBreak(atMs: Long) {
        commands.add(Command.Break(atMs))
    }

    // ── The frame ──────────────────────────────────────────────────────────

    /**
     * The whole room at [nowMs].
     *
     * [ceiling] is the user's brightness ceiling, 0..1: the peak of a Perfect, and the
     * most the idle glow may be. [idle] is the colour of the resting glow, normally
     * the track's album colour; it does not follow the beat.
     */
    fun render(nowMs: Long, ceiling: Float, idle: Rgb?): Map<Int, Rgb> {
        drain()
        val n = ids.size
        if (n == 0) return emptyMap()
        val cap = ceiling.coerceIn(0f, 1f)

        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        fun lift(i: Int, c: FloatArray, level: Float) {
            r[i] = max(r[i], c[0] * level)
            g[i] = max(g[i], c[1] * level)
            b[i] = max(b[i], c[2] * level)
        }

        val glow = unit(idle)
        val glowLevel = min(IDLE_LEVEL, cap)
        for (i in 0 until n) lift(i, glow, glowLevel)

        val combo = comboAt(nowMs)
        val whiten = WHITEN_AT_ZERO_COMBO * (1f - combo)

        for (bloom in blooms) {
            val age = (nowMs - bloom.startMs).toFloat()
            if (age < 0f || age >= BLOOM_MS) continue
            val env = if (age < BLOOM_ATTACK_MS) age / BLOOM_ATTACK_MS else {
                val d = 1f - (age - BLOOM_ATTACK_MS) / (BLOOM_MS - BLOOM_ATTACK_MS)
                d * d
            }
            for (i in 0 until n) lift(i, bloom.rgb, BLOOM_PEAK * cap * env)
        }

        for (e in embers) {
            val age = (nowMs - e.startMs).toFloat()
            if (age < 0f || age >= EMBER_MS) continue
            // Eased in so it can never read as a flash, then a straight fade.
            val env = if (age < EMBER_ATTACK_MS) age / EMBER_ATTACK_MS
            else 1f - (age - EMBER_ATTACK_MS) / (EMBER_MS - EMBER_ATTACK_MS)
            for (i in laneIdx[e.lane]) lift(i, EMBER_RGB, min(EMBER_LEVEL, cap) * env)
        }

        for (f in flashes) {
            val age = (nowMs - f.startMs).toFloat()
            if (age < 0f || age >= f.decayMs) continue
            val d = 1f - age / f.decayMs
            val level = f.peak * cap * d * d
            val c = laneColour(f.lane, whiten)
            for (i in laneIdx[f.lane]) lift(i, c, level)
            val s = spill[f.lane]
            for (j in 0 until n) if (s[j] > 0f) lift(j, c, level * s[j])
        }

        prune(nowMs)

        val out = HashMap<Int, Rgb>(n)
        for (i in 0 until n) out[ids[i]] = Rgb(r[i].coerceIn(0f, 1f), g[i].coerceIn(0f, 1f), b[i].coerceIn(0f, 1f))
        return out
    }

    private fun drain() {
        while (true) {
            when (val c = commands.poll() ?: return) {
                is Command.Hit -> {
                    comboNorm = min(c.combo, COMBO_FULL) / COMBO_FULL.toFloat()
                    breakAtMs = -1L
                    val peak = when (c.judgement) {
                        Judgement.PERFECT -> PEAK_PERFECT
                        Judgement.GREAT -> PEAK_GREAT
                        else -> PEAK_GOOD
                    }
                    flashes.add(Flash(c.lane, c.startMs, peak, DECAY_MS * (1f + DECAY_COMBO_GAIN * comboNorm)))
                    if (c.combo in BLOOM_COMBOS) {
                        val lc = laneColour(c.lane, 0.5f)
                        blooms.add(Bloom(c.startMs, lc))
                    }
                }
                is Command.Miss -> embers.add(Ember(c.lane, c.atMs))
                is Command.Break -> {
                    breakFrom = comboAt(c.atMs)
                    breakAtMs = c.atMs
                    comboNorm = 0f
                }
            }
        }
    }

    /** The combo's pull on colour at [nowMs]: held while it lasts, fading over 400 ms once it breaks. */
    private fun comboAt(nowMs: Long): Float {
        if (breakAtMs < 0L) return comboNorm
        val t = (nowMs - breakAtMs).toFloat() / BREAK_FADE_MS
        return (breakFrom * (1f - t)).coerceIn(0f, 1f)
    }

    private fun prune(nowMs: Long) {
        flashes.removeAll { nowMs - it.startMs >= it.decayMs }
        embers.removeAll { nowMs - it.startMs >= EMBER_MS }
        blooms.removeAll { nowMs - it.startMs >= BLOOM_MS }
    }

    private fun laneColour(lane: Int, whiten: Float): FloatArray {
        val c = unitRgb(LANE_RGB[lane])
        val w = whiten.coerceIn(0f, 1f)
        return floatArrayOf(c[0] + (1f - c[0]) * w, c[1] + (1f - c[1]) * w, c[2] + (1f - c[2]) * w)
    }

    companion object {
        const val LANE_COUNT = 4

        /** Peak of a Perfect, Great and Good, before the brightness ceiling. */
        const val PEAK_PERFECT = 1.0f
        const val PEAK_GREAT = 0.7f
        const val PEAK_GOOD = 0.45f

        /** The resting glow, before the ceiling. */
        const val IDLE_LEVEL = 0.06f

        /** A missed note's ember. */
        const val EMBER_LEVEL = 0.12f
        const val EMBER_MS = 300f
        private const val EMBER_ATTACK_MS = 60f

        /** Flash decay at no combo, and the extra fraction it gains at full combo. */
        const val DECAY_MS = 250f
        private const val DECAY_COMBO_GAIN = 0.6f

        /** The combo at which the saturation and decay gains stop growing. */
        const val COMBO_FULL = 50

        /** How much of a lane colour is washed toward white at combo zero. */
        private const val WHITEN_AT_ZERO_COMBO = 0.3f

        const val BREAK_FADE_MS = 400f

        /** The whole-room bloom at a combo milestone. */
        val BLOOM_COMBOS = setOf(10, 25, 50)
        private const val BLOOM_PEAK = 0.3f
        private const val BLOOM_ATTACK_MS = 80f
        const val BLOOM_MS = 700f

        /** Share of a flash that reaches the nearest neighbouring lamp. */
        const val SPILL = 0.2f

        private val EMBER_RGB = floatArrayOf(1f, 0.1f, 0.08f)

        /** The idle glow's colour when the track has none: a cool white. */
        private val NEUTRAL = floatArrayOf(0.8f, 0.88f, 1f)

        private fun unitRgb(rgb: Int): FloatArray = unit(
            Rgb(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f),
        )

        /** [c] scaled so its brightest channel is 1, so a level means the same on every colour. */
        private fun unit(c: Rgb?): FloatArray {
            if (c == null) return NEUTRAL
            val m = max(c.first, max(c.second, c.third))
            if (m <= 1e-3f) return NEUTRAL
            return floatArrayOf(c.first / m, c.second / m, c.third / m)
        }

        /**
         * Which lamps each of the four lanes lights.
         *
         * Lamps are ordered around the room (azimuth on a ring, position along the
         * main axis otherwise) and cut into four contiguous zones, so each lane owns a
         * region rather than a scatter. With fewer than four lamps the lanes share:
         * kick at one end, melody at the other, snare and hat in between, so every
         * lane lights something.
         */
        fun laneMap(room: RoomModel): List<List<Int>> {
            val coordinate = if (room.topology == RoomTopology.RING) room.azimuth else room.axisPos
            return laneMap(room.ids, coordinate)
        }

        fun laneMap(ids: List<Int>, coordinate: Map<Int, Float>): List<List<Int>> {
            val n = ids.size
            if (n == 0) return List(LANE_COUNT) { emptyList() }
            val ordered = ids.sortedWith(compareBy({ coordinate[it] ?: 0f }, { it }))
            if (n >= LANE_COUNT) {
                return List(LANE_COUNT) { lane ->
                    ordered.subList(lane * n / LANE_COUNT, (lane + 1) * n / LANE_COUNT)
                }
            }
            return List(LANE_COUNT) { lane ->
                listOf(ordered[(lane * (n - 1) / (LANE_COUNT - 1f)).roundToInt()])
            }
        }

        /**
         * How much of a [lane] flash reaches each lamp outside it: the nearest
         * neighbour gets [SPILL], the rest in proportion to how strongly the room's
         * coupling kernel ties them to the lane's lamps.
         */
        internal fun spillFor(room: RoomModel, lane: IntArray): FloatArray {
            val n = room.count
            val out = FloatArray(n)
            if (n < 2 || lane.isEmpty()) return out
            val inLane = BooleanArray(n)
            for (i in lane) inLane[i] = true
            for (i in lane) {
                val row = room.coupling[i]
                var peak = 0f
                for (j in 0 until n) if (j != i && !inLane[j] && row[j] > peak) peak = row[j]
                if (peak <= 0f) continue
                for (j in 0 until n) if (!inLane[j]) out[j] = max(out[j], SPILL * row[j] / peak)
            }
            return out
        }
    }
}
