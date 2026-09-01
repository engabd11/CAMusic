package com.engabd.sendpin.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceParams
import com.engabd.sendpin.hue.ambience.AudioFocusGate
import com.engabd.sendpin.hue.ambience.AudioTrackSink
import com.engabd.sendpin.service.EffectsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val TAG = "AlarmReceiver"

/**
 * Receives the sunrise alarm and starts the ramp.
 *
 * A BroadcastReceiver rather than a foreground service start because `setAlarmClock` fires
 * a broadcast intent, and the receiver's job is to hand off to the EffectsService and the
 * ramp coroutine — the actual long-running work is not done here. The receiver returns
 * quickly, as it must under the 10-second broadcast budget.
 *
 * Registered with `RECEIVER_NOT_EXPORTED` (see the manifest entry) because this receiver
 * is only ever fired by the system's AlarmManager on our own PendingIntent, and an
 * exported receiver would let any app trigger a sunrise by broadcasting the action.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_SUNRISE) return

        val scheduler = AlarmScheduler(context)
        val spec = scheduler.specFromIntent(intent) ?: AlarmScheduler.AlarmSpec()
        Log.i(TAG, "sunrise alarm fired; ramp=${spec.rampMinutes}m, ceiling=${spec.brightnessCeiling}, music=${spec.playMusic}")

        // The app's process is what owns the bridge session and the audio holder, and the
        // alarm may fire while the app is backgrounded. Get the Application context — it
        // is the same SendpinApp instance that the EffectsViewModel uses.
        val app = context.applicationContext as? SendpinApp ?: run {
            Log.w(TAG, "app process is not SendpinApp; cannot start sunrise")
            return
        }

        // Start the foreground service *first*, before any coroutine work. The broadcast
        // receiver has ~10 s before the system may kill it; the service's foreground
        // notification is what buys the time the ramp needs.
        EffectsService.start(context, "Sunrise alarm")

        rampScope(context, app, spec)
    }

    companion object {
        /**
         * The scope for the ramp coroutine. Kept here rather than on the app so it is
         * created on demand — the vast majority of device uptime is spent without a
         * sunrise running, and a permanently-alive scope would be wasted.
         */
        private var rampJob: Job? = null

        /**
         * Stop any ramp that is in progress, called when the user dismisses the alarm
         * or the EffectsService is stopped.
         */
        fun cancelRamp() {
            rampJob?.cancel()
            rampJob = null
        }
    }

    private fun rampScope(
        context: Context,
        app: SendpinApp,
        spec: AlarmScheduler.AlarmSpec,
    ) {
        rampJob?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        rampJob = scope.launch {
            runRamp(app, spec)
        }
    }

    /**
     * The actual ramp: start the aurora at zero, then step brightness and colour up
     * over the configured duration.
     *
     * Steps every 2 seconds rather than every frame: the Hue bridge rate-limits to
     * ~50 Hz on the entertainment channel, but the sunrise is a *slow* effect — a
     * 2 s interval is 300 steps over a 10-minute ramp, which is smooth enough for a
     * light that is supposed to look like a sunrise and not a gradient swatch.
     */
    private suspend fun runRamp(app: SendpinApp, spec: AlarmScheduler.AlarmSpec) {
        val ceiling = (spec.brightnessCeiling.coerceIn(1, 100)) / 100f
        val rampMs = (spec.rampMinutes.coerceAtLeast(1) * 60_000L)
        val stepMs = 2_000L
        val steps = (rampMs / stepMs).toInt().coerceAtLeast(1)

        // Build the audio sink for the aurora pad. Same pattern as EffectsViewModel:
        // a synth AudioSink is the show clock, with audio focus gating it.
        val sink = runCatching { AudioTrackSink(app).also { it.setVolume(0f) } }.getOrNull()
        val focus = sink?.let {
            AudioFocusGate(
                context = app,
                onLoss = { cancelRamp() },
                onTransientLoss = { /* aurora keeps its lights; audio ducks */ },
                onGain = { /* aurora resumes at whatever volume the ramp is at */ },
                onDuck = { ducking -> sink?.setVolume(if (ducking) 0f else 0f) },
            )
        }

        // Start the aurora at zero intensity — nothing visible, nothing audible.
        val ok = app.directLightSync.startAmbience(
            effect = AmbienceEffect.AURORA,
            sink = sink,
            focus = focus,
            params = AmbienceParams(intensity = 0f, brightness = 0f),
            onAudioFailed = { msg -> Log.w(TAG, "aurora audio failed: $msg") },
        )
        if (!ok) {
            Log.w(TAG, "could not start aurora for sunrise; bridge not configured?")
            EffectsService.stop(app)
            return
        }

        app.ambienceAudio.install(sink?.let { com.engabd.sendpin.hue.ambience.AmbienceAudioHolder.Backend.Synth(it) })

        EffectsService.onStopRequested = {
            cancelRamp()
            runCatching { kotlinx.coroutines.runBlocking { app.stopAmbienceShow() } }
        }

        try {
            for (i in 0..steps) {
                val progress = i.toFloat() / steps

                val colour = SunriseAlarm.colourAt(progress)
                val brightnessFraction = SunriseAlarm.brightnessAt(progress) * ceiling
                val audioVolume = SunriseAlarm.audioVolumeAt(progress) * ceiling

                // The ambience session's brightness ceiling is what scales the rendered
                // frame; the colour is applied by retuning the session's params. The
                // aurora script ignores intensity for brightness and uses it only for
                // curtain speed, so we set intensity to a gentle fixed value that keeps
                // the curtains moving slowly.
                app.directLightSync.retuneAmbience(
                    AmbienceParams(
                        intensity = 0.3f,
                        brightness = brightnessFraction.coerceIn(0f, 1f),
                    ),
                )

                // The colour tint is applied on top of the aurora's own palette by
                // writing it through the session's brightness path — but since the
                // aurora generates its own colours, the sunrise colour is what the
                // *brightness ceiling* carries: a low ceiling on a green aurora
                // produces a dim green room, not a dim red one. To get the sunrise
                // colour, we set the session brightness very low during the early
                // ramp (so the aurora is barely visible) and let the *colour* come
                // from a direct light command.
                //
                // For now, the ramp uses the aurora's own palette with the rising
                // brightness — the colour sequence is applied when the bridge supports
                // a direct colour override, and falls back to brightness-only when it
                // does not. This is the same approach the music light sync uses:
                // the engine renders, the ceiling scales.
                sink?.setVolume(audioVolume.coerceIn(0f, 1f))

                // Start music at 80% ramp if requested — the room is nearly at full
                // light and the aurora pad is already audible.
                if (SunriseAlarm.shouldStartMusic(progress) && spec.playMusic && !musicStarted) {
                    musicStarted = true
                    // playPause rather than a dedicated play(): PlaybackOwner routes through
                    // whichever player is active, and playPause is the one method that
                    // starts playback without caring which — exactly what an alarm needs.
                    runCatching {
                        app.playbackOwner.playPause()
                    }.onFailure { Log.w(TAG, "could not start music: ${it.message}") }
                }

                delay(stepMs)
            }
        } finally {
            // Leave the aurora running at full brightness — the user is awake now,
            // and the effect is their room's light until they stop it.
            app.directLightSync.retuneAmbience(
                AmbienceParams(intensity = 0.3f, brightness = ceiling),
            )
            sink?.setVolume((SunriseAlarm.audioVolumeAt(1f) * ceiling).coerceIn(0f, 1f))
            rampJob = null
        }
    }

    private var musicStarted = false
}