package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SendspinNativeEngine.convertPcm24To16] runs on every sample of a 24-bit
 * stream, so a bug here is audible on every track rather than an edge case.
 * The previous version just dropped the low byte — plain truncation, a
 * consistent bias toward zero. These check the replacement actually rounds,
 * and that rounding a sample already at positive full scale saturates rather
 * than wrapping into a negative one.
 */
class Pcm24To16Test {

    /** Packs signed 24-bit values into little-endian bytes — the wire format [convertPcm24To16] reads. */
    private fun pack24(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 3)
        values.forEachIndexed { i, v ->
            out[i * 3] = (v and 0xFF).toByte()
            out[i * 3 + 1] = ((v shr 8) and 0xFF).toByte()
            out[i * 3 + 2] = ((v shr 16) and 0xFF).toByte()
        }
        return out
    }

    /** Reads little-endian 16-bit samples back out as signed ints. */
    private fun unpack16(bytes: ByteArray): List<Int> =
        bytes.toList().chunked(2).map { (lo, hi) ->
            (((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)).toShort().toInt()
        }

    @Test
    fun `a mid-scale value rounds to the nearer 16-bit value`() {
        // 320 / 256 = 1.25 -> nearer to 1.
        assertEquals(listOf(1), unpack16(SendspinNativeEngine.convertPcm24To16(pack24(320))))
        // 448 / 256 = 1.75 -> nearer to 2.
        assertEquals(listOf(2), unpack16(SendspinNativeEngine.convertPcm24To16(pack24(448))))
    }

    @Test
    fun `positive full scale saturates instead of wrapping negative`() {
        val result = unpack16(SendspinNativeEngine.convertPcm24To16(pack24(0x7FFFFF)))
        assertEquals(listOf(Short.MAX_VALUE.toInt()), result)
        assertTrue(result[0] > 0, "must not wrap to a negative sample: $result")
    }

    @Test
    fun `negative values round toward the nearer 16-bit value too`() {
        // -400 / 256 = -1.5625 -> nearer to -2.
        assertEquals(listOf(-2), unpack16(SendspinNativeEngine.convertPcm24To16(pack24(-400))))
    }

    @Test
    fun `negative full scale is exact, no saturation needed`() {
        assertEquals(
            listOf(Short.MIN_VALUE.toInt()),
            unpack16(SendspinNativeEngine.convertPcm24To16(pack24(-8_388_608))),
        )
    }

    @Test
    fun `a trailing partial sample is dropped, not read out of bounds`() {
        // Two full samples plus a dangling byte and a half — a chunk boundary
        // landing mid-sample, which the old `while (src + 2 < data.size)` guard
        // also dropped rather than reading past the array.
        val data = pack24(100, -100) + byteArrayOf(0x11, 0x22)
        val result = unpack16(SendspinNativeEngine.convertPcm24To16(data))
        assertEquals(2, result.size)
    }
}
