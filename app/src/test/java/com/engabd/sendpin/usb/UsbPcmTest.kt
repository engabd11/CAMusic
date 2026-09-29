package com.engabd.sendpin.usb

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbPcmTest {

    private fun floats(vararg f: Float): ByteBuffer =
        ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { f.forEach { putFloat(it) }; flip() }

    private fun s16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)
    private fun s24(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i + 2].toInt() shl 16)

    @Test
    fun `every 16-bit value survives the float path into a 16-bit DAC`() {
        val n = 65536
        val src = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in -32768..32767) src.putFloat(v / 32768f)  // what the decoder hands over
        src.flip()
        val out = ByteArray(n * 2)
        UsbPcm.convert(src, UsbPcm.PCM_FLOAT, n, out, 0, slotBytes = 2, bits = 16)
        for ((k, v) in (-32768..32767).withIndex()) assertEquals("sample $v", v, s16(out, k * 2))
    }

    @Test
    fun `every 16-bit value into a 24-bit DAC is the same number with zeros below`() {
        val n = 65536
        val src = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in -32768..32767) src.putFloat(v / 32768f)
        src.flip()
        val out = ByteArray(n * 3)
        UsbPcm.convert(src, UsbPcm.PCM_FLOAT, n, out, 0, slotBytes = 3, bits = 24)
        for ((k, v) in (-32768..32767).withIndex()) assertEquals("sample $v", v shl 8, s24(out, k * 3))
    }

    @Test
    fun `24-bit values survive the float path, across the whole range`() {
        val values = (-8_388_608..8_388_607 step 997).toList() + listOf(-8_388_608, 8_388_607, -1, 0, 1)
        val src = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { src.putFloat(it / 8_388_608f) }
        src.flip()
        val out = ByteArray(values.size * 3)
        UsbPcm.convert(src, UsbPcm.PCM_FLOAT, values.size, out, 0, slotBytes = 3, bits = 24)
        values.forEachIndexed { k, v -> assertEquals("sample $v", v, s24(out, k * 3)) }
    }

    @Test
    fun `integer inputs pass straight through`() {
        val src16 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(-12345).putShort(32767).flip() as ByteBuffer
        val o16 = ByteArray(6)
        UsbPcm.convert(src16, UsbPcm.PCM_16, 2, o16, 0, 3, 24)
        assertEquals(-12345 shl 8, s24(o16, 0))
        assertEquals(32767 shl 8, s24(o16, 3))

        val src24 = ByteBuffer.wrap(byteArrayOf(0x01, 0x02, 0x83.toByte()))  // 0x830201, negative
        val o24 = ByteArray(3)
        UsbPcm.convert(src24, UsbPcm.PCM_24, 1, o24, 0, 3, 24)
        assertEquals(0x830201 - 0x1000000, s24(o24, 0))
    }

    @Test
    fun `left-justified in a 4-byte slot`() {
        val out = ByteArray(4)
        UsbPcm.convert(floats(0.5f), UsbPcm.PCM_FLOAT, 1, out, 0, slotBytes = 4, bits = 24)
        assertEquals(0, out[0].toInt())                      // padding byte
        assertEquals(0x400000, s24(out, 1))                   // 0.5 at 24 bits
    }

    @Test
    fun `full scale float clamps instead of wrapping`() {
        val out = ByteArray(6)
        UsbPcm.convert(floats(1.0f, -1.0f), UsbPcm.PCM_FLOAT, 2, out, 0, 3, 24)
        assertEquals(8_388_607, s24(out, 0))
        assertEquals(-8_388_608, s24(out, 3))
    }

    @Test
    fun `volume scales, and only then are samples changed`() {
        val out = ByteArray(2)
        UsbPcm.convert(floats(0.5f), UsbPcm.PCM_FLOAT, 1, out, 0, 2, 16, volume = 0.5f)
        assertEquals(8192, s16(out, 0))
    }
}
