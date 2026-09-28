package com.engabd.sendpin.ui.screens

import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*

/*
 * The "Gallery" dress for the album and artist pages, and the optional shelves.
 *
 * Everything here is painted from [LocalPalette] — the cover's own colours, the same
 * source the rest of the app is tinted from — so a page in this style is a different
 * arrangement of the album's colour world, not a different colour scheme. Nothing is
 * shown unless its setting asks for it; see [com.engabd.sendpin.data.PageLook].
 */

// ── Album: the sleeve and its record ──────────────────────────────────────────

/**
 * The album hero in the Gallery style: the sleeve, with its record sliding out from
 * behind it, and editorial type below.
 *
 * The record's label is printed in the cover's colours, so every album's disc is its
 * own. It spins at 33⅓ while [spinning] — this album is what is playing — and stands
 * still under Reduced motion, as every other moving thing in the app does.
 */
@Composable
internal fun GalleryAlbumHero(
    album: MaItem?,
    albumName: String,
    artUrl: String?,
    sharedArtKey: String,
    trackCount: Int,
    totalDuration: Int,
    spinning: Boolean,
    onArtistClick: (String, String) -> Unit,
    actions: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccent.current
    val reduced = LocalReducedMotion.current
    val sleeve = 224.dp

    // The record slides out once, as the page arrives, and then stays out.
    val slide = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) slide.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow))
    }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Box(Modifier.fillMaxWidth().height(sleeve + 28.dp)) {
            VinylRecord(
                spinning = spinning && !reduced,
                label = accent,
                labelEdge = palette.swatch(1),
                mark = palette.swatch(2),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
                    .offset(x = sleeve * 0.52f * slide.value)
                    .size(sleeve * 0.94f),
            )
            Box(
                Modifier.align(Alignment.CenterStart).padding(start = 24.dp).size(sleeve),
                contentAlignment = Alignment.Center,
            ) {
                CastGlow(accent, RoundedCornerShape(10.dp), blurRadius = 36.dp, alpha = 0.40f, offsetY = 14.dp)
                val art = rememberArtRequest(artUrl, pixels = 600)
                if (art != null) {
                    AsyncImage(
                        model = art,
                        contentDescription = "Album art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .sharedArt(sharedArtKey)
                            .shadow(20.dp, RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Ink3),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Album, null, tint = TextFaint, modifier = Modifier.size(48.dp)) }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(18.dp))
            Text(
                albumName,
                color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 34.sp,
                letterSpacing = (-0.5).sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            album?.subtitle?.takeIf { it.isNotBlank() }?.let { artist ->
                Spacer(Modifier.height(6.dp))
                Text(
                    artist,
                    color = accent, fontFamily = AppFont,
                    fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable {
                        onArtistClick(artist.substringBefore(",").trim(), album.provider)
                    },
                )
            }
            val meta = buildList {
                album?.year?.takeIf { it > 0 }?.let { add(it.toString()) }
                album?.genres?.firstOrNull()?.let { add(it) }
                if (trackCount > 0) add("$trackCount tracks")
                if (totalDuration > 0) add(formatDuration(totalDuration))
            }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    meta.joinToString("  ·  ").uppercase(),
                    color = TextMuted, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                    fontSize = 10.sp, letterSpacing = 1.4.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            // The sleeve's colours as a row of dots — the page's key, in miniature.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                paletteSwatches(palette, accent).take(6).forEach { c ->
                    Box(Modifier.size(12.dp).clip(CircleShape).background(c).border(1.dp, HairlineSoft, CircleShape))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        actions()
    }
}

/**
 * A vinyl record, drawn: grooves, a label in [label] ringed by [labelEdge], and a
 * small printed [mark] so that turning is visible at all. The sheen is drawn outside
 * the rotation — light does not turn with the disc.
 */
