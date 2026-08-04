@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.engabd.sendpin.ui.design

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The two scopes a shared-element flight needs, published down the tree.
 *
 * A cover flying from the library grid into the album screen has its two ends in
 * different places: one inside a `LazyVerticalGrid` several composables below
 * `LibraryScreen`, the other inside `AlbumHero`. Threading `SharedTransitionScope`
 * and `AnimatedVisibilityScope` through every signature between here and there —
 * including a `LazyGridScope` receiver that is not composable and cannot carry them
 * — would touch a lot of code that has no other interest in animation.
 *
 * Null by default, and [sharedArt] no-ops when either is missing, so a screen
 * rendered outside a `SharedTransitionLayout` (a preview, a sheet, the mini player)
 * simply draws normally.
 */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The current destination's animation scope. See [LocalSharedTransitionScope]. */
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Marks this element as one end of a shared-element flight identified by [key].
 *
 * Both ends must use the same key and be composed in different destinations of the
 * same `SharedTransitionLayout`; [artKey] builds one from an item id.
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

/** The shared-element key for an item's artwork, so both ends agree without prose. */
fun artKey(itemId: String) = "art:$itemId"
