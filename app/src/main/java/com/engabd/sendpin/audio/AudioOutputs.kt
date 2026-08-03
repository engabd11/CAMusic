package com.engabd.sendpin.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * The audio output devices this phone can be pointed at, for the "play through
 * the DAC, not the speaker" case.
 *
 * Android routes to whatever it thinks is best, which for a USB DAC is usually
 * right — but "usually" is not what someone who bought a DAC wants, and the
 * system offers no way to pin it. Both players call `setPreferredDevice` with
 * whatever this resolves, so the choice survives track changes (the AudioTrack
 * is rebuilt per stream) and falls back to the system route when the device is
 * unplugged.
 */
object AudioOutputs {

    /** A device the user can pick, and the stable id we persist it under. */
    data class Output(val id: String, val label: String, val isUsb: Boolean)

    /**
     * Every output device currently attached, most interesting first: USB before
     * wired, wired before Bluetooth, the built-in speaker last. A phone lists the
     * same physical output under several types, so entries are de-duplicated by
     * label.
     */
    fun list(am: AudioManager): List<Output> =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in INTERESTING }
            .map { Output(it.id.toString(), label(it), it.type in USB_TYPES) }
            .distinctBy { it.label }
            .sortedBy { RANK.indexOf(it.isUsb) }

    /**
     * Resolve a persisted id back to a live device. Null when the id is blank (the
     * system default) or the device is gone — an unplugged DAC must not leave the
     * player pinned to something that no longer exists.
     */
    fun resolve(am: AudioManager, deviceId: String): AudioDeviceInfo? {
        if (deviceId.isBlank()) return null
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.id.toString() == deviceId }
    }

    private fun label(d: AudioDeviceInfo): String {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB DAC"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            else -> "Output"
        }
        // The product name is the DAC's own name for itself ("FiiO K3"), which is
        // far more use than the type when two are plugged in.
        val name = d.productName?.toString()?.trim().orEmpty()
        return if (name.isEmpty() || name.equals(kind, ignoreCase = true)) kind else "$kind · $name"
    }

    private val USB_TYPES = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )

    private val INTERESTING = USB_TYPES + setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    )

    /** USB first — that is the one someone goes looking for this setting to pick. */
    private val RANK = listOf(true, false)
}
