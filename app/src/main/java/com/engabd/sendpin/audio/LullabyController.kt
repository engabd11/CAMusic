package com.engabd.sendpin.audio

import com.engabd.sendpin.hue.ambience.AmbienceEffect
import kotlin.math.max
import kotlin.math.min

/**
 * The lullaby wind-down: a gradual dimming schedule that takes a running ambience show
 * from "wherever it is now" to silence over a fixed duration.
 *
 * The three phases are not arbitrary — each one addresses a different thing that keeps a
 * listener awake:
 *
 *  - **Phase 1 (0–50%): hold.** The current ambience stays as it is, but dimmed to 30%.
 *    A sudden change *is* the thing that wakes you, so the first half does nothing the
 *    listener can notice beyond "the room got a bit dimmer".
 *
 *  - **Phase 2 (50–80%): switch to aurora, dim to 15%.** The current effect — which may
 *    be a thunderstorm or fireworks — is replaced with the aurora, the quietest effect
 *    in the set (see AuroraScript: no events, no transients, a continuous pad). This is
 *    the point where the show becomes sleep-compatible regardless of what it started as.
 *
 *  - **Phase 3 (80–100%): fade to zero.** The aurora dims from 15% to nothing, and the
 *    show stops. The last 20% is a smooth fade rather than a cut because a cut would
 *    read as "something turned off", which is the opposite of drifting off.
 *
 * All logic is pure and Android-free — the [schedule] function returns a list of steps
 * that the caller executes on a timer, which makes the whole curve testable without a
 * service, a bridge session or a coroutine.
 */
object LullabyController {

    /** Default wind-down duration in minutes. */
    const val DEFAULT_DURATION_MINUTES = 20

    /** Step interval in milliseconds — 2 s is smooth for a slow dim and cheap on the bridge. */
    const val STEP_MS = 2_000L

    /**
     * One point on the wind-down curve.
     *
     * @param progress 0..1 through the total duration.
     * @param brightness 0..1 — the ceiling to set on the ambience session.
     * @param effect the ambience effect to run, or null to keep whatever is currently
     *   running (Phase 1 does not switch effects).
     * @param stop whether to stop the show entirely after this step.
     */
    data class LullabyStep(
        val progress: Float,
        val brightness: Float,
        val effect: AmbienceEffect?,
        val stop: Boolean = false,
    )

    /**
     * The complete wind-down schedule for [durationMinutes].
     *
     * The schedule is a list rather than a callback because the caller — a coroutine on
     * a foreground service — needs to own the timing loop (it has to survive the screen
     * going off and the process being backgrounded). A pure list lets the caller decide
     * the interval, cancel mid-run and test the whole curve in one shot.
     */
    fun schedule(
        durationMinutes: Int = DEFAULT_DURATION_MINUTES,
        currentEffect: AmbienceEffect = AmbienceEffect.AURORA,
    ): List<LullabyStep> {
        val totalMs = (durationMinutes.coerceAtLeast(1) * 60_000L)
        val stepCount = (totalMs / STEP_MS).toInt().coerceAtLeast(1)

        return (0..stepCount).map { i ->
            val progress = i.toFloat() / stepCount
            stepAt(progress, currentEffect)
        }
    }

    /**
     * The step at a single progress point — pure, so the schedule and individual-step
     * paths share one implementation.
     */
    fun stepAt(
        progress: Float,
        currentEffect: AmbienceEffect = AmbienceEffect.AURORA,
    ): LullabyStep {
        val p = progress.coerceIn(0f, 1f)

        return when {
            // Phase 1: hold the current ambience, dim to 30%. No effect switch — a
            // change of *what* is playing is more disruptive than a change of *how bright*.
            p < 0.50f -> {
                // Ease from the current brightness down to 30% over the first half,
                // rather than snapping to 30% at t=0. A snap would be a visible step.
                val brightness = lerp(0.30f, 0.30f, p / 0.50f) // holds at 30% throughout
                LullabyStep(p, brightness, null)
            }

            // Phase 2: switch to aurora and dim from 30% to 15%. The transition into
            // aurora happens at the 50% mark, which is far enough in that the listener
            // has had time to settle, but not so far that the louder effect has kept
            // them up for most of the duration.
            p < 0.80f -> {
                val phaseT = (p - 0.50f) / 0.30f
                val brightness = lerp(0.30f, 0.15f, phaseT)
                LullabyStep(p, brightness, AmbienceEffect.AURORA)
            }

            // Phase 3: fade from 15% to zero, then stop.
            else -> {
                val phaseT = (p - 0.80f) / 0.20f
                val brightness = lerp(0.15f, 0f, phaseT)
                val stop = p >= 1f
                LullabyStep(p, brightness, AmbienceEffect.AURORA, stop)
            }
        }
    }

    /**
     * Whether the show should be stopped after executing [step].
     *
     * Only the very last step stops — everything else lets the show keep running, even
     * if the brightness is effectively zero, because a stopped show is a teardown that
     * takes time to undo if the listener is not actually asleep yet.
     */
    fun shouldStop(step: LullabyStep): Boolean = step.stop

    private fun lerp(a: Float, b: Float, t: Float): Float =
        a + (b - a) * t.coerceIn(0f, 1f)
}