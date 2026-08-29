package com.engabd.sendpin.hue.ambience

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The small synthesis kit the ambience scripts are built from.
 *
 * Everything here is allocation-free after construction and single-threaded by
 * contract — one instance per voice, owned by the generator coroutine. Nothing is
 * shared, so nothing needs a lock.
 *
 * ## Denormals
 *
 * Long-tailed IIR filters fed near-silence eventually produce numbers small enough to be
 * denormal, which on some ARM configurations cost tens of times a normal multiply. An
 * ambience bed is *exactly* that case: quiet, continuous, and running for an hour. Every
 * filter here flushes below [DENORMAL] instead. Easy to leave out, miserable to diagnose
 * afterwards — the symptom is an effect that gets progressively more expensive the
 * longer it runs.
 */
internal const val DENORMAL = 1e-15f

internal fun flush(x: Float): Float = if (abs(x) < DENORMAL) 0f else x

/**
 * A deterministic noise source, seeded per event.
 *
 * xorshift64* rather than `java.util.Random`: it is a handful of instructions with no
 * synchronisation, which matters at 48000 calls a second, and it is reproducible from a
 * seed so a script's audio can be tested by re-rendering it.
 */
internal class Rng(seed: Long) {
    private var s: Long = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed

    fun nextLong(): Long {
        s = s xor (s ushr 12); s = s xor (s shl 25); s = s xor (s ushr 27)
        return s * -7046029254386353131L
    }

