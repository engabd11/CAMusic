package com.engabd.sendpin.usb

import android.app.Activity
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import com.engabd.sendpin.SendpinApp

/**
 * The target of the manifest's USB_DEVICE_ATTACHED filter for audio-class devices.
 *
 * Mostly its job is to exist: an app with a matching attach filter is what Android offers
 * to open for a USB device, answering that dialog grants the USB permission, and choosing
 * "always" there is the one way to make the permission stick across unplugging — so USB
 * bit-perfect does not ask again every time the DAC goes back in. Android starts this
 * once the DAC is allowed, which is the moment a song paused by a dropout can carry on;
 * see [UsbDacWatcher.onAttachHandled]. It shows nothing and closes at once. Enabled only
 * while USB bit-perfect is selected; see [UsbDacWatcher.bitperfectSelected].
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        device?.let { runCatching { (application as SendpinApp).usbDacWatcher.onAttachHandled(it) } }
        finish()
    }
}
