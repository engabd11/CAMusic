package com.engabd.sendpin.protocol.noise

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Type-byte framing and fragmentation, both directions (spec §Fragmentation). */
class NoiseFramingTest {

    private fun reassemble(frames: List<ByteArray>): ByteArray? {
        val r = NoiseFraming.Reassembler()
        var out: ByteArray? = null
        for (f in frames) out = r.accept(f)
        return out
    }

    @Test
    fun `a message that fits is one frame, untouched`() {
        val msg = byteArrayOf(4, 1, 2, 3)
        val frames = NoiseFraming.fragment(msg)
        assertEquals(1, frames.size)
        assertContentEquals(msg, frames[0])
        assertContentEquals(msg, reassemble(frames))
    }

    @Test
    fun `a big audio chunk is split and comes back identical`() {
        // A 24/192 stereo 150 ms PCM chunk plus the 9-byte header: several frames' worth.
        val msg = ByteArray(9 + 192_000 * 6 * 150 / 1000) { (it * 7).toByte() }.also { it[0] = 4 }
        val frames = NoiseFraming.fragment(msg)
        // 172 809 bytes: 65 517 in the opening frame, then two of up to 65 518.
        assertEquals(3, frames.size)
        assertEquals(NoiseFraming.TYPE_FRAGMENT_MORE, frames[0][0].toInt())
        assertEquals(4, frames[0][1].toInt())
        frames.forEach { assertEquals(true, it.size <= NoiseFraming.MAX_PLAINTEXT, "frame within Noise limit") }
        assertEquals(NoiseFraming.TYPE_FRAGMENT_END, frames.last()[0].toInt())
        assertContentEquals(msg, reassemble(frames))
    }

    @Test
    fun `one byte over the limit still fragments correctly`() {
        val msg = ByteArray(NoiseFraming.MAX_PLAINTEXT + 1) { it.toByte() }.also { it[0] = 0 }
        val frames = NoiseFraming.fragment(msg)
        assertEquals(2, frames.size)
        assertContentEquals(msg, reassemble(frames))
    }

    @Test
    fun `fragments in flight return nothing until the end frame`() {
        val r = NoiseFraming.Reassembler()
        assertNull(r.accept(byteArrayOf(2, 0, 'a'.code.toByte())))
        assertNull(r.accept(byteArrayOf(2, 'b'.code.toByte())))
        assertContentEquals(byteArrayOf(0) + "abc".toByteArray(), r.accept(byteArrayOf(3, 'c'.code.toByte())))
        // And the buffer is clear for the next message.
        assertContentEquals(byteArrayOf(4, 9), r.accept(byteArrayOf(4, 9)))
    }

    @Test
    fun `malformed sequences are protocol errors`() {
        assertFailsWith<NoiseFraming.Reassembler.ProtocolError> { NoiseFraming.Reassembler().accept(byteArrayOf(3, 1)) }
        assertFailsWith<NoiseFraming.Reassembler.ProtocolError> {
            NoiseFraming.Reassembler().apply { accept(byteArrayOf(2, 0, 1)) }.accept(byteArrayOf(0, 1))
        }
        assertFailsWith<NoiseFraming.Reassembler.ProtocolError> { NoiseFraming.Reassembler().accept(byteArrayOf(2, 3, 1)) }
        assertFailsWith<NoiseFraming.Reassembler.ProtocolError> { NoiseFraming.Reassembler().accept(ByteArray(0)) }
    }

    @Test
    fun `json bodies carry type byte zero`() {
        val f = NoiseFraming.json("{\"a\":1}")
        assertEquals(0, f[0].toInt())
        assertEquals("{\"a\":1}", String(f, 1, f.size - 1, Charsets.UTF_8))
    }
}
