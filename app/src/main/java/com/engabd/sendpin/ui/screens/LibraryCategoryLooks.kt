package com.engabd.sendpin.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ui.design.LocalPalette
import com.engabd.sendpin.ui.design.Motion
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.pressScale
import com.engabd.sendpin.ui.design.rememberArtRequest
import com.engabd.sendpin.ui.design.rememberPressScale
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.HairlineSoft
import com.engabd.sendpin.ui.theme.Ink3
import com.engabd.sendpin.ui.theme.LocalSendspinColors
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary

/**
 * The five ways a library category can be drawn.
 *
 * [CategoryCard] — the original — stays exactly as it was and stays the default; it
 * is simply one branch of [CategoryEntry] now. The other four exist because this row
 * is the first thing on the library page and the most-looked-at furniture in the app,
 * and one fixed 96dp gradient tile is not what everyone wants from it.
 *
 * All five share three things deliberately:
 *  - the same [categoryHueOf] palette position per category, so Albums is the same
 *    colour whichever look is on and switching look does not reshuffle the set;
 *  - the same [categoryIconOf] glyph;
 *  - the same press-scale gesture feedback, on the same `spatialFast` token.
 *
 * Sizes come from [categoryMetrics] rather than from literals in here, which is what
 * lets them be tested — see `LibraryCategoryLook.kt`.
 *
 * **These are reused by the car.** `CarScreen` renders `LibraryScreen` whole at
 * `fontScale * 1.3`, so every label here is `maxLines`-bounded and every height comes
 * from the metrics floor rather than from a literal that assumes phone text.
 */
@Composable
internal fun CategoryEntry(
    item: MaItem,
    style: CategoryStyle,
    metrics: CategoryMetrics,
    modifier: Modifier = Modifier,
    /**
     * Covers to fill an [CategoryStyle.MOSAIC] tile with, already picked for this
     * category by the caller — the screen has the shelves and this does not.
     */
    mosaicArt: List<String> = emptyList(),
    onClick: () -> Unit,
) {
    when (style) {
        // Untouched. The shape and size arguments are honoured, but the drawing is
        // the tile that shipped — see [CategoryCard].
        CategoryStyle.CARDS -> CategoryCard(item, metrics, modifier, onClick)
        CategoryStyle.CHIPS -> CategoryChip(item, metrics, modifier, onClick)
        CategoryStyle.LIST -> CategoryListRow(item, metrics, modifier, onClick)
        CategoryStyle.GRID -> CategoryIconTile(item, metrics, modifier, onClick)
        CategoryStyle.MOSAIC -> CategoryMosaicTile(item, metrics, mosaicArt, modifier, onClick)
    }
}

/**
 * A wrapping run of chips.
 *
 * The one look that is not a grid cell: chips are intrinsically sized, so they are
 * laid out as a `FlowRow` filling the whole width rather than as N equal cells. That
 * is what makes it dense — eight categories land in about three rows instead of four,
 * and the album shelves start that much higher up the page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryChipRow(
    items: List<MaItem>,
    metrics: CategoryMetrics,
    modifier: Modifier = Modifier,
    onClick: (MaItem) -> Unit,
) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            CategoryChip(item, metrics) { onClick(item) }
        }
    }
}

@Composable
private fun CategoryChip(
    item: MaItem,
    metrics: CategoryMetrics,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val hue = rememberCategoryHueFor(item.itemId)
    val light = LocalSendspinColors.current.isLight
    val press = rememberPressScale()
    val shape = RoundedCornerShape(metrics.cornerDp.dp)
    Row(
        modifier
            .pressScale(press)
            .height(metrics.heightDp.dp)
            .clip(shape)
            .background(hue.a(if (light) 0.22f else 0.14f))
            .border(1.dp, hue.a(if (light) 0.32f else 0.22f), shape)
            .clickable(interactionSource = press.interactions, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            categoryIconOf(item.itemId),
            null,
            tint = if (light) lerp(hue, Color.Black, 0.45f) else hue,
            modifier = Modifier.size(metrics.glyphDp.dp),
        )
        Text(
            item.name,
            color = TextPrimary,
            fontFamily = AppFont,
            fontWeight = FontWeight.Bold,
            fontSize = metrics.labelSp.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A full-width row: a tinted glyph box, then the name.
 *
 * The calmest of the five and the one that behaves best at large system font sizes —
 * a row grows downwards, where a fixed-height tile has to either clip its label or
 * push the shelves off the screen.
 */
