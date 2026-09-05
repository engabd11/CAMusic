package com.engabd.sendpin.hue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The encoder's xy slew limiter exists because the bridge does not interpolate
 * between streamed frames: a chromaticity the engine did not reach in one step
 * would pop. A reconnect rebuilds the encoder — its sequence numbers belong to
 * the old session — and until the slew state could be carried, every channel's
 * colour jumped in that same one frame. These tests pin the seam: state out,
 * state into a fresh encoder, identical continuation.
 */
class HueStreamEncoderSlewStateTest {

    private val configId = "1a8d99cc-967b-44f2-9202-43f976c0fa6b"

    @Test
    fun `a fresh encoder starts with empty slew state`() {
        assertEquals(0, HueStreamEncoder(configId).snapshotXy().size)
    }

    @Test
    fun `slewing records per-channel state`() {
        val enc = HueStreamEncoder(configId)
        // A red channel walked along the spectrum edge: several frames so the
        // 0.08-per-frame slew engages and the stored xy is not the first target.
        repeat(6) {
            enc.buildPackets(mapOf(0 to Triple(1f, it * 0.2f, 0f)))
        }
        val state = enc.snapshotXy()
        assertEquals(setOf(0), state.keys)
        assertTrue(state.getValue(0).first > 0f)
    }

    @Test
    fun `restored state continues exactly where the old encoder left off`() {
        val a = HueStreamEncoder(configId)
        repeat(6) { a.buildPackets(mapOf(0 to Triple(1f, 0.9f, 0f))) }
        val snapshot = a.snapshotXy()
        assertTrue(snapshot.isNotEmpty())

        val b = HueStreamEncoder(configId)
        b.restoreXy(snapshot)
        assertEquals(snapshot, b.snapshotXy())

        // Same next frame into both encoders: the restored one must continue
        // from the carried state, so its emitted chromaticity is the same step
        // the original would have taken. The fresh-encoder answer differs,
        // which is exactly the pop this prevents.
        // Compared from byte 52 on (past header + config UUID): the seq byte
        // inside the header legitimately differs — it counts frames per session.
        val next = mapOf(0 to Triple(0.2f, 0.1f, 0.9f))
        val fromRestored = b.buildPackets(next)
        val fromOriginal = a.buildPackets(next)
        val fromFresh = HueStreamEncoder(configId).buildPackets(next)
        assertTrue(fromRestored.size == 1 && fromOriginal.size == 1 && fromFresh.size == 1)
        assertTrue(fromRestored[0].copyOfRange(52, fromRestored[0].size)
            .contentEquals(fromOriginal[0].copyOfRange(52, fromOriginal[0].size)))
        assertTrue(!fromFresh[0].copyOfRange(52, fromFresh[0].size)
            .contentEquals(fromOriginal[0].copyOfRange(52, fromOriginal[0].size)))
    }

    @Test
    fun `restoring into a used encoder replaces its state`() {
        val a = HueStreamEncoder(configId)
        a.buildPackets(mapOf(0 to Triple(1f, 0f, 0f)))
        val b = HueStreamEncoder(configId)
        b.buildPackets(mapOf(1 to Triple(0f, 1f, 0f)))
        b.restoreXy(a.snapshotXy())
        assertEquals(setOf(0), b.snapshotXy().keys)
    }

    @Test
    fun `sequence numbers do not survive the round trip`() {
        // Deliberate: the new session's sequence counter belongs to the new
        // encoder. Only the chromaticity state crosses.
        val a = HueStreamEncoder(configId)
        repeat(3) { a.buildPackets(mapOf(0 to Triple(1f, 1f, 1f))) }
        val b = HueStreamEncoder(configId)
        b.restoreXy(a.snapshotXy())
        val bytes = b.buildPackets(mapOf(0 to Triple(1f, 1f, 1f)))
        // Header: 9 name bytes + 2 version bytes, so the seq byte is index 11.
        assertEquals(1, bytes[0][11].toInt()) // seq byte: fresh counter's first step
    }
}
