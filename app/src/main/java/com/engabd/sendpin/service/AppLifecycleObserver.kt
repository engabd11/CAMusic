package com.engabd.sendpin.service

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.engabd.sendpin.audio.SendspinAudioEngine
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.protocol.SendspinClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Watches the app's process lifecycle and manages the Sendspin connection accordingly.
 *
 * Two behaviours, both driven by whether [keepAliveForAnnouncements] is on:
 *
 * - **Keep-alive on (default):** the connection persists through backgrounding. When
 *   the app goes to the background, [disconnect] is called with `reason = "restart"`
 *   — the spec's warm-reconnect signal, which asks MA to hold the player slot for
 *   ~30 seconds. On foreground return, [reconnect] brings it back. TTS announcements
 *   continue to work while backgrounded (the connection service stays up).
 *
 * - **Keep-alive off:** the connection is torn down with `reason = "user_request"`
 *   when playback goes idle and the app is backgrounded — no persistent service, no
 *   wake lock, no wifi lock. The connection only runs during active playback. This
 *   saves battery for users who don't use HA TTS announcements.
 *
 * Registered on [SendpinApp] so it lives for the process lifetime.
 */
class AppLifecycleObserver(
    private val context: Context,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = AppSettings(context)

    /**
     * Whether the connection should persist in the background for TTS. Read once at
     * background time rather than collected, because the decision is a single point
     * in the lifecycle, not a continuous state.
     */
    private suspend fun shouldKeepAlive(): Boolean = settings.keepAliveForAnnouncements.first()

    /**
     * Called by [Playback] on background — sends the warm goodbye if keep-alive is on,
     * or tears the connection down fully if it is off.
     */
    var onBackground: (suspend (keepAlive: Boolean) -> Unit)? = null

    /** Called by [Playback] on foreground — reconnects if we were warm-disconnected. */
    var onForeground: (() -> Unit)? = null

    /** Whether the app is currently in the foreground. */
    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    override fun onStart(owner: LifecycleOwner) {
        _foreground.value = true
        onForeground?.invoke()
    }

    override fun onStop(owner: LifecycleOwner) {
        _foreground.value = false
        scope.launch {
            val keepAlive = shouldKeepAlive()
            onBackground?.invoke(keepAlive)
        }
    }

    companion object {
        @Volatile private var instance: AppLifecycleObserver? = null

        fun register(context: Context): AppLifecycleObserver {
            instance?.let { return it }
            val observer = AppLifecycleObserver(context.applicationContext)
            instance = observer
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
            return observer
        }

        fun get(): AppLifecycleObserver? = instance
    }
}