@Composable
private fun CategoryListRow(
    item: MaItem,
    metrics: CategoryMetrics,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val hue = rememberCategoryHueFor(item.itemId)
    val light = LocalSendspinColors.current.isLight
    val press = rememberPressScale()
    val shape = RoundedCornerShape(metrics.cornerDp.dp)
    val boxSize = (metrics.heightDp - 16).coerceAtLeast(28)
    Row(
        modifier
            .pressScale(press)
            .fillMaxWidth()
            .height(metrics.heightDp.dp)
            .clip(shape)
            .background(hue.a(if (light) 0.10f else 0.06f))
            .border(1.dp, hue.a(if (light) 0.24f else 0.16f), shape)
            .clickable(interactionSource = press.interactions, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(boxSize.dp)
                .clip(RoundedCornerShape((metrics.cornerDp - 4).coerceAtLeast(6).dp))
                .background(hue.a(if (light) 0.26f else 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                categoryIconOf(item.itemId),
                null,
                tint = if (light) lerp(hue, Color.Black, 0.45f) else hue,
                modifier = Modifier.size(metrics.glyphDp.dp),
            )
        }
        Text(
            item.name,
            color = TextPrimary,
            fontFamily = AppFont,
            fontWeight = FontWeight.Bold,
            fontSize = metrics.labelSp.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A small square: big centred glyph, label underneath. Three or four per row. */
@Composable
private fun CategoryIconTile(
    item: MaItem,
    metrics: CategoryMetrics,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val hue = rememberCategoryHueFor(item.itemId)
    val light = LocalSendspinColors.current.isLight
    val press = rememberPressScale()
    val shape = RoundedCornerShape(metrics.cornerDp.dp)
    Column(
        modifier
            .pressScale(press)
            .height(metrics.heightDp.dp)
            .clip(shape)
            .background(hue.a(if (light) 0.18f else 0.11f))
            .border(1.dp, hue.a(if (light) 0.28f else 0.18f), shape)
            .clickable(interactionSource = press.interactions, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            categoryIconOf(item.itemId),
            null,
            tint = if (light) lerp(hue, Color.Black, 0.4f) else hue,
            modifier = Modifier.size(metrics.glyphDp.dp),
        )
        Text(
            item.name,
            color = TextPrimary,
            fontFamily = AppFont,
            fontWeight = FontWeight.Bold,
            fontSize = metrics.labelSp.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )
    }
}

/**
 * A tile filled with real covers from that category.
 *
 * Up to four, tiled two by two behind a scrim so the label stays readable over
 * whatever the artwork happens to be — a pale sleeve under white text is the failure
 * mode, and it is the *common* one on jazz and classical.
 *
 * Falls back to the plain tinted tile when there is nothing to draw: an empty library,
 * a category with no artwork (Shuffle all, Genres), or covers not fetched yet. That
 * fallback is the reason this look is safe to offer at all — it degrades to something
 * deliberate rather than to a hole.
 */
@Composable
private fun CategoryMosaicTile(
    item: MaItem,
    metrics: CategoryMetrics,
    art: List<String>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val hue = rememberCategoryHueFor(item.itemId)
    val light = LocalSendspinColors.current.isLight
    val press = rememberPressScale()
    val shape = RoundedCornerShape(metrics.cornerDp.dp)
    val tiles = art.take(4)

    Box(
        modifier
            .pressScale(press)
            .height(metrics.heightDp.dp)
            .clip(shape)
            .background(hue.a(if (light) 0.20f else 0.12f))
            .border(1.dp, hue.a(if (light) 0.30f else 0.20f), shape)
            .clickable(interactionSource = press.interactions, indication = null, onClick = onClick),
    ) {
        if (tiles.isNotEmpty()) {
            Column(Modifier.fillMaxSize()) {
                // Two rows of two. An odd count leaves the last cell as the tint
                // showing through, which reads as intentional at this size.
                listOf(tiles.take(2), tiles.drop(2).take(2)).forEach { rowArt ->
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        rowArt.forEach { url ->
                            val req = rememberArtRequest(url, pixels = 200)
                            Box(Modifier.fillMaxSize().weight(1f).background(Ink3)) {
                                if (req != null) {
                                    AsyncImage(
                                        model = req,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                        // Pad a short row so two covers do not stretch across the
                        // whole tile and stop reading as a mosaic.
                        repeat(2 - rowArt.size) { Box(Modifier.fillMaxSize().weight(1f)) }
                    }
                }
            }
            // Bottom-weighted scrim: the label sits at the bottom-left, so the top of
            // the artwork stays visible and only the part under the text is darkened.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color.Black.a(0.72f)),
                            start = Offset.Zero,
                            end = Offset.Infinite,
                        ),
                    ),
            )
        }

        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier
                    .size((metrics.glyphDp + 14).dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (tiles.isEmpty()) hue.a(0.26f) else Color.Black.a(0.42f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    categoryIconOf(item.itemId),
                    null,
                    // Over artwork the glyph is always white — the album's own hue is
                    // not reliably legible against the album's own cover.
                    tint = when {
                        tiles.isNotEmpty() -> Color.White
                        light -> lerp(hue, Color.Black, 0.45f)
                        else -> hue
                    },
                    modifier = Modifier.size(metrics.glyphDp.dp),
                )
            }
            Text(
                item.name,
                color = if (tiles.isNotEmpty()) Color.White else TextPrimary,
                fontFamily = AppFont,
                fontWeight = FontWeight.Bold,
                fontSize = metrics.labelSp.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The hue a category wears, eased as the album under it changes.
 *
 * Shared by all five looks rather than duplicated per renderer, so switching look
 * never reshuffles the colours — see `LibraryTiles.kt`, which this mirrors and which
 * keeps its own private copy for the card it still owns.
 */
@Composable
private fun rememberCategoryHueFor(id: String): Color {
    val target = LocalPalette.current.swatch(categoryHueOf(id))
    val eased by animateColorAsState(target, Motion.effects(), label = "categoryHue")
    return eased
}

/** An unstyled placeholder for a category with nothing to say. Kept for parity. */
@Composable
internal fun CategoryEntryPlaceholder(metrics: CategoryMetrics, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(metrics.heightDp.dp)
            .clip(RoundedCornerShape(metrics.cornerDp.dp))
            .background(Ink3)
            .border(1.dp, HairlineSoft, RoundedCornerShape(metrics.cornerDp.dp)),
    ) {
        Text(
            "",
            color = TextSecondary,
            fontFamily = AppFont,
            fontSize = metrics.labelSp.sp,
            modifier = Modifier.align(Alignment.BottomStart).offset(x = 12.dp, y = (-12).dp),
        )
    }
}
