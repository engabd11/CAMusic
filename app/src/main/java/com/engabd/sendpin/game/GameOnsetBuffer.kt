package com.engabd.sendpin.game

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * One beat or onset flag from the analyser, stamped where it happened.
 *
 * The frame the game reads is a conflated `StateFlow`: when the main thread is busy
 * the analyser produces several frames before one is read, and only the last is
 * kept. A beat flag lives on a single frame, so a dropped frame was a dropped beat.
 * The engine pushes each flag here instead, with the real time it was analysed at,
 * and the game drains the buffer rather than trusting what the flow happened to keep.
 */
data class GameOnset(
    /** `elapsedRealtime` when the frame was analysed. */
    val atMs: Long,
    /** The frame's analysis lag, for [GameTiming.effectiveLeadMs]. */
    val lagMs: Float,
    val beat: Boolean,
    val beatStrength: Float,
    val bass: Boolean,
    val bassStrength: Float,
    val mid: Boolean,
    val midStrength: Float,
)

/**
 * A small thread-safe queue of [GameOnset]s: pushed by the analysis thread, drained by
 * the game on the main thread. Bounded, so a screen that is not draining (the game is
 * paused, or not open) cannot grow it.
 */
class GameOnsetBuffer(private val capacity: Int = 64) {
    private val queue = ConcurrentLinkedQueue<GameOnset>()
    private val size = AtomicInteger()

    fun push(onset: GameOnset) {
        queue.add(onset)
        if (size.incrementAndGet() > capacity && queue.poll() != null) size.decrementAndGet()
    }

    /** Everything pushed since the last drain, oldest first. */
    fun drain(): List<GameOnset> {
        if (queue.isEmpty()) return emptyList()
        val out = ArrayList<GameOnset>()
        while (true) {
            val next = queue.poll() ?: break
            size.decrementAndGet()
            out.add(next)
        }
        return out
    }

    fun clear() {
        queue.clear()
        size.set(0)
    }
}
