package com.engabd.sendpin.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The first-chunk edge: once per arming, never on a stale chunk. */
class StreamChunkEdgeTest {

    private val edge = StreamChunkEdge()

    @Test
    fun `a stream start makes the next chunk the edge, and only that one`() {
        edge.onStreamStart()
        assertTrue(edge.onAudio())
        assertFalse(edge.onAudio())
        assertFalse(edge.onAudio())
    }

    @Test
    fun `a clear re-arms without a new start`() {
        edge.onStreamStart()
        assertTrue(edge.onAudio())
        edge.onStreamClear()
        assertTrue(edge.onAudio())
        assertFalse(edge.onAudio())
    }

    @Test
    fun `a chunk with nothing armed is not an edge`() {
        assertFalse(edge.onAudio())
    }

    @Test
    fun `an end or a drop disarms, so a late chunk cannot count`() {
        edge.onStreamStart()
        edge.onStreamEnd()
        assertFalse(edge.onAudio())
        edge.onStreamStart()
        edge.onDisconnected()
        assertFalse(edge.onAudio())
    }
}
