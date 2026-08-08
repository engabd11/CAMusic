package com.engabd.sendpin.hue

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A "flash" half-transition must move the field luminance by at least this much
 * of full scale to count, per the WCAG 10%-of-max threshold.
 */
const val FLASH_DELTA = 0.10f

/** Hard ceiling on full-field flashes in any rolling one-second window. */
const val MAX_FLASHES_PER_S = 3

/**
 * The club rungs run a *relaxed* limiter rather than none at all. Eight flashes
 * a second is a beat flash at 480 BPM, so it is transparent to the
 * hardest-hitting real music while still capping pathological strobing —
 * broken analysis, adversarial input, a stuck oscillation. It is **not** a
 * WCAG-compliant level.
 */
const val RELAXED_MAX_FLASHES_PER_S = 8

/**
 * The physical ceiling on visible effect rate, from the Hue Entertainment API
 * best practices: the bridge relays at most 25 Hz over Zigbee, so the fastest
 * effect rate should be "2–3 times slower than this 25 Hz, i.e. < 12.5 Hz".
 * Beyond it the bridge coalesces changes and the bulbs simply cannot follow, so
 * exceeding it buys nothing and only risks the strobing the spec warns about.
 *
 * Unlike the flash budget this is a hardware fact rather than a comfort
 * setting, so [EffectRateLimiter] applies it even on the paths that bypass
 * [FieldSafety].
 */
const val PHYSICAL_MAX_EFFECT_HZ = 12.5f

/** Saturated red carries a stricter threshold; allow far fewer red flashes. */
private const val MAX_RED_FLASHES_PER_S = 1
private const val RED_SATURATION = 0.55f

private const val WINDOW_S = 1.0f

/** Slow anchor (~0.5 s) the field is pinned toward while limiting. */
private const val EMA_ALPHA = 0.04f

/** Compression engages instantly once the budget is exceeded… */
private const val ENGAGE_RATE = 1.0f

/** …and releases slowly, so it cannot oscillate back into strobing. */
private const val RELEASE_RATE = 0.02f

private const val ACTIVITY_DECAY = 0.92f
private const val ACTIVITY_CALM = 0.04f
private const val DIR_EPS = 1e-4f

/** Perceived whole-field brightness: the mean of each light's max channel. */
private fun fieldBrightness(colors: Map<Int, Rgb>): Float {
    if (colors.isEmpty()) return 0f
    var sum = 0f
    for (c in colors.values) sum += max(c.first, max(c.second, c.third))
    return sum / colors.size
}

/** 0..1 red dominance of the lit field — red far above green and blue. */
private fun fieldRedness(colors: Map<Int, Rgb>): Float {
    if (colors.isEmpty()) return 0f
    var total = 0f
    var lit = 0f
    for ((r, g, b) in colors.values) {
        val m = max(r, max(g, b))
        if (m <= 1e-4f) continue
        lit += m
        total += m * max(0f, (r - max(g, b)) / (r + 1e-6f))
    }
    return if (lit > 1e-6f) total / lit else 0f
}

/**
 * Final-stage eye-safety limiter — the last thing every frame passes through.
 *
 * Audio-reactive lighting filling a large share of the visual field can, on
 * aggressive content, produce rapid whole-room brightness swings. WCAG 2.3.1
 * ("Three Flashes or Below Threshold", derived from broadcast
 * photosensitive-epilepsy limits) caps a flashing field at three flashes per
 * second, where a flash is a pair of opposing luminance changes of at least 10%
 * of max, and treats saturated red more strictly still.
 *
 * This matters more here than in syncoV2. Streaming straight to the bridge means
 * there is no Home Assistant between the engine and the bulbs, so this class is
 * the only thing standing between a bad analysis frame and the room.
 *
 * Intense runs a *relaxed* instance ([RELAXED_MAX_FLASHES_PER_S]) that never
 * engages on real music but still caps true strobe output. Extreme bypasses this
 * limiter entirely at the user's explicit request — that decision lives in the
 * caller, not here, and [EffectRateLimiter] still binds it. Both remain
 * unsuitable for photosensitive viewers.
 *
 * When engaged it is deliberately transparent on well-behaved content, acting
 * only once the aggregate field actually starts to strobe, and degrades by
 * compressing the *global* brightness swing while preserving each light's colour
 * and the spatial pattern between lights — which is what makes the show look
 * good in the first place.
 *
 * Ported from syncoV2 `effects/safety.py`. One instance per session; pure and
 * dt-driven, so the invariants are unit-testable directly.
 *
 * @param calmGated the strict default also *engages* on hard-swinging content
 *   before the budget is spent and refuses to release until the content calms —
 *   the conservative WCAG posture. The relaxed club-mode limiter passes false so
 *   engagement keys purely off the flash budget.
 */
