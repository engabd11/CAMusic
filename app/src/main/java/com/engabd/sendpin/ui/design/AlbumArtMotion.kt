package com.engabd.sendpin.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The cover that is actually on screen, and how far through a page turn it is.
 *
 * Hoisted out of [AlbumArt] because the artwork is not the only thing painted from
 * it: the full-bleed [MeltBackdrop] behind Now Playing is the same picture, blurred
 * past recognition. Fed the raw url it changed at t=0 while the cover only revealed
 * the new sleeve at the half-turn, so for a quarter of a second the room was lit by
 * one album and the sleeve was still the other one.
 */
@Stable
class SettledArt internal constructor(
    private val shown: State<String?>,
    private val progress: State<Float>,
) {
    /** The url actually painted right now. Held steady for the life of an album. */
    val url: String? get() = shown.value

    /**
     * Page-turn progress, 0..1.
     *
     * A function rather than a property read with `by`, so that call sites are pushed
     * into reading it inside a `graphicsLayer` or a draw scope. Read in composition it
     * would recompose its caller on every frame of the turn — which is what the old
     * `val flipping = turn.value > 0f` did.
     */
    fun turn(): Float = progress.value
}

/**
 * Follow [url], but only change what is painted when [flipKey] says the *album* changed.
 *
 * The problem this solves: plenty of servers hand out per-track artwork urls, so two
 * tracks of one record arrive with different urls for the identical picture.
 * `rememberArtRequest` keys its Coil request on the url, so a new url is a new cache
 * key, which is a cache miss — and on a miss `AsyncImage` draws nothing at all until
 * the bytes land, then fades the same cover back in. Skipping through an album
 * flashed the sleeve off and on once per track.
 *
 * So the url is *held*. While the album is unchanged and something is already on
 * screen, a new url is ignored outright: no new request, no miss, no fade, no motion.
 * The only cover motion left in the app is a real change of album.
 */
@Composable
fun rememberSettledArt(url: String?, flipKey: Any?): SettledArt {
    // Held as the state objects themselves rather than through `by`, because the
    // painted url is handed straight to SettledArt: a delegated read here would
    // recompose this function instead of only the layers that draw the cover.
    val shownUrl = remember { mutableStateOf(url) }
    var shownKey by remember { mutableStateOf(flipKey) }
    val turn = remember { Animatable(0f) }
    val reduced = LocalReducedMotion.current

    LaunchedEffect(flipKey, url) {
        if (flipKey != null && flipKey == shownKey) {
            // The whole point. Artwork that arrives late is still adopted — there was
            // nothing on screen to protect — but a url that merely differs is not.
            if (shownUrl.value.isNullOrBlank()) shownUrl.value = url
            return@LaunchedEffect
        }
        val flippable = flipKey != null && shownKey != null && !reduced
        if (!flippable) {
            // First cover of the session, reduced motion, or the player going idle.
            shownUrl.value = url
            shownKey = flipKey
            if (turn.value != 0f) turn.snapTo(0f)
            return@LaunchedEffect
        }
        turn.snapTo(0f)
        turn.animateTo(1f, FlipSpec) {
            // Swapped edge-on, where there is nothing to see either way. Doing it any
            // earlier shows the new sleeve mid-turn; any later, the old one.
            if (value >= 0.5f && shownKey != flipKey) {
                shownUrl.value = url
                shownKey = flipKey
            }
        }
        shownUrl.value = url
        shownKey = flipKey
        turn.snapTo(0f)
    }

    val progress = turn.asState()
    return remember(shownUrl, progress) { SettledArt(shownUrl, progress) }
}

/**
 * One page turn, weighted.
 *
 * A symmetric `tween` turned the sleeve at the same speed the whole way round, which
 * reads as a card on a motor rather than a page being lifted. Three phases instead:
 * slow off the mark while the page is picked up, a fast even sweep through edge-on
 * where a real page has nothing slowing it, then a long decelerating settle as it
 * lands flat.
 */
internal val FlipSpec = keyframes<Float> {
    durationMillis = FLIP_MS
    0.10f at 90 using CubicBezierEasing(0.40f, 0f, 1f, 1f)
    0.50f at 260 using LinearEasing
    0.90f at 440 using CubicBezierEasing(0f, 0f, 0.20f, 1f)
    1f at FLIP_MS
}

/** How long a page takes to turn. Deliberate enough to read, short enough not to wait on. */
internal const val FLIP_MS = 560

/** Perspective depth, in dp, multiplied by density at the layer. */
internal const val FLIP_CAMERA_DISTANCE = 16f

/** How far the page pulls back at edge-on. Small — this is depth, not a bounce. */
internal const val FLIP_SCALE_DIP = 0.04f

/**
 * Degrees of roll through the turn, peaking a quarter of the way in and again three
 * quarters through. A page hinged along one edge does not stay level as it comes over;
 * without this the cover reads as spun on a rigid spindle.
 */
internal const val FLIP_TILT = 1.6f

/** Resting shadow depth under the cover, in dp. Swells to nearly double at edge-on. */
internal const val FLIP_SHADOW_DP = 20f
