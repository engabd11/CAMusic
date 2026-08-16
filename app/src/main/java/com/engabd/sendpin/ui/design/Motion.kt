package com.engabd.sendpin.ui.design

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset

/**
 * The app's motion tokens.
 *
 * These mirror Material 3's *expressive* motion scheme, which is not usable yet:
 * `MotionScheme` exists in material3 1.4.0 but `MaterialTheme.motionScheme` is
 * `internal` there, and the release that makes it public — 1.5.0 — is still alpha.
 * Rather than take an alpha dependency for an app in daily use, the same two families
 * of spring live here.
 *
 * The split is the one M3 draws, and it matters:
 *
 *  - **Spatial** springs move things — position, size, offset. They are slightly
 *    under-damped, so a pushed screen settles with a trace of overshoot instead of
 *    stopping dead. That is what reads as responsive rather than mechanical.
 *  - **Effects** springs change appearance — alpha, colour. They are critically
 *    damped (ratio 1.0): a fade that overshoots would flicker past full opacity and
 *    back, which is visible and wrong.
 *
 * Never animate alpha on a spatial spec, or position on an effects spec.
 *
 * When material3 1.5 stabilises, these can be deleted and each call site pointed at
 * `MaterialTheme.motionScheme` — the shapes of the two APIs line up deliberately.
 */
object Motion {

    /** Position and size, for most things. */
    fun <T> spatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 380f)

    /** Position and size, for small elements that should feel immediate. */
    fun <T> spatialFast(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.6f, stiffness = 800f)

    /** Position and size, for large surfaces where speed would read as a jolt. */
    fun <T> spatialSlow(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 200f)

    /** Alpha and colour. Critically damped — no overshoot. */
    fun <T> effects(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)

    /** Alpha and colour, for quick state flips. */
    fun <T> effectsFast(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 3800f)

    /**
     * Spatial motion for a whole-screen slide.
     *
     * `IntOffset` needs an explicit visibility threshold: the generic default is tuned
     * for a 0f..1f range, and against a screen-width offset in pixels it treats the
     * spring as settled while the screen is still visibly short of its mark, so the
     * last few pixels snap.
     */
    fun screenSlide(): FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = 0.9f,
        stiffness = 380f,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )

    /**
     * Spatial motion for a large pixel-scale drag-and-settle offset, e.g. the Now
     * Playing cover sliding down to the mini bar. Same problem as [screenSlide] but
     * for a raw `Float` `Animatable` rather than an `IntOffset`: without an explicit
     * threshold, `spring<Float>()` falls back to [Spring.DefaultDisplacementThreshold]
     * (0.01f) — tuned for a 0f..1f alpha, not a ~2000px drag. Against a distance that
     * large the spring keeps correcting sub-pixel error for a very visible extra
     * stretch after the motion has already looked finished, which is what read as the
     * settled player "sticking" for a second before it actually finished collapsing.
     * 1px is close enough that arriving there is indistinguishable from the exact target.
     */
    fun spatialOffsetPx(): FiniteAnimationSpec<Float> = spring(
        dampingRatio = 0.8f,
        stiffness = 380f,
        visibilityThreshold = 1f,
    )
}
