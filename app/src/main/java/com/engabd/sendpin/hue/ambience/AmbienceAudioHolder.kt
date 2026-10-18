package com.engabd.sendpin.hue.ambience

/**
 * The sound backend of the running ambience show, owned for the life of the process.
 *
 * This used to be a private field on `EffectsViewModel`, which was wrong as soon as
 * effects gained recorded beds. A synthesised show is safe there by accident: the
 * [AudioTrackSink] is handed to [AmbienceSession], so `stopAmbience()` releases it no
 * matter who asks. A clip-backed show is not — the session runs with `sink = null` and
 * the only reference to the player was the view model's, which is Activity-scoped and
 * deliberately does *not* stop the show when cleared (a foreground service keeps it
 * alive). Swiping the app away therefore left the bed looping with nothing able to
 * reach it: the notification's Stop ran a lambda over a cancelled `viewModelScope`, and
 * a freshly built view model started with no reference at all.
 *
 * Main thread only, matching ExoPlayer's own contract — every caller is either
 * `viewModelScope` or `SendpinApp.appScope`, both of which are `Dispatchers.Main`.
 */
class AmbienceAudioHolder {

    sealed class Backend {
        class Synth(val sink: AudioTrackSink) : Backend()
        class Clip(val player: AmbienceClipPlayer) : Backend()
    }

    private var current: Backend? = null

    /** The show clock, when this backend can be one. Null for a clip or for silence. */
    val sink: AudioTrackSink? get() = (current as? Backend.Synth)?.sink

    /**
     * The analysed recording, when a clip is what is playing.
     *
     * The clip backend's counterpart to [sink]: where a synth hands the show a playhead
     * to run on, a recording hands it a position *and* a stream of what it is doing, and
     * the show reacts to that instead of inventing events of its own.
     */
    val analysis: AmbienceAudioAnalysis? get() = (current as? Backend.Clip)?.player?.analysis

    /**
     * The volume the listener asked for, and the fade the sleep timer is applying on top.
     *
     * Kept apart so a fade-out and a volume slider (or a duck) never overwrite one
     * another: what reaches the speaker is always `level * fade`.
     */
    private var level = 1f
    private var fade = 1f

    /** Replace the backend, releasing whatever it displaces. */
    fun install(next: Backend?) {
        current?.release()
        current = next
        // A new show starts at full, whatever the last one was fading to.
        fade = 1f
    }

    /** Scale the sound by [f] (1 untouched, 0 silent) without losing the chosen level. */
    fun setFade(f: Float) {
        fade = f.coerceIn(0f, 1f)
        apply()
    }

    private fun apply() = when (val c = current) {
        is Backend.Synth -> c.sink.setVolume(level * fade)
        is Backend.Clip -> c.player.setVolume(level * fade)
        null -> {}
    }

    fun release() = install(null)

    /** Release [player] only if it is still the one making sound. */
    fun releaseIfCurrent(player: AmbienceClipPlayer) {
        if ((current as? Backend.Clip)?.player === player) release()
    }

    fun setVolume(v: Float) {
        level = v
        apply()
    }

    fun pause() = (current as? Backend.Clip)?.player?.pause() ?: Unit

    fun resume() = (current as? Backend.Clip)?.player?.resume() ?: Unit

    private fun Backend.release() = when (this) {
        is Backend.Synth -> sink.release()
        is Backend.Clip -> player.release()
    }
}
