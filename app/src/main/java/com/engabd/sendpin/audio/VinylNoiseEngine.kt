package com.engabd.sendpin.audio

import kotlin.random.Random

/**
 * Vinyl surface noise mechanics: crackle, dust pops, rumble and hiss.
 *
 * Extracted from [VinylNoiseProcessor] so a second caller — [LoFiProcessor]'s
 * own noise texture, used when [VinylNoiseProcessor] is not also active and
 * sharing its crackle stage — can produce the same properly-shaped noise
 * instead of a separate, cruder generator. Each caller owns its own instance:
 * this class holds no shared mutable state between callers, only shared code.
 *
 * Four components:
 * - **Crackle**: sparse impulses, independent per channel.
 * - **Dust pops**: the same mechanism, rarer, louder, faster decay.
 * - **Rumble**: continuous low-passed white noise, felt more than heard.
 * - **Surface hiss**: continuous, band-limited broadband noise, independent
 *   per channel.
 *
 * A crackle or pop impulse's polarity and decay rate are chosen once, at the
 * instant it triggers, and held for the whole decay — not re-rolled every
 * sample. A coin flip per sample turns an exponential-envelope impulse into a
 * burst of white noise, which reads as static, not as a click; this is the
 * one invariant every caller of [crackle]/[pop] depends on for a soft, analog
 * texture rather than harsh digital noise.
 *
 * Callers are responsible for walking their own [ByteBuffer][java.nio.ByteBuffer]
 * and deciding which components to sum; this class only tracks per-channel
 * state and computes one sample of each component at a time.
 */
class VinylNoiseEngine(private val rng: Random = Random(System.nanoTime())) {

    /** Per-channel crackle/pop/hiss state — see the class doc for why these run independently per channel. */
    class Channel {
        var crackleCounter = 0
        var crackleThreshold = 800
        var crackleEnv = 0f
        var crackleSign = 1f
        var crackleDecay = 0.92f

        var popCounter = 0
        var popThreshold = 20_000
        var popEnv = 0f
        var popSign = 1f
        var popDecay = 0.80f

        /** Two cascaded one-pole low-passes: hiss = fast - slow, a crude band-pass. */
        var hissFast = 0f
        var hissSlow = 0f

        fun onFlush() {
            crackleCounter = 0
            popCounter = 0
            crackleEnv = 0f
            popEnv = 0f
            hissFast = 0f
            hissSlow = 0f
        }
    }

    var channels: Array<Channel> = Array(2) { Channel() }
        private set

    /**
     * Slow multiplier on crackle/pop rate, one-pole-smoothed toward a target
     * re-rolled every few seconds — the "breathing" density of a scratched or
     * dusty patch, rather than a perfectly uniform statistical rate.
     */
    private var densityMultiplier = 1f
    private var densityTarget = 1f
    private var densityCounter = 0
    private var densityRerollPeriod = 44_100 * 2
    private var densitySmoothCoeff = 0.00002f

    private var rumbleState = 0f
    private var rumbleCoeff = 0.0035f

    private var hissFastCoeff = 1f
    private var hissSlowCoeff = 1f

    /** Grows [channels] to at least [channelCount], same pattern as every other processor's `onConfigure`. */
    fun resize(channelCount: Int) {
        if (channelCount > channels.size) {
            channels = Array(channelCount) { Channel() }
        }
    }

    /** Recomputes the rumble/hiss filter coefficients for [sampleRate]. */
    fun configureFilters(
        sampleRate: Int,
        rumbleHz: Float = 50f,
        hissFastHz: Float = 9_000f,
        hissSlowHz: Float = 3_000f,
    ) {
        rumbleCoeff = VinylNoiseProcessor.lowPassCoeff(rumbleHz, sampleRate)
        hissFastCoeff = VinylNoiseProcessor.lowPassCoeff(hissFastHz, sampleRate)
        hissSlowCoeff = VinylNoiseProcessor.lowPassCoeff(hissSlowHz, sampleRate)
        // ~1s smoothing time constant for the density walk, at this sample rate.
        densitySmoothCoeff = VinylNoiseProcessor.lowPassCoeff(0.3f, sampleRate)
    }

    /** Re-seeds every channel's crackle threshold, e.g. when intensity changes. */
    fun resetCrackleThreshold(base: Int) {
        for (c in channels) c.crackleThreshold = base
    }

    /** Re-seeds every channel's pop threshold, e.g. when intensity changes. */
    fun resetPopThreshold(base: Int) {
        for (c in channels) c.popThreshold = base
    }

