package com.engabd.sendpin.wake

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A request to start an ambience scene, from Wake & Sleep.
 *
 * Scenes are started by `EffectsViewModel`, which owns the show's sound and its place on
 * the lights, and there is deliberately no second way in. This hands it the wire name of
 * the scene to start.
 *
 * Limit, stated plainly: that view model lives with the app's screen. If the system has
 * already let go of it (the app was swiped away hours ago), the request is held for
 * [HOLD_MS] and started if the app is opened inside that window; otherwise it lapses
 * rather than starting a storm at breakfast.
 */
object WakeAmbience {
    const val HOLD_MS = 2 * 60_000L

    private val _requests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val requests: SharedFlow<String> = _requests.asSharedFlow()

    private var pending: Pair<String, Long>? = null

    @Synchronized
    fun request(wire: String, nowMs: Long = System.currentTimeMillis()) {
        pending = wire to nowMs
        _requests.tryEmit(wire)
    }

    /** The scene waiting to start, once, if it was asked for recently enough. */
    @Synchronized
    fun takePending(nowMs: Long = System.currentTimeMillis()): String? {
        val p = pending ?: return null
        pending = null
        return p.first.takeIf { nowMs - p.second <= HOLD_MS }
    }
}
