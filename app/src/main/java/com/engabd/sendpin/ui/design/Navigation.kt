package com.engabd.sendpin.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * Bottom padding a screen needs so its last row clears the floating tab bar.
 * The bar overlays content rather than displacing it, so the album wash runs
 * behind it all the way to the bottom of the panel.
 */
@Composable
fun navBarInset(): Dp =
    NavBarHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

data class NavTab(val route: String, val label: String, val icon: ImageVector)

/**
 * The tab bar. A gradient that fades from semi-transparent at the very top to
 * solid #000, so content scrolling behind it is obscured before it reaches the
 * icons. On OLED the solid portion melts into the bezel.
 */
@Composable
fun SendspinNavBar(
    tabs: List<NavTab>,
    currentRoute: String?,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    val accent = LocalAccent.current
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        0f to Ink,
                        0.45f to Ink,
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
                val on = currentRoute == tab.route
                val tint = if (on) accent else TextMuted
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
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