@Composable
internal fun VinylRecord(
    spinning: Boolean,
    label: Color,
    labelEdge: Color,
    mark: Color,
    modifier: Modifier = Modifier,
) {
    val angle = if (spinning) {
        val t = rememberInfiniteTransition(label = "vinyl")
        // 33⅓ rpm: one turn in 1.8 s.
        t.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
            label = "vinyl-angle",
        ).value
    } else 0f

    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        rotate(angle, c) {
            drawCircle(Color(0xFF0C0C0E), r, c)
            // Grooves: fine rings, alternately a touch lighter, to catch the light.
            var gr = r * 0.97f
            var i = 0
            while (gr > r * 0.40f) {
                drawCircle(
                    Color.White.copy(alpha = if (i % 3 == 0) 0.07f else 0.03f),
                    gr, c, style = Stroke(width = 0.7.dp.toPx()),
                )
                gr -= r * 0.022f
                i++
            }
            // The label, in the sleeve's colours.
            drawCircle(Brush.radialGradient(listOf(label, labelEdge), c, r * 0.34f), r * 0.34f, c)
            drawCircle(labelEdge.copy(alpha = 0.8f), r * 0.34f, c, style = Stroke(width = 1.5.dp.toPx()))
            // Printing on the label: two arcs and a dot, off-centre on purpose — a disc
            // that is symmetric all the way round looks exactly the same turning as
            // standing still, which is what the first version of this did.
            val band = Stroke(width = r * 0.045f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawArc(
                mark.copy(alpha = 0.9f), startAngle = -150f, sweepAngle = 110f, useCenter = false,
                topLeft = Offset(c.x - r * 0.25f, c.y - r * 0.25f),
                size = androidx.compose.ui.geometry.Size(r * 0.5f, r * 0.5f), style = band,
            )
            drawArc(
                Color.White.copy(alpha = 0.55f), startAngle = 20f, sweepAngle = 60f, useCenter = false,
                topLeft = Offset(c.x - r * 0.17f, c.y - r * 0.17f),
                size = androidx.compose.ui.geometry.Size(r * 0.34f, r * 0.34f),
                style = Stroke(width = r * 0.03f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            drawCircle(mark.copy(alpha = 0.85f), r * 0.045f, Offset(c.x + r * 0.2f, c.y - r * 0.08f))
            drawCircle(Color(0xFF0C0C0E), r * 0.03f, c)
        }
        // A fixed highlight across the grooves.
        drawCircle(
            Brush.sweepGradient(
                0f to Color.Transparent, 0.10f to Color.White.copy(alpha = 0.08f), 0.2f to Color.Transparent,
                0.5f to Color.Transparent, 0.60f to Color.White.copy(alpha = 0.06f), 0.7f to Color.Transparent,
                1f to Color.Transparent,
                center = c,
            ),
            r, c,
        )
    }
}

/** The palette as a short list of distinct colours, accent first. */
internal fun paletteSwatches(palette: AlbumPalette, accent: Color): List<Color> =
    (listOf(accent) + palette.swatches).distinctBy { it.toArgb() }

// ── Artist: the banner ─────────────────────────────────────────────────────────

/**
 * The artist hero in the Gallery style: a full-width portrait, turned to a duotone in
 * the palette's colours, with the name set large across its foot.
 *
 * The duotone is what makes a photograph belong to the page — a press shot in its own
 * colours sits on top of an album's colour world rather than in it. Without a photo
 * the banner is the palette itself, with the initial.
 */
@Composable
internal fun GalleryArtistHero(
    name: String,
    artUrl: String?,
    albums: List<MaItem>,
    genres: List<String>,
    actions: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccent.current
    val years = albums.mapNotNull { it.year?.takeIf { y -> y > 0 } }
    val active = when {
        years.isEmpty() -> null
        years.min() == years.max() -> years.min().toString()
        else -> "${years.min()}–${years.max()}"
    }

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(340.dp)
                // Its own layer, so the duotone blends with the photo and nothing else.
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            val art = rememberArtRequest(artUrl, pixels = 900)
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = "Artist image",
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
                    modifier = Modifier.fillMaxSize(),
                )
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(
                        Brush.linearGradient(listOf(accent, palette.swatch(1)), Offset.Zero, Offset(size.width, size.height)),
                        blendMode = BlendMode.Color,
                    )
                }
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(accent.a(0.85f), palette.swatch(1).a(0.7f), palette.swatch(2).a(0.5f))),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name.firstOrNull()?.uppercase() ?: "?",
                        color = Color.White.copy(alpha = 0.18f), fontFamily = AppFont,
                        fontWeight = FontWeight.Black, fontSize = 220.sp,
                    )
                }
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 1f to Ink),
                ),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 24.dp, vertical = 18.dp)) {
                Text(
                    "ARTIST", color = accent, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                    fontSize = 11.sp, letterSpacing = 2.sp,
                )
                Text(
                    name, color = Color.White, fontFamily = AppFont,
                    fontWeight = FontWeight.Black, fontSize = 40.sp, lineHeight = 42.sp,
                    letterSpacing = (-1).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val stats = buildList {
                    if (albums.isNotEmpty()) add("${albums.size} ${if (albums.size == 1) "record" else "records"}")
                    active?.let { add(it) }
                    genres.take(2).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
                }
                if (stats.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stats.joinToString("  ·  "), color = Color.White.copy(alpha = 0.72f),
                        fontFamily = AppFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        actions()
    }
}

// ── Shelves ────────────────────────────────────────────────────────────────────

@Composable
private fun ShelfTitle(text: String) {
    Box(Modifier.padding(horizontal = 20.dp)) { SectionLabel(text) }
}

/** "Colours of the sleeve": the palette the page is painted in, as swatches. */
@Composable
internal fun PaletteShelf() {
    val palette = LocalPalette.current
    val accent = LocalAccent.current
    val colours = paletteSwatches(palette, accent).take(5)
    Column(Modifier.padding(top = 26.dp)) {
        ShelfTitle("Colours of the sleeve")
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            colours.forEachIndexed { i, c ->
                Column(Modifier.weight(if (i == 0) 1.4f else 1f)) {
                    Box(
                        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(14.dp))
                            .background(c).border(1.dp, HairlineSoft, RoundedCornerShape(14.dp)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "#%06X".format(c.toArgb() and 0xFFFFFF), color = TextFaint,
                        fontFamily = MonoFont, fontSize = 9.sp, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** What the "Your listening" shelves show. Null fields simply are not drawn. */
internal data class ListeningStats(
    val plays: Int,
    val firstPlayed: Long?,
    val lastPlayed: Long?,
    val totalMs: Long,
    val topTrack: String?,
    val topTrackPlays: Int,
)

/**
 * "Your listening": this phone's own history of the album or artist.
 *
 * From the Stats screen's play history, so it counts what was listened to here — a
 * library server's play count is a different number and says so on its own screens.
 */
@Composable
internal fun ListeningShelf(stats: ListeningStats?, forArtist: Boolean) {
    val accent = LocalAccent.current
    Column(Modifier.padding(top = 26.dp)) {
        ShelfTitle("Your listening")
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(accent.a(0.16f), Glass)))
                .border(1.dp, HairlineSoft, RoundedCornerShape(18.dp))
                .padding(16.dp),
        ) {
            if (stats == null || stats.plays == 0) {
                Text(
                    if (stats == null) "Reading your history…"
                    else "Not played on this phone yet. It fills in as you listen.",
                    color = TextMuted, fontFamily = AppFont, fontSize = 13.sp,
                )
                return@Column
            }
            val now = System.currentTimeMillis()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(stats.plays.toString(), if (stats.plays == 1) "play" else "plays", Modifier.weight(1f))
                // Rows written before the player measured listening time carry none,
                // and "0m listened" beside six plays reads as broken.
                if (stats.totalMs >= 60_000) Stat(formatHours(stats.totalMs), "listened", Modifier.weight(1f))
                if (forArtist) {
                    stats.firstPlayed?.let {
                        Stat(DateUtils.getRelativeTimeSpanString(it, now, DateUtils.DAY_IN_MILLIS).toString(), "first heard", Modifier.weight(1.3f))
                    }
                } else {
                    stats.lastPlayed?.let {
                        Stat(DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString(), "last played", Modifier.weight(1.3f))
                    }
                }
            }
            stats.topTrack?.takeIf { it.isNotBlank() }?.let { title ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Your favourite: ", color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
                    )
                    Text(
                        title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        "  ·  ${stats.topTrackPlays}×", color = TextFaint, fontFamily = AppFont, fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold,
            fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(label.uppercase(), color = TextFaint, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.2.sp)
    }
}

private fun formatHours(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "${minutes}m" else "%.1fh".format(minutes / 60f)
}

/**
 * "Latest release": the newest record, given the stage — a card in that record's own
 * colours rather than the page's, so it reads as the next thing to open.
 */
@Composable
internal fun LatestReleaseShelf(album: MaItem, onClick: () -> Unit) {
    val own = rememberAlbumPalette(album.image)
    Column(Modifier.padding(top = 22.dp)) {
        ShelfTitle("Latest release")
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.horizontalGradient(listOf(own.accent.a(0.34f), own.swatch(1).a(0.14f), Glass)))
                .border(1.dp, HairlineSoft, RoundedCornerShape(22.dp))
                .clickable(onClick = onClick)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                CastGlow(own.accent, RoundedCornerShape(12.dp), blurRadius = 22.dp, alpha = 0.45f, offsetY = 8.dp)
                val art = rememberArtRequest(album.image, pixels = 320)
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Ink3)) {
                    if (art != null) {
                        AsyncImage(model = art, contentDescription = album.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                album.year?.takeIf { it > 0 }?.let {
                    Text(
                        it.toString(), color = own.accent, fontFamily = MonoFont, fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
                Text(
                    album.name, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold,
                    fontSize = 19.sp, lineHeight = 23.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                album.genres.firstOrNull()?.let {
                    Text(it, color = TextMuted, fontFamily = AppFont, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

/**
 * "Through the years": the discography along a timeline, oldest first, one stop per
 * record, the year printed above the line only where it changes.
 */
@Composable
internal fun TimelineShelf(albums: List<MaItem>, onClick: (MaItem) -> Unit) {
    val accent = LocalAccent.current
    val line = Hairline
    val ordered = remember(albums) { albums.sortedWith(compareBy(nullsLast()) { it.year?.takeIf { y -> y > 0 } }) }
    val tile: Dp = 108.dp
    val gap: Dp = 14.dp
    Column(Modifier.padding(top = 26.dp)) {
        ShelfTitle("Through the years")
        Spacer(Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            itemsIndexed(ordered, key = { i, a -> "tl:$i:${a.itemId}" }, contentType = { _, _ -> "timeline" }) { i, album ->
                val year = album.year?.takeIf { it > 0 }
                val showYear = i == 0 || ordered[i - 1].year != album.year
                val last = i == ordered.lastIndex
                Column(Modifier.width(tile).clickable { onClick(album) }) {
                    Text(
                        if (showYear) (year?.toString() ?: "—") else "",
                        color = accent, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        modifier = Modifier.height(18.dp),
                    )
                    val gapPx = gap
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(18.dp)
                            .drawBehind {
                                val y = size.height / 2f
                                val end = if (last) size.width / 2f else size.width + gapPx.toPx()
                                drawLine(line, Offset(if (i == 0) size.width / 2f else 0f, y), Offset(end, y), strokeWidth = 1.5.dp.toPx())
                                drawCircle(if (showYear) accent else line, (if (showYear) 5f else 3.5f).dp.toPx(), Offset(size.width / 2f, y))
                            },
                    )
                    Spacer(Modifier.height(8.dp))
                    val art = rememberArtRequest(album.image, pixels = 280)
                    Box(Modifier.size(tile).clip(RoundedCornerShape(12.dp)).background(Ink3)) {
                        if (art != null) {
                            AsyncImage(model = art, contentDescription = album.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        album.name, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                        fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The newest record: highest year, and among equals the one listed first. */
internal fun latestOf(albums: List<MaItem>): MaItem? =
    albums.filter { (it.year ?: 0) > 0 }.maxByOrNull { it.year ?: 0 }

/** A play pill for cards that start something. */
@Composable
internal fun PlayPill(label: String, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(100)).background(accent).clickable(onClick = onClick)
            .padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Color.Black, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

// ── History, for the "Your listening" shelves ─────────────────────────────────

/** This phone's plays of [album] by [artist] (blank matches any artist). */
internal suspend fun albumListening(context: android.content.Context, album: String, artist: String): ListeningStats =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dao = com.engabd.sendpin.local.db.LocalMediaDatabase.get(context).playHistoryDao()
        val sum = dao.albumListening(album, artist)
        val top = if (sum.plays > 0) dao.albumTopTrack(album, artist) else null
        ListeningStats(sum.plays, sum.firstPlayed, sum.lastPlayed, sum.totalMs, top?.title, top?.plays ?: 0)
    }

/** This phone's plays of [artist]. */
internal suspend fun artistListening(context: android.content.Context, artist: String): ListeningStats =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dao = com.engabd.sendpin.local.db.LocalMediaDatabase.get(context).playHistoryDao()
        val sum = dao.artistListening(artist)
        val top = if (sum.plays > 0) dao.artistTopTrack(artist) else null
        ListeningStats(sum.plays, sum.firstPlayed, sum.lastPlayed, sum.totalMs, top?.title, top?.plays ?: 0)
    }

// ── Library: the spotlight ────────────────────────────────────────────────────

/**
 * One record from what is new in the library, on a card painted in its own colours.
 * The same one all day — [spotlightPick] keys it on the date — so it reads as a
 * choice rather than a slot machine.
 */
@Composable
internal fun SpotlightCard(item: MaItem, onOpen: () -> Unit, onPlay: () -> Unit) {
    val own = rememberAlbumPalette(item.image)
    val art = rememberArtRequest(item.image, pixels = 420)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Ink2)
            .clickable(onClick = onOpen),
    ) {
        // The cover itself, blown up and softened, as the card's ground.
        if (art != null) {
            AsyncImage(
                model = art, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
                    .graphicsLayer { alpha = 0.55f; scaleX = 1.4f; scaleY = 1.4f }
                    .blur(48.dp),
            )
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(listOf(Ink.a(0.25f), own.accent.a(0.22f), Ink.a(0.78f))),
            ),
        )
        Box(Modifier.matchParentSize().border(1.dp, own.accent.a(0.30f), RoundedCornerShape(24.dp)))
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                CastGlow(own.accent, RoundedCornerShape(12.dp), blurRadius = 26.dp, alpha = 0.55f, offsetY = 10.dp)
                Box(Modifier.fillMaxSize().shadow(16.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(Ink3)) {
                    if (art != null) {
                        AsyncImage(model = art, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                // White, not the accent: the card is tinted with that same accent, and on
                // a red sleeve a red label on a red card all but disappeared.
                Text(
                    "SPOTLIGHT", color = Color.White.copy(alpha = 0.8f), fontFamily = AppFont, fontWeight = FontWeight.Bold,
                    fontSize = 10.sp, letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    item.name, color = Color.White, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp, lineHeight = 23.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                item.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color.White.copy(alpha = 0.75f), fontFamily = AppFont, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                item.year?.takeIf { it > 0 }?.let {
                    Text(it.toString(), color = Color.White.copy(alpha = 0.5f), fontFamily = MonoFont, fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                PlayPill("Play", own.accent, onPlay)
            }
        }
    }
}

/** Today's record from [candidates]: the same all day, a different one tomorrow. */
internal fun spotlightPick(candidates: List<MaItem>): MaItem? {
    val albums = candidates.filter { it.mediaType == "album" && it.image != null }
    if (albums.isEmpty()) return null
    val day = System.currentTimeMillis() / DateUtils.DAY_IN_MILLIS
    return albums[(day % albums.size).toInt()]
}


/** Two titles for the same thing, however they are spaced, cased or width-encoded. */
internal fun sameTitle(a: String?, b: String?): Boolean {
    fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
        .trim().replace(Regex("\\s+"), " ").lowercase()
    if (a.isNullOrBlank() || b.isNullOrBlank()) return false
    return norm(a) == norm(b)
}
