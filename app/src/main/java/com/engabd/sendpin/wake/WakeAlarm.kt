package com.engabd.sendpin.wake

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** What wakes the sleeper: a library item, whatever played last, or an ambience scene. */
@Serializable
data class WakeSource(
    val kind: Kind = Kind.NONE,
    /** A [com.engabd.sendpin.car.CarMediaId] for [Kind.ITEM]; the play path Android Auto uses. */
    val mediaId: String = "",
    /** Shown in the list: a playlist or album name, or the scene's title. */
    val name: String = "",
    /** [com.engabd.sendpin.hue.ambience.AmbienceEffect.wire] for [Kind.AMBIENCE]. */
    val ambience: String = "",
) {
    @Serializable
    enum class Kind { NONE, ITEM, RESUME, AMBIENCE }
}

/**
 * One alarm.
 *
 * [days] is a bitmask, Monday = bit 0 to Sunday = bit 6; 0 means "once", the next time the
 * clock reaches [hour]:[minute]. [roomId] is the `grouped_light` id of a Hue room or zone,
 * blank for music only.
 */
@Serializable
data class WakeAlarm(
    val id: String,
    val hour: Int,
    val minute: Int,
    val days: Int = 0,
    val enabled: Boolean = true,
    val label: String = "",
    val source: WakeSource = WakeSource(),
    val roomId: String = "",
    val roomName: String = "",
    val rampMinutes: Int = DEFAULT_RAMP_MIN,
    val fadeInMinutes: Int = DEFAULT_FADE_MIN,
    /** Device volume the music settles at, in percent. */
    val volume: Int = DEFAULT_VOLUME,
) {
    val rampMs: Long get() = rampMinutes.coerceAtLeast(0) * 60_000L
    val fadeInMs: Long get() = fadeInMinutes.coerceAtLeast(0) * 60_000L
    val hasLights: Boolean get() = roomId.isNotBlank() && rampMs > 0
    val hasMusic: Boolean get() = source.kind != WakeSource.Kind.NONE

    companion object {
        const val DEFAULT_RAMP_MIN = 30
        const val DEFAULT_FADE_MIN = 3
        const val DEFAULT_VOLUME = 70
        val RAMP_CHOICES = listOf(20, 30, 45)
        val FADE_CHOICES = listOf(1, 3, 5, 10)

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(list: List<WakeAlarm>): String = json.encodeToString(ListSerializer(serializer()), list)

        /** Null when unreadable, so a bad blob is never mistaken for "no alarms" and overwritten. */
        fun decode(raw: String): List<WakeAlarm>? =
            runCatching { json.decodeFromString(ListSerializer(serializer()), raw) }.getOrNull()

        fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)
        const val WEEKDAYS = 0b0011111
        const val WEEKEND = 0b1100000
        const val EVERY_DAY = 0b1111111
    }
}

/** The moments before an alarm that need the phone awake. Each is its own exact alarm. */
enum class WakeStage { LIGHTS_START, LIGHTS_LEG2, MUSIC }

/** One stage and when it runs. */
data class StageAt(val stage: WakeStage, val atMs: Long) {
    /** Identifies this stage of this occurrence, for remembering that it ran. */
    fun key(alarmAtMs: Long) = "$alarmAtMs:${stage.name}"
}

object AlarmMath {
    /** How long a snooze lasts. */
    const val SNOOZE_MS = 9 * 60_000L

    /**
     * The next time [alarm] sounds strictly after [nowMs], in [zone], or null if it is off.
     *
     * Walks calendar days in the zone rather than adding 24 hours, so a 6:30 alarm stays at
     * 6:30 on the wall clock across a daylight saving change. A time that does not exist
     * (the spring-forward gap) lands at the instant the clock jumps to, as `ZonedDateTime`
     * resolves it; a repeated time (autumn) uses the first of the two.
     */
    fun nextOccurrence(alarm: WakeAlarm, nowMs: Long, zone: ZoneId): Long? {
        if (!alarm.enabled) return null
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val time = LocalTime.of(alarm.hour.coerceIn(0, 23), alarm.minute.coerceIn(0, 59))
        // 8 covers a full week plus today having already passed.
        for (offset in 0L..8L) {
            val date = now.toLocalDate().plusDays(offset)
            if (alarm.days != 0 && (alarm.days and WakeAlarm.bit(date.dayOfWeek)) == 0) continue
            val at = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
            if (at > nowMs) return at
        }
        return null
    }

    /** The stages of one occurrence, earliest first. Lights need a room, music needs a source. */
    fun stages(alarm: WakeAlarm, alarmAtMs: Long): List<StageAt> = buildList {
        if (alarm.hasLights) {
            add(StageAt(WakeStage.LIGHTS_START, alarmAtMs - alarm.rampMs))
            add(StageAt(WakeStage.LIGHTS_LEG2, alarmAtMs - alarm.rampMs + RampPlan.legOneMs(alarm.rampMs)))
        }
        if (alarm.hasMusic) add(StageAt(WakeStage.MUSIC, alarmAtMs - alarm.fadeInMs))
    }.sortedBy { it.atMs }

    /** The alarm that sounds soonest after [nowMs], with when. */
    fun next(alarms: List<WakeAlarm>, nowMs: Long, zone: ZoneId): Pair<WakeAlarm, Long>? =
        alarms.mapNotNull { a -> nextOccurrence(a, nowMs, zone)?.let { a to it } }.minByOrNull { it.second }

    fun snoozeAt(nowMs: Long): Long = nowMs + SNOOZE_MS

    /** A gap such as "7 h 30 min", rounded down to the minute. */
    fun untilText(nowMs: Long, atMs: Long): String {
        val d = Duration.ofMillis((atMs - nowMs).coerceAtLeast(0))
        val days = d.toDays()
        val hours = d.toHours() % 24
        val mins = d.toMinutes() % 60
        return when {
            days > 0 -> "$days d $hours h"
            hours > 0 -> "$hours h $mins min"
            else -> "$mins min"
        }
    }

    fun daysText(days: Int): String = when (days) {
        0 -> "Once"
        WakeAlarm.EVERY_DAY -> "Every day"
        WakeAlarm.WEEKDAYS -> "Weekdays"
        WakeAlarm.WEEKEND -> "Weekends"
        else -> DayOfWeek.entries.filter { (days and WakeAlarm.bit(it)) != 0 }
            .joinToString(" ") { it.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } }
    }
}
