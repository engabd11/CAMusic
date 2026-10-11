package com.engabd.sendpin.usb

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The media session's view of the player, with the volume keys pointed at the DAC.
 *
 * While USB bit-perfect holds the DAC, Android is not playing through it, so the volume
 * keys and the system volume panel would move a media volume nobody can hear. Reported
 * as *remote* playback with its own volume — which is what it is, as far as Android's
 * audio is concerned — the session gets the keys, and they drive [UsbVolume]: the DAC's
 * own hardware volume, with the samples left alone. Everything else passes straight
 * through to the wrapped player, and with USB bit-perfect off this is the wrapped
 * player exactly.
 */
@OptIn(UnstableApi::class)
class UsbVolumePlayer(player: Player) : ForwardingPlayer(player) {

    private val listeners = CopyOnWriteArraySet<Player.Listener>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val main = Handler(Looper.getMainLooper())

    init {
        scope.launch {
            var lastRemote = false
            UsbVolume.state.collect { info ->
                val remote = info != null
                main.post {
                    if (remote != lastRemote) listeners.forEach { it.onDeviceInfoChanged(deviceInfo) }
                    listeners.forEach { it.onDeviceVolumeChanged(deviceVolume, isDeviceMuted) }
                    lastRemote = remote
                }
            }
        }
    }

    /** Stop following [UsbVolume]; the session that used this is gone. */
    fun detach() = scope.cancel()

    private val usb get() = UsbVolume.state.value

    override fun addListener(listener: Player.Listener) {
        listeners += listener
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners -= listener
        super.removeListener(listener)
    }

    override fun getDeviceInfo(): DeviceInfo {
        val info = usb ?: return super.getDeviceInfo()
        return DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(0)
            .setMaxVolume(info.steps)
            .build()
    }

    override fun getDeviceVolume(): Int {
        val info = usb ?: return super.getDeviceVolume()
        return Math.round(info.level * info.steps)
    }

    override fun isDeviceMuted(): Boolean {
        val info = usb ?: return super.isDeviceMuted()
        return info.level <= 0f
    }

    override fun setDeviceVolume(volume: Int, flags: Int) {
        val info = usb ?: return super.setDeviceVolume(volume, flags)
        UsbVolume.set(volume.toFloat() / info.steps.coerceAtLeast(1))
    }

    @Deprecated("Deprecated in media3")
    override fun setDeviceVolume(volume: Int) = setDeviceVolume(volume, 0)

    override fun increaseDeviceVolume(flags: Int) {
        if (usb == null) return super.increaseDeviceVolume(flags)
        UsbVolume.step(up = true)
    }

    @Deprecated("Deprecated in media3")
    override fun increaseDeviceVolume() = increaseDeviceVolume(0)

    override fun decreaseDeviceVolume(flags: Int) {
        if (usb == null) return super.decreaseDeviceVolume(flags)
        UsbVolume.step(up = false)
    }

    @Deprecated("Deprecated in media3")
    override fun decreaseDeviceVolume() = decreaseDeviceVolume(0)

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        if (usb == null) return super.setDeviceMuted(muted, flags)
        if (muted) UsbVolume.set(0f) else UsbVolume.unmute()
    }

    @Deprecated("Deprecated in media3")
    override fun setDeviceMuted(muted: Boolean) = setDeviceMuted(muted, 0)

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        if (usb == null) return base
        return base.buildUpon()
            .addAll(
                Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            )
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        Player.COMMAND_GET_DEVICE_VOLUME,
        Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
        Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        -> usb != null || super.isCommandAvailable(command)
        else -> super.isCommandAvailable(command)
    }
}
