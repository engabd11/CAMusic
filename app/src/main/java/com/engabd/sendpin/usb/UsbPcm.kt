package com.engabd.sendpin.usb

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decoded PCM to a USB DAC's own sample layout, without changing a sample.
 *
 * Every input is first lifted to a signed 32-bit full-scale value and then placed in the
 * DAC's slot: [bits] of audio, left-justified in [slotBytes] bytes, little-endian, as USB
 * Audio Class Type I requires.
 *
 * Why this is exact for real music: the float path carries a 16-bit sample n as n/2^15
 * and a 24-bit sample as n/2^23, and a 32-bit float's 24-bit mantissa holds both without
 * loss. Scaling by 2^31 gives n·2^16 or n·2^8 exactly, so shifting down to the DAC's
 * depth returns the file's own integer: a 16-bit file into a 24-bit DAC arrives as n·2^8,
 * the same number with zeros below it. The tests prove it for every 16-bit value.
 *
 * [volume] other than 1 is digital gain — used only for fades and ducking — and the one
 * case where the output is no longer the file's own samples.
 */
object UsbPcm {

    /** The media3 encodings this accepts. */
    const val PCM_16 = C.ENCODING_PCM_16BIT
    const val PCM_24 = C.ENCODING_PCM_24BIT
    const val PCM_32 = C.ENCODING_PCM_32BIT
    const val PCM_FLOAT = C.ENCODING_PCM_FLOAT

    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        PCM_16 -> 2
        PCM_24 -> 3
        PCM_32, PCM_FLOAT -> 4
        else -> 0
    }

    /**
     * Convert [samples] samples starting at [src]'s position (which advances) into [dst]
     * from [dstOffset]. [dst] needs `samples * slotBytes` bytes. Returns the bytes written.
     */
    fun convert(
        src: ByteBuffer, encoding: Int, samples: Int,
        dst: ByteArray, dstOffset: Int, slotBytes: Int, bits: Int, volume: Float = 1f,
    ): Int {
        val buf = src.order(ByteOrder.LITTLE_ENDIAN)
        val down = 32 - bits                 // from 32-bit full scale to the DAC's depth
        val justify = slotBytes * 8 - bits   // then left-justified in its slot
        val gain = volume.toDouble()
        var o = dstOffset
        repeat(samples) {
            var v: Int = when (encoding) {
                PCM_FLOAT -> {
                    val scaled = Math.rint(buf.float.toDouble() * gain * TWO_31)
                    when {
                        scaled >= Int.MAX_VALUE -> Int.MAX_VALUE
                        scaled <= Int.MIN_VALUE -> Int.MIN_VALUE
                        else -> scaled.toInt()
                    }
                }
                PCM_16 -> buf.short.toInt() shl 16
                PCM_24 -> {
                    val b0 = buf.get().toInt() and 0xFF
                    val b1 = buf.get().toInt() and 0xFF
                    val b2 = buf.get().toInt()  // sign-carrying
                    ((b2 shl 16) or (b1 shl 8) or b0) shl 8
                }
                PCM_32 -> buf.int
                else -> 0
            }
            if (gain != 1.0 && encoding != PCM_FLOAT) v = Math.rint(v * gain).toInt()
            val out = (v shr down) shl justify
            for (b in 0 until slotBytes) dst[o++] = (out shr (8 * b)).toByte()
        }
        return o - dstOffset
    }

    private const val TWO_31 = 2147483648.0
}
