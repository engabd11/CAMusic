@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.engabd.sendpin.ui.design

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * CompositionLocals for shared element transitions.
 *
 * [SharedTransitionLayout] wraps the whole app content in `App.kt` and provides
 * the [SharedTransitionScope]. Each NavHost destination is an
 * [AnimatedVisibilityScope] (the `this` receiver inside a `composable {}`
 * block's content lambda). Both are published here so any composable deep in
 * the tree can opt into a shared element without receiving them through every
 * intermediate call — the library grid tile and the album hero are several
 * levels apart and neither knows about the other.
 *
 * Both default to `null` so that a preview, a sheet, or any other context
 * without shared transition support simply no-ops — the modifier returns `this`
 * unchanged and the image draws normally.
 *
 * ## What is deliberately *not* shared
 *
 * The now-playing cover. In the overlay layout the mini bar and the expanded
 * player are two ends of an obvious flight, and it was one — until the player
 * moved. A shared element is drawn in the transition layout's own overlay,
 * outside the composable it came from, on its own spring: while the player slid
 * under the finger the artwork travelled separately, and the cover read as
 * coming unstuck from the surface carrying it. It is an ordinary child of the
 * sliding player now, and the bar's thumbnail cross-fades. See
 * `NowPlayingOverlay`.
 */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Apply a shared element transition to an image (album cover, artist art).
 *
 * Call this on **both** ends of the transition — the grid tile and the detail
 * screen hero — with the same [key]. When the detail screen pushes, the cover
 * animates from its grid position to the hero position; when it pops, it flies
 * back. The spring comes from the app's [Motion] tokens.
 *
 * When either scope is missing (preview, sheet, unmounted), this is a no-op —
 * the modifier is returned unchanged.
 */
@Composable
fun Modifier.sharedArt(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedArt.sharedElement(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = animated,
        )
    }
}