    /** Advances the density walk once per audio frame. Callers gate this on channel 0. */
    fun advanceFrame(sampleRate: Int) {
        densityCounter++
        if (densityCounter >= densityRerollPeriod) {
            densityCounter = 0
            densityTarget = 0.6f + rng.nextFloat() * 1.2f
            densityRerollPeriod = (sampleRate * (1f + rng.nextFloat() * 2f)).toInt()
        }
        densityMultiplier += (densityTarget - densityMultiplier) * densitySmoothCoeff
    }

    /** One sample of crackle on [channel]: sparse, Poisson-timed impulses with a fixed-per-impulse polarity. */
    fun crackle(
        channel: Int,
        intensity: Float,
        sampleRate: Int,
        amplitude: (Float) -> Float = VinylNoiseProcessor::crackleAmplitude,
        interval: (Float, Int) -> Int = VinylNoiseProcessor::crackleInterval,
    ): Float {
        val ch = channels[channel.coerceIn(0, channels.lastIndex)]
        ch.crackleCounter++
        if (ch.crackleCounter >= ch.crackleThreshold) {
            ch.crackleCounter = 0
            ch.crackleThreshold = (interval(intensity, sampleRate) * densityMultiplier *
                (0.5f + rng.nextFloat())).toInt().coerceAtLeast(1)
            ch.crackleEnv = amplitude(intensity) * (0.5f + rng.nextFloat() * 0.5f)
            // Polarity and decay are decided once, here, at the trigger — see the class doc.
            ch.crackleSign = if (rng.nextBoolean()) 1f else -1f
            ch.crackleDecay = 0.88f + rng.nextFloat() * 0.07f
        }
        ch.crackleEnv *= ch.crackleDecay
        return if (ch.crackleEnv > 0f) ch.crackleSign * ch.crackleEnv else 0f
    }

    /** One sample of a dust pop on [channel]: rarer, sharper, faster decay than [crackle]. */
    fun pop(
        channel: Int,
        intensity: Float,
        sampleRate: Int,
        amplitude: (Float) -> Float = VinylNoiseProcessor::popAmplitude,
        interval: (Float, Int) -> Int = VinylNoiseProcessor::popInterval,
    ): Float {
        val ch = channels[channel.coerceIn(0, channels.lastIndex)]
        ch.popCounter++
        if (ch.popCounter >= ch.popThreshold) {
            ch.popCounter = 0
            ch.popThreshold = (interval(intensity, sampleRate) * densityMultiplier *
                (0.5f + rng.nextFloat())).toInt().coerceAtLeast(1)
            ch.popEnv = amplitude(intensity) * (0.7f + rng.nextFloat() * 0.3f)
            ch.popSign = if (rng.nextBoolean()) 1f else -1f
            ch.popDecay = 0.65f + rng.nextFloat() * 0.20f
        }
        ch.popEnv *= ch.popDecay
        return if (ch.popEnv > 0f) ch.popSign * ch.popEnv else 0f
    }

    /** One sample of rumble: continuous low-passed white noise, shared across channels. */
    fun rumble(intensity: Float, amplitude: (Float) -> Float = VinylNoiseProcessor::rumbleAmplitude): Float {
        rumbleState = rumbleCoeff * (rng.nextFloat() * 2f - 1f) + (1f - rumbleCoeff) * rumbleState
        return rumbleState * amplitude(intensity)
    }

    /** One sample of surface hiss on [channel]: band-limited noise, independent per channel. */
    fun hiss(channel: Int, intensity: Float, amplitude: (Float) -> Float = VinylNoiseProcessor::hissAmplitude): Float {
        val ch = channels[channel.coerceIn(0, channels.lastIndex)]
        val hissNoise = rng.nextFloat() * 2f - 1f
        ch.hissFast = hissFastCoeff * hissNoise + (1f - hissFastCoeff) * ch.hissFast
        ch.hissSlow = hissSlowCoeff * ch.hissFast + (1f - hissSlowCoeff) * ch.hissSlow
        return (ch.hissFast - ch.hissSlow) * amplitude(intensity)
    }

    fun onFlush() {
        for (c in channels) c.onFlush()
        rumbleState = 0f
        densityMultiplier = 1f
        densityTarget = 1f
        densityCounter = 0
    }

    /** Shrinks [channels] back to the default, same pattern as every other processor's `onReset`. */
    fun onReset() {
        channels = Array(2) { Channel() }
    }
}
