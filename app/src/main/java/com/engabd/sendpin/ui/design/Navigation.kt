package com.engabd.sendpin.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
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
val MiniBarHeight = 64.dp

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
    onSelect: (String) -> Unit,
) {
    val accent = LocalAccent.current
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        0f to Ink.a(0.5f),
                        0.16f to Ink,
                        1f to Ink,
                    )
                )
            }
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
                    off -> TextFaint.a(0.45f)
                    on -> accent
                    else -> TextMuted
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = !off,
                        ) { onSelect(tab.route) },
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
