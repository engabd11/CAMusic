package com.engabd.sendpin.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.engabd.sendpin.MainActivity

/**
 * Notices a USB DAC connect and says what it can do, since Android routes to it
 * silently and the capability reading already built for the Settings picker
 * ([DeviceCapabilities.activeRoute]) is otherwise only seen by someone who thinks
 * to go looking for it.
 *
 * Deliberately does **not** change routing on its own — [AudioOutputs.resolve] +
 * `preferredAudioDeviceId` (set from the Settings picker) is what both players
 * pin to, and staying hands-off here is what keeps "plug in a DAC" from being a
 * surprise switch away from whatever the user already chose (a Bluetooth
 * headset, say). This only tells them the option exists.
 */
class UsbDacMonitor(private val context: Context) {

    private val am get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var known: Set<Int> = emptySet()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val newUsb = addedDevices.filter { it.type in USB_TYPES && it.id !in known }
            known = known + addedDevices.map { it.id }
            newUsb.forEach(::notifyConnected)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            known = known - removedDevices.map { it.id }.toSet()
        }
    }

    fun start() {
        createChannel()
        // Seed `known` with what's already attached at start — only a device that
        // arrives *after* this is watching is a "connect" worth telling anyone
        // about; one already plugged in when the app launches is not new.
        known = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrDefault(emptyArray()).map { it.id }.toSet()
        am.registerAudioDeviceCallback(callback, null)
    }

    fun stop() = am.unregisterAudioDeviceCallback(callback)

    private fun notifyConnected(device: AudioDeviceInfo) {
        val route = DeviceCapabilities.activeRoute(am, preferredId = device.id.toString())
        val capability = listOfNotNull(route?.sampleRateLabel, route?.bitDepthLabel)
            .joinToString(" / ").takeIf { it.isNotBlank() }
        val text = if (capability != null) "Up to $capability. Pin it in Settings > Audio." else "Pin it in Settings > Audio to use it."

        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.engabd.sendpin.R.drawable.ic_usb)
            .setContentTitle("${AudioOutputs.describe(device)} connected")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Audio device changes", NotificationManager.IMPORTANCE_LOW)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "usb_dac"
        private const val NOTIFICATION_ID = 4821

        private val USB_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
    }
}
