package com.engabd.sendpin.game

import com.engabd.sendpin.hue.Rgb
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.math.min

/** Lane colours as 0xRRGGBB, for the highway on the glass. The room wears the show's colours. */
val LANE_RGB = intArrayOf(0xFF3B4E, 0x3D8BFF, 0xFFC01E, 0x5BE85B)

/**
 * The room as the rhythm game shows it: the light show the music already had in
 * mind, let through only while the player keeps time.
 *
 * ## A gate, not a painter
 *
 * The music show renders exactly as it would without the game — the user's own
 * show, delay-aligned to the audio — and this multiplies it by a whole-room gate.
 * Shut, the room rests in a dim album-colour glow. A hit opens it, so the flashes,
 * colours and movement the room shows are the show's own, on the show's own beats;
 * the player's part is keeping them coming. The earlier version painted lane
 * colours over a dark room instead, which was the player's light but never the
 * music's.
 *
 * ## Why a hit carries the gate to the next note
 *
 * A frame has to be sent about [GameTiming.LIGHT_PIPELINE_MS] before it is seen, so
 * the frame carrying a beat's flash leaves before an on-time tap for that beat can
 * exist. A gate opened by each tap alone would only ever show the tail of each
 * flash. So a hit opens the gate from just before its own note through the next
 * one: on a clean run the gate never shuts and the show plays in time, and a miss
 * or combo break ([postClose]) shuts it from the moment it is known.
 *
 * ## Threading and time
 *
 * Commands arrive from the main thread through a queue and are applied at the top of
 * [composite], on the render thread, so the state below has one owner. Every time is
 * `SystemClock.elapsedRealtime()` *send* time, the clock the render loop runs on.
 */
class GameLightField {

    private sealed interface Command {
        class Open(val fromMs: Long, val untilMs: Long, val level: Float) : Command
        class Close(val atMs: Long) : Command
        class Charge(val level: Float) : Command
    }

    private val commands = ConcurrentLinkedQueue<Command>()

    // ── Render-thread state ────────────────────────────────────────────────

    private var fromMs = Long.MAX_VALUE
    private var untilMs = Long.MIN_VALUE
    private var level = 0f
    private var charge = 0f

    // ── Commands, from any thread ──────────────────────────────────────────

    /**
     * Let the show through from [fromMs] (ramping in over [ATTACK_MS]) until [untilMs]
     * (fading over [RELEASE_MS]), at [level] of its own brightness. A window that
     * overlaps the open one extends it; [level] is the newest hit's.
     */
    fun postOpen(fromMs: Long, untilMs: Long, level: Float) {
        commands.add(Command.Open(fromMs, untilMs, level.coerceIn(0f, 1f)))
    }

    /** Shut the gate from [atMs] — a miss, a combo break, a dropped hold. */
    fun postClose(atMs: Long) {
        commands.add(Command.Close(atMs))
    }

    /** How much of the build the player has earned, 0..1; lets the show in at half of it. */
    fun postCharge(level: Float) {
        commands.add(Command.Charge(level.coerceIn(0f, 1f)))
    }

    // ── The frame ──────────────────────────────────────────────────────────

    /**
     * The room at [nowMs]: [show] through the gate, never below the idle glow.
     *
     * [ceiling] is the user's brightness ceiling, 0..1, which caps the glow; the show
     * already respects it. [idle] is the glow's colour, normally the album's.
     */
    fun composite(show: Map<Int, Rgb>, nowMs: Long, ceiling: Float, idle: Rgb?): Map<Int, Rgb> {
        drain(nowMs)
        val gate = max(gateAt(nowMs), charge * CHARGE_GATE)
        val glow = unit(idle)
        val glowLevel = min(IDLE_LEVEL, ceiling.coerceIn(0f, 1f))
        val out = HashMap<Int, Rgb>(show.size)
        for ((id, c) in show) {
            out[id] = Rgb(
                max(glow[0] * glowLevel, c.first * gate).coerceIn(0f, 1f),
                max(glow[1] * glowLevel, c.second * gate).coerceIn(0f, 1f),
                max(glow[2] * glowLevel, c.third * gate).coerceIn(0f, 1f),
            )
        }
        return out
    }

    /** The gate's opening at [nowMs], 0..1. */
    internal fun gateAt(nowMs: Long): Float {
        if (nowMs < fromMs) return 0f
        val env = if (nowMs <= untilMs) {
            min(1f, (nowMs - fromMs + 1).toFloat() / ATTACK_MS)
        } else {
            1f - (nowMs - untilMs).toFloat() / RELEASE_MS
        }
        return (env * level).coerceIn(0f, 1f)
    }

    private fun drain(nowMs: Long) {
        while (true) {
            when (val c = commands.poll() ?: return) {
                is Command.Open -> {
                    // Overlapping (or still fading) windows merge; a fresh one starts over.
                    if (fromMs != Long.MAX_VALUE && nowMs <= untilMs + RELEASE_MS && c.fromMs <= untilMs + RELEASE_MS) {
                        fromMs = min(fromMs, c.fromMs)
                        untilMs = max(untilMs, c.untilMs)
                    } else {
                        fromMs = c.fromMs
                        untilMs = c.untilMs
                    }
                    level = c.level
                }
                is Command.Close -> if (c.atMs < untilMs) untilMs = max(c.atMs, fromMs)
                is Command.Charge -> charge = c.level
            }
        }
    }

    companion object {
        /** The resting glow, before the ceiling. */
        const val IDLE_LEVEL = 0.06f

        /** How fast the gate opens, and how slowly it lets go. */
        const val ATTACK_MS = 30f
        const val RELEASE_MS = 150f

        /** The share of the show a fully charged build lets in on its own. */
        const val CHARGE_GATE = 0.5f

        /** The idle glow's colour when the track has none: a cool white. */
        private val NEUTRAL = floatArrayOf(0.8f, 0.88f, 1f)

        /** [c] scaled so its brightest channel is 1, so a level means the same on every colour. */
        private fun unit(c: Rgb?): FloatArray {
            if (c == null) return NEUTRAL
            val m = max(c.first, max(c.second, c.third))
            if (m <= 1e-3f) return NEUTRAL
            return floatArrayOf(c.first / m, c.second / m, c.third / m)
        }
    }
}
