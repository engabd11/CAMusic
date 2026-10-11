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
    external fun nativeSetPaused(ptr: Long, paused: Boolean)
    external fun nativeFlush(ptr: Long)
    external fun nativePlayedFrames(ptr: Long): Long
    external fun nativePendingFrames(ptr: Long): Long
    external fun nativeHalt(ptr: Long)
    external fun nativeRestart(ptr: Long)
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

    /**
     * Whether the rate a DAC reported back is the rate that was asked for. Within 0.1 %,
     * so a DAC that reports its real crystal rate (44 099 for 44 100) still counts; any
     * further off and the DAC is running a different rate, and samples written for the
     * asked one would play at the wrong speed and pitch.
     */
    fun rateMatches(wanted: Int, confirmed: Int?): Boolean =
        confirmed != null && kotlin.math.abs(confirmed - wanted) <= wanted / 1000

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

    /** The DAC's own name, for the signal path. */
    val dacName: String get() = listOfNotNull(device.manufacturerName, device.productName).joinToString(" ").ifBlank { "USB DAC" }

    /** The kernel's name for the device, to match against attach/detach broadcasts. */
    val deviceName: String get() = device.deviceName

    /** Set when the DAC was unplugged: nothing more will reach it. */
    @Volatile
    var lost = false
        private set

    fun markLost() { lost = true }

    /**
     * Whether the stream has died under us — the transfers stopped without being asked
     * to (an unplug the broadcast has not reported yet, or a kernel error).
     */
    val streamDied: Boolean
        get() = lost || (stream != 0L && !suspended && stats()?.running == false)
    private var stream = 0L
    private val streamLock = Any()

    private fun setStream(ptr: Long) = synchronized(streamLock) { stream = ptr }
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
        val keptVolume = readHardwareVolume()
        // Zero bandwidth first, then the format. Taken from Android mid-stream, a DAC can
        // already be on this alternate setting; selecting it again is then no change at
        // all to some firmware, and a Sennheiser BTD 700 left that way took the audio
        // and played none of it. Going through alt 0 makes every take a clean start.
        iface(alt.interfaceNumber, 0)?.let { conn.setInterface(it) }
        Thread.sleep(STREAM_RESTART_MS)
        if (!conn.setInterface(streaming)) return fail("could not select alternate setting ${alt.alternateSetting}")

        val ep = alt.endpoint ?: return fail("no playback endpoint")
        if (info.uacVersion >= 0x0200) {
            // The clock's own list, read now that the control interface is ours. A rate it
            // does not list is refused here, cleanly, rather than set and read back wrong.
            val offered = ratesFor(alt)
            if (offered.isNotEmpty() && rate !in offered) {
                return fail("the DAC's clock offers ${offered.joinToString { UacReport.khz(it) }} kHz, not ${UacReport.khz(rate)}")
            }
        }
        val reported = if (info.uacVersion >= 0x0200) setUac2Rate(alt, rate) else setUac1Rate(ep.address, rate)
        if (reported == null) return fail("the DAC did not accept $rate Hz")
        // Read back and *checked*. A rate SET the DAC quietly ignored used to be taken at
        // its read-back word and streamed anyway: a 192 kHz track clocked out at 96 kHz,
        // at half speed and an octave down.
        if (!UsbAudioMath.rateMatches(rate, reported)) {
            return fail("the DAC was asked for ${UacReport.khz(rate)} kHz and runs at ${UacReport.khz(reported)} kHz")
        }
        confirmedRate = reported
        format = alt
        // Claimed again: whatever the 30 s hand-back left, this session holds the DAC now.
        suspended = false
        // A new stream can reset the DAC's own volume (a BTD 700 re-negotiates its
        // Bluetooth codec on a rate change); put back what it was a moment ago.
        keptVolume?.let { writeHardwareVolume(it) }
        return true
    }

    /** The DAC's playback volume range in 1/256 dB (min, max, step), or null when it has no volume control. */
    fun hardwareVolumeRange(): Triple<Int, Int, Int>? {
        val unit = info.volumeUnits.firstOrNull { it.playback && it.masterVolume } ?: return null
        fun get(req: Int): Int? {
            val b = ByteArray(2)
            val n = conn.controlTransfer(0xA1, req, 0x0200, (unit.id shl 8) or info.controlInterface, b, 2, 1000)
            return if (n == 2) ((b[0].toInt() and 0xFF) or (b[1].toInt() shl 8)) else null
        }
        val min = get(0x82) ?: return null
        val max = get(0x83) ?: return null
        val res = (get(0x84) ?: 256).coerceAtLeast(1)
        return if (max > min) Triple(min, max, res) else null
    }

    /** Read the DAC's current playback volume, in 1/256 dB. */
    fun hardwareVolume(): Int? = readHardwareVolume()

    /** Set the DAC's playback volume, in 1/256 dB, and its mute. */
    fun setHardwareVolume(value: Int, mute: Boolean) {
        writeHardwareVolume(value)
        val unit = info.volumeUnits.firstOrNull { it.playback && it.mute } ?: return
        conn.controlTransfer(0x21, 0x01, 0x0100, (unit.id shl 8) or info.controlInterface, byteArrayOf(if (mute) 1 else 0), 1, 1000)
    }

    /** The playback feature unit's master volume, in 1/256 dB, or null if it has none or will not say. */
    private fun readHardwareVolume(): Int? {
        val unit = info.volumeUnits.firstOrNull { it.playback && it.masterVolume } ?: return null
        val b = ByteArray(2)
        val n = conn.controlTransfer(0xA1, 0x81, 0x0200, (unit.id shl 8) or info.controlInterface, b, 2, 1000)
        return if (n == 2) ((b[0].toInt() and 0xFF) or (b[1].toInt() shl 8)) else null
    }

    private fun writeHardwareVolume(value: Int) {
        val unit = info.volumeUnits.firstOrNull { it.playback && it.masterVolume } ?: return
        val b = byteArrayOf((value and 0xFF).toByte(), (value shr 8 and 0xFF).toByte())
        conn.controlTransfer(0x21, 0x01, 0x0200, (unit.id shl 8) or info.controlInterface, b, 2, 1000)
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

    /** The clock source [alt]'s stream runs from, following any selector to its current input. */
    private fun clockFor(alt: StreamingAlt): ClockSource? = info.clockSourceFor(alt.terminalLink) { selector ->
        // GET_CUR on the selector's control: one byte, the input pin it is on now.
        val b = ByteArray(1)
        val n = conn.controlTransfer(0xA1, 0x01, 0x0100, (selector shl 8) or info.controlInterface, b, 1, 1000)
        if (n == 1) (b[0].toInt() and 0xFF).takeIf { it > 0 } else null
    }

    private val clockRates = mutableMapOf<Int, List<Int>>()

    /**
     * The rates [alt] can run at. UAC1 lists them in the descriptors; UAC2 leaves them
     * out and answers a `RANGE` request on the clock instead, which is read once per
     * clock and kept. Empty when it is not known (the request was refused before the
     * interface was ours): [configure]'s read-back check is then what catches a miss.
     */
    fun ratesFor(alt: StreamingAlt): List<Int> {
        if (alt.sampleRates.isNotEmpty() || info.uacVersion < 0x0200) return alt.sampleRates
        val clock = clockFor(alt) ?: return emptyList()
        clockRates[clock.id]?.let { return it }
        val read = runCatching { UsbDacProbe.clockRates(conn, info, clock.id) }.getOrNull()
        if (!read.isNullOrEmpty()) clockRates[clock.id] = read
        return read.orEmpty()
    }

    /** [UacDevice.outputs], with each UAC2 alternate's rates filled in where the clock has said. */
    fun outputs(): List<StreamingAlt> = info.outputs.map { alt ->
        if (alt.sampleRates.isNotEmpty() || info.uacVersion < 0x0200) alt
        else clockFor(alt)?.let { clockRates[it.id] }?.let { alt.copy(sampleRates = it) } ?: alt
    }

    private fun setUac2Rate(alt: StreamingAlt, rate: Int): Int? {
        val clock = clockFor(alt) ?: return null
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
        fun launch() = UsbAudioNative.nativeStart(
            conn.fileDescriptor, ep.address, ep.maxPacketBytes * ep.transactionsPerMicroframe,
            alt.channels * alt.subslotBytes, rate, UsbAudioMath.packetsPerSecond(speed, ep.interval),
        )
        setStream(launch())
        if (stream == 0L) return fail("the native stream did not start")
        // Taken straight back from Android, a DAC can refuse the very first transfers
        // (the endpoint not there yet: ENOENT) - once, and then accept. Seen on a BTD 700
        // 30 s after a hand-back. Give it one clean retry before calling the DAC gone.
        if (refusedAtStart(0L)) {
            stopStream()
            reselect(alt, rate)
            setStream(launch())
            if (stream == 0L) return fail("the native stream did not start")
        }
        return true
    }

    /** True when a stream just started stopped again without a single transfer completing. */
    private fun refusedAtStart(completedBefore: Long): Boolean {
        Thread.sleep(40)
        val st = stats() ?: return false
        val refused = !st.running && st.urbsCompleted == completedBefore
        if (refused) Log.w(TAG, "first transfers refused (errno ${st.lastErrno}); selecting the format again")
        return refused
    }

    /** Zero bandwidth and back to [alt], and its rate again: the clean restart a refusing DAC needs. */
    private fun reselect(alt: StreamingAlt, rate: Int) {
        iface(alt.interfaceNumber, 0)?.let { conn.setInterface(it) }
        Thread.sleep(STREAM_RESTART_MS)
        iface(alt.interfaceNumber, alt.alternateSetting)?.let { conn.setInterface(it) }
        // Selecting the setting again can reset a UAC1 endpoint's rate.
        val ep = alt.endpoint ?: return
        if (info.uacVersion >= 0x0200) setUac2Rate(alt, rate) else setUac1Rate(ep.address, rate)
    }

    /** Queue PCM already in the DAC's own format; returns the bytes taken. */
    fun write(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): Int =
        if (stream == 0L) 0 else UsbAudioNative.nativeWrite(stream, pcm, offset, length)

    fun queuedBytes(): Int = if (stream == 0L) 0 else UsbAudioNative.nativeQueuedBytes(stream)

    data class Stats(
        val urbsCompleted: Long, val packetErrors: Long, val silentFrames: Long,
        val framesSent: Long, val lastErrno: Long, val running: Boolean,
    )

    /**
     * The native stream's counters. Safe from any thread (the signal-path page reads them
     * while the player's thread may be stopping the stream) because the stream is only
     * ever freed under the same lock.
     */
    fun stats(): Stats? = synchronized(streamLock) {
        if (stream == 0L) null else UsbAudioNative.nativeStats(stream).let {
            Stats(it[0], it[1], it[2], it[3], it[4], it[5] != 0L)
        }
    }

    fun setPaused(paused: Boolean) { if (stream != 0L) UsbAudioNative.nativeSetPaused(stream, paused) }

    /** Drop queued audio (a seek). */
    fun flush() { if (stream != 0L) UsbAudioNative.nativeFlush(stream) }

    /** Real frames the DAC has taken since the stream started. */
    fun playedFrames(): Long = if (stream == 0L) 0L else UsbAudioNative.nativePlayedFrames(stream)

    /** Real frames written and not yet played: queued plus on the wire. */
    fun pendingFrames(): Long = if (stream == 0L) 0L else UsbAudioNative.nativePendingFrames(stream)

    /** Whether the DAC is currently handed back to Android by [suspendForAndroid]. */
    var suspended = false
        private set

    /**
     * Give the DAC back to Android without losing the queued audio: stop the transfers,
     * return to zero bandwidth, release and re-attach. [resumeFromAndroid] takes it back.
     */
    fun suspendForAndroid() {
        if (suspended || stream == 0L) return
        UsbAudioNative.nativeHalt(stream)
        releaseAll()
        suspended = true
    }

    /** Take the DAC back from Android at the same format and carry on with the queue. */
    fun resumeFromAndroid(): Boolean {
        if (!suspended) return true
        val alt = format ?: return fail("not configured")
        val rate = confirmedRate ?: return fail("no rate")
        val keep = stream
        setStream(0L)  // configure() would otherwise stop and free it.
        val ok = configure(alt, rate)
        setStream(keep)
        if (!ok) return false
        val before = stats()?.urbsCompleted ?: 0L
        UsbAudioNative.nativeRestart(stream)
        suspended = false
        // The same refusal start() guards against, and the case it was written for: a
        // BTD 700 taken back thirty seconds after a hand-back. Restarting skipped the
        // check, so the stream died at once and playback fell back to Android as if the
        // DAC had been unplugged.
        if (refusedAtStart(before)) {
            reselect(alt, rate)
            UsbAudioNative.nativeRestart(stream)
            if (stats()?.running == false) return fail("the DAC refused the stream after being taken back")
        }
        return true
    }

    private fun releaseAll() {
        lastReleaseAtMs = android.os.SystemClock.elapsedRealtime()
        format?.let { alt -> iface(alt.interfaceNumber, 0)?.let { conn.setInterface(it) } }
        val fd = conn.fileDescriptor
        for (i in claimed.reversed()) {
            conn.releaseInterface(i)
            if (!UsbAudioNative.nativeReattach(fd, i.id)) Log.w(TAG, "Android did not take interface ${i.id} back")
        }
        claimed.clear()
    }

    fun stopStream() = synchronized(streamLock) {
        if (stream != 0L) {
            UsbAudioNative.nativeStop(stream)
            stream = 0L
        }
    }

    /** Stop, return the streaming interface to zero bandwidth, and give the DAC back to Android. */
    override fun close() {
        stopStream()
        // Whatever is claimed goes back, suspended or not. A take-back that failed half
        // way (interfaces claimed again, rate refused) left `suspended` set, this skipped
        // the release, and Android had no USB output until the DAC was replugged.
        if (claimed.isNotEmpty()) releaseAll()
        conn.close()
        synchronized(Companion) { if (current === this) current = null }
    }

    companion object {
        private const val TAG = "UsbAudioSession"
        private const val STREAM_RESTART_MS = 50L

        /**
         * The session holding the DAC, if any. Two connections claiming the same DAC —
         * the player's, paused, and the test tone's — was one of the ways sound went
         * missing; the tone test checks this and steps aside.
         */
        @Volatile
        var current: UsbAudioSession? = null
            private set

        /**
         * When CAMusic last handed a DAC back to Android. Android sees that as the DAC
         * arriving; the connect notice uses this to tell it from a real plug-in.
         */
        @Volatile
        var lastReleaseAtMs = 0L
            private set

        /** Open [device], which must already have USB permission. Null if it is not a USB audio device. */
        fun open(context: Context, device: UsbDevice): UsbAudioSession? {
            val conn = context.getSystemService(UsbManager::class.java)?.openDevice(device) ?: return null
            val info = conn.rawDescriptors?.let(UacDescriptors::parse)
            if (info == null) {
                conn.close()
                return null
            }
            return UsbAudioSession(device, conn, info, UsbAudioNative.nativeSpeed(conn.fileDescriptor))
                .also { synchronized(Companion) { current = it } }
        }
    }
}
