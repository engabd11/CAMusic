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
     * "My Clip" plays the user's own file as a bed with no further fallback beyond the
     * synthesised sound — the effect's script drives the lights either way, never the
     * file (see the copy in `EffectsScreen`). "Default" prefers a bundled real-recording
     * bed for [effect] when one ships as an app asset, and otherwise plays exactly the
     * synthesised sound this mode has always played — effects with no bundled asset yet
     * are unaffected by this change.
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
            // A recorded bed cannot be the show clock, so effects whose sound has
            // to line up with their lights generate it instead. See
            // AmbienceEffect.defaultSoundIsGenerated.
            else -> if (effect.defaultSoundIsGenerated) synth()
            else AmbienceAssets.bedAssetPath(app, effect)
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
            // 1. Say the output is changing hands, so the loser knows it was us.
            runCatching { app.playbackOwner.noteTakingOutput() }
            // 2. Stop the music properly rather than letting focus loss do it.
            runCatching { app.playbackOwner.pause() }

            val mode = settings.effectsSoundMode.first()
            val vol = settings.effectsVolume.first() / 100f
            val newActive = buildActiveAudio(app, effect, mode, vol)
            audio.install(newActive)

            val gate = if (newActive == null) null else AudioFocusGate(
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
                onDuck = { ducking -> audio.setVolume(if (ducking) vol * 0.2f else vol) },
            )

            // Only a Synth backend is a real AudioSink the show clock can read; a
            // Clip backend runs in parallel with sink = null, which falls the
            // light-side clock back to wall time (see AmbienceSession.nowS()).
            val sink = audio.sink

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
            audio.setVolume(v / 100f)
        }
    }

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
