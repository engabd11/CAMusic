package com.engabd.sendpin.audio

import kotlin.math.pow

/**
 * Speed → volume-gain mapping, to compensate for road noise at higher speeds.
 *
 * A gain *offset* on top of the user's own volume, not a replacement for it —
 * [LocalPlayer] multiplies [gainFactor] into its existing volume chain alongside
 * ReplayGain and the fade ramp, smoothed over about a second so a speed change
 * never jumps the volume. Local playback only: [Playback]'s MA-path gain scalar
 * is explicitly reserved for audio-focus ducking (see its own doc — "must not move
 * the user's system volume, or every notification would permanently turn the phone
 * down"), and a second, unrelated multiplier competing for that same scalar risks
 * fighting that mechanism rather than the two coexisting cleanly.
 */
object SpeedAdaptiveGain {

    /** The gain offset in dB for [speedKmh], in fixed bands. */
    fun gainDb(speedKmh: Float): Float = when {
        speedKmh < 40f -> 0f
        speedKmh < 80f -> 2f
        speedKmh < 120f -> 4f
        else -> 6f
    }

    /** [gainDb] as a linear multiplier — what actually gets multiplied into volume. */
    fun gainFactor(speedKmh: Float): Float = 10f.pow(gainDb(speedKmh) / 20f)
}
