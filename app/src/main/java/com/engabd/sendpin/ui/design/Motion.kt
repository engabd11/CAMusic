package com.engabd.sendpin.ui.design

import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.basicMarquee
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.engabd.sendpin.data.AppSettings

/**
 * The app's motion tokens.
 *
 * These mirror Material 3's *expressive* motion scheme. material3 1.5.0-alpha26
 * (overridden in build.gradle.kts over the BOM's 1.4.0) provides
 * `MaterialExpressiveTheme` and `MotionScheme.expressive()`, now wired into
 * `SendspinTheme`. 21 M3 components (Switch, Slider, FAB, NavigationBar, Chip,
 * Button, etc.) automatically use these exact spring values via
 * `MaterialTheme.motionScheme`.
 *
 * This object remains for **custom components** that don't go through M3
 * composables — `GlassCard`, `Pill`, `IconChip`, the Now Playing overlay,
 * sheet dismissals, screen transitions. The values here are intentionally
 * identical to the M3 Expressive scheme so the whole app feels cohesive.
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
 * ## Why these are not `MaterialTheme.motionScheme` reads
 *
 * The values below are intentionally identical to the M3 Expressive scheme, and the
 * obvious next step is to read them from it. It does not work, for a structural
 * reason rather than a stylistic one: `MaterialTheme.motionScheme` is a
 * `@Composable` read, and several of these specs are needed where there is no
 * composition to read from — `Animatable.animateTo` inside a `scope.launch`
 * (`SendspinDesign`'s sheet drag), a `suspend fun` default argument
 * (`NowPlayingOverlay.settleTo`), a scroll call inside a coroutine (`LyricsPane`).
 * Making them `@Composable` would force each of those to hoist a spec into
 * composition and thread it down, which is more moving parts than the duplication
 * costs. The theme still provides the scheme, so M3's own components use it.
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
     * One full turn of a refresh spinner.
     *
     * Here rather than at the two call sites that had it (the library header and the
     * speakers list), which had drifted into being the same 900 ms linear loop written
     * out twice — a duplicated constant is a duplicated decision, and one of them
     * eventually gets retuned alone.
     */
    const val SPINNER_PERIOD_MS = 900

    /**
     * A swiped row springing back to rest.
     *
     * The one motion in the app that is deliberately a tween rather than a spring: the
     * row is returning to a position it never left under its own momentum, so overshoot
     * would read as the gesture having done something.
     */
    const val SNAP_BACK_MS = 220

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
     * Spatial motion for a container growing or shrinking to fit its content — the
     * album notes and the artist biography unclamping from a few lines to all of them.
     *
     * The third member of the family [screenSlide] and [spatialOffsetPx] belong to, and
     * it is here for the same reason they are: a `spring<IntSize>()` written without an
     * explicit threshold falls back to [Spring.DefaultDisplacementThreshold] (0.01f),
     * which is tuned for a 0f..1f range and is being asked to judge a height in pixels.
     * A biography opening adds several hundred of them, so the spring spends a visible
     * stretch past the end of the motion correcting hundredths of a pixel while the
     * content below it is still nominally in flight.
     *
     * [IntSize.VisibilityThreshold] is the platform's own answer — one pixel, which is
     * the smallest layout change there is — and is what `animateContentSize` uses in
     * its own default spec. The damping and stiffness stay [spatial]'s, so a panel
     * opening carries the same weight as everything else that moves.
     */
    fun contentSize(): FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = 0.8f,
        stiffness = 380f,
        visibilityThreshold = IntSize.VisibilityThreshold,
    )

    /**
     * A row moving to a new place in a list it is already in.
     *
     * `Modifier.animateItem`'s placement spec. Its own default is a bare
     * `spring(stiffness = StiffnessMediumLow)` — softer than anything else in this
     * file, so a reordering list drifts while the rest of the app springs. This is
     * [spatial]'s physics with the [IntOffset.VisibilityThreshold] that a pixel-scale
     * offset needs, for the reason [screenSlide] gives.
     *
     * Slightly stiffer than [spatial] (`k = 450`), and the queue is why. A row that is
     * following a finger has to get out of the way *before* the finger reaches where
     * it was, or the drag overtakes its own animation and the list reads as lagging.
     */
    fun itemPlacement(): FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = 0.85f,
        stiffness = 450f,
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
 * The app's own answer, layered over the system's.
 *
 * [AppSettings.MOTION_SYSTEM] is the default and defers to [systemSaysReduced]
 * entirely, which is the behaviour that shipped. The other two exist because the
 * platform setting is global: someone who finds this app in particular too busy had to
 * quieten every app on the phone to say so, and someone who has that setting off for
 * an unrelated reason had no way to ask for it here.
 */
fun reducedMotionFor(mode: String, systemSaysReduced: Boolean): Boolean = when (mode) {
    AppSettings.MOTION_REDUCED -> true
    AppSettings.MOTION_FULL -> false
    else -> systemSaysReduced
}

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

/**
 * Scroll a title that is too long to fit, once every [restMs], then rest.
 *
 * Long titles were ellipsised everywhere, which for classical, live and remixed tracks
 * means the part that distinguishes one from another is the part that gets cut. A
 * marquee shows the whole thing without giving it more room.
 *
 * Three things it deliberately does not do.
 *
 * It does not scroll continuously. A title sliding without pause is movement in the
 * corner of the eye for as long as the song lasts; twenty seconds of stillness between
 * passes is long enough to stop being noticed and short enough that the rest of the
 * title is never far away.
 *
 * It does not engage for text that fits. `basicMarquee` only animates when the content
 * overflows its constraints, so a short title is left completely alone — no jitter, and
 * no layout difference between a title that scrolls and one that does not.
 *
 * It does not run when the listener has asked for less motion. [LocalReducedMotion]
 * already gates the shimmer and the artwork drift; a scrolling title is exactly the kind
 * of ambient movement that setting exists to stop.
 *
 * Note for callers: a marquee needs a bounded width, and it left-aligns once it starts
 * moving. Centre the *container* rather than the text, or a centred title will appear to
 * jump left as the animation begins.
 */
@Composable
fun Modifier.titleMarquee(restMs: Int = TITLE_MARQUEE_REST_MS): Modifier {
    if (LocalReducedMotion.current) return this
    return this.basicMarquee(
        iterations = Int.MAX_VALUE,
        // Both delays, not just the repeat: the first pass should not start the instant
        // a track changes, or the title moves while the listener is still reading it.
        initialDelayMillis = restMs,
        repeatDelayMillis = restMs,
        velocity = TITLE_MARQUEE_VELOCITY,
    )
}

/** How long a title rests between passes. */
const val TITLE_MARQUEE_REST_MS = 20_000

/** Scroll speed. Slower than the 30 dp/s default — this is a title, not a ticker. */
val TITLE_MARQUEE_VELOCITY = 24.dp