class FieldSafety(
    private val maxFlashes: Int = MAX_FLASHES_PER_S,
    private val delta: Float = FLASH_DELTA,
    private val calmGated: Boolean = true,
) {
    private var t = 0f
    private var ema: Float? = null
    private var prevField: Float? = null
    private var lastExtreme = 0f
    private var dir = 0
    private var half = 0
    private val flashes = ArrayDeque<Float>()

    /** 1 = transparent, 0 = field pinned to its slow average. */
    private var comp = 1f
    private var prevRaw: Float? = null

    /** How hard the incoming field is currently swinging. */
    private var activity = 0f

    fun reset() {
        ema = null
        prevField = null
        dir = 0
        half = 0
        flashes.clear()
        comp = 1f
        prevRaw = null
        activity = 0f
    }

    /** Flashes currently inside the rolling window. Diagnostics and tests. */
    val flashesInWindow: Int get() = flashes.size

    /**
     * Return [colors] with whole-field flashing bounded. [dt] is the wall-clock
     * time since the previously emitted frame.
     */
    fun process(colors: Map<Int, Rgb>, dt: Float): Map<Int, Rgb> {
        if (colors.isEmpty()) return colors
        t += dt
        val field = fieldBrightness(colors)
        if (ema == null) {
            ema = field
            prevField = field
            lastExtreme = field
        }

        // Track how hard the *incoming* field is swinging, peak-held, so release
        // waits for the content to genuinely calm rather than merely for a timer.
        if (prevRaw == null) prevRaw = field
        activity = max(activity * ACTIVITY_DECAY, abs(field - prevRaw!!))
        prevRaw = field

        prune()
        // Engage one flash *before* the ceiling: when compression kicks in there
        // is always a half-formed flash already in flight, so triggering at the
        // ceiling itself would let a final (N+1)th flash leak out.
        val over = flashes.size >= max(1, maxFlashes - 1)
        val calm = activity < ACTIVITY_CALM
        val compTarget = if (calmGated) {
            if (!over && calm) 1f else 0f
        } else {
            if (over) 0f else 1f
        }
        val rate = if (compTarget < comp) ENGAGE_RATE else RELEASE_RATE
        comp += (compTarget - comp) * rate
        val limiting = comp < 0.999f

        // The anchor tracks the field while not limiting. While limiting it may
        // still RISE toward the field but never FALL: a stale-dark anchor —
        // seeded on the dark frame right after a mode switch, then frozen while
        // the new mode flashes — would otherwise pin the room black indefinitely.
        // Letting it climb can only reduce compression, never create a flash.
        val anchor = ema!!
        if (!limiting || field > anchor) ema = anchor + (field - anchor) * EMA_ALPHA

        // While limiting, pull the field's temporal swing toward the anchor two
        // ways at once: a gain compresses the bright peaks, and a white floor
        // lifts the dark dips so a black-to-white strobe cannot survive — a
        // purely multiplicative gain can never lift a true-black frame. Colour
        // and per-light ratios are otherwise untouched, and both collapse to a
        // no-op when comp == 1, so normal content passes through unchanged.
        val target = if (!limiting) field else ema!! + (field - ema!!) * comp
        val gain = if (field > 1e-6f) (target / field).coerceIn(0f, 2f) else 1f
        val floor = if (limiting) max(0f, target - 0.5f * delta) else 0f

        val out = HashMap<Int, Rgb>(colors.size)
        for ((cid, c) in colors) {
            var r = c.first * gain
            var g = c.second * gain
            var b = c.third * gain
            val lift = floor - max(r, max(g, b))
            if (lift > 0f) {  // raise the dark floor with neutral white
                r += lift; g += lift; b += lift
            }
            out[cid] = Rgb(min(1f, r), min(1f, g), min(1f, b))
        }

        trackFlash(fieldBrightness(out))
        return applyRedGuard(out)
    }

    private fun prune() {
        val cutoff = t - WINDOW_S
        while (flashes.isNotEmpty() && flashes.first() < cutoff) flashes.removeFirst()
    }

    /** Detect opposing-transition pairs in the emitted field brightness. */
    private fun trackFlash(field: Float) {
        val prev = prevField
        if (prev == null) {
            prevField = field
            return
        }
        val cur = when {
            field > prev + DIR_EPS -> 1
            field < prev - DIR_EPS -> -1
            else -> dir
        }
        if (dir != 0 && cur != 0 && cur != dir) {
            // Direction reversed, so `prev` was a local extreme.
            if (abs(prev - lastExtreme) >= delta) {
                half++
                if (half % 2 == 0) flashes.addLast(t)  // two halves make one flash
            }
            lastExtreme = prev
        }
        if (cur != 0) dir = cur
        prevField = field
    }

    /**
     * Desaturate a rapidly changing saturated-red field toward white.
     *
     * Saturated red carries a stricter flash threshold, so swinging red is
     * pulled toward white — lowering the risk and softening the look — without
     * dimming the field. Driven by raw-field activity plus registered flashes:
     * the brightness limiter may already be pinning the field while the red
     * content still swings underneath it.
     */
    private fun applyRedGuard(colors: Map<Int, Rgb>): Map<Int, Rgb> {
        val redness = fieldRedness(colors)
        if (redness < RED_SATURATION) return colors
        val over = max(0, flashes.size - MAX_RED_FLASHES_PER_S)
        val drive = 0.15f * over + max(0f, activity - ACTIVITY_CALM)
        val mix = min(0.5f, drive) * redness
        if (mix <= 0.01f) return colors
        val out = HashMap<Int, Rgb>(colors.size)
        for ((cid, c) in colors) {
            val m = max(c.first, max(c.second, c.third))
            val white = m * mix  // add white at the light's own level, don't brighten
            out[cid] = Rgb(
                min(m, c.first * (1f - mix) + white),
                min(m, c.second * (1f - mix) + white),
                min(m, c.third * (1f - mix) + white),
            )
        }
        return out
    }
}

