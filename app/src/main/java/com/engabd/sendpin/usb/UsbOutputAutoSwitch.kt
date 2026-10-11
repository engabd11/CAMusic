package com.engabd.sendpin.usb

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.engabd.sendpin.audio.OutputMode
import com.engabd.sendpin.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * USB bit-perfect when a USB DAC is plugged in, and the previous output mode back once it
 * has been gone a while (Settings › Output & signal path; [AppSettings.usbAutoSwitch]).
 *
 * The switch happens when Android lets CAMusic use the DAC, not merely when it appears:
 * [UsbAttachActivity] (Android starts it once the DAC is allowed, at once with "always")
 * and the attach broadcast for a DAC already allowed. A DAC the user declined leaves the
 * mode alone, rather than switching to a rung that would only fall back to Android.
 *
 * "Gone" means gone for [SETTLE_MS], the window [UsbDacReturn] gives a DAC that restarted
 * itself: a dropout must not rebuild the player twice for nothing.
 *
 * A mode chosen by hand wins ([userChose]). Chosen with the DAC plugged in, it holds
 * until the DAC is really unplugged, so a DAC that drops out and comes back does not
 * undo it; and only a mode this switched to is switched back. Both are kept on disk, so
 * they survive the process dying in between.
 */
class UsbOutputAutoSwitch(context: Context) {

    private val app = context.applicationContext
    private val settings = AppSettings(app)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The DAC has stayed gone: it was unplugged, not restarting. */
    private val settle = Runnable {
        if (UsbDacProbe.audioDevices(app).isNotEmpty()) return@Runnable
        val from = switchedFrom()
        prefs.edit().remove(KEY_FROM).remove(KEY_USER_CHOSE).apply()
        if (from != null && settings.bootOutputMode == OutputMode.USB_BITPERFECT) {
            Log.i(TAG, "the USB DAC has gone: back to ${from.title}")
            scope.launch { settings.setOutputMode(from) }
        }
    }

    /** CAMusic may use a USB DAC now: switch to USB bit-perfect if that is wanted. */
    fun onDacAllowed() {
        handler.removeCallbacks(settle)
        if (!settings.bootUsbAutoSwitch || prefs.getBoolean(KEY_USER_CHOSE, false)) return
        val mode = settings.bootOutputMode
        if (mode == OutputMode.USB_BITPERFECT) return
        Log.i(TAG, "a USB DAC is plugged in: ${mode.title} -> ${OutputMode.USB_BITPERFECT.title}")
        prefs.edit().putString(KEY_FROM, mode.name).apply()
        scope.launch { settings.setOutputMode(OutputMode.USB_BITPERFECT) }
    }

    /** A USB DAC went: settle up if it stays gone. */
    fun onDacDetached() {
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, SETTLE_MS)
    }

    private fun switchedFrom(): OutputMode? =
        prefs.getString(KEY_FROM, null)?.let { name -> OutputMode.entries.firstOrNull { it.name == name } }

    companion object {
        private const val TAG = "UsbOutputAutoSwitch"
        private const val PREFS = "usb_output_auto_switch"
        private const val KEY_FROM = "switched_from"
        private const val KEY_USER_CHOSE = "user_chose_while_attached"

        /** As long as [UsbDacReturn] waits for a DAC that dropped out to come back. */
        const val SETTLE_MS = 20_000L

        /**
         * The output mode was chosen by hand: nothing is switched back on unplug, and while
         * a USB DAC is attached its plugging back in switches nothing either.
         */
        fun userChose(context: Context) {
            val app = context.applicationContext
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_FROM)
                .putBoolean(KEY_USER_CHOSE, UsbDacProbe.audioDevices(app).isNotEmpty())
                .apply()
        }
    }
}
