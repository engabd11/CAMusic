package com.engabd.sendpin.ui.design

import android.animation.ValueAnimator
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

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
     * Swapping one whole screen for a sibling — a tab change.
     *
     * A *fade through*, not a cross-fade, and the difference is the bug it fixes. On a
     * cross-fade both screens are on screen at partial alpha for the whole transition,
     * so whatever the outgoing one is painted with shows through the incoming one:
     * leaving an album — a screen washed in that album's colours — for Settings put a
     * ghost of the cover's palette behind the settings cards for the length of the
     * fade. Every tab pair does it; the album screens are simply the ones colourful
     * enough to notice.
     *
     * So the outgoing screen leaves *first*, over [FADE_THROUGH_OUT_MS], and the
     * incoming one begins only once it has gone. The gap between them is the app's own
     * background, which is what a screen change should reveal. Material calls this a
     * fade-through and specifies exactly this split.
     *
     * Tweens rather than springs, uniquely in this file: the two halves have to be
     * sequenced, and a delay is only meaningful against a known duration.
     */
    fun <T> fadeThroughOut(): FiniteAnimationSpec<T> =
        tween(durationMillis = FADE_THROUGH_OUT_MS, easing = LinearEasing)

    fun <T> fadeThroughIn(): FiniteAnimationSpec<T> = tween(
        durationMillis = FADE_THROUGH_IN_MS,
        delayMillis = FADE_THROUGH_OUT_MS,
        easing = LinearOutSlowInEasing,
    )

    /** How long the outgoing screen takes to leave, and the incoming one waits. */
    private const val FADE_THROUGH_OUT_MS = 90

    /** How long the incoming screen takes to arrive, once it is alone. */
    private const val FADE_THROUGH_IN_MS = 210

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
     * Spatial motion for sheet slides (queue menu, options panels).
     *
     * Softer than [screenSlide] — sheets are lighter surfaces that should feel like
     * they're gliding over the content rather than pushing it. Lower stiffness and
     * slightly more damping makes the motion feel smooth and controlled.
     */
    fun sheetSlide(): FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = 0.95f,
        stiffness = 280f,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )

    /**\n     * Spatial motion for sheet vertical drag dismissals.\n     *\n     * Balanced between responsiveness and smoothness — soft enough to feel like\n     * the sheet is gliding rather than snapping, but still responsive to user input.\n     * Lower stiffness and higher damping creates a smooth, controlled motion.\n     */\n    fun sheetDismiss(): FiniteAnimationSpec<Float> = spring(\n        dampingRatio = 0.9f,\n        stiffness = 200f,\n        visibilityThreshold = 2f,\n    )

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

    /**
     * A large surface being **dismissed**, where something else is waiting on the
     * animation actually finishing — the Now Playing cover sliding down to the mini
     * bar, whose handover only happens once `animateTo` returns.
     *
     * [spatialOffsetPx] is the wrong spec for that, and the reason is arithmetic
     * rather than taste. Its spring is `ζ = 0.8, k = 380`, so `ω = √380 ≈ 19.5 rad/s`
     * and the error decays as `e^(-ζωt) = e^(-15.6t)`. Reaching the 1px threshold from
     * a ~2000px travel needs `ln(2000) / 15.6 ≈ 0.49s` — half a second in which the
     * cover has visually arrived and the swap to the mini bar has not happened yet.
     * (The threshold is doing real work: at the generic 0.01px default the same sum
     * gives 0.78s. That was the previous fix, and it removed about 300ms of a tail
     * that still had ~490ms left in it — enough to still read as "stuck".)
     *
     * Three changes, each addressing one term:
     *
     *  - **No overshoot.** A dismissal that bounces back up is wrong on its own terms:
     *    the surface is leaving, and `ζ = 0.8` walks it back toward the viewer before
     *    it settles. Critically damped also removes the oscillation the tail is spent
     *    converging out of.
     *  - **Stiffer** (`k = 1200`, `ω ≈ 34.6`), because the decay rate is what the
     *    settling time is made of.
     *  - **A 4px threshold**, not 1px. Against a travel three orders of magnitude
     *    larger, 4px is below what anyone can see arriving, and each halving of the
     *    threshold costs another `ln 2 / ζω` of tail for nothing.
     *
     * Together: `(1 + ωt)e^(-ωt) = 4/2000` lands at roughly **240ms**, and it ends when
     * it looks like it has ended.
     *
     * Not a general replacement for [spatialOffsetPx] — the overshoot there is
     * deliberate on a surface that is *arriving*, where a trace of overshoot is what
     * reads as responsive.
     */
    fun dismissOffsetPx(): FiniteAnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 1200f,
        visibilityThreshold = 4f,
    )
}

/**
 * Whether the user has asked the system to remove animations.
 *
 * **This is not a duration multiplier, and deliberately so** — read the next two
 * paragraphs before adding one, because the obvious version of that is already there
 * and adding a second would fight it.
 *
 * Compose already honours the setting for anything animating in a coroutine context
 * derived from the composition, which in this app is everything: `WindowRecomposer`
 * installs a `MotionDurationScale` that reads `Settings.Global.ANIMATOR_DURATION_SCALE`
 * and watches it through a `ContentObserver`, and `animation-core` consumes it in two
 * different ways. A finite animation with a scale of 0 "will end in the next frame
 * callback" (the interface's own words), so every spec in [Motion] already arrives
 * instantly with no help from us. An [androidx.compose.animation.core.InfiniteTransition]
 * instead *suspends* — it parks on `snapshotFlow { durationScale }.first { it > 0f }`
 * and resumes if the setting is turned back on.
 *
 * That second behaviour is the whole reason this flag exists. An infinite animation has
 * no end to jump to, so "off" leaves it **frozen wherever it happened to be**: a refresh
 * icon stopped at 200°, a shimmer stuck mid-sweep. Frozen mid-gesture reads as a broken
 * screen, which is worse than the animation it replaced — and no duration multiplier can
 * fix it, because the problem is that a static frame of an animation is not a design.
 *
 * So this answers a design question, not a timing one: *given that motion is off, what
 * should this surface be instead?* Usually a still, resolved state — an upright icon, a
 * flat placeholder — chosen by the surface rather than fallen into. Reach for it when a
 * surface animates forever, or when it should look genuinely different rather than
 * merely faster (a shared-element flight, a parallax, a blurred backdrop). Ordinary
 * finite motion needs nothing.
 *
 * Provided by `SendspinTheme`; defaults to false so a preview or test that provides no
 * theme still animates.
 */
val LocalReducedMotion = compositionLocalOf { false }

/**
 * Reads the system "remove animations" setting, re-checked whenever the app comes back
 * to the foreground.
 *
 * [ValueAnimator.areAnimatorsEnabled] is the platform's own published answer for this —
 * it reports false exactly when `ANIMATOR_DURATION_SCALE` is 0, which is what the
 * accessibility toggle and Developer Options both write. Re-read on `ON_RESUME` rather
 * than observed continuously because changing it means leaving for Settings and coming
 * back, so resume is every moment it can have changed. Same pattern, for the same
 * reason, as `rememberIsIgnoringBatteryOptimizations`.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    var reduced by remember { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reduced = !ValueAnimator.areAnimatorsEnabled()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return reduced
}
