package com.engabd.sendpin.usb

import android.app.Activity
import android.os.Bundle

/**
 * The target of the manifest's USB_DEVICE_ATTACHED filter for audio-class devices.
 *
 * Its only job is to exist: an app with a matching attach filter is what Android offers
 * to open for a USB device, and choosing "always" there is the one way to make the USB
 * permission stick across unplugging — so USB bit-perfect does not ask again every time
 * the DAC goes back in. It shows nothing and closes at once. Enabled only while USB
 * bit-perfect is selected; see [UsbDacWatcher.bitperfectSelected].
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
