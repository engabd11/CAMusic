package com.engabd.sendpin.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Holds the `MediaProjection` while Light Sync listens to other apps.
 *
 * ## The order in [onStartCommand] is the whole class
 *
 * On API 34+ the platform requires, in this order:
 *
 * 1. the service to be in the foreground **with type `mediaProjection`**, then
 * 2. `getMediaProjection(...)`, then
 * 3. `registerCallback(...)` — before any capture is built.
 *
 * Getting it wrong throws `SecurityException` at runtime with nothing to catch it at
 * compile time, and there is no way to unit-test any of it (no Robolectric here, and
 * a consent dialog cannot be faked). So it is written once, in one place, in order,
 * with the reason beside each step.
 */
class PlaybackCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var loop: CaptureLoop? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Fires when the user taps the system's stop-casting chip, when another app takes
     * the projection, or on [MediaProjection.stop]. Treated as a first-class state,
     * not as silence: restarting means a fresh consent dialog on 14+, and the user
     * needs to be told why rather than watching the lights quietly stop.
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            teardown()
            PlaybackCapture.publish(PlaybackCapture.State.STOPPED_BY_SYSTEM)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0)?.takeIf { it != 0 }
            ?: PlaybackCapture.pendingResultCode
        val data = intent?.let { IntentCompat.resultData(it) } ?: PlaybackCapture.pendingResultData
        if (code == 0 || data == null) {
            PlaybackCapture.publish(PlaybackCapture.State.DENIED)
            stopSelf()
            return START_NOT_STICKY
        }

        // 1. Foreground, typed, *first*.
        runCatching {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        }.onFailure {
            android.util.Log.e("PlaybackCapture", "startForeground(mediaProjection) refused", it)
            PlaybackCapture.publish(PlaybackCapture.State.DENIED)
            stopSelf()
            return START_NOT_STICKY
        }

        // 2. Only now may the token be redeemed.
        val manager = getSystemService(MediaProjectionManager::class.java)
        val proj = runCatching { manager?.getMediaProjection(code, data) }.getOrNull()
        if (proj == null) {
            android.util.Log.e("PlaybackCapture", "getMediaProjection returned nothing")
            PlaybackCapture.publish(PlaybackCapture.State.DENIED)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // 3. Callback before capture — required on 34+, and the only warning we get
        // that the projection has gone away.
        proj.registerCallback(projectionCallback, handler)
        projection = proj

        val capture = CaptureLoop(
            context = applicationContext,
            projection = proj,
            tap = PlaybackCapture.tap,
            lead = PlaybackCapture.lead,
            onVerdict = ::onVerdict,
        )
        if (!capture.start()) {
            android.util.Log.e("PlaybackCapture", "AudioRecord would not start")
            teardown()
            PlaybackCapture.publish(PlaybackCapture.State.DENIED)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        loop = capture
        PlaybackCapture.publish(PlaybackCapture.State.RUNNING)
        return START_NOT_STICKY
    }

    private fun onVerdict(verdict: CaptureSilenceWatchdog.Verdict) {
        PlaybackCapture.publish(
            when (verdict) {
                CaptureSilenceWatchdog.Verdict.AUDIBLE -> PlaybackCapture.State.RUNNING
                CaptureSilenceWatchdog.Verdict.SILENT_LIKELY_BLOCKED -> PlaybackCapture.State.BLOCKED
                CaptureSilenceWatchdog.Verdict.SILENT_NOTHING_PLAYING -> PlaybackCapture.State.QUIET
                CaptureSilenceWatchdog.Verdict.WARMING -> PlaybackCapture.State.STARTING
            },
        )
    }

    private fun teardown() {
        loop?.stop()
        loop = null
        projection?.runCatching { unregisterCallback(projectionCallback) }
        projection?.runCatching { stop() }
        projection = null
    }

    override fun onDestroy() {
        teardown()
        if (PlaybackCapture.state.value != PlaybackCapture.State.STOPPED_BY_SYSTEM) {
            PlaybackCapture.publish(PlaybackCapture.State.OFF)
        }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Light Sync capture", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Light Sync is listening to other apps."
                    setShowBadge(false)
                },
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Light Sync is listening")
            .setContentText("Reacting to whatever is playing on this phone.")
            .setContentIntent(com.engabd.sendpin.service.openAppIntent(this, NOTIFICATION_ID))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "light_sync_capture"
        private const val NOTIFICATION_ID = 0x5CAB
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, PlaybackCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            runCatching { context.startForegroundService(intent) }
                .onFailure {
                    android.util.Log.e("PlaybackCapture", "could not start the capture service", it)
                    PlaybackCapture.publish(PlaybackCapture.State.DENIED)
                }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PlaybackCaptureService::class.java)) }
        }
    }

    /** `getParcelableExtra` is deprecated untyped; kept in one place. */
    private object IntentCompat {
        fun resultData(intent: Intent): Intent? =
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
    }
}