/**
 * Caps how often any single channel may make a *perceptible* change, at
 * [PHYSICAL_MAX_EFFECT_HZ].
 *
 * Separate from [FieldSafety] and deliberately not bypassable. The flash budget
 * is a comfort limit that Extreme is allowed to escape at the user's request;
 * this is a statement about the hardware. Philips' guidance is that the bridge
 * relays at 25 Hz over Zigbee and the fastest effect rate should therefore stay
 * under 12.5 Hz — past that the change is coalesced away before it reaches a
 * bulb, so emitting it produces no visible benefit and only more strobing.
 *
 * Holds the previous value rather than dropping the frame, so the stream stays
 * continuous as the Entertainment API asks; only the *change* is deferred.
 */
class EffectRateLimiter(maxHz: Float = PHYSICAL_MAX_EFFECT_HZ) {

    private val minInterval = 1f / maxHz
    private val lastChangeAt = HashMap<Int, Float>()
    private val held = HashMap<Int, Rgb>()
    private var t = 0f

    fun reset() {
        lastChangeAt.clear()
        held.clear()
        t = 0f
    }

    fun process(colors: Map<Int, Rgb>, dt: Float): Map<Int, Rgb> {
        if (colors.isEmpty()) return colors
        t += dt
        val out = HashMap<Int, Rgb>(colors.size)
        for ((cid, c) in colors) {
            val prev = held[cid]
            if (prev == null) {
                held[cid] = c
                lastChangeAt[cid] = t
                out[cid] = c
                continue
            }
            val since = t - (lastChangeAt[cid] ?: 0f)
            if (!perceptible(prev, c) || since >= minInterval) {
                // Either nothing worth calling a change, or enough time has
                // passed that the bridge can actually deliver one.
                if (perceptible(prev, c)) lastChangeAt[cid] = t
                held[cid] = c
                out[cid] = c
            } else {
                out[cid] = prev
            }
        }
        return out
    }

    private companion object {
        /**
         * What counts as a change worth rate-limiting. Below the WCAG flash
         * threshold a step is a smooth gradation rather than a transition, and
         * holding those would visibly quantise fades.
         */
        fun perceptible(a: Rgb, b: Rgb): Boolean =
            abs(max(a.first, max(a.second, a.third)) - max(b.first, max(b.second, b.third))) >= FLASH_DELTA
    }
}
