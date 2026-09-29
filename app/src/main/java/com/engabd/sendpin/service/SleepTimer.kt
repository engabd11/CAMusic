package com.engabd.sendpin.service

import com.engabd.sendpin.SendpinApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The sleep timer, owned by the process rather than by a screen.
 *
 * It used to live in `NowPlayingViewModel`, which has three problems for something
 * whose whole job is to act while nobody is looking: the ViewModel goes when its
 * Activity does, so a timer set before the screen was swiped away never fired; it
 * faded by stepping the *system* media volume, and a timer cut short mid-fade left
 * the phone turned down; and it could only count minutes, not "stop after this song".
 *
 * Now:
 *  - The countdown runs on the application's scope, against a wall-clock deadline.
 *  - On this phone the fade is the player's own gain ([com.engabd.sendpin.audio.LocalPlayer.setSleepFade]),
 *    so the system volume is never touched and nothing is left turned down. A
 *    Music Assistant speaker is faded with its own volume and put back after the
 *    pause, as before — there is no other knob on a speaker across the room.
 *  - [endOfTrack] pauses when the current song finishes, on whichever player is
 *    playing it.
 *
 * [fade] publishes the fade as it happens (1 → 0) for anything that wants to follow
 * it down — the room's lights, for one.
 */
class SleepTimer(private val app: SendpinApp) {

    enum class Mode { OFF, TIMED, END_OF_TRACK }

    data class State(
        val mode: Mode = Mode.OFF,
        /** Minutes the timer was set for; 0 for [Mode.END_OF_TRACK] and [Mode.OFF]. */
        val minutes: Int = 0,
        /** Time left, ticking once a second. 0 when not [Mode.TIMED]. */
        val remainingMs: Long = 0,
    ) {
        val running: Boolean get() = mode != Mode.OFF
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _fade = MutableStateFlow(1f)
    /** The fade in progress, 1 (none) down to 0 (silent). */
    val fade: StateFlow<Float> = _fade.asStateFlow()

    private val _ended = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The timer ran out and playback was paused — for a screen to say so. */
    val ended: SharedFlow<Unit> = _ended.asSharedFlow()

    /** Fade out over the last [FADE_MS] of [minutes], then pause. 0 cancels. */
    fun start(minutes: Int) {
        cancel()
        if (minutes <= 0) return
        val deadline = System.currentTimeMillis() + minutes * 60_000L
        _state.value = State(Mode.TIMED, minutes, minutes * 60_000L)
        job = scope.launch {
            while (true) {
                val left = deadline - System.currentTimeMillis()
                _state.value = _state.value.copy(remainingMs = left.coerceAtLeast(0))
                if (left <= FADE_MS) break
                delay(minOf(1_000L, left - FADE_MS))
            }
            fadeOutAndPause(deadline)
            finish()
        }
    }

    /** Pause when the song playing now ends. */
    fun endOfTrack() {
        cancel()
        _state.value = State(Mode.END_OF_TRACK)
        job = scope.launch {
            val local = app.localPlayer
            if (local.active.value && local.playing.value) {
                if (local.playsRemotely) {
                    // A player that plays itself (MPD): the next track starting is the
                    // only end this phone can see. Pause the moment it does.
                    local.current.map { it?.id }.distinctUntilChanged().drop(1).first()
                    local.pause()
                } else {
                    // Exact: the player itself stops at the item boundary, before a
                    // single sample of the next song is heard.
                    local.setPauseAtEndOfTrack(true)
                    try {
                        // Either the player stops at the boundary, or — when a crossfade
                        // hands over before it — the next song starts, and is paused
                        // at once.
                        val next = merge(
                            local.playing.drop(1).filter { !it }.map { false },
                            local.started.map { true },
                        ).first()
                        if (next) local.pause()
                    } finally {
                        local.setPauseAtEndOfTrack(false)
                    }
                }
            } else {
                val ma = app.maNowPlaying
                val now = ma.now.value
                if (now != null && now.isPlaying) {
                    val start = now.title to now.album
                    // Until the song changes, or the player stops on its own.
                    ma.now.first { it == null || !it.isPlaying || (it.title to it.album) != start }
                        ?.takeIf { it.isPlaying }
                        ?.let { ma.pause() }
                }
            }
            finish()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        restore()
        _state.value = State()
    }

    private fun finish() {
        _state.value = State()
        job = null
        _ended.tryEmit(Unit)
    }

    private suspend fun fadeOutAndPause(deadline: Long) {
        val local = app.localPlayer
        val onPhone = local.active.value && local.playing.value
        if (onPhone && !local.playsRemotely) {
            try {
                ramp(deadline) { local.setSleepFade(it) }
                local.pause()
            } finally {
                // Back to full for whatever is played next, even if cancelled mid-fade.
                local.setSleepFade(1f)
                _fade.value = 1f
            }
            return
        }
        if (onPhone) {
            // MPD plays itself and has its own mixer the phone should not be fighting
            // at bedtime; a clean pause is the honest version.
            local.pause()
            return
        }
        val ma = app.maNowPlaying
        val now = ma.now.value ?: return
        if (!now.isPlaying) return
        val start = now.volumeLevel.coerceIn(0, 100)
        try {
            ramp(deadline) { ma.setVolume(start / 100f * it) }
            ma.pause()
            // Let the pause land before the volume goes back up, or the last
            // half-second plays at full level.
            withTimeoutOrNull(3_000) { ma.now.first { it == null || !it.isPlaying } }
        } finally {
            ma.setVolume(start / 100f)
            _fade.value = 1f
        }
    }

    /** Step from 1 to 0 across whatever is left of the fade window. */
    private suspend fun ramp(deadline: Long, apply: (Float) -> Unit) {
        val steps = FADE_STEPS
        val span = (deadline - System.currentTimeMillis()).coerceIn(1_000L, FADE_MS)
        for (i in 1..steps) {
            val level = 1f - i.toFloat() / steps
            _fade.value = level
            apply(level)
            _state.value = _state.value.copy(remainingMs = (deadline - System.currentTimeMillis()).coerceAtLeast(0))
            delay(span / steps)
        }
    }

    private fun restore() {
        if (_fade.value < 1f) {
            app.localPlayer.setSleepFade(1f)
            _fade.value = 1f
        }
        app.localPlayer.setPauseAtEndOfTrack(false)
    }

    companion object {
        const val FADE_MS = 10_000L
        private const val FADE_STEPS = 40
    }
}
