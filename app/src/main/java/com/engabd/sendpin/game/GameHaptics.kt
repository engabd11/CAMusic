package com.engabd.sendpin.game

import android.content.Context
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The rhythm game's haptic channel: the confirmation that reaches the hand before
 * the eye, and the one reward that works with no bridge and no screen attention.
 *
 * Composition primitives where the phone has them (crisp clicks and thuds rather
 * than buzz), the old one-shot waveforms where it does not. Every call fires at
 * once; only the lamps wait for the beat.
 */
class GameHaptics(context: Context) {
    private val vibrator: Vibrator? =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            ?.takeIf { it.hasVibrator() }

    private val primitives = vibrator?.areAllPrimitivesSupported(
        Composition.PRIMITIVE_CLICK,
        Composition.PRIMITIVE_TICK,
        Composition.PRIMITIVE_THUD,
        Composition.PRIMITIVE_QUICK_RISE,
    ) == true

    var enabled = true

    /** A scored tap; [steppedUp] when the multiplier just went up. */
    fun hit(judgement: Judgement, steppedUp: Boolean) = play {
        when {
            steppedUp -> compose(Composition.PRIMITIVE_CLICK to 1f, Composition.PRIMITIVE_CLICK to 1f)
                ?: VibrationEffect.createWaveform(longArrayOf(0, 24, 60, 24), -1)
            judgement == Judgement.PERFECT -> compose(Composition.PRIMITIVE_CLICK to 1f)
                ?: VibrationEffect.createWaveform(longArrayOf(0, 28), -1)
            judgement == Judgement.GREAT -> compose(Composition.PRIMITIVE_CLICK to 0.6f)
                ?: VibrationEffect.createOneShot(16, 140)
            else -> compose(Composition.PRIMITIVE_TICK to 0.8f)
                ?: VibrationEffect.createOneShot(12, 96)
        }
    }

    /**
     * A hold started: a soft bed for [holdMs] with a spike on every beat inside it,
     * [beatMs] apart, so the held note is felt in time. One waveform for the whole
     * hold; [holdEnd] cuts it short.
     */
    fun holdStart(holdMs: Long, beatMs: Long) = play {
        val v = vibrator ?: return@play null
        if (!v.hasAmplitudeControl() || beatMs <= 0L) return@play VibrationEffect.createOneShot(holdMs.coerceIn(1L, 4_000L), 60)
        val timings = ArrayList<Long>()
        val amps = ArrayList<Int>()
        var t = 0L
        while (t < holdMs) {
            val spike = minOf(HOLD_SPIKE_MS, holdMs - t)
            timings += spike; amps += HOLD_SPIKE_AMP
            val bed = minOf(beatMs - HOLD_SPIKE_MS, holdMs - t - spike)
            if (bed > 0) { timings += bed; amps += HOLD_BED_AMP }
            t += spike + bed.coerceAtLeast(0L)
        }
        VibrationEffect.createWaveform(timings.toLongArray(), amps.toIntArray(), -1)
    }

    /** The hold ended: a crisp click when it was carried to the end, silence when dropped. */
    fun holdEnd(completed: Boolean) {
        vibrator?.cancel()
        if (completed) play { compose(Composition.PRIMITIVE_CLICK to 1f) ?: VibrationEffect.createOneShot(20, 200) }
    }

    /** Both thumbs on the drop. */
    fun slam() = play {
        compose(Composition.PRIMITIVE_THUD to 1f, Composition.PRIMITIVE_QUICK_RISE to 0.8f)
            ?: VibrationEffect.createWaveform(longArrayOf(0, 60, 30, 40), -1)
    }

    /** One beat of the count-in. */
    fun countIn() = play { compose(Composition.PRIMITIVE_TICK to 0.6f) ?: VibrationEffect.createOneShot(8, 80) }

    fun cancel() {
        vibrator?.cancel()
    }

    private fun compose(vararg parts: Pair<Int, Float>): VibrationEffect? {
        if (!primitives) return null
        val c = VibrationEffect.startComposition()
        for ((i, p) in parts.withIndex()) c.addPrimitive(p.first, p.second, if (i == 0) 0 else PRIMITIVE_GAP_MS)
        return c.compose()
    }

    private inline fun play(effect: () -> VibrationEffect?) {
        val v = vibrator ?: return
        if (!enabled) return
        runCatching { effect()?.let(v::vibrate) }
    }

    private companion object {
        const val PRIMITIVE_GAP_MS = 50
        const val HOLD_SPIKE_MS = 30L
        const val HOLD_SPIKE_AMP = 200
        const val HOLD_BED_AMP = 40
    }
}