    /** Uniform 0..1. */
    fun f01(): Float = ((nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat()

    /** Uniform -1..1 — the white-noise sample. */
    fun bipolar(): Float = f01() * 2f - 1f

    /** Uniform 0..1 biased towards 0, so "near" events are rarer than "far" ones. */
    fun pow2(): Float = f01().let { it * it }

    fun nextInt(bound: Int): Int = if (bound <= 0) 0 else (f01() * bound).toInt().coerceIn(0, bound - 1)
}

/**
 * Pink-ish noise by the Voss-McCartney method.
 *
 * Rain, wind and the sea are all closer to pink than to white — white noise sounds like
 * a hiss from a broken speaker, pink like weather. Seven octave generators is the usual
 * compromise: spectrally flat enough per octave to be convincing, cheap enough to run
 * several of them at once.
 */
internal class PinkNoise(seed: Long) {
    private val rng = Rng(seed)
    private val rows = FloatArray(7)
    private var runningSum = 0f
    private var counter = 0

    fun next(): Float {
        counter++
        var n = counter
        var i = 0
        while (i < rows.size && (n and 1) == 0) { n = n shr 1; i++ }
        if (i < rows.size) {
            runningSum -= rows[i]
            rows[i] = rng.bipolar()
            runningSum += rows[i]
        }
        return flush((runningSum + rng.bipolar()) * 0.14f)
    }
}

/** Brown noise — integrated white, leaky so it cannot wander off. Rumble and thunder. */
internal class BrownNoise(seed: Long) {
    private val rng = Rng(seed)
    private var last = 0f

    fun next(): Float {
        last = flush(last * 0.995f + rng.bipolar() * 0.05f)
        return (last * 6f).coerceIn(-1f, 1f)
    }
}

/** One-pole low-pass. The cheapest thing that makes noise sound like it is behind something. */
internal class OnePole {
    private var z = 0f
    private var a = 0.5f

    /** Recomputed only when the cutoff moves, since exp() is not free. */
    fun setCutoff(hz: Float, sampleRate: Int) {
        a = 1f - exp(-2f * PI.toFloat() * hz.coerceIn(1f, sampleRate / 2.2f) / sampleRate)
    }

    fun lp(x: Float): Float { z = flush(z + a * (x - z)); return z }
    fun hp(x: Float): Float = x - lp(x)
    fun reset() { z = 0f }
}

/**
 * A state-variable filter — band-pass with a resonance control.
 *
 * The Chamberlin topology, which is stable enough to sweep the cutoff every sample. That
 * is the point: a thunder crack is a band-pass whose centre falls from 3 kHz to a couple
 * of hundred hertz over about forty milliseconds, and a filter that had to be
 * re-coefficiented carefully could not do that.
 */
internal class Svf {
    private var low = 0f
    private var band = 0f
    private var f = 0.1f
    private var q = 1f

    fun set(cutoffHz: Float, resonance: Float, sampleRate: Int) {
        val fc = cutoffHz.coerceIn(20f, sampleRate / 2.5f)
        f = (2f * sin(PI.toFloat() * fc / sampleRate)).coerceIn(0.001f, 1.2f)
        q = (1f / resonance.coerceIn(0.5f, 20f)).coerceIn(0.05f, 2f)
    }

    /** Band-pass output. */
    fun bp(x: Float): Float {
        val high = x - low - q * band
        band = flush(band + f * high)
        low = flush(low + f * band)
        return band
    }

    /** Low-pass output, for the same input. */
    fun lp(x: Float): Float {
        val high = x - low - q * band
        band = flush(band + f * high)
        low = flush(low + f * band)
        return low
    }

    fun reset() { low = 0f; band = 0f }
}

/**
 * Grains of noise fired at a rate — crackle, bubbles, rain on glass.
 *
 * A crackling fire is not filtered noise, it is a Poisson process of very short bursts,
 * and the difference is immediately audible: filtered noise is a hiss, grains are a fire.
 */
internal class GrainCloud(seed: Long, private val sampleRate: Int) {
    private val rng = Rng(seed)
    private val svf = Svf()
    private var remaining = 0
    private var length = 1
    private var age = 0
    private var amp = 0f
    private var nextIn = 0

    /** @param rateHz grains per second. @param centreHz where each grain sits. */
    fun next(rateHz: Float, centreHz: Float, lengthMs: Float): Float {
        if (remaining <= 0) {
            if (nextIn > 0) { nextIn--; return 0f }
            // Exponential gaps: the defining property of a Poisson process, and what
            // stops the crackle sounding metronomic.
            val mean = sampleRate / rateHz.coerceAtLeast(0.01f)
            nextIn = (-kotlin.math.ln(rng.f01().coerceAtLeast(1e-6f)) * mean).toInt().coerceAtLeast(1)
            length = (lengthMs * sampleRate / 1000f * (0.5f + rng.f01())).toInt().coerceAtLeast(4)
            remaining = length
            age = 0
            amp = 0.25f + 0.75f * rng.pow2()
            svf.reset()
            svf.set(centreHz * (0.6f + 1.2f * rng.f01()), 3.5f, sampleRate)
        }
        remaining--
        age++
        // A short exponential decay per grain: an attack that is a step and a tail that
        // is not is what reads as a "tick" rather than a "blip".
        val env = exp(-age.toFloat() / (length * 0.35f))
        return svf.bp(rng.bipolar()) * env * amp
    }
}

/**
 * Equal-power pan.
 *
 * `sqrt`, not linear: a linear pan loses about 3 dB in the middle, so an event sweeping
 * across the room would audibly dip as it passed the centre — which for a light train is
 * precisely where the listener is watching.
 */
internal inline fun panGains(pan: Float, out: FloatArray) {
    val p = ((pan.coerceIn(-1f, 1f)) + 1f) * 0.5f
    out[0] = sqrt(1f - p)
    out[1] = sqrt(p)
}

/** Gentle saturation. Keeps a stack of events inside the rails without a hard clip. */
internal inline fun softClip(x: Float): Float =
    if (x > 1.5f) 1f else if (x < -1.5f) -1f else x - (x * x * x) / 6.75f

/** A slow sine for breathing beds, in turns. */
internal inline fun lfo(phase: Float): Float = sin(2f * PI.toFloat() * phase)

/** Linear interpolation, used everywhere for per-block envelope stepping. */
internal inline fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** A one-pole coefficient for a time constant, for smoothing control values. */
internal fun smoothingCoeff(tauS: Float, sampleRate: Int): Float =
    1f - exp(-1f / (tauS.coerceAtLeast(1e-4f) * sampleRate))

/** Unused today but the natural partner to [Svf.set] for shelf voicing. */
internal fun prewarp(hz: Float, sampleRate: Int): Float =
    tan(PI.toFloat() * hz.coerceIn(1f, sampleRate / 2.2f) / sampleRate)

/**
 * Per-event voice allocation.
 *
 * Filters and noise generators carry state, so sharing one across concurrent events
 * makes the output depend on the order they happen to be processed in — and that order
 * changes with the audio buffer size, because a block covering two events processes them
 * A-then-B while two half-blocks process A, B, A, B. The result was a show that sounded
 * subtly different at 512 frames than at 1024, which is both untestable and, on a device
 * that renegotiates its buffer, audible as a change with no cause.
 *
 * Giving each live event its own voice is what every hardware synth does, and it makes
 * the render a pure function of the events again. The pool is fixed-size and allocated
 * once: an effect with more simultaneous events than slots recycles the oldest, which is
 * inaudible at that density and far better than allocating on the audio thread.
 */
internal class VoicePool<T : Any>(size: Int, make: () -> T) {
    private val owners = arrayOfNulls<Any>(size)
    private val voices = List(size) { make() }
    private var next = 0

    /**
     * The voice belonging to [key], allocating one on first sight.
     *
     * [reset] is called only when a slot is newly assigned, so a voice keeps its state
     * for the whole life of its event and starts clean for the next one.
     */
    fun voiceFor(key: Any, reset: (T) -> Unit): T {
        for (i in owners.indices) if (owners[i] === key) return voices[i]
        val slot = next
        next = (next + 1) % owners.size
        owners[slot] = key
        reset(voices[slot])
        return voices[slot]
    }

    fun clear() {
        java.util.Arrays.fill(owners, null)
        next = 0
    }
}
