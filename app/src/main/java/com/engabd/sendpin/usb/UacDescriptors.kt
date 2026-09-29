package com.engabd.sendpin.usb

/**
 * A USB Audio Class device, read from its raw descriptors.
 *
 * The first piece of CAMusic's own USB audio driver (docs/plan/usb-bitperfect-driver.md).
 * Android's audio stack runs a USB DAC at whatever rate its policy picks — a fixed
 * 48 kHz / 16-bit on a Galaxy S23 — so the only route to a file's own samples is to drive
 * the device ourselves, and that starts with knowing, from the device itself, which
 * formats and rates it takes and how it wants to be clocked.
 *
 * Pure Kotlin on purpose: descriptors are bytes, the rules are the USB Audio Class 1.0
 * and 2.0 specs, and every rule here is checked by a unit test against real layouts.
 */
data class UacDevice(
    val vendorId: Int,
    val productId: Int,
    /** 0x0100 or 0x0200 — which USB Audio Class the AudioControl interface speaks. */
    val uacVersion: Int,
    val controlInterface: Int,
    val clockSources: List<ClockSource>,
    val volumeUnits: List<VolumeUnit>,
    val outputs: List<StreamingAlt>,
)

/** A UAC2 clock source: the entity a sample rate is set and read on. */
data class ClockSource(val id: Int, val type: String, val frequencyControl: String)

/** A feature unit with a volume control the DAC can apply itself. */
data class VolumeUnit(val id: Int, val masterVolume: Boolean, val channelVolume: Boolean, val mute: Boolean)

/** One playback alternate setting of an AudioStreaming interface. */
data class StreamingAlt(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val terminalLink: Int,
    val channels: Int,
    /** Bytes each sample occupies on the wire: 2, 3 or 4. */
    val subslotBytes: Int,
    /** Bits of real audio in each sample: 16, 24 or 32. */
    val bitResolution: Int,
    /** UAC1 lists its rates here; UAC2 leaves this empty and answers a RANGE request instead. */
    val sampleRates: List<Int>,
    /** UAC1 only: a continuous range, low to high, instead of a list. */
    val sampleRateRange: IntRange?,
    val endpoint: IsoEndpoint?,
    val feedback: IsoEndpoint?,
)

data class IsoEndpoint(
    val address: Int,
    /** "asynchronous", "adaptive", "synchronous" or "none". */
    val sync: String,
    /** "data", "feedback" or "implicit feedback". */
    val usage: String,
    val maxPacketBytes: Int,
    /** High-bandwidth transactions per microframe (1–3); 1 below high speed. */
    val transactionsPerMicroframe: Int,
    val interval: Int,
)

object UacDescriptors {

    private const val DT_DEVICE = 1
    private const val DT_INTERFACE = 4
    private const val DT_ENDPOINT = 5
    private const val CS_INTERFACE = 0x24

    private const val CLASS_AUDIO = 1
    private const val SUBCLASS_CONTROL = 1
    private const val SUBCLASS_STREAMING = 2

    // AudioControl interface descriptor subtypes.
    private const val AC_HEADER = 0x01
    private const val AC_FEATURE_UNIT = 0x06
    private const val AC2_CLOCK_SOURCE = 0x0A

    // AudioStreaming interface descriptor subtypes.
    private const val AS_GENERAL = 0x01
    private const val AS_FORMAT_TYPE = 0x02
    private const val FORMAT_TYPE_I = 0x01

