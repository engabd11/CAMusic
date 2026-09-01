package com.engabd.sendpin.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Calendar

/**
 * Schedules and cancels sunrise alarms through [AlarmManager.setAlarmClock].
 *
 * `setAlarmClock` rather than `setExact` or `setAlarmAndAllowWhileIdle` because a sunrise
 * alarm is a *user-visible* alarm — it appears in the system's "next alarm" slot, Doze
 * defers around it, and the clock app's own alarm tile picks it up. A fire-and-forget exact
 * alarm would get none of that, and a Doze-while-idle alarm would fire late when the phone
 * has been sitting on the nightstand for hours.
 *
 * The alarm payload — including the ramp duration, brightness ceiling and whether to
 * play music — is serialised into the intent's extra so the receiver can reconstruct it
 * without a DataStore read at fire time. The DataStore may not be warm at 6 AM and a
 * blocking read on the receiver thread is the kind of thing that makes an alarm arrive
 * late.
 */
class AlarmScheduler(private val context: Context) {

    /**
     * Persisted alarm description, carried in the PendingIntent's extras so the receiver
     * has everything it needs without touching DataStore.
     *
     * `Serializable` via kotlinx.serialization: the intent extra is a JSON string, not a
     * Java-serialised blob — the latter would tie the alarm to the exact class layout
     * at the time it was set, which is fragile across app updates.
     */
    @Serializable
    data class AlarmSpec(
        /** Minutes from now until the alarm fires. */
        val rampMinutes: Int = SunriseAlarm.DEFAULT_RAMP_MINUTES,
        /** User's brightness ceiling, 1..100. */
        val brightnessCeiling: Int = 70,
        /** Whether to start music at the end of the ramp. */
        val playMusic: Boolean = false,
    )

    companion object {
        private const val TAG = "AlarmScheduler"
        private const val EXTRA_SPEC = "alarm_spec_json"
        private const val REQUEST_CODE = 10_013

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * The action the [AlarmReceiver] listens for.
         *
         * Not a system-defined action, so it never collides with another app's receiver.
         */
        const val ACTION_SUNRISE = "com.engabd.sendpin.SUNRISE_ALARM"
    }

    /**
     * Schedule a sunrise alarm for [hour]:[minute] today (or tomorrow if that time has
     * already passed).
     */
    fun schedule(hour: Int, minute: Int, spec: AlarmSpec = AlarmSpec()) {
        val triggerAt = nextOccurrence(hour, minute)
        scheduleAt(triggerAt, spec)
    }

    /**
     * Schedule a sunrise alarm at an absolute epoch-millis time.
     *
     * Exposed so a caller can set an alarm for a specific day, not just "next 7:30".
     */
    fun scheduleAt(triggerAtMillis: Long, spec: AlarmSpec = AlarmSpec()) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_SUNRISE
            putExtra(EXTRA_SPEC, json.encodeToString(AlarmSpec.serializer(), spec))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // setAlarmClock is the one that survives Doze and shows in the system alarm slot.
        // The info that appears on the lock screen — a label, not a full UI — is built
        // from the show intent so tapping it opens the app.
        val info = AlarmManager.AlarmClockInfo(triggerAtMillis, pi)
        am.setAlarmClock(info, pi)
        Log.i(TAG, "sunrise alarm set for ${java.text.SimpleDateFormat.getDateTimeInstance().format(java.util.Date(triggerAtMillis))}")
    }

    /** Cancel the pending sunrise alarm, if any. */
    fun cancel() {
        val intent = Intent(context, AlarmReceiver::class.java).apply { action = ACTION_SUNRISE }
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        Log.i(TAG, "sunrise alarm cancelled")
    }

    /** Reconstruct the [AlarmSpec] from a received intent, or null if absent/unparseable. */
    fun specFromIntent(intent: Intent): AlarmSpec? =
        intent.getStringExtra(EXTRA_SPEC)
            ?.let { runCatching { json.decodeFromString(AlarmSpec.serializer(), it) }.getOrNull() }

    private fun nextOccurrence(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // If the time has already passed today, it is tomorrow's alarm.
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}