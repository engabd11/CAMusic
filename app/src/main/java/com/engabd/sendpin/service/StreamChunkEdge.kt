package com.engabd.sendpin.service

/**
 * Detects the **first audio chunk of a (re)started stream** — the edge the official
 * Music Assistant app calls `Buffering → Synchronized`, and the one thing this phone
 * can see that the server's polled state cannot.
 *
 * Why this edge and not the audible one: the playhead's optimistic hold on a seek or
 * a skip has to release at the instant Music Assistant's own clock for the new stream
 * starts, or the two disagree from the first reading. MA's `elapsed_time` for a
 * Sendspin player counts from its first chunk *commit*, which is this arrival to
 * within a network hop. Sound reaches the ear about 1.6 s later (decoder and output
 * warm-up); releasing there put the bar 1.6 s behind every reading that followed,
 * which is the "snap forward after a seek" this replaces.
 *
 * Armed by `stream/start` and by `stream/clear` (a strict-mode server changes track
 * without a new `stream/start` when the format is unchanged; it clears and sends new
 * chunks). Disarmed by `stream/end` and by a dropped socket, so a stale chunk after
 * either cannot count. All four calls come from the one ingest coroutine in
 * `SendspinClient`, so a plain flag is enough.
 *
 * Pure, so it can be tested without an engine.
 */
class StreamChunkEdge {
    @Volatile private var armed = false

    fun onStreamStart() { armed = true }
    fun onStreamClear() { armed = true }
    fun onStreamEnd() { armed = false }
    fun onDisconnected() { armed = false }

    /** True exactly once per arming: this chunk is the first of a (re)started stream. */
    fun onAudio(): Boolean = if (armed) { armed = false; true } else false
}