    /**
     * Parse [raw], the bytes `UsbDeviceConnection.getRawDescriptors()` returns: the device
     * descriptor followed by the active configuration. Null when there is no audio
     * control interface — not a USB audio device.
     */
    fun parse(raw: ByteArray): UacDevice? {
        var vendor = 0
        var product = 0
        var uacVersion = 0
        var controlInterface = -1
        val clocks = mutableListOf<ClockSource>()
        val volumes = mutableListOf<VolumeUnit>()
        val outputs = mutableListOf<StreamingAlt>()

        // Where we are: the interface the descriptors that follow belong to.
        var ifNum = -1
        var ifAlt = 0
        var ifSub = -1
        var ifProtocol = 0
        var pending: PendingAlt? = null

        fun flush() {
            pending?.build()?.let(outputs::add)
            pending = null
        }

        var i = 0
        while (i + 1 < raw.size) {
            val len = raw.u8(i)
            if (len < 2 || i + len > raw.size) break
            val type = raw.u8(i + 1)
            when {
                type == DT_DEVICE && len >= 12 -> {
                    vendor = raw.u16(i + 8)
                    product = raw.u16(i + 10)
                }
                type == DT_INTERFACE && len >= 9 -> {
                    flush()
                    ifNum = raw.u8(i + 2)
                    ifAlt = raw.u8(i + 3)
                    val cls = raw.u8(i + 5)
                    ifSub = if (cls == CLASS_AUDIO) raw.u8(i + 6) else -1
                    ifProtocol = raw.u8(i + 7)
                    if (ifSub == SUBCLASS_CONTROL && controlInterface < 0) controlInterface = ifNum
                    // Alternate setting 0 of a streaming interface is the zero-bandwidth one.
                    if (ifSub == SUBCLASS_STREAMING && ifAlt > 0) pending = PendingAlt(ifNum, ifAlt)
                }
                type == CS_INTERFACE && ifSub == SUBCLASS_CONTROL && len >= 3 -> {
                    when (raw.u8(i + 2)) {
                        AC_HEADER -> if (len >= 5) uacVersion = raw.u16(i + 3)
                        AC2_CLOCK_SOURCE -> if (ifProtocol == 0x20 && len >= 8) {
                            clocks += ClockSource(
                                id = raw.u8(i + 3),
                                type = when (raw.u8(i + 4) and 0x03) {
                                    0 -> "external"
                                    1 -> "internal fixed"
                                    2 -> "internal variable"
                                    else -> "internal programmable"
                                },
                                frequencyControl = controlAccess(raw.u8(i + 5) and 0x03),
                            )
                        }
                        AC_FEATURE_UNIT -> featureUnit(raw, i, len, ifProtocol == 0x20)?.let(volumes::add)
                    }
                }
                type == CS_INTERFACE && ifSub == SUBCLASS_STREAMING && len >= 3 -> {
                    val p = pending
                    if (p != null) when (raw.u8(i + 2)) {
                        AS_GENERAL -> {
                            p.terminalLink = raw.u8(i + 3)
                            // UAC2 carries the channel count here; UAC1 in the format descriptor.
                            if (ifProtocol == 0x20 && len >= 11) p.channels = raw.u8(i + 10)
                        }
                        AS_FORMAT_TYPE -> if (raw.u8(i + 3) == FORMAT_TYPE_I) {
                            p.typeI = true
                            if (ifProtocol == 0x20) {
                                if (len >= 6) {
                                    p.subslot = raw.u8(i + 4)
                                    p.bits = raw.u8(i + 5)
                                }
                            } else if (len >= 8) {
                                p.channels = raw.u8(i + 4)
                                p.subslot = raw.u8(i + 5)
                                p.bits = raw.u8(i + 6)
                                val count = raw.u8(i + 7)
                                if (count == 0 && len >= 14) {
                                    p.range = raw.u24(i + 8)..raw.u24(i + 11)
                                } else {
                                    for (k in 0 until count) {
                                        val at = i + 8 + k * 3
                                        if (at + 3 <= i + len) p.rates += raw.u24(at)
                                    }
                                }
                            }
                        }
                    }
                }
                type == DT_ENDPOINT && len >= 7 -> {
                    val p = pending
                    val attrs = raw.u8(i + 3)
                    if (p != null && attrs and 0x03 == 1) {
                        val wMax = raw.u16(i + 4)
                        val ep = IsoEndpoint(
                            address = raw.u8(i + 2),
                            sync = when ((attrs shr 2) and 0x03) {
                                1 -> "asynchronous"
                                2 -> "adaptive"
                                3 -> "synchronous"
                                else -> "none"
                            },
                            usage = when ((attrs shr 4) and 0x03) {
                                1 -> "feedback"
                                2 -> "implicit feedback"
                                else -> "data"
                            },
                            maxPacketBytes = wMax and 0x7FF,
                            transactionsPerMicroframe = ((wMax shr 11) and 0x03) + 1,
                            interval = raw.u8(i + 6),
                        )
                        val isOut = ep.address and 0x80 == 0
                        if (isOut && ep.usage != "feedback") p.endpoint = ep
                        else if (!isOut && ep.usage == "feedback") p.feedback = ep
                    }
                }
            }
            i += len
        }
        flush()
        if (controlInterface < 0) return null
        return UacDevice(vendor, product, uacVersion, controlInterface, clocks, volumes, outputs)
    }

