package com.engabd.sendpin.audio

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * What this phone's output chain can actually do, right now.
 *
 * The quality badge answers "what is coming down the pipe". This answers the other
 * half, which the app has never been able to say: **what the far end can take**. A
 * phone plays 44.1/16 through its own speaker and 96/24 through a USB DAC, and the
 * same 96 kHz file is a different listening experience depending on which is plugged
 * in — but nothing on screen ever said so.
 *
 * Everything here is *asked*, not assumed. `AudioDeviceInfo` reports the rates,
 * encodings and channel masks the platform will accept for a device, and
 * `AudioTrack.getNativeOutputSampleRate` reports what the mixer is actually running
 * at. Where the platform declines to answer we say nothing rather than guessing —
 * a spec sheet that invents a number is worse than one with a gap in it.
 *
 * **On Bluetooth codecs.** Which codec an A2DP link negotiated (SBC, AAC, aptX, LDAC)
 * is not readable by an ordinary app: `BluetoothA2dp.getCodecStatus` is a system API
 * behind `BLUETOOTH_PRIVILEGED`. So this reports the device and its PCM capabilities
 * and says plainly that the codec is not ours to know — see [Route.bluetoothCodecNote].
 */
object DeviceCapabilities {

    /** The output this phone is playing through, as far as the platform will say. */
    data class Route(
        /** "USB DAC · FiiO K3", "Bluetooth · WH-1000XM5", "Phone speaker". */
        val label: String,
        val type: Int,
        /** Rates the platform will open a track at, ascending. Empty when unreported. */
        val sampleRates: List<Int>,
        /** `AudioFormat.ENCODING_*` values the device accepts. */
        val encodings: List<Int>,
        /** Channel counts the device accepts, ascending. */
        val channelCounts: List<Int>,
        val isBluetooth: Boolean,
        val isUsb: Boolean,
        /** The user pinned this output in Settings, rather than Android choosing it. */
        val pinned: Boolean,
    ) {
        /** `44.1 / 48 / 96 / 192 kHz`, or null when the device didn't say. */
        val sampleRateLabel: String?
            get() = sampleRates.takeIf { it.isNotEmpty() }
                ?.joinToString(" / ") { StreamQuality.khz(it) }
                ?.let { "$it kHz" }

        /**
         * `16-bit, 24-bit, 32-bit float`, or null when the device didn't say.
         *
         * Only the PCM encodings are named. A device also advertising a passthrough
         * format (AC3, DTS) is describing something this app never sends, and listing
         * it here would read as a claim about music playback.
         */
        val bitDepthLabel: String?
            get() = encodings.mapNotNull { depthName(it) }.distinct()
                .takeIf { it.isNotEmpty() }?.joinToString(", ")

        /** "Stereo", "Up to 5.1", or null. */
        val channelLabel: String?
            get() = channelCounts.maxOrNull()?.let { max ->
                when {
                    max <= 1 -> "Mono"
                    max == 2 -> "Stereo"
                    max == 6 -> "Up to 5.1"
                    max == 8 -> "Up to 7.1"
                    else -> "Up to $max channels"
                }
            }

        /** The honest note about what Android will not tell us. Null off Bluetooth. */
        val bluetoothCodecNote: String?
            get() = if (!isBluetooth) null else
                "Android doesn't tell apps which Bluetooth codec is in use (SBC, AAC, " +
                    "aptX, LDAC). Developer options → Bluetooth audio codec shows it."
    }

    /**
     * The output that is actually carrying audio.
     *
     * [preferredId] wins when it names a device that is still attached, because that
     * is the one both players pin with `setPreferredDevice`. Otherwise this is Android's
     * own choice, and Android does not expose which device that is — so the same
     * priority the routing policy uses is applied: USB, then wired, then Bluetooth,
     * then the built-in speaker. It is a very good guess and it is labelled as one
     * (see [Route.pinned]) rather than presented as a reading.
     */
    fun activeRoute(am: AudioManager, preferredId: String = ""): Route? {
        val outputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull()?.filter { it.type in INTERESTING } ?: return null
        if (outputs.isEmpty()) return null

        val pinned = preferredId.takeIf { it.isNotBlank() }
            ?.let { id -> outputs.firstOrNull { it.id.toString() == id } }
        val device = pinned ?: outputs.minByOrNull { ROUTE_ORDER.indexOf(it.type).takeIf { i -> i >= 0 } ?: ROUTE_ORDER.size }
            ?: return null

        return Route(
            label = AudioOutputs.describe(device),
            type = device.type,
            sampleRates = device.sampleRates.toList().filter { it > 0 }.distinct().sorted(),
            encodings = device.encodings.toList().distinct(),
            channelCounts = device.channelCounts.toList().filter { it > 0 }.distinct().sorted(),
            isBluetooth = device.type in BLUETOOTH_TYPES,
            isUsb = device.type in USB_TYPES,
            pinned = pinned != null,
        )
    }

    /**
     * The rate the Android mixer is running at, which is what everything is resampled
     * to on its way out. 0 when the platform won't say.
     */
    fun mixerRateHz(): Int = runCatching {
        AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
    }.getOrDefault(0)

    /** The mixer's buffer size in frames — the floor under output latency. Null if unknown. */
    fun framesPerBuffer(am: AudioManager): Int? =
        am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.takeIf { it > 0 }

    /** How a listener names a PCM encoding, or null for one that isn't music. */
    private fun depthName(encoding: Int): String? = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> "8-bit"
        AudioFormat.ENCODING_PCM_16BIT -> "16-bit"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit"
        AudioFormat.ENCODING_PCM_32BIT -> "32-bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "32-bit float"
        else -> null
    }

    private val USB_TYPES = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )

    private val BLUETOOTH_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    )

    /** Android's own routing priority, most specific first. */
    private val ROUTE_ORDER = listOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    )

    private val INTERESTING = ROUTE_ORDER.toSet()
}
