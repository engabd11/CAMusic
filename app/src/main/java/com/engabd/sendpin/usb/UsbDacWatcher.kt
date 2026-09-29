package com.engabd.sendpin.usb

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * USB DAC comings and goings, for the bit-perfect driver (milestone 4 of
 * docs/plan/usb-bitperfect-driver.md).
 *
 * - A DAC CAMusic holds being unplugged is announced on [lost], so the player pauses
 *   the way it would for unplugged headphones, instead of streaming into nothing.
 * - A DAC plugged in while USB bit-perfect is selected gets the USB permission asked
 *   for straight away, so the next track can take it.
 * - [recoverOrphans] hands a DAC back to Android when a previous run died holding it.
 */
class UsbDacWatcher(private val context: Context) {

    private val _lost = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** A DAC CAMusic was holding has gone. */
    val lost: SharedFlow<Unit> = _lost.asSharedFlow()

    /** Whether USB bit-perfect is the selected output; set by the app from the setting. */
    @Volatile
    var bitperfectSelected = false
        set(value) {
            field = value
            setAttachEntryEnabled(value)
        }

    private val usb = context.getSystemService(UsbManager::class.java)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            if (!device.isAudio()) return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val session = UsbAudioSession.current
                    if (session != null && session.deviceName == device.deviceName) {
                        Log.i(TAG, "${device.productName} unplugged while CAMusic held it")
                        session.markLost()
                        _lost.tryEmit(Unit)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (bitperfectSelected && usb?.hasPermission(device) == false) {
                        Log.i(TAG, "${device.productName} plugged in: asking for USB permission")
                        usb.requestPermission(device, UsbDacProbe.permissionIntent(context))
                    }
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // Exported, deliberately: both are protected broadcasts only the system can send,
        // and NOT_EXPORTED is not guaranteed to deliver system broadcasts on every build.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    /**
     * A previous run that died holding the DAC released it without giving it back to
     * the kernel's audio driver: the DAC stays attached but Android has no USB audio
     * output until it is unplugged. Seen that way at start-up, hand its audio interfaces
     * back. Nothing in this process can be holding it yet, so it is ours to return.
     */
    fun recoverOrphans() {
        val manager = usb ?: return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val androidHasUsbOutput = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
        if (androidHasUsbOutput || UsbAudioSession.current != null) return
        for (device in manager.deviceList.values.filter { it.isAudio() && manager.hasPermission(it) }) {
            val conn = manager.openDevice(device) ?: continue
            try {
                val fd = conn.fileDescriptor
                (0 until device.interfaceCount).map(device::getInterface)
                    .filter { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.alternateSetting == 0 }
                    .forEach { UsbAudioNative.nativeReattach(fd, it.id) }
                Log.i(TAG, "handed ${device.productName} back to Android after a previous run")
            } finally {
                conn.close()
            }
        }
    }

    /**
     * The manifest's USB-attach entry ([UsbAttachActivity]) is enabled only while USB
     * bit-perfect is selected. With it enabled, Android offers to open CAMusic for the DAC,
     * and "always" makes the USB permission stick; with it disabled, plugging a DAC in
     * never involves CAMusic.
     */
    private fun setAttachEntryEnabled(enabled: Boolean) {
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, UsbAttachActivity::class.java),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }.onFailure { Log.w(TAG, "could not switch the USB attach entry", it) }
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun UsbDevice.isAudio() =
        (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }

    private companion object {
        const val TAG = "UsbDacWatcher"
    }
}