    /**
     * Parse the answer to a UAC2 `RANGE` request on a clock source's sampling-frequency
     * control: `wNumSubRanges`, then (min, max, res) as 32-bit values per sub-range.
     * A sub-range with res 0 or min == max is a single rate; otherwise the rates run from
     * min to max in steps of res.
     */
    fun parseRateRanges(reply: ByteArray, length: Int = reply.size): List<Int> {
        if (length < 2) return emptyList()
        val n = reply.u16(0)
        val rates = sortedSetOf<Int>()
        for (k in 0 until n) {
            val at = 2 + k * 12
            if (at + 12 > length) break
            val min = reply.u32(at)
            val max = reply.u32(at + 4)
            val res = reply.u32(at + 8)
            if (min <= 0) continue
            if (res <= 0 || max <= min) {
                rates += min
            } else {
                // A continuous range: list the standard audio rates it covers, not every step.
                STANDARD_RATES.filter { it in min..max && (it - min) % res == 0 }.forEach { rates += it }
            }
        }
        return rates.toList()
    }

    private val STANDARD_RATES = listOf(
        44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000, 705_600, 768_000,
    )

    private fun featureUnit(raw: ByteArray, i: Int, len: Int, uac2: Boolean): VolumeUnit? {
        val id = raw.u8(i + 3)
        return if (uac2) {
            // bmaControls: 4 bytes per channel, master first. Mute is bits 0-1, volume 2-3.
            if (len < 10) return null
            val master = raw.u32(i + 5)
            val channels = (len - 6) / 4
            val chVolume = (1 until channels).any { ch -> (raw.u32(i + 5 + ch * 4) shr 2) and 0x03 != 0 }
            val masterVolume = (master shr 2) and 0x03 != 0
            val mute = master and 0x03 != 0
            if (!masterVolume && !chVolume && !mute) null else VolumeUnit(id, masterVolume, chVolume, mute)
        } else {
            // bControlSize bytes per channel, master first. Mute is bit 0, volume bit 1.
            if (len < 7) return null
            val size = raw.u8(i + 5)
            if (size < 1) return null
            val master = raw.u8(i + 6)
            val channels = (len - 7) / size
            val chVolume = (1 until channels).any { ch -> raw.u8(i + 6 + ch * size) and 0x02 != 0 }
            val masterVolume = master and 0x02 != 0
            val mute = master and 0x01 != 0
            if (!masterVolume && !chVolume && !mute) null else VolumeUnit(id, masterVolume, chVolume, mute)
        }
    }

    private fun controlAccess(bits: Int) = when (bits) {
        1 -> "read only"
        3 -> "read/write"
        else -> "none"
    }

    private class PendingAlt(val ifNum: Int, val alt: Int) {
        var terminalLink = 0
        var channels = 0
        var subslot = 0
        var bits = 0
        var typeI = false
        val rates = mutableListOf<Int>()
        var range: IntRange? = null
        var endpoint: IsoEndpoint? = null
        var feedback: IsoEndpoint? = null

        /** A playback alternate setting: Type I PCM with an isochronous OUT endpoint. */
        fun build(): StreamingAlt? {
            if (!typeI || endpoint == null) return null
            return StreamingAlt(ifNum, alt, terminalLink, channels, subslot, bits, rates.toList(), range, endpoint, feedback)
        }
    }

    private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF
    private fun ByteArray.u16(at: Int) = u8(at) or (u8(at + 1) shl 8)
    private fun ByteArray.u24(at: Int) = u16(at) or (u8(at + 2) shl 16)
    private fun ByteArray.u32(at: Int) = u24(at) or (u8(at + 3) shl 24)
}
