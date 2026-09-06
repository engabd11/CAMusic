package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.hue.ambience.AmbienceAssets
import com.engabd.sendpin.hue.ambience.AmbienceAudioHolder
import com.engabd.sendpin.hue.ambience.AmbienceClipPlayer
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceParams
import com.engabd.sendpin.hue.ambience.AmbienceSyncOwnership
import com.engabd.sendpin.hue.ambience.AudioFocusGate
import com.engabd.sendpin.hue.ambience.AudioTrackSink
import com.engabd.sendpin.hue.ambience.SharedOutputGate
import com.engabd.sendpin.service.EffectsService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Backs the Effects screen.
 *
 * Activity-scoped, not entry-scoped — see the note in `App.kt` about the tab bar's
 * `popUpTo(saveState = true)` clearing a destination's `ViewModelStore`. A show that
 * stopped because the user glanced at the Library tab would be a poor ambience effect.
 */
class EffectsViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        /**
         * How loud a bed is when it is sharing the room with a record.
         *
         * A fifth. Ambience beds are broadband and continuous - rain, a fire, a room
         * tone - which is exactly the signal that masks music worst at equal level.
         * Low enough to be weather rather than a second track, and the slider is still
         * there for anyone who wants more of it.
         */
        const val UNDER_MUSIC_GAIN = 0.2f
    }

    private val settings = AppSettings(app)
    private val lights = (app as SendpinApp).directLightSync

    /** Process-scoped: the show outlives this view model. See [AmbienceAudioHolder]. */
    private val audio: AmbienceAudioHolder = (app as SendpinApp).ambienceAudio

    /**
     * Serialises [start] and [stop].
     *
     * Both suspend for the length of a bridge handshake, and both read state that
     * the other writes. Without this, tapping a second effect while the first was
     * still connecting had the second call read `running.value == null` *and* the
     * `lightSyncEnabled` flag the first call had just written — recording
     * `USER_ADOPTED` for a switch the user never touched, which then left Light
     * Sync on forever once the show stopped.
     */
    private val gate = Mutex()

    /** Wire name of the running effect, straight from the engine. */
    val running: StateFlow<String?> = lights.ambienceRunning

    val soundMode: StateFlow<String> = settings.effectsSoundMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "synth")
    val volume: StateFlow<Int> = settings.effectsVolume
        .stateIn(viewModelScope, SharingStarted.Eagerly, 70)
    val intensities: StateFlow<Map<String, Float>> = settings.effectsIntensity
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val clips: StateFlow<Map<String, String>> = settings.effectsClips
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val sleepMinutes: StateFlow<Int> = settings.effectsSleepMinutes
        .stateIn(viewModelScope, SharingStarted.Eagerly, 60)

    /** Whether a show sits under the music rather than replacing it. See [startLocked]. */
    val overMusic: StateFlow<Boolean> = settings.effectsOverMusic
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * The effect the screen should open on: the last one started.
     *
     * `effectsLast` was written on every start and read by nothing, while its own doc
     * promised the screen "reopens where it was left". This is that promise, and only
     * that one — the show is **not** resumed. A 60 Hz render loop, a synth, a wake
     * lock and a foreground service coming back on their own after a glance at another
     * tab would be the opposite of an ambience feature, which is why the key's doc has
     * always also said "Not auto-resumed".
     *
     * Filtered through [AmbienceEffect.fromWire] so a wire name left behind by a
     * removed effect opens nothing rather than pointing at a tile that isn't there.
     */
    val lastEffect: StateFlow<String?> = settings.effectsLast
        .map { wire -> wire.takeIf { it.isNotBlank() && AmbienceEffect.fromWire(it) != null } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    /** Seconds a running show has left before the sleep timer stops it, or null. */
    private val _remainingS = MutableStateFlow<Int?>(null)
    val remainingS: StateFlow<Int?> = _remainingS

    private var sleepJob: Job? = null

    /**
     * >0 for the whole duration of any [start] or [stop] call this view model is
     * running, so the [running] observer below can tell "this view model is doing
     * that itself" from "the show ended some other way" — the master Light Sync
     * switch turned off directly, or the bridge session died.
     *
     * Needed because `DirectLightSync.startAmbience` always calls `stopAmbience`
     * first, even to switch straight to a different effect or restart for a sound
     * change — so `running` genuinely passes through null on every ordinary
     * [start], not just on [stop]. A depth counter, not a bool, so two overlapping
     * calls (a rapid effect switch) can't have the first call's cleanup clear a
     * guard the second call still needs.
     */
    private var mutatingSelfDepth = 0

    init {
        // A show can end without ever calling stop() on this view model: turning
        // the master switch off directly tears down the bridge session, which
        // tears down any ambience show riding on it too (see DirectLightSync.stop).
        // Without this, that path left this view model's own audio backend and
        // foreground-service notification orphaned — the lights went dark but the
        // sound kept playing and the "stop" affordance stayed up for a show that
        // had already ended.
        viewModelScope.launch {
            var previous = running.value
            running.collect { current ->
                if (previous != null && current == null && mutatingSelfDepth == 0) {
                    sleepJob?.cancel(); sleepJob = null
                    _remainingS.value = null
                    audio.release()
                    EffectsService.onStopRequested = null
                    EffectsService.stop(getApplication())
                    (getApplication<Application>() as SendpinApp).ambienceSyncOwnership.value =
                        AmbienceSyncOwnership.NONE
                }
                previous = current
            }
        }
    }

    fun intensityOf(effect: AmbienceEffect): Float =
        intensities.value[effect.wire] ?: 0.5f

    /**
     * The sound backend for [effect] given the current `effectsSoundMode`, or null for
     * a silent show ("Off", or a failed clip/synth with nothing left to fall back to).
     *
     * "Default" plays the bundled real-recording bed for [effect] when one ships as an
     * app asset, and falls back to the synthesised sound only when none does. Every
     * effect ships one today, so the synth is the fallback rather than the norm — which
     * is the right way round: a storm recorded by a microphone is better than anything
     * this app can synthesise, and now that the lights are driven by *analysis* of
     * whatever is playing, keeping the recording costs the show nothing.
     *
     * "My Clip" plays the user's own file instead, on exactly the same terms. The
     * lights follow that file too — the script reacts to the audio it is given, not to
     * the one it expected — which is what the Effects screen now says.
     */
    private suspend fun buildActiveAudio(
        app: SendpinApp,
        effect: AmbienceEffect,
        mode: String,
        vol: Float,
    ): AmbienceAudioHolder.Backend? {
        fun synth(): AmbienceAudioHolder.Backend? =
            runCatching { AudioTrackSink(app).also { it.setVolume(vol) } }
                .getOrNull()?.let { AmbienceAudioHolder.Backend.Synth(it) }

        fun clip(uri: Uri): AmbienceAudioHolder.Backend.Clip? {
            // `player` (nullable var) exists only so the error callback can refer to
            // the instance it belongs to — a local val can't appear in its own
            // initializer. `p` is what every immediate call below actually uses.
            var player: AmbienceClipPlayer? = null
            val p = AmbienceClipPlayer(app) {
                _toast.tryEmit("Couldn't play that sound — showing lights only")
                player?.let(audio::releaseIfCurrent)
            }
            player = p
            return runCatching { p.start(uri, vol) }
                .fold({ AmbienceAudioHolder.Backend.Clip(p) }, { p.release(); null })
        }

        return when (mode) {
            "off" -> null
            "clip" -> settings.effectsClips.first()[effect.wire]
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?.let { clip(it) } ?: synth()
            else -> AmbienceAssets.bedAssetPath(app, effect)
                ?.let { clip(Uri.parse("asset:///$it")) } ?: synth()
        }
    }

    /**
     * Start [effect], stopping whatever was running.
     *
     * Order matters, and it is the order the in-process focus hazard demands. The other
     * backends are stopped **explicitly** before focus is requested, rather than being
     * left to the platform's eviction: `LocalPlayer` documents that an in-process
     * `AUDIOFOCUS_GAIN` evicts the Sendspin path, which reads the eviction as another
     * app taking over and releases its engine. Announcing the handover first turns that
     * into an ordinary internal switch.
     *
     * **Unless the music is meant to keep playing.** `effectsOverMusic` is on by
     * default, and when something is already making sound it takes the other branch
     * entirely: nothing is paused, no focus is requested (see [SharedOutputGate]) and
     * the bed is mixed in at [UNDER_MUSIC_GAIN] so it sits beneath the record instead
     * of over it. Rain under an album, a storm under a film score - the reason anybody
     * wanted a room with its own sound in the first place.
     */
    fun start(effect: AmbienceEffect) {
        viewModelScope.launch {
            gate.withLock { startLocked(effect) }
        }
    }

    private suspend fun startLocked(effect: AmbienceEffect) {
        mutatingSelfDepth++
        try {
            val app = getApplication<Application>() as SendpinApp
            // Whether this show shares the output with something already playing.
            // Read once, here, and used three times below: sampling it again later
            // could have the show take focus it had already decided not to, or duck a
            // bed that is running at full level.
            //
            // `anyPlaying` as well as the setting, because with nothing playing there
            // is nothing to sit under - a show that then quietly declined focus and
            // ran at a fifth of its level would simply sound broken.
            val shareOutput = settings.effectsOverMusic.first() &&
                app.playbackOwner.state.value.anyPlaying
            if (!shareOutput) {
                // 1. Say the output is changing hands, so the loser knows it was us.
                runCatching { app.playbackOwner.noteTakingOutput() }
                // 2. Stop the music properly rather than letting focus loss do it.
                runCatching { app.playbackOwner.pause() }
            }

            val mode = settings.effectsSoundMode.first()
            // Under the music, the bed is a bed. At the level the slider names it is
            // the loudest thing in the room, which is right for a show playing alone
            // and wrong for one accompanying a record.
            sharedGain = if (shareOutput) UNDER_MUSIC_GAIN else 1f
            val vol = settings.effectsVolume.first() / 100f * sharedGain
            liveLevel = vol
            val newActive = buildActiveAudio(app, effect, mode, vol)
            audio.install(newActive)

            // Null for a silent show, which has nothing to hold focus for, and null
            // again for one sharing the output, which deliberately asks for none.
            val exclusive = if (newActive == null || shareOutput) null else AudioFocusGate(
                context = app,
                onLoss = { stop() },
                onTransientLoss = {
                    lights.pauseAmbience()
                    audio.pause()
                },
                onGain = {
                    lights.resumeAmbience()
                    audio.resume()
                },
                // A nav prompt should duck the sound, not end the show. The lights
                // are the point; the audio accompanies them.
                //
                // The duck level is the *current* level, not `vol`, which is what the
                // slider said when the show started. Capturing it here meant: duck,
                // drag the level slider, unduck - and the volume snapped back to the
                // drag's start value, silently discarding what the user had chosen.
                // duckLevel is a plain volatile mirror kept in step by setVolume()
                // below, so the focus listener - which is an ordinary callback on a
                // binder thread, not a coroutine - reads it with no suspension.
                onDuck = { ducking ->
                    val now = if (ducking) liveLevel * 0.2f else liveLevel
                    audio.setVolume(now)
                },
            )
            // A show playing over the music still needs a gate - the session takes one
            // and refuses to start without a grant - it just needs one that grants
            // without evicting anybody. See [SharedOutputGate] for what that gives up.
            val gate = exclusive ?: SharedOutputGate.takeIf { newActive != null && shareOutput }

            // Exactly one of these is ever non-null, and which one decides what the
            // show is clocked on and where its events come from.
            //
            // A Synth backend is a real AudioSink: its playhead is the clock and the
            // script invents the events. A Clip backend is a recording being analysed:
            // its own position in the file is the clock and the recording is the event
            // source. A silent show has neither and falls back to wall time.
            val sink = audio.sink
            val analysis = audio.analysis

            // startAmbience self-opens the bridge session if sync is off, but
            // never told the master switch that — so the switch stayed visually
            // off under a running show, and turning it on and back off later
            // could kill a show it never knew it owned. Own that here instead:
            // turn sync on if it was off, and remember whether this show is the
            // reason, so stop() knows whether turning it back off again is its
            // call to make.
            //
            // Only on a genuine start from idle — `startAmbience` always stops
            // and restarts the session internally (a sound-mode change or
            // switching straight to a different effect calls this same function
            // while one is already running), and re-deciding ownership on every
            // one of those would read "sync is already on" and wrongly adopt a
            // show that never stopped.
            val fromIdle = running.value == null
            val syncWasOn = settings.lightSyncEnabled.first()

            val ok = lights.startAmbience(
                effect = effect,
                sink = sink,
                focus = gate,
                params = AmbienceParams(
                    intensity = intensityOf(effect),
                    brightness = settings.lightSyncBrightness.first() / 100f,
                ),
                onAudioFailed = { msg -> _toast.tryEmit(msg) },
                analysis = analysis,
            )
            if (!ok) {
                audio.release()
                _toast.tryEmit("Couldn't start — check a Hue entertainment area is selected")
                return
            }

            // Written only now that the session is actually up. Doing it before
            // `startAmbience` let the settings collector in `SendpinApp` see the
            // flag flip and call `DirectLightSync.start()` concurrently with the
            // one `startAmbience` makes internally — and `start()` guards on a
            // plain `running.get()` that stays false until an HTTPS round trip
            // and a DTLS handshake have both completed, so both calls got
            // through. Two `action: start` PUTs on one entertainment area is the
            // state that file documents as poisoning the session for good.
            if (fromIdle) {
                if (syncWasOn) {
                    app.ambienceSyncOwnership.value = AmbienceSyncOwnership.USER_ADOPTED
                } else {
                    app.ambienceSyncOwnership.value = AmbienceSyncOwnership.AUTO_ENABLED
                    settings.setLightSyncEnabled(true)
                }
            }
            settings.setEffectsLast(effect.wire)
            EffectsService.onStopRequested = { app.stopAmbienceShow() }
            EffectsService.start(app, effect.title)
            armSleepTimer()
        } finally {
            mutatingSelfDepth--
        }
    }

    fun stop() {
        viewModelScope.launch {
            gate.withLock { stopLocked() }
        }
    }

    private suspend fun stopLocked() {
        mutatingSelfDepth++
        try {
            sleepJob?.cancel(); sleepJob = null
            _remainingS.value = null
            lights.stopAmbience()
            audio.release()
            val app = getApplication<Application>() as SendpinApp
            // Only turn sync back off if this show is the reason it was on —
            // never for a show that started with sync already on for its own
            // reasons.
            if (app.ambienceSyncOwnership.value == AmbienceSyncOwnership.AUTO_ENABLED) {
                settings.setLightSyncEnabled(false)
            }
            app.ambienceSyncOwnership.value = AmbienceSyncOwnership.NONE
            EffectsService.onStopRequested = null
            EffectsService.stop(getApplication())
        } finally {
            mutatingSelfDepth--
        }
    }

    fun setIntensity(effect: AmbienceEffect, value: Float) {
        viewModelScope.launch {
            settings.setEffectIntensity(effect.wire, value)
            // Only the running show needs telling; the rest read it when they start.
            if (running.value == effect.wire) {
                lights.retuneAmbience(
                    AmbienceParams(
                        intensity = value,
                        brightness = settings.lightSyncBrightness.first() / 100f,
                    ),
                )
            }
        }
    }

    fun setSoundMode(mode: String) {
        viewModelScope.launch {
            settings.setEffectsSoundMode(mode)
            // The sink is built at start time, so a live show has to be restarted for a
            // sound change to mean anything. Restarting is also honest: the show clock
            // is the audio playhead, and swapping that under a running session would be
            // a different and much worse kind of surprise.
            AmbienceEffect.fromWire(running.value)?.let { start(it) }
        }
    }

    fun setVolume(v: Int) {
        viewModelScope.launch {
            settings.setEffectsVolume(v)
            // Scaled by the same factor the show started with, so dragging the slider
            // during a show that is sitting under a record does not lift it back out.
            val level = v / 100f * sharedGain
            audio.setVolume(level)
            // The duck mirror: whatever the slider last set is what un-ducking
            // restores, however long the show has been running.
            liveLevel = level
        }
    }

    /**
     * Choose whether a show plays over the music or instead of it.
     *
     * A running show is restarted, for the same reason a sound-mode change restarts
     * one: which focus gate it holds, and whether the music was paused, are both
     * decided at start time and cannot be changed under a live session.
     */
    fun setOverMusic(on: Boolean) {
        viewModelScope.launch {
            settings.setEffectsOverMusic(on)
            AmbienceEffect.fromWire(running.value)?.let { start(it) }
        }
    }

    /**
     * The listener's chosen level, mirrored out of [setVolume] so the audio-focus
     * listener can read it without suspending. Initialised from the persisted
     * setting at show start, which is exactly what the old captured `vol` was -
     * the difference is that this one keeps moving when the slider does.
     */
    @Volatile private var liveLevel: Float = 0.7f

    /**
     * [UNDER_MUSIC_GAIN] while the running show is sharing the output, else 1.
     *
     * Held rather than recomputed, because the question it answers is "what did this
     * show start as" and the answer must not change when the music stops halfway
     * through - a bed swelling to full level the moment a record ended would be the
     * most startling thing in the app.
     */
    @Volatile private var sharedGain: Float = 1f

    fun setSleepMinutes(m: Int) {
        viewModelScope.launch {
            settings.setEffectsSleepMinutes(m)
            if (running.value != null) armSleepTimer()
        }
    }

    /**
     * Persist a clip the listener picked, keeping read access across reboots.
     *
     * Without `takePersistableUriPermission` the grant dies with the activity, and the
     * clip would work once and then silently fall back to the synth.
     */
    fun setClip(effect: AmbienceEffect, uri: android.net.Uri?) {
        viewModelScope.launch {
            if (uri != null) {
                val ok = runCatching {
                    getApplication<Application>().contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }.isSuccess
                if (!ok) {
                    _toast.tryEmit("Couldn't keep access to that file")
                    return@launch
                }
            }
            settings.setEffectClip(effect.wire, uri?.toString())
        }
    }

    private fun armSleepTimer() {
        sleepJob?.cancel()
        val minutes = sleepMinutes.value
        if (minutes <= 0) { _remainingS.value = null; return }
        sleepJob = viewModelScope.launch {
            var left = minutes * 60
            while (left > 0) {
                _remainingS.value = left
                delay(1_000)
                left--
            }
            _remainingS.value = null
            stop()
        }
    }

    override fun onCleared() {
        // Deliberately does *not* stop the show. This view model is Activity-scoped, so
        // it clears when the Activity is finished for good — at which point the
        // foreground service is what keeps the show alive, and tearing it down here
        // would make rotating the phone end the effect.
        super.onCleared()
    }
}
