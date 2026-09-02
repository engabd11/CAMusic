package com.engabd.sendpin.hue.ambience

/**
 * The events an ambience show has scheduled, readable from two threads at two rates.
 *
 * One writer — the generator coroutine — and two readers: the audio block it is about to
 * render, and the 60 Hz light tick. No locks, and none needed:
 *
 * 1. [AmbienceEvent] is deeply immutable with final fields, so publishing one is safe
 *    however it is seen.
 * 2. [head] is volatile. The writer fills a slot and *then* bumps it; a reader reads it
 *    and *then* reads slots. That pairing is the release/acquire edge, so a reader can
 *    never see a slot that was not fully written.
 * 3. The generator runs one audio buffer — about 200 ms — ahead of the playhead, and
 *    schedules a block before it renders it. So by the time the light tick asks what is
 *    happening *now*, everything it could need was published a fifth of a second ago.
 *    There is no window in which lights can outrun scheduling.
 *
 * Fixed capacity, overwriting oldest. That is safe precisely because
 * [AmbienceEvent.spanS] is finite: an event older than the longest span in play cannot
 * be alive in either medium, so overwriting it loses nothing. The span, not the light
 * envelope — a strike stops being a flash long before it stops being thunder, and a
 * ring sized on the light alone would recycle a slot the audio was still due to read.
 * [windowAt] still checks, rather than trusting the arithmetic.
 */
class AmbienceTimeline(capacity: Int = 512) {

    init {
        require(capacity > 0 && capacity and (capacity - 1) == 0) {
            "capacity must be a power of two, was $capacity"
        }
    }

    private val mask = (capacity - 1).toLong()
    private val slots = arrayOfNulls<AmbienceEvent>(capacity)

    /** Total events ever appended. Volatile: this is the publish barrier. */
    @Volatile private var head = 0L

    val size: Long get() = head

    /** Single writer only. */
    fun append(e: AmbienceEvent) {
        slots[(head and mask).toInt()] = e
        head++          // volatile write: publishes the slot above
    }

    /**
     * Fill [into] with the events alive at [tS] and return how many.
     *
     * Walks back from the newest and stops at the capacity, so cost is bounded by the
     * ring rather than by how long the show has been running. Never allocates; [into] is
     * the caller's own scratch array, which is why two readers never contend.
     *
     * Overflows silently when more events are alive than [into] can hold — an effect
     * that dense is already past what the room can express, and dropping the oldest few
     * is far better than allocating on the audio thread.
     */
    fun windowAt(tS: Double, into: Array<AmbienceEvent?>): Int =
        fill(into) { it.aliveAt(tS) }

    /**
     * Fill [into] with the events alive anywhere in `[fromS, toS)` and return how many.
     *
     * What the audio path wants, and [windowAt] is not it. A block is a span of about
     * 21 ms, and an event that starts inside that span is not alive at its first
     * sample — so gating a block on its start instant silently dropped the leading
     * edge of every event whose `startS` did not land exactly on a block boundary.
     * For a thunder crack, whose whole envelope is a 1 ms attack and a 50 ms tail,
     * that leading edge *is* the sound.
     */
    fun windowOver(fromS: Double, toS: Double, into: Array<AmbienceEvent?>): Int =
        fill(into) { it.aliveOver(fromS, toS) }

    private inline fun fill(into: Array<AmbienceEvent?>, keep: (AmbienceEvent) -> Boolean): Int {
        val h = head          // volatile read: acquires every slot written before it
        var n = 0
        var i = h - 1
        val floor = maxOf(0L, h - slots.size)
        while (i >= floor && n < into.size) {
            val e = slots[(i and mask).toInt()]
            if (e != null && keep(e)) into[n++] = e
            i--
        }
        // Stale entries past n are never read — callers are handed the count — but
        // clearing one keeps a long-lived event from being pinned by the array alone.
        if (n < into.size) into[n] = null
        return n
    }

    fun clear() {
        java.util.Arrays.fill(slots, null)
        head = 0L
    }
}
