package com.engabd.sendpin.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.engabd.sendpin.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide record of which stages have run, so a re-arm does not run them again.
 *
 * In memory on purpose: if the process dies mid-window the stages simply run again, and
 * every handler is safe to repeat.
 */
object WakeRuntime {
    private val done: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Set while an alarm is ringing, so a second trigger for the same occurrence is ignored. */
    @Volatile var firingAtMs: Long = 0L

    /** Why the lights are not on, in words for the notification; null when all is well. */
    @Volatile var lightsNote: String? = null

    /** The occurrence whose music has been started, so a later trigger does not restart it. */
    @Volatile var musicStartedFor: Long = 0L

    fun markDone(key: String) { done.add(key) }
    fun doneKeys(): Set<String> = done.toSet()

    /** Forget finished occurrences, keeping the set small. */
    fun forgetBefore(nowMs: Long) {
        done.removeAll { (it.substringBefore(':').toLongOrNull() ?: 0L) < nowMs - 24 * 3_600_000L }
    }
}

/** Re-arm from the stored list. Safe to call from anywhere, any number of times. */
suspend fun rearmWakeAlarms(context: Context): Armed? {
    val app = context.applicationContext
    val alarms = AppSettings(app).wakeAlarms.first()
    val now = System.currentTimeMillis()
    WakeRuntime.forgetBefore(now)
    return AlarmPlanner.rearm(AndroidAlarmPort(app), alarms, now, ZoneId.systemDefault(), WakeRuntime.doneKeys())
}

/**
 * Receives the alarm triggers and the events that invalidate them.
 *
 * Re-arming happens on boot, on a clock or zone change, and after an app update, because
 * AlarmManager forgets everything at reboot and on update, and a moved clock moves every
 * wall-clock alarm with it. Not exported: the system delivers these protected broadcasts
 * regardless, and our own triggers are explicit-component.
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STAGE, ACTION_DUE, ACTION_SNOOZE_DUE -> WakeService.start(context, intent)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    try {
                        rearmWakeAlarms(context)
                    } catch (e: Exception) {
                        Log.w(TAG, "re-arm failed: ${e.message}")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "WakeReceiver"
        const val ACTION_STAGE = "com.engabd.sendpin.wake.STAGE"
        const val ACTION_DUE = "com.engabd.sendpin.wake.DUE"
        const val ACTION_SNOOZE_DUE = "com.engabd.sendpin.wake.SNOOZE_DUE"
        const val EXTRA_ID = "alarm_id"
        const val EXTRA_AT = "alarm_at"
        const val EXTRA_STAGE = "stage"
    }
}
