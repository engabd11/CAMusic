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

    /** Replace the backend, releasing whatever it displaces. */
    fun install(next: Backend?) {
        current?.release()
        current = next
    }

    fun release() = install(null)

    /** Release [player] only if it is still the one making sound. */
    fun releaseIfCurrent(player: AmbienceClipPlayer) {
        if ((current as? Backend.Clip)?.player === player) release()
    }

    fun setVolume(v: Float) = when (val c = current) {
        is Backend.Synth -> c.sink.setVolume(v)
        is Backend.Clip -> c.player.setVolume(v)
        null -> {}
    }

    fun pause() = (current as? Backend.Clip)?.player?.pause() ?: Unit

    fun resume() = (current as? Backend.Clip)?.player?.resume() ?: Unit

    private fun Backend.release() = when (this) {
        is Backend.Synth -> sink.release()
        is Backend.Clip -> player.release()
    }
}
