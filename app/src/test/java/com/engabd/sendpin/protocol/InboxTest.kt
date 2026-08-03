package com.engabd.sendpin.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The admission rule for the ordered inbound queue.
 *
 * The queue is unbounded on purpose — dropping to make room is exactly the
 * reordering the queue exists to prevent — so this is the only thing standing
 * between a stalled consumer and unbounded memory. Which end it sacrifices matters:
 * a stale audio chunk is worthless by the time the backlog is this deep, but a lost
 * `stream/start` or `server/time` breaks the session in ways it never recovers from.
 */
class InboxTest {

    @Test
    fun `audio is accepted while there is room`() {
        assertTrue(Inbox.accept(depth = 0, isAudio = true, max = 4))
        assertTrue(Inbox.accept(depth = 3, isAudio = true, max = 4))
    }

    @Test
    fun `audio is dropped once the queue is full`() {
        assertFalse(Inbox.accept(depth = 4, isAudio = true, max = 4))
        assertFalse(Inbox.accept(depth = 400, isAudio = true, max = 4))
    }

    /** The whole point: a backlog must never cost a control message. */
    @Test
    fun `control messages are never dropped, however deep the backlog`() {
        assertTrue(Inbox.accept(depth = 4, isAudio = false, max = 4))
        assertTrue(Inbox.accept(depth = Int.MAX_VALUE, isAudio = false, max = 4))
    }

    @Test
    fun `the default cap is deep enough to be a stall, not a hiccup`() {
        // ~20 ms per FLAC chunk, so the default is over a minute of backlog —
        // far past anything a merely-busy consumer produces.
        assertTrue(Inbox.MAX_DEPTH >= 2048)
    }
}
