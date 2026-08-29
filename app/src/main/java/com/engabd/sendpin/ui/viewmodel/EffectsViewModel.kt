package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceParams
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
    private var sink: AudioTrackSink? = null

    fun intensityOf(effect: AmbienceEffect): Float =
        intensities.value[effect.wire] ?: 0.5f

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
            val app = getApplication<Application>() as SendpinApp
            // 1. Say the output is changing hands, so the loser knows it was us.
            runCatching { app.playbackOwner.noteTakingOutput() }
            // 2. Stop the music properly rather than letting focus loss do it.
            runCatching { app.playbackOwner.pause() }

            val mode = settings.effectsSoundMode.first()
            val vol = settings.effectsVolume.first() / 100f
            val newSink = if (mode == "off") null else runCatching {
                AudioTrackSink(app).also { it.setVolume(vol) }
            }.getOrNull()
            sink?.release()
            sink = newSink

            val gate = if (newSink == null) null else AudioFocusGate(
                context = app,
                onLoss = { stop() },
                onTransientLoss = { lights.pauseAmbience() },
                onGain = { lights.resumeAmbience() },
                // A nav prompt should duck the sound, not end the show. The lights are
                // the point; the audio accompanies them.
                onDuck = { ducking -> sink?.setVolume(if (ducking) vol * 0.2f else vol) },
            )

            val ok = lights.startAmbience(
                effect = effect,
                sink = newSink,
                focus = gate,
                params = AmbienceParams(intensity = intensityOf(effect)),
                onAudioFailed = { msg -> _toast.tryEmit(msg) },
            )
            if (!ok) {
                sink?.release(); sink = null
                _toast.tryEmit("Couldn't start — check a Hue entertainment area is selected")
                return@launch
            }
            settings.setEffectsLast(effect.wire)
            EffectsService.onStopRequested = { stop() }
            EffectsService.start(app, effect.title)
            armSleepTimer()
        }
    }

    fun stop() {
        viewModelScope.launch {
            sleepJob?.cancel(); sleepJob = null
            _remainingS.value = null
            lights.stopAmbience()
            sink?.release(); sink = null
            EffectsService.onStopRequested = null
            EffectsService.stop(getApplication())
        }
    }

    fun setIntensity(effect: AmbienceEffect, value: Float) {
        viewModelScope.launch {
            settings.setEffectIntensity(effect.wire, value)
            // Only the running show needs telling; the rest read it when they start.
            if (running.value == effect.wire) {
                lights.retuneAmbience(AmbienceParams(intensity = value))
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
            sink?.setVolume(v / 100f)
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
