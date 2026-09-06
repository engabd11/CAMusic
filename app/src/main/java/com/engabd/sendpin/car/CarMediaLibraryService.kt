package com.engabd.sendpin.car

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
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
            .build()
        watchBrowseOptions(libraryBridge)
    }

    /**
     * Rebuild the car's screen when its settings change on the phone.
     *
     * Android Auto subscribes to the root once and then trusts it: a browse tree that
     * only re-read its settings on the next connection meant changing the layout with
     * the phone plugged in did nothing visible until the cable was pulled, which reads
     * as a setting that does not work rather than one that is deferred. `drop(1)` skips
     * the flow's own first emission — that is the state the tree was just built from,
     * not a change to it.
     *
     * The cached rows go with it: each was built with the old options, down to whether
     * it carries a cover.
     */
    private fun watchBrowseOptions(libraryBridge: CarLibraryBridge) {
        val settings = AppSettings(this)
        scope.launch {
            settings.carBrowseOptions.drop(1).collect {
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
     * This service never decodes audio, so it must never post its own foreground
     * notification: whichever of [com.engabd.sendpin.service.LocalPlaybackService] /
     * [com.engabd.sendpin.service.SendspinService] is actually playing already posts
     * the real one. Without this override, [MediaSessionService] auto-promotes to
     * foreground and shows a *third*, redundant "CAMusic playback" notification the
     * moment this facade mirrors a playing session.
     *
     * [MediaSessionService]'s own doc says an app that overrides this "must also start
     * or stop the service from the foreground" — that is written for a service that is
     * the one making the sound, and not doing it here is safe on two counts. Android
     * Auto *binds* rather than starts this service ([onGetSession] is reached from
     * `onBind`), so no `startForeground` deadline is ever opened against it; and
     * `MediaSessionListener.onPlayRequested` — the one place media3 can veto a play
     * because the service failed to reach the foreground, on API 31/32 — takes its
     * answer from `onUpdateNotificationInternal`, which returns `true` regardless of
     * what this override does. Worth re-reading on a media3 upgrade: that return is
     * the whole reason a silent no-op here does not swallow the play.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {}

    override fun onDestroy() {
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
