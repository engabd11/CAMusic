package com.engabd.sendpin.car

/**
 * The slice of the phone's queue the car is shown, and the way back from a row in it
 * to a place in the queue.
 *
 * Android Auto draws a queue from the session's timeline. The car facade used to
 * publish three copies of the playing track - "previous", current, "next" - so the
 * car's queue view showed one song three times and a tap on any of them did nothing.
 * Now it is the real queue around the playing track, capped at [MAX] rows either
 * way so a 2,000-track queue does not cross binder with every state change.
 *
 * When the playing track is the last one, a trailing stand-in row keeps the car's Next
 * button enabled: DJ Radio and "keep the music going" add tracks at the end, and a
 * Next greyed out by the timeline would never ask.
 */
data class CarQueueWindow(
    /** Queue index of the window's first row. */
    val start: Int,
    /** Queue indices shown, `start until endExclusive`. */
    val endExclusive: Int,
    /** The playing track's row within the window. */
    val currentRow: Int,
    /** A stand-in row after the last real one. */
    val trailingNext: Boolean,
) {
    val rows: Int get() = endExclusive - start + if (trailingNext) 1 else 0

    /** The queue index a tapped row means, or null for the stand-in. */
    fun queueIndexOf(row: Int): Int? = (start + row).takeIf { it in start until endExclusive }

    companion object {
        const val MAX = 50

        fun of(queueSize: Int, current: Int, max: Int = MAX): CarQueueWindow? {
            if (queueSize <= 0 || current !in 0 until queueSize) return null
            val start = (current - max).coerceAtLeast(0)
            val end = (current + max + 1).coerceAtMost(queueSize)
            return CarQueueWindow(start, end, current - start, trailingNext = current == queueSize - 1)
        }
    }
}
