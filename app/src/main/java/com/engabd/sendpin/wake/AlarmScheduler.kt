package com.engabd.sendpin.wake

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.engabd.sendpin.service.OpenAppRequest
import com.engabd.sendpin.service.openAppIntent
import java.time.ZoneId

/** What the planner needs from the platform, so re-arming can be tested without a clock service. */
interface AlarmPort {
    /** The user-visible alarm the system clock UI shows; fires [WakeReceiver.ACTION_DUE]. */
    fun setAlarmClock(atMs: Long, alarmId: String)

    /** The next stage that needs the phone awake before the alarm; fires [WakeReceiver.ACTION_STAGE]. */
    fun setStage(atMs: Long, alarmId: String, alarmAtMs: Long, stage: WakeStage)

    /** A snooze ending; fires [WakeReceiver.ACTION_SNOOZE_DUE]. */
    fun setSnooze(atMs: Long, alarmId: String, alarmAtMs: Long)

    fun cancelSchedule()
    fun cancelSnooze()
}

/** The next alarm that was armed, for the UI's summary. */
data class Armed(val alarm: WakeAlarm, val alarmAtMs: Long, val nextStage: StageAt?)

object AlarmPlanner {
    /**
     * Arm the single next alarm and cancel anything left over.
     *
     * Only one alarm is ever armed at a time: whichever sounds first, plus the one stage of
     * it that is next. Everything that can move it (an edit, a boot, a clock or zone change,
     * an app update, a stage that just ran) calls this again, so the answer is recomputed
     * from the stored list and never carried over.
     *
     * A stage whose moment has passed, but whose alarm has not, is run as soon as possible
     * ([MIN_LEAD_MS] from now) unless [done] says it already ran. That is how an alarm set
     * 10 minutes ahead of a 30 minute ramp, or a reboot half way through one, picks the
     * sunrise up where it should be rather than skipping it. Stage handlers are safe to
     * run twice, so a lost [done] set only repeats work.
     */
    fun rearm(
        port: AlarmPort,
        alarms: List<WakeAlarm>,
        nowMs: Long,
        zone: ZoneId,
        done: Set<String> = emptySet(),
    ): Armed? {
        port.cancelSchedule()
        val (alarm, at) = AlarmMath.next(alarms, nowMs, zone) ?: return null
        port.setAlarmClock(at, alarm.id)
        val todo = AlarmMath.stages(alarm, at).filter { it.key(at) !in done }
        val next = todo.firstOrNull { it.atMs <= nowMs }?.copy(atMs = nowMs + MIN_LEAD_MS)
            ?: todo.firstOrNull()
        next?.let { port.setStage(it.atMs, alarm.id, at, it.stage) }
        return Armed(alarm, at, next)
    }

    const val MIN_LEAD_MS = 2_000L
}
/** [AlarmPort] over [AlarmManager]. All three triggers are exact and wake an idle device. */
class AndroidAlarmPort(private val context: Context) : AlarmPort {
    private val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(
        code: Int, action: String, alarmId: String, alarmAtMs: Long, stage: WakeStage? = null,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, WakeReceiver::class.java).setAction(action)
                .putExtra(WakeReceiver.EXTRA_ID, alarmId)
                .putExtra(WakeReceiver.EXTRA_AT, alarmAtMs)
                .putExtra(WakeReceiver.EXTRA_STAGE, stage?.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override fun setAlarmClock(atMs: Long, alarmId: String) {
        // setAlarmClock needs no exact-alarm grant: the platform treats it as a real alarm.
        // It is also what makes the next alarm appear in the system clock and lock screen.
        runCatching {
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(atMs, openAppIntent(context, OpenAppRequest.WAKE)),
                pending(CODE_DUE, WakeReceiver.ACTION_DUE, alarmId, atMs),
            )
        }.onFailure { Log.w(TAG, "setAlarmClock failed: ${it.message}") }
    }

    override fun setStage(atMs: Long, alarmId: String, alarmAtMs: Long, stage: WakeStage) =
        setExact(atMs, pending(CODE_STAGE, WakeReceiver.ACTION_STAGE, alarmId, alarmAtMs, stage))

    override fun setSnooze(atMs: Long, alarmId: String, alarmAtMs: Long) {
        runCatching {
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(atMs, openAppIntent(context, OpenAppRequest.WAKE)),
                pending(CODE_SNOOZE, WakeReceiver.ACTION_SNOOZE_DUE, alarmId, alarmAtMs),
            )
        }.onFailure { Log.w(TAG, "snooze failed: ${it.message}") }
    }

    private fun setExact(atMs: Long, pi: PendingIntent) {
        runCatching {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            } else {
                // Not granted (API 31 and 32 only, if the user withdrew it): inexact still
                // fires in Doze, a little late, and the ramp joins part way.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
        }.onFailure { Log.w(TAG, "stage alarm failed: ${it.message}") }
    }

    override fun cancelSchedule() {
        am.cancel(pending(CODE_DUE, WakeReceiver.ACTION_DUE, "", 0))
        am.cancel(pending(CODE_STAGE, WakeReceiver.ACTION_STAGE, "", 0))
    }

    override fun cancelSnooze() {
        am.cancel(pending(CODE_SNOOZE, WakeReceiver.ACTION_SNOOZE_DUE, "", 0))
    }

    private companion object {
        const val TAG = "AlarmScheduler"
        const val CODE_DUE = 7101
        const val CODE_STAGE = 7102
        const val CODE_SNOOZE = 7103
    }
}
