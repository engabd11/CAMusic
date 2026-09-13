package com.engabd.sendpin.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.TextFaint
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary

/**
 * One server or provider as a frosted tile carrying its own colour.
 *
 * The same tile everywhere a library is picked — the switcher over the Library tab,
 * the onboarding "choose your music source" list, Settings › Libraries' provider
 * picker — so a Jellyfin row looks like the Jellyfin row wherever it appears. Before
 * this each screen drew its own grey pill with a generic Material icon, and the
 * onboarding list did not match the switcher it leads to.
 *
 * The tile is glass: a translucent fill, a hairline, a whisper of white down from
 * the top edge so it reads as a pane rather than a tint, and the kind's
 * [serverKindColor] bleeding in from behind the mark and fading out across the row.
 * [active] bleeds harder and takes a stronger border, for "the one I am on".
 * [enabled] false greys it out for a kind that is not built yet.
 */
@Composable
fun ServerKindTile(
    kind: ServerKind,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    radius: Dp = 16.dp,
    /** Size of the mark's own pane; the glyph inside is 60% of it. */
    markSize: Dp = 42.dp,
    subtitleMaxLines: Int = 2,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit,
) {
    val brand = if (enabled) serverKindColor(kind) else TextFaint
    val shape = RoundedCornerShape(radius)
    val bleed = Brush.horizontalGradient(
        0f to brand.copy(alpha = if (active) 0.34f else if (enabled) 0.20f else 0.06f),
        0.65f to brand.copy(alpha = 0.04f),
        1f to Color.Transparent,
    )
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.10f),
        0.45f to Color.Transparent,
    )
    val markShape = RoundedCornerShape(radius * 0.7f)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Glass)
            .background(bleed)
            .background(sheen)
            .border(1.dp, brand.copy(alpha = if (active) 0.6f else 0.28f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        // The mark sits in its own small pane so a logo with a transparent
        // background has something to be seen against, and a generic glyph gets the
        // same footprint as a brand mark.
        Box(
            Modifier
                .size(markSize)
                .clip(markShape)
                .background(brand.copy(alpha = 0.18f))
                .border(1.dp, brand.copy(alpha = 0.35f), markShape),
            contentAlignment = Alignment.Center,
        ) {
            ServerKindGlyph(kind, tint = brand, modifier = Modifier.size(markSize * 0.6f))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                title,
                color = if (enabled) TextPrimary else TextMuted, fontFamily = AppFont,
                fontWeight = FontWeight.Bold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = TextMuted, fontFamily = AppFont,
                fontSize = 12.sp, lineHeight = 16.sp,
                maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}
