package com.engabd.sendpin.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin

/** JNI for the native usbfs streaming engine, `usb_audio.cpp`. */
internal object UsbAudioNative {
    init { System.loadLibrary("usb_audio") }

    external fun nativeSpeed(fd: Int): Int
    external fun nativeStart(fd: Int, endpoint: Int, maxPacket: Int, bytesPerFrame: Int, rate: Int, packetsPerSecond: Int): Long
    external fun nativeWrite(ptr: Long, pcm: ByteArray, offset: Int, length: Int): Int
    external fun nativeQueuedBytes(ptr: Long): Int
    external fun nativeStats(ptr: Long): LongArray
    external fun nativeStop(ptr: Long)
    external fun nativeReattach(fd: Int, ifno: Int): Boolean
}

/**
 * The rules the session follows, kept free of Android so they are tested, not trusted.
 */
object UsbAudioMath {

    /** `USBDEVFS_GET_SPEED` values (enum usb_device_speed). */
    const val SPEED_FULL = 2
    const val SPEED_HIGH = 3

    /**
     * Isochronous service opportunities per second for [endpoint]: one per 1 ms frame at
     * full speed; at high speed and above, 8000 microframes divided by 2^(bInterval-1).
     */
    fun packetsPerSecond(speed: Int, interval: Int): Int =
        if (speed >= SPEED_HIGH) 8000 shr (interval.coerceIn(1, 4) - 1) else 1000

    /** A UAC1 endpoint sampling-frequency value: 3 bytes, little-endian. */
    fun uac1Rate(hz: Int) = byteArrayOf((hz and 0xFF).toByte(), (hz shr 8 and 0xFF).toByte(), (hz shr 16 and 0xFF).toByte())

