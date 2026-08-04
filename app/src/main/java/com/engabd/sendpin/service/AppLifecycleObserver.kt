package com.engabd.sendpin.service

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.engabd.sendpin.data.AppSettings
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
 * One question, asked on backgrounding: does this phone still need to be reachable?
 *
 * - **Keep-alive on (default):** nothing happens. The connection and
 *   `SendspinConnectionService` both stay up, which is what lets a Home Assistant
 *   TTS announcement arrive while the app is in the background. This is the
 *   behaviour the app has always had.
 *
 * - **Keep-alive off:** the connection is dropped once playback is idle, which lets
 *   the connection service stop — releasing the partial wake lock, the
 *   `WIFI_MODE_FULL_LOW_LATENCY` lock and the persistent notification it holds. For
 *   users who never send announcements to this phone, that is the whole background
 *   cost of the app, spent on nothing. It reconnects on return to the foreground.
 *
 * Neither disconnects while audio is playing — see the guard in [Playback].
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
