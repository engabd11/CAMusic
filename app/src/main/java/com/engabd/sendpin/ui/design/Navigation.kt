package com.engabd.sendpin.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ui.theme.*

/** Height of the tab bar's own content, above whatever the system nav takes. */
val NavBarHeight = 62.dp

/**
 * How much room the mini player bar takes above the tab bar: a 40.dp thumbnail with
 * 8.dp of padding either side, plus the 4.dp its wrapper adds top and bottom.
 *
 * Keep in step with `MiniPlayerBar` and the `Box` that wraps it in `App.kt`.
 */
/**
 * How much room the minimised overlay player takes above the tab bar.
 *
 * Doubled from 64.dp. At the old height the bar was a strip with a thumbnail and a
 * play button wedged into it — the only way back into the full player, and the
 * hardest thing on screen to hit, sitting directly above the nav bar it competed
 * with. The extra room is what lets it carry previous/next as well, and gives the
 * artwork a size worth looking at.
 */
val MiniBarHeight = 128.dp

/**
 * The mini player's height on a screen that needs the bottom of the phone back.
 *
 * The full-size bar is 128.dp, and with the tab bar under it that is nearly 200.dp
 * of chrome — fine on a list, wrong on the rhythm game, where the hit line has to
 * sit where a thumb naturally falls and every dp of chrome pushes it further up
 * into the middle of the screen. At this height the bar still says what is playing
 * and still plays and pauses; it gives up the artist line, the speaker line and the
 * skip buttons, none of which anyone is reading mid-song.
 */
val CompactMiniBarHeight = 56.dp

/**
 * The mini player's height when it is on screen, 0 otherwise.
 *
 * Provided by `App.kt`, which is the only place that knows whether the overlay layout
 * is in use. Screens don't read it directly — [navBarInset] folds it in, so a screen
 * that already reserves space for the tab bar reserves space for the mini bar too.
 */
val LocalMiniBarInset = compositionLocalOf { 0.dp }

/**
 * Bottom padding a screen needs so its last row clears the floating bottom chrome.
 * The bar overlays content rather than displacing it, so the album wash runs
 * behind it all the way to the bottom of the panel.
 *
 * In the overlay layout the mini player sits above the tab bar and is part of that
 * chrome; leaving it out is what hid the last row of every scrollable screen.
 */
@Composable
fun navBarInset(): Dp =
    NavBarHeight + LocalMiniBarInset.current +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * Only what the *system* navigation bar takes — no app chrome.
 *
 * What a bottom sheet needs. A sheet is drawn over the tab bar and the mini player,
 * so reserving room for them left a band of dead sheet-coloured space at the bottom
 * — 98dp in the tab layout, 186dp in the overlay one, close enough to a third of the
 * sheet to read as a box sitting on top of it. Sheets pair this with
 * [HideBottomChrome]; screens keep [navBarInset].
 */
@Composable
fun systemNavInset(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * How many sheets are open right now.
 *
 * A sheet lives inside its own screen's Box, which in the tab layout is a `NavHost`
 * sibling *below* [SendspinNavBar] — so the bar's opaque black rect, hairline and
 * all, paints over the bottom of the sheet. Rather than re-parent every sheet out of
 * the screens that provide their palette, the chrome stands down while one is up.
 * The bar overlays content and never displaced it, so nothing reflows.
 *
 * A counter rather than a flag: two sheets briefly overlapping during a transition
 * must not leave the chrome hidden when the first one closes.
 */
class BottomChromeState {
    var openSheets by mutableIntStateOf(0)
        private set

    fun acquire() { openSheets++ }
    fun release() { openSheets = (openSheets - 1).coerceAtLeast(0) }
}

val LocalBottomChrome = staticCompositionLocalOf { BottomChromeState() }

/** Hide the mini player and tab bar for as long as the caller is composed. */
@Composable
fun HideBottomChrome() {
    val chrome = LocalBottomChrome.current
    DisposableEffect(chrome) {
        chrome.acquire()
        onDispose { chrome.release() }
    }
}

data class NavTab(val route: String, val label: String, val icon: ImageVector)

/**
 * The tab bar. A gradient that goes opaque within its top few dp, so scrolling
 * content is cut off well above the icons rather than sliding visibly behind
 * them — but the very top edge still feathers, so on OLED the bar has no seam,
 * it just stops emitting.
 */
@Composable
fun SendspinNavBar(
    tabs: List<NavTab>,
    currentRoute: String?,
    modifier: Modifier = Modifier,
    /**
     * Routes that exist but aren't reachable right now — they stay in place, faded
     * and inert, so the bar doesn't reshuffle under the user's thumb when the
     * library backend changes.
     */
    disabledRoutes: Set<String> = emptySet(),
    /**
     * Why a disabled route is disabled, keyed by route.
     *
     * A tab that swallows a tap in silence is its own small mystery — the user is
     * left unsure whether they missed, whether the app is wedged, or whether the tab
     * means anything at all. This says so out loud instead. It is deliberately *not*
     * an affordance: the tab still looks and behaves as dead as it is.
     */
    disabledReasons: Map<String, String> = emptyMap(),
    onDisabledTap: (String) -> Unit = {},
    onSelect: (String) -> Unit,
) {
    val accent = LocalAccent.current
    // Read in composition, not in the draw lambda: `drawBehind` runs in a DrawScope,
    // which is not a composable and so cannot read the theme.
    val ink = Ink
    val fade = remember(ink) {
        Brush.verticalGradient(0f to ink.a(0.5f), 0.16f to ink, 1f to ink)
    }
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind { drawRect(fade) }
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineSoft).align(Alignment.TopCenter))
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(NavBarHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val off = tab.route in disabledRoutes
                val on = currentRoute == tab.route && !off
                val tint = when {
                    off -> TextFaint
                    on -> accent
                    else -> TextMuted
                }
                val reason = disabledReasons[tab.route]
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // The fade goes on the whole tab rather than on the tint, so
                        // icon and label recede together and by the same amount.
                        // Tinting alone left `TextFaint` close enough to the ordinary
                        // `TextMuted` of a live tab that the two read the same, and a
                        // dead tab looked merely unselected.
                        .alpha(if (off) 0.32f else 1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { if (off) onDisabledTap(tab.route) else onSelect(tab.route) }
                        // Announced as disabled, and carrying the reason, so the bar
                        // says the same thing to TalkBack that it says on screen.
                        .semantics {
                            if (off) {
                                disabled()
                                reason?.let { stateDescription = it }
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // The selected tab's icon sits in its own small bloom.
                        if (on) Bloom(accent, 34.dp, 0.dp, 0.dp, 0.55f)
                        Icon(tab.icon, tab.label, tint = tint, modifier = Modifier.size(21.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tab.label,
                        color = tint,
                        fontFamily = AppFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
