package com.engabd.sendpin.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An output the equaliser can keep a curve for.
 *
 * [id] is stable across connections: a Bluetooth device by its address (by its name
 * when the address is withheld), a USB DAC by its product name, and one id each for
 * wired headphones, HDMI and the phone's own speaker.
 */
data class OutputKey(val id: String, val label: String)

/** Turning what Android reports about an output into an [OutputKey]. Pure. */
object OutputKeys {
    val SPEAKER = OutputKey("speaker", "Phone speaker")

    fun of(type: Int, address: String?, productName: String?): OutputKey {
        val name = productName?.trim()?.takeIf { it.isNotEmpty() }
        return when (kindOf(type)) {
            Kind.BLUETOOTH -> {
                // Without the Bluetooth permission Android hands back an empty or
                // all-zero address; the name is the next most stable thing.
                val addr = address?.trim()?.takeIf { it.isNotEmpty() && it.any { c -> c != '0' && c != ':' } }
                OutputKey("bt:" + (addr?.uppercase() ?: name ?: "unknown"), name ?: "Bluetooth audio")
            }
            Kind.USB -> OutputKey("usb:" + (name ?: "unknown"), name ?: "USB audio")
            Kind.WIRED -> OutputKey("wired", "Wired headphones")
            Kind.HDMI -> OutputKey("hdmi", "HDMI")
            Kind.SPEAKER -> SPEAKER
        }
    }

    /**
     * Which of [types] music would play from, for when Android cannot say directly
     * (before Android 13): a headset of any kind ahead of the speaker, Bluetooth
     * ahead of USB ahead of wired, the way Android itself routes media.
     */
    fun preferred(types: List<Int>): Int? = types.minByOrNull { kindOf(it).rank }

    private enum class Kind(val rank: Int) { BLUETOOTH(0), USB(1), WIRED(2), HDMI(3), SPEAKER(4) }

    // The newer type constants are compile-time ints, inlined: comparing against one
    // on an older Android simply never matches.
    @android.annotation.SuppressLint("InlinedApi")
    private fun kindOf(type: Int): Kind = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID -> Kind.BLUETOOTH
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_DOCK -> Kind.USB
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE -> Kind.WIRED
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> Kind.HDMI
        else -> Kind.SPEAKER
    }
}

/**
 * The output music is playing from right now, kept current as devices come and go.
 *
 * Process-wide, for the equaliser's "a curve for each output". Listening costs
 * nothing between connections: Android calls back only when a device is added or
 * removed.
 */
class OutputRouteWatcher(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val _current = MutableStateFlow(OutputKeys.SPEAKER)
    val current: StateFlow<OutputKey> = _current.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refresh()
    }
    private var started = false

    fun start() {
        if (started || audio == null) return
        started = true
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    private fun refresh() {
        val am = audio ?: return
        val device = runCatching { routed(am) }.getOrNull()
        _current.value = device?.let { OutputKeys.of(it.type, it.address, it.productName?.toString()) } ?: OutputKeys.SPEAKER
    }

    private fun routed(am: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val media = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            am.getAudioDevicesForAttributes(media).firstOrNull()?.let { return it }
        }
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
        val type = OutputKeys.preferred(outputs.map { it.type }) ?: return null
        return outputs.firstOrNull { it.type == type }
    }
}
