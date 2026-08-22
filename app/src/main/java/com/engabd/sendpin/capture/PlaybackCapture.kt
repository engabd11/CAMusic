package com.engabd.sendpin.capture

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.engabd.sendpin.audio.AudioAnalysisTap
import com.engabd.sendpin.audio.AudioLead
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Light Sync reacting to *other apps'* playback, through `MediaProjection`.
 *
 * ## What this can and cannot do
 *
 * `AudioPlaybackCapture` (API 29+) hands this app a post-mix copy of what other apps
 * are playing — but **only apps that permit it**. An app declaring
 * `android:allowAudioPlaybackCapture="false"` is captured as bit-exact silence, and
 * YouTube, YouTube Music and most DRM-protected apps do exactly that. Spotify,
 * podcast apps and most local players do not. [CaptureSilenceWatchdog] exists to tell
 * the user which of those they are looking at, because the platform will not.
 *
 * ## Why the state lives here, at process scope
 *
 * The projection token comes from an Activity result and is consumed by a foreground
 * service that outlives every Activity, so neither end can own it. It also **cannot
 * be persisted**: on Android 14+ a projection result is single-use, so every start
 * shows the system consent dialog again. That is a platform rule, not an oversight,
 * and the settings copy says so.
 */
object PlaybackCapture {

    /** Where the light show reads its PCM from while capture is running. */
    val tap: AudioAnalysisTap by lazy { AudioAnalysisTap(lead) }

    /**
     * No media clock behind capture, so this carries a *session* clock: microseconds
     * since capture began. `leadUs` stays [AudioLead.UNKNOWN] on purpose — capture is
     * post-mix, so the tap hears the audio at essentially the moment the speaker does
     * and there is no lead to spend on the frame delay queue.
     */
    val lead: AudioLead by lazy { AudioLead() }

    enum class State {
        /** Not running. */
        OFF,

        /** Consent granted, service starting, nothing read yet. */
        STARTING,

        /** Reading, and hearing something. */
        RUNNING,

        /** Reading, and hearing bit-exact silence from an app that opted out. */
        BLOCKED,

        /** Reading, and nothing is playing anywhere. */
        QUIET,

        /**
         * The system stopped the projection — the stop-casting chip, another app
         * taking the projection, or the process being trimmed. Restarting needs a
         * fresh consent dialog, so the UI has to say why rather than silently retry.
         */
        STOPPED_BY_SYSTEM,

        /** Consent was refused, or the platform rejected the request. */
        DENIED,
    }

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _running = MutableStateFlow(false)

    /**
     * True while frames are actually being produced — read by the feed picker.
     *
     * Written only by [publish], never independently, so it cannot drift from [state].
     * `QUIET` counts: capture is working, the room is simply silent, and treating that
     * as "not running" would hand the feed back to a tap that is equally silent.
     */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    internal fun publish(next: State) {
        _state.value = next
        _running.value = next == State.RUNNING || next == State.QUIET
    }

    /**
     * The consent result, handed from [CaptureConsentActivity] to
     * [PlaybackCaptureService].
     *
     * Kept here *and* copied into the service Intent: the Intent covers a cold start,
     * this covers the service being restarted inside a live process.
     */
    @Volatile
    var pendingResultCode: Int = 0

    @Volatile
    var pendingResultData: Intent? = null

    fun hasToken(): Boolean = pendingResultData != null

    /** Ask for consent. Goes through a trampoline Activity — see that class. */
    fun requestStart(context: Context) {
        publish(State.STARTING)
        CaptureConsentActivity.launch(context)
    }

    fun stop(context: Context) {
        PlaybackCaptureService.stop(context)
        pendingResultData = null
        pendingResultCode = 0
        publish(State.OFF)
    }

    /** Whether the platform thinks anything is playing — see [CaptureSilenceWatchdog]. */
    internal fun somethingIsPlaying(context: Context): Boolean =
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMusicActive == true
}
