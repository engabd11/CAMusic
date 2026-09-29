package com.engabd.sendpin.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Reads a connected USB DAC's own description of itself — milestone 1 of the USB driver
 * (docs/plan/usb-bitperfect-driver.md).
 *
 * Read-only. Nothing here claims an interface or detaches the kernel's driver, so Android
 * keeps playing through the DAC while it is read. The one request sent to the device, a
 * UAC2 RANGE query for the clock's rates, is a GET that changes nothing; when the kernel's
 * hold on the interface refuses it, the report says so rather than guessing.
 */
object UsbDacProbe {

    private const val TAG = "UsbDacProbe"
    private const val ACTION_PERMISSION = "com.engabd.sendpin.USB_DAC_PERMISSION"

    // UAC2 class request: device-to-host, class, interface recipient.
    private const val REQ_TYPE_GET_INTERFACE = 0xA1
    private const val REQ_RANGE = 0x02
    private const val CS_SAM_FREQ_CONTROL = 0x01

    /**
     * A permission request whose answer nobody waits for — for asking on plug-in, where
     * the grant only has to be in place by the next track.
     */
    fun permissionIntent(context: Context): PendingIntent {
        val app = context.applicationContext
        return PendingIntent.getBroadcast(
            app, 1, Intent(ACTION_PERMISSION).setPackage(app.packageName), PendingIntent.FLAG_MUTABLE,
        )
    }

    /** Attached devices with an audio-class interface. */
    fun audioDevices(context: Context): List<UsbDevice> {
        val usb = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return usb.deviceList.values.filter { dev ->
            (0 until dev.interfaceCount).any { dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
        }
    }

    /**
     * Android's own USB permission dialog — the only one there is. Resolves to whether
     * the user allowed it; a device already allowed answers at once.
     */
    suspend fun requestPermission(context: Context, device: UsbDevice): Boolean {
        val usb = context.getSystemService(UsbManager::class.java) ?: return false
        if (usb.hasPermission(device)) return true
        val app = context.applicationContext
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    runCatching { app.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            // The system fills the result into this intent, so it has to be mutable; it is
            // explicit to this package, which is what makes a mutable PendingIntent safe.
            ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
            val pending = PendingIntent.getBroadcast(
                app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), PendingIntent.FLAG_MUTABLE,
            )
            cont.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }
            usb.requestPermission(device, pending)
        }
    }

    /** The report for every attached audio device, asking permission for each as needed. */
    suspend fun report(context: Context): String {
        val devices = audioDevices(context)
        if (devices.isEmpty()) return "No USB audio device is connected."
        val parts = devices.map { dev ->
            val name = listOfNotNull(dev.manufacturerName, dev.productName).joinToString(" ").ifBlank { dev.deviceName }
            if (!requestPermission(context, dev)) {
                UacReport.format(name, null, null, note = "USB permission was not granted, so the DAC could not be read.")
            } else {
                withContext(Dispatchers.IO) { read(context, dev, name) }
            }
        }
        return parts.joinToString("\n\n")
    }

    private fun read(context: Context, dev: UsbDevice, name: String): String {
        val usb = context.getSystemService(UsbManager::class.java)
        val conn = usb?.openDevice(dev)
            ?: return UacReport.format(name, null, null, note = "Android would not open the device.")
        try {
            val raw = conn.rawDescriptors
                ?: return UacReport.format(name, null, null, note = "The device returned no descriptors.")
            val parsed = UacDescriptors.parse(raw)
            val clockRates = parsed?.takeIf { it.uacVersion == 0x0200 && it.clockSources.isNotEmpty() }?.let { d ->
                val rates = mutableMapOf<Int, List<Int>>()
                for (clock in d.clockSources) {
                    val index = (clock.id shl 8) or d.controlInterface
                    val value = CS_SAM_FREQ_CONTROL shl 8
                    // wNumSubRanges first, then the whole reply at the size that implies.
                    val head = ByteArray(2)
                    val got = conn.controlTransfer(REQ_TYPE_GET_INTERFACE, REQ_RANGE, value, index, head, 2, 1000)
                    if (got < 2) continue
                    val n = (head[0].toInt() and 0xFF) or ((head[1].toInt() and 0xFF) shl 8)
                    val full = ByteArray(2 + 12 * n.coerceIn(0, 64))
                    val len = conn.controlTransfer(REQ_TYPE_GET_INTERFACE, REQ_RANGE, value, index, full, full.size, 1000)
                    if (len >= 2) rates[clock.id] = UacDescriptors.parseRateRanges(full, len)
                }
                rates.takeIf { it.isNotEmpty() }
            }
            return UacReport.format(name, parsed, raw, clockRates)
        } catch (e: Exception) {
            Log.w(TAG, "reading $name failed", e)
            return UacReport.format(name, null, null, note = "Reading the device failed: ${e.message}")
        } finally {
            conn.close()
        }
    }
}
