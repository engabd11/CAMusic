package com.engabd.sendpin.car

import android.app.PendingIntent
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.service.LocalPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Android Auto's entry point into this app: a browse tree ([CarLibraryBridge]) and a
 * session backed by a facade player ([CarSessionPlayer]) that mirrors whichever of
 * this app's own playback paths is actually active.
 *
 * Deliberately a third, separate service from [com.engabd.sendpin.service.LocalPlaybackService]
 * and [com.engabd.sendpin.service.SendspinService] — see this package's own notes in
 * the implementation plan. Those two come and go with playback and each wrap a real
 * decoder or a decoder-adjacent facade; this one decodes nothing and must stay
 * bound and queryable (browse, search) whether or not anything is currently playing,
 * which is a different lifecycle than either of them has.
 */
@OptIn(UnstableApi::class)
class CarMediaLibraryService : MediaLibraryService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaLibrarySession: MediaLibrarySession? = null
    private var carPlayer: CarSessionPlayer? = null
    private var callback: CarLibrarySessionCallback? = null

    override fun onCreate() {
        super.onCreate()
        val player = CarSessionPlayer(Looper.getMainLooper(), scope).also { it.start() }
        carPlayer = player
        val libraryBridge = CarLibraryBridge(SendpinApp.instance)
        val sessionCallback = CarLibrarySessionCallback(libraryBridge)
        callback = sessionCallback
        mediaLibrarySession = MediaLibrarySession.Builder(this, player, sessionCallback)
            // Must differ from LocalPlaybackService's "local" and SendspinService's
            // "sendspin" - a media3 MediaSession id has to be unique per process.
            .setId("auto")
            .apply { sessionActivity()?.let { setSessionActivity(it) } }
            .build()
        watchBrowseOptions(libraryBridge)
    }

    /**
     * Where "open the app" goes from the car.
     *
     * A session with no activity attached leaves the browser nothing to launch, so the
     * app's own icon on the car's now-playing card does nothing — and the phone, which
     * is the only screen where this app's real settings live, cannot be reached from
     * the driver's seat without unplugging.
     *
     * `FLAG_IMMUTABLE` because nothing outside this process has any business filling in
     * the intent, matching every other `PendingIntent` in the app.
     */
    private fun sessionActivity(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Rebuild the car's screen when its settings change on the phone.
     *
     * Android Auto subscribes to the root once and then trusts it: a browse tree that
     * only re-read its settings on the next connection meant changing the layout with
     * the phone plugged in did nothing visible until the cable was pulled, which reads
     * as a setting that does not work rather than one that is deferred.
     *
     * The cached rows go with it: each was built with the old options, down to whether
     * it carries a cover.
     *
     * This also **primes** the bridge's own copy of the options, which is what lets
     * [CarLibrarySessionCallback.onGetLibraryRoot] answer a connecting browser without
     * touching DataStore — see [CarLibraryBridge.primeOptions]. The first emission is
     * the priming one and invalidates nothing, because that is the state the tree was
     * just built from rather than a change to it; every later one is a real change.
     */
    private fun watchBrowseOptions(libraryBridge: CarLibraryBridge) {
        val settings = AppSettings(this)
        scope.launch {
            var seen = false
            settings.carBrowseOptions.collect { options ->
                libraryBridge.primeOptions(options)
                if (!seen) {
                    seen = true
                    return@collect
                }
                libraryBridge.invalidate()
                // Int.MAX_VALUE, not a real count: the browser is being told the root
                // changed, not how much of it. It comes back through onGetChildren for
                // the answer, which is where the count is decided anyway.
                mediaLibrarySession?.notifyChildrenChanged(CarMediaId.ROOT, Int.MAX_VALUE, null)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaLibrarySession

    /**
     * This service never decodes audio, so media3 must never post its own playback
     * notification for it: whichever of [com.engabd.sendpin.service.LocalPlaybackService] /
     * [com.engabd.sendpin.service.SendspinService] is actually playing already posts
     * the real one. Without this override, [MediaSessionService] auto-promotes to
     * foreground and shows a *third*, redundant "CAMusic playback" notification the
     * moment this facade mirrors a playing session.
     *
     * [MediaSessionService]'s own doc says an app that overrides this "must also start
     * or stop the service from the foreground". Android Auto only *binds* this service,
     * which opens no foreground deadline. But since media3's `MediaButtonReceiver` was
     * declared (playback resumption), a headset or car Play after the process died
     * *starts* it with `startForegroundService` — and with this override silent,
     * nothing ever called `startForeground`, so Android killed the app about ten
     * seconds later ("did not then call Service.startForeground()"). That start is
     * answered in [onStartCommand] instead, with a stand-in notification that stays
     * only until the real player's own is up.
     *
     * `MediaSessionListener.onPlayRequested` — the one place media3 can veto a play
     * because the service failed to reach the foreground, on API 31/32 — takes its
     * answer from `onUpdateNotificationInternal`, which returns `true` regardless of
     * what this override does. Worth re-reading on a media3 upgrade: that return is
     * the whole reason a silent no-op here does not swallow the play.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {}

    /**
     * A media button that (re)started this service, typically Play from a headset or a
     * car after the process was killed: media3's `MediaButtonReceiver` reached it with
     * `startForegroundService`, which obliges the service to call `startForeground`
     * within a few seconds or the app is killed.
     *
     * So: a minimal "Resuming…" notification on the playback channel, posted *before*
     * the button is handed to media3, held until the real player's service is in the
     * foreground with its own notification (or [STANDIN_MAX_MS] passes — nothing to
     * resume is a valid answer too), then withdrawn. The stand-in has its own id so it
     * never replaces or cancels a real notification.
     */
    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == android.content.Intent.ACTION_MEDIA_BUTTON) holdForegroundBriefly(startId)
        return super.onStartCommand(intent, flags, startId)
    }

    private var standInJob: kotlinx.coroutines.Job? = null

    private fun holdForegroundBriefly(startId: Int) {
        val posted = runCatching {
            LocalPlaybackService.ensureChannel(this)
            val n = androidx.core.app.NotificationCompat.Builder(this, LocalPlaybackService.CHANNEL_ID)
                .setContentTitle("CAMusic")
                .setContentText("Resuming…")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .build()
            androidx.core.app.ServiceCompat.startForeground(
                this, STANDIN_NOTIFICATION_ID, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        }.onFailure {
            android.util.Log.w("CarMediaLibrary", "couldn't hold the foreground for a media button: ${it.message}")
        }.isSuccess
        if (!posted) return
        standInJob?.cancel()
        standInJob = scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(STANDIN_MAX_MS) {
                LocalPlaybackService.foreground.first { it }
            }
            // Long enough for the real notification to be drawn before this one goes,
            // so the shade does not blink empty in between.
            kotlinx.coroutines.delay(STANDIN_OVERLAP_MS)
            androidx.core.app.ServiceCompat.stopForeground(
                this@CarMediaLibraryService, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE,
            )
            // Only the start this stand-in answered: a later media button has its own.
            // A bound Android Auto keeps the service alive regardless.
            stopSelf(startId)
        }
    }

    private companion object {
        /** Its own id: never one a real notification uses (1000-1004 are taken). */
        const val STANDIN_NOTIFICATION_ID = 1005

        /** Longest the stand-in stays when nothing comes up to replace it. */
        const val STANDIN_MAX_MS = 8_000L

        const val STANDIN_OVERLAP_MS = 500L
    }

    override fun onDestroy() {
        standInJob?.cancel()
        carPlayer?.stopObserving()
        carPlayer = null
        mediaLibrarySession?.release()
        mediaLibrarySession = null
        callback?.release()
        callback = null
        scope.cancel()
        super.onDestroy()
    }
}