    fun readUac1Rate(b: ByteArray, len: Int): Int? =
        if (len < 3) null else (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or ((b[2].toInt() and 0xFF) shl 16)

    /** A UAC2 clock sampling-frequency value: 4 bytes, little-endian. */
    fun uac2Rate(hz: Int) = uac1Rate(hz) + byteArrayOf((hz shr 24 and 0xFF).toByte())

    fun readUac2Rate(b: ByteArray, len: Int): Int? =
        if (len < 4) null else readUac1Rate(b, 3)!! or ((b[3].toInt() and 0xFF) shl 24)

    /**
     * [frames] of a sine at [hz] and [dbfs], interleaved over [channels], as little-endian
     * integers in [slotBytes]-byte slots carrying [bits] of audio (left-justified, as UAC
     * Type I requires). [startFrame] keeps the phase continuous across chunks.
     */
    fun tone(
        frames: Int, startFrame: Long, rate: Int, channels: Int, slotBytes: Int, bits: Int,
        hz: Double = 1000.0, dbfs: Double = -20.0,
    ): ByteArray {
        val out = ByteArray(frames * channels * slotBytes)
        val amp = Math.pow(10.0, dbfs / 20.0) * ((1L shl (bits - 1)) - 1)
        val shift = slotBytes * 8 - bits
        var o = 0
        for (f in 0 until frames) {
            val v = (amp * sin(2 * PI * hz * (startFrame + f) / rate)).roundToLong() shl shift
            repeat(channels) {
                for (b in 0 until slotBytes) out[o++] = (v shr (8 * b)).toByte()
            }
        }
        return out
    }
}

/**
 * CAMusic holding a USB DAC: its interfaces claimed from Android's driver, one format set,
 * and a native stream feeding it. docs/plan/usb-bitperfect-driver.md, milestone 2.
 *
 * While a session is open Android cannot use the DAC; [close] hands it back.
 */
class UsbAudioSession private constructor(
    private val device: UsbDevice,
    private val conn: UsbDeviceConnection,
    val info: UacDevice,
    private val speed: Int,
) : AutoCloseable {

    private val claimed = mutableListOf<UsbInterface>()
    private var stream = 0L
    var confirmedRate: Int? = null
        private set
    var format: StreamingAlt? = null
        private set

    val speedName: String
        get() = when (speed) {
            1 -> "low speed"
            UsbAudioMath.SPEED_FULL -> "full speed"
            UsbAudioMath.SPEED_HIGH -> "high speed"
            4 -> "wireless"
            5, 6 -> "SuperSpeed"
            else -> "unknown speed"
        }

    private fun iface(number: Int, alt: Int): UsbInterface? =
        (0 until device.interfaceCount).map(device::getInterface).firstOrNull { it.id == number && it.alternateSetting == alt }

    /**
     * Claim the control and [alt]'s streaming interface from Android's driver, select
     * [alt], set [rate] and read it back. False with [error] set when any step fails.
     */
    fun configure(alt: StreamingAlt, rate: Int): Boolean {
        stopStream()
        confirmedRate = null
        format = null
        for (number in listOf(info.controlInterface, alt.interfaceNumber)) {
            if (claimed.any { it.id == number }) continue
            val i = iface(number, 0) ?: return fail("interface $number not found")
            if (!conn.claimInterface(i, true)) return fail("could not claim interface $number")
            claimed += i
        }
        val streaming = iface(alt.interfaceNumber, alt.alternateSetting)
            ?: return fail("alternate setting ${alt.alternateSetting} not found")
        if (!conn.setInterface(streaming)) return fail("could not select alternate setting ${alt.alternateSetting}")

        val ep = alt.endpoint ?: return fail("no playback endpoint")
        confirmedRate = if (info.uacVersion >= 0x0200) setUac2Rate(rate) else setUac1Rate(ep.address, rate)
        if (confirmedRate == null) return fail("the DAC did not accept $rate Hz")
        format = alt
        return true
    }

    private fun setUac1Rate(endpoint: Int, rate: Int): Int? {
        // SET_CUR / GET_CUR on the endpoint's sampling-frequency control.
        val set = conn.controlTransfer(0x22, 0x01, 0x0100, endpoint, UsbAudioMath.uac1Rate(rate), 3, 1000)
        if (set < 0) Log.w(TAG, "SET_CUR rate $rate failed on 0x%02x".format(endpoint))
        val back = ByteArray(3)
        val len = conn.controlTransfer(0xA2, 0x81, 0x0100, endpoint, back, 3, 1000)
        // Some UAC1 DACs have no readable rate; if the SET went through, take it at its word.
        return UsbAudioMath.readUac1Rate(back, len) ?: rate.takeIf { set >= 0 }
    }

    private fun setUac2Rate(rate: Int): Int? {
        val clock = info.clockSources.firstOrNull() ?: return null
        val index = (clock.id shl 8) or info.controlInterface
        val set = conn.controlTransfer(0x21, 0x01, 0x0100, index, UsbAudioMath.uac2Rate(rate), 4, 1000)
        if (set < 0) Log.w(TAG, "clock SET_CUR rate $rate failed")
        val back = ByteArray(4)
        val len = conn.controlTransfer(0xA1, 0x01, 0x0100, index, back, 4, 1000)
        return UsbAudioMath.readUac2Rate(back, len) ?: rate.takeIf { set >= 0 }
    }

    var error: String? = null
        private set

    private fun fail(why: String): Boolean {
        error = why
        Log.w(TAG, why)
        return false
    }

    /** Start the native stream for the configured format. */
    fun start(): Boolean {
        val alt = format ?: return fail("not configured")
        val rate = confirmedRate ?: return fail("no rate")
        val ep = alt.endpoint ?: return fail("no endpoint")
        stream = UsbAudioNative.nativeStart(
            conn.fileDescriptor, ep.address, ep.maxPacketBytes * ep.transactionsPerMicroframe,
            alt.channels * alt.subslotBytes, rate, UsbAudioMath.packetsPerSecond(speed, ep.interval),
        )
        return stream != 0L || fail("the native stream did not start")
    }

    /** Queue PCM already in the DAC's own format; returns the bytes taken. */
    fun write(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): Int =
        if (stream == 0L) 0 else UsbAudioNative.nativeWrite(stream, pcm, offset, length)

    fun queuedBytes(): Int = if (stream == 0L) 0 else UsbAudioNative.nativeQueuedBytes(stream)

    data class Stats(
        val urbsCompleted: Long, val packetErrors: Long, val silentFrames: Long,
        val framesSent: Long, val lastErrno: Long, val running: Boolean,
    )

    fun stats(): Stats? = if (stream == 0L) null else UsbAudioNative.nativeStats(stream).let {
        Stats(it[0], it[1], it[2], it[3], it[4], it[5] != 0L)
    }

    fun stopStream() {
        if (stream != 0L) {
            UsbAudioNative.nativeStop(stream)
            stream = 0L
        }
    }

    /** Stop, return the streaming interface to zero bandwidth, and give the DAC back to Android. */
    override fun close() {
        stopStream()
        format?.let { alt -> iface(alt.interfaceNumber, 0)?.let { conn.setInterface(it) } }
        val fd = conn.fileDescriptor
        for (i in claimed.reversed()) {
            conn.releaseInterface(i)
            if (!UsbAudioNative.nativeReattach(fd, i.id)) Log.w(TAG, "Android did not take interface ${i.id} back")
        }
        claimed.clear()
        conn.close()
    }

    companion object {
        private const val TAG = "UsbAudioSession"

        /** Open [device], which must already have USB permission. Null if it is not a USB audio device. */
        fun open(context: Context, device: UsbDevice): UsbAudioSession? {
            val conn = context.getSystemService(UsbManager::class.java)?.openDevice(device) ?: return null
            val info = conn.rawDescriptors?.let(UacDescriptors::parse)
            if (info == null) {
                conn.close()
                return null
            }
            return UsbAudioSession(device, conn, info, UsbAudioNative.nativeSpeed(conn.fileDescriptor))
        }
    }
}
