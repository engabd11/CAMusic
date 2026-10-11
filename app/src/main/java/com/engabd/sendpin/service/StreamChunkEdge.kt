package com.engabd.sendpin.service

/**
 * Detects the **first audio chunk of a (re)started stream**. It arms the playhead's
 * stream edge; [Playback] bumps `streamChunkSeq` once that audio is heard (the
 * engine's startup mute lifting), the official Music Assistant app's
 * `PlaybackStarted`. Music Assistant 2.10.6 counts a Sendspin player's elapsed time
 * from the first chunk's scheduled play time, so that is the instant the server's
 * clock and the bar agree. (Releasing on arrival instead, ~60 ms after a seek, let
 * the server's bogus post-seek reading through.)
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
