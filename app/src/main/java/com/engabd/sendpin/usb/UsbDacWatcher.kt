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
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * USB DAC comings and goings, for the bit-perfect driver (milestone 4 of
 * docs/plan/usb-bitperfect-driver.md).
 *
 * - A DAC CAMusic holds going away is announced on [lost], so the player pauses the way
 *   it would for unplugged headphones, instead of carrying on out of the phone.
 * - The same DAC back within seconds (a DAC that restarted itself; see [UsbDacReturn])
 *   is announced on [returned] once CAMusic may use it, so the song carries on through
 *   it without anyone pressing play.
 * - [recoverOrphans] hands a DAC back to Android when a previous run died holding it.
 *
 * Permission on plug-in is Android's own business here: with USB bit-perfect selected,
 * [UsbAttachActivity]'s filter makes Android ask "Open CAMusic for this DAC?", and
 * answering it grants the permission. This used to request permission as well, which
 * put a second dialog on top of that one at every plug-in.
 */
class UsbDacWatcher(private val context: Context) {

    /** A DAC CAMusic was holding has gone. */
    val lost: SharedFlow<Unit> = lostSignal.asSharedFlow()

    private val _returned = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The DAC lost mid-song is back and allowed: take it again and carry on. */
    val returned: SharedFlow<Unit> = _returned.asSharedFlow()

    /** Whether USB bit-perfect is the selected output; set by the app from the setting. */
    @Volatile
    var bitperfectSelected = false
        set(value) {
            field = value
            setAttachEntryEnabled(value)
            if (!value) dropouts.cancel()
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
                        reportLost(device)
                    }
                    // Not held any more is no proof it was not playing: a stream that
                    // died a moment before this broadcast already failed over and
                    // closed its session. UsbBitperfectOutput reports that loss itself.
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (!bitperfectSelected) return
                    val permitted = usb?.hasPermission(device) == true
                    if (dropouts.attached(keyOf(device), SystemClock.elapsedRealtime(), permitted)) {
                        Log.i(TAG, "${device.productName} is back: carrying on")
                        _returned.tryEmit(Unit)
                    } else if (!permitted) {
                        Log.i(TAG, "${device.productName} plugged in: Android asks whether CAMusic may use it")
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

    /** The player paused for [lost]: carry on if the DAC comes straight back. */
    fun awaitReturn() = dropouts.awaitReturn(SystemClock.elapsedRealtime())

    /** Playback was started some other way meanwhile: do not start it again. */
    fun cancelReturn() = dropouts.cancel()

    /**
     * Android opened [UsbAttachActivity] for [device]: the user allowed CAMusic in
     * Android's dialog, or chose "always" before. Either way it is allowed now.
     */
    fun onAttachHandled(device: UsbDevice) {
        if (!bitperfectSelected || usb?.hasPermission(device) != true) return
        if (dropouts.permitted(keyOf(device), SystemClock.elapsedRealtime())) {
            Log.i(TAG, "${device.productName} allowed again: carrying on")
            _returned.tryEmit(Unit)
        }
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

    companion object {
        private const val TAG = "UsbDacWatcher"

        private val lostSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        private val dropouts = UsbDacReturn()

        /**
         * What identifies a DAC across a reconnection. Not its device name, which is
         * new each time it enumerates, and not its serial number, which Android hides
         * until permission is granted.
         */
        fun keyOf(device: UsbDevice): String = "%04x:%04x".format(device.vendorId, device.productId)

        /**
         * The DAC CAMusic was playing through has gone. From the detach broadcast, or
         * from [UsbBitperfectOutput] when the stream died first, which it can by a
         * hundred milliseconds or so; either way the player pauses.
         */
        fun reportLost(device: UsbDevice) {
            dropouts.lost(keyOf(device), SystemClock.elapsedRealtime())
            lostSignal.tryEmit(Unit)
        }
    }
}
