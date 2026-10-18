package com.engabd.sendpin.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.engabd.sendpin.R
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.service.OpenAppRequest
import com.engabd.sendpin.service.openAppIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Runs the stages of an alarm and rings it.
 *
 * Each trigger arrives as its own exact alarm and does a short piece of work:
 *  - [WakeStage.LIGHTS_START] and [WakeStage.LIGHTS_LEG2] send the sunrise legs to the
 *    bridge (see [RampPlan]) and let the bridge do the rest;
 *  - [WakeStage.MUSIC] starts the music quietly and fades it up over the alarm's fade-in;
 *  - the alarm itself posts the ringing notification with Snooze and Stop.
 *
 * It is a foreground service of type mediaPlayback because that is what holds while music
 * is fading up and the notification is ringing. It stops itself the moment it has nothing
 * left to hold: a lights-only stage ends it a second after the last PUT.
 *
 * The phone never streams to the lamps: the bridge ramps on its own, so a flat battery or
 * a closed app after the first stage does not stop the sunrise.
 */
class WakeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as SendpinApp
    private val settings by lazy { AppSettings(applicationContext) }
    private val lights by lazy { BridgeRoomLights(applicationContext) }

    private var fadeJob: Job? = null
    private var ringJob: Job? = null
    private var ringing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val id = intent?.getStringExtra(WakeReceiver.EXTRA_ID).orEmpty()
        val at = intent?.getLongExtra(WakeReceiver.EXTRA_AT, 0L) ?: 0L
        // Always promote first: a service started with startForegroundService has five seconds.
        goForeground(if (ringing) ringingNotification(null) else workingNotification())
        when (action) {
            WakeReceiver.ACTION_STAGE -> {
                val stage = runCatching { WakeStage.valueOf(intent?.getStringExtra(WakeReceiver.EXTRA_STAGE).orEmpty()) }.getOrNull()
                scope.launch { runStage(id, at, stage) }
            }
            WakeReceiver.ACTION_DUE -> scope.launch { ring(id, at, resume = false) }
            WakeReceiver.ACTION_SNOOZE_DUE -> scope.launch { ring(id, at, resume = true) }
            ACTION_SNOOZE -> scope.launch { snooze(id, at) }
            ACTION_STOP -> scope.launch { stopAlarm() }
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    // ── Stages ──────────────────────────────────────────────────────────────

    private suspend fun runStage(id: String, at: Long, stage: WakeStage?) {
        val alarm = settings.wakeAlarms.first().firstOrNull { it.id == id }
        if (alarm == null || stage == null || !alarm.enabled) {
            stopIfIdle()
            return
        }
        when (stage) {
            WakeStage.LIGHTS_START, WakeStage.LIGHTS_LEG2 -> sunrise(alarm, at, stage)
            WakeStage.MUSIC -> startMusic(alarm, at, at - System.currentTimeMillis())
        }
        WakeRuntime.markDone(StageAt(stage, 0).key(at))
        rearmWakeAlarms(applicationContext)
        stopIfIdle()
    }

    private suspend fun sunrise(alarm: WakeAlarm, at: Long, stage: WakeStage) {
        // The phone's own streaming owns the lamps while Light Sync runs. Sending the
        // bridge a ramp on top would fight it, so step aside and say so.
        if (app.directLightSync.active.value) {
            WakeRuntime.lightsNote = "Light Sync was running, so the sunrise lights stayed off."
            return
        }
        val rampStart = at - alarm.rampMs
        val bodies = RampPlan.bodies(stage, alarm.rampMs, System.currentTimeMillis() - rampStart)
        try {
            for (body in bodies) lights.put(alarm.roomId, body)
            WakeRuntime.lightsNote = null
        } catch (e: Exception) {
            Log.w(TAG, "sunrise failed: ${e.message}")
            // Music still plays. The notification says the lights could not be reached.
            WakeRuntime.lightsNote = "The lights could not be reached. Is the Hue Bridge on this network?"
        }
    }

    // ── Music ───────────────────────────────────────────────────────────────

    /**
     * Start the alarm's music quietly and raise it to the target volume over what is left
     * of the fade-in. Joining late (a stage that ran after its moment) fades over the time
     * that remains, never the full length again.
     */
    private suspend fun startMusic(alarm: WakeAlarm, at: Long, remainingMs: Long) {
        if (alarm.source.kind == WakeSource.Kind.NONE) return
        if (WakeRuntime.musicStartedFor == at) return
        val fadeMs = minOf(alarm.fadeInMs, remainingMs.coerceAtLeast(0))
        // The system volume goes to the target and the player's own gain does the fading.
        // That way nothing is left turned down afterwards, as the sleep timer does it.
        app.deviceVolume.setVolumePercent(alarm.volume)
        if (fadeMs > 0) app.localPlayer.setSleepFade(FADE_FLOOR)
        try {
            when (alarm.source.kind) {
                WakeSource.Kind.ITEM -> app.playLibraryItem(alarm.source.mediaId)
                WakeSource.Kind.RESUME -> if (!app.playbackOwner.state.value.anyPlaying) app.playbackOwner.playPause()
                WakeSource.Kind.AMBIENCE -> WakeAmbience.request(alarm.source.ambience)
                WakeSource.Kind.NONE -> Unit
            }
            WakeRuntime.musicStartedFor = at
        } catch (e: Exception) {
            Log.w(TAG, "music failed: ${e.message}")
            app.localPlayer.setSleepFade(1f)
            return
        }
        if (alarm.source.kind == WakeSource.Kind.AMBIENCE || fadeMs <= 0) {
            app.localPlayer.setSleepFade(1f)
            return
        }
        fadeJob?.cancel()
        fadeJob = scope.launch { fadeIn(fadeMs) }
    }

    private suspend fun fadeIn(fadeMs: Long) {
        val steps = (fadeMs / FADE_STEP_MS).toInt().coerceIn(1, 400)
        val maStart = app.maNowPlaying.now.value
        val maTarget = maStart?.volumeLevel?.coerceIn(0, 100)?.div(100f)
        for (i in 1..steps) {
            val level = (i.toFloat() / steps).coerceAtLeast(FADE_FLOOR)
            if (app.localPlayer.active.value) {
                app.localPlayer.setSleepFade(level)
            } else if (maTarget != null) {
                app.maNowPlaying.setVolume(maTarget * level)
            }
            delay(fadeMs / steps)
        }
        app.localPlayer.setSleepFade(1f)
    }

    private suspend fun stopMusic() {
        fadeJob?.cancel()
        fadeJob = null
        runCatching { app.playbackOwner.pause() }
        app.localPlayer.setSleepFade(1f)
        WakeRuntime.musicStartedFor = 0L
    }

    // ── Ringing ─────────────────────────────────────────────────────────────

    private suspend fun ring(id: String, at: Long, resume: Boolean) {
        if (!resume && WakeRuntime.firingAtMs == at) return
        val alarm = settings.wakeAlarms.first().firstOrNull { it.id == id }
        if (alarm == null) {
            stopIfIdle()
            return
        }
        WakeRuntime.firingAtMs = at
        ringing = true
        currentAlarmId = id
        currentAlarmAt = at
        // The music stage may have been missed (a reboot, a late alarm); make sure it plays.
        if (alarm.hasMusic && WakeRuntime.musicStartedFor != at) startMusic(alarm, at, if (resume) SNOOZE_RESUME_FADE_MS else alarm.fadeInMs)
        val note = WakeRuntime.lightsNote
        goForeground(ringingNotification(alarm, note))
        if (!resume) {
            // A one-off alarm has done its job. Repeating ones were already re-armed for the
            // next occurrence by the planner, since "next" is always strictly after now.
            if (alarm.days == 0) settings.upsertWakeAlarm(alarm.copy(enabled = false))
            WakeRuntime.markDone(StageAt(WakeStage.MUSIC, 0).key(at))
            rearmWakeAlarms(applicationContext)
        }
        ringJob?.cancel()
        ringJob = scope.launch {
            delay(RING_LIMIT_MS)
            // Nobody answered. Stop ringing but leave the music: it is ordinary playback now.
            finishRinging(stopMusic = false)
        }
    }

    private suspend fun snooze(id: String, at: Long) {
        val now = System.currentTimeMillis()
        stopMusic()
        // Lights hold: nothing is sent, so whatever the ramp reached stays.
        AndroidAlarmPort(applicationContext).setSnooze(AlarmMath.snoozeAt(now), id, at)
        finishRinging(stopMusic = false)
    }

    private suspend fun stopAlarm() {
        AndroidAlarmPort(applicationContext).cancelSnooze()
        // Music stops, lights stay on: the room is already where the sleeper wanted it.
        finishRinging(stopMusic = true)
    }

    private suspend fun finishRinging(stopMusic: Boolean) {
        if (stopMusic) stopMusic()
        ringJob?.cancel()
        ringJob = null
        ringing = false
        WakeRuntime.lightsNote = null
        stopForegroundAndService()
    }

    private fun stopIfIdle() {
        if (ringing || fadeJob?.isActive == true) return
        stopForegroundAndService()
    }

    private fun stopForegroundAndService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Notifications ───────────────────────────────────────────────────────

    private fun goForeground(n: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        }.onFailure { Log.w(TAG, "startForeground failed: ${it.message}") }
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WORKING, "Sunrise in progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown briefly while a sunrise alarm sends the lights ramp or fades the music in."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RINGING, "Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings when a sunrise alarm is due, with Snooze and Stop."
                setBypassDnd(false)
            },
        )
    }

    private fun workingNotification(): Notification {
        ensureChannels()
        return NotificationCompat.Builder(this, CHANNEL_WORKING)
            .setSmallIcon(R.drawable.ic_stat_camusic)
            .setContentTitle("Sunrise getting ready")
            .setContentText("Your wake up is under way.")
            .setContentIntent(openAppIntent(this, OpenAppRequest.WAKE))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ringingNotification(alarm: WakeAlarm?, note: String? = WakeRuntime.lightsNote): Notification {
        ensureChannels()
        val open = openAppIntent(this, OpenAppRequest.WAKE)
        fun action(code: Int, act: String) = PendingIntent.getService(
            this, code,
            Intent(this, WakeService::class.java).setAction(act)
                .putExtra(WakeReceiver.EXTRA_ID, currentAlarmId)
                .putExtra(WakeReceiver.EXTRA_AT, currentAlarmAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = alarm?.label?.takeIf { it.isNotBlank() } ?: "Good morning"
        val text = note ?: "Time to rise. Your lights and music are ready."
        return NotificationCompat.Builder(this, CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_stat_camusic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setOngoing(true)
            .addAction(0, "Snooze 9 min", action(1, ACTION_SNOOZE))
            .addAction(0, "Stop", action(2, ACTION_STOP))
            .build()
    }

    companion object {
        private const val TAG = "WakeService"
        const val CHANNEL_WORKING = "wake_working"
        const val CHANNEL_RINGING = "wake_ringing"
        const val NOTIFICATION_ID = 1007
        const val ACTION_SNOOZE = "com.engabd.sendpin.wake.SNOOZE"
        const val ACTION_STOP = "com.engabd.sendpin.wake.STOP"

        private const val FADE_FLOOR = 0.02f
        private const val FADE_STEP_MS = 3_000L
        private const val SNOOZE_RESUME_FADE_MS = 30_000L
        private const val RING_LIMIT_MS = 60 * 60_000L

        @Volatile private var currentAlarmId: String = ""
        @Volatile private var currentAlarmAt: Long = 0L

        /** Hand a receiver intent to the service, as a foreground start. */
        fun start(context: Context, source: Intent) {
            val intent = Intent(context, WakeService::class.java).setAction(source.action)
            source.extras?.let { intent.putExtras(it) }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "could not start: ${it.message}") }
        }
    }
}
