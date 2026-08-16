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
 * A NavHost destination is not the only source of an [AnimatedVisibilityScope],
 * and Now Playing is the reason: in the overlay layout it is not a destination
 * at all, it is a branch in `App.kt`'s bottom chrome. Anything that can supply
 * an `AnimatedVisibilityScope` will do, and `AnimatedVisibility`'s content
 * lambda receiver *is* one — which is how the mini bar and the expanded cover
 * each get theirs. See `App.kt`.
 *
 * Both default to `null` so that a preview, a sheet, or any other context
 * without shared transition support simply no-ops — the modifier returns `this`
 * unchanged and the image draws normally.
 */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * The shared-element key for the now-playing cover.
 *
 * A **constant**, not `art-<itemId>-<provider>` like the library grid uses, and
 * that is deliberate. In the overlay layout the mini bar is on screen at the
 * same time as the library grid, so an item-keyed mini bar would collide with
 * the grid tile of whatever is currently playing whenever that album happens to
 * be visible — two live shared elements claiming one key, which Compose treats
 * as an error and resolves arbitrarily.
 *
 * The mini bar and the expanded cover always show the same artwork as each
 * other, so they need no item identity to agree on: one key that means "the
 * now-playing cover, wherever it currently lives" is exactly the relationship
 * being animated, and it cannot collide with the per-item keys.
 */
const val NowPlayingArtKey = "now-playing-art"

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