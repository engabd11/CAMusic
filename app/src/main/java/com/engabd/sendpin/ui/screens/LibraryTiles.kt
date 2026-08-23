package com.engabd.sendpin.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.engabd.sendpin.ui.design.sharedArt
import com.engabd.sendpin.download.DownloadJob
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*

/**
 * What one library item looks like: category cards, cover tiles, row cards and list
 * rows, plus the download-job row that sits among them.
 *
 * Split out of `LibraryScreen.kt` — see the note at the top of that file. This is the
 * most-edited part of the library and the part with the most repeated elements on
 * screen, so it is the one worth being able to open on its own.
 */

/**
 * A root-level browse category — Artists, Albums, Radio stations, Podcasts.
 *
 * These are the only things on the front page with no artwork of their own, and as
 * plain bordered rows with a chevron they were the dullest thing on a screen made of
 * album covers. So each one is given a colour and a picture instead: a hue drawn from
 * the palette of whatever is playing, a wash across the tile, and its own glyph blown
 * up and ghosted into the bottom corner where the chevron used to be.
 *
 * The hue is [categoryHue] — fixed per category, so Albums is always the same
 * *position* in the palette and the grid does not reshuffle its colours as you
 * browse — but taken from the current album, so the whole row belongs to the record
 * on the player. It eases rather than cutting when that record changes.
 *
 * A gradient and a border, and nothing else. No shadow, no bloom, no blur: this is a
 * grid cell, and every render effect here is paid for once per category on every
 * pass. The cover tiles gave up their elevation for the same reason.
 *
 * The press-scale is the same gesture feedback the cover tiles give, on the same
 * `spatialFast` token. Scale only — never alpha, which belongs on an `effects` spec
 * (see [Motion]).
 */
@Composable
internal fun CategoryCard(item: MaItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val hue = rememberCategoryHue(item.itemId)
    val light = LocalSendspinColors.current.isLight
    // An album swatch is lifted to sit on black. On the off-white page the same colour
    // at the same alpha is a tint with nothing behind it, so the wash and the edges
    // firm up — and the glyph, which has to be legible rather than atmospheric, comes
    // down towards the ink instead.
    val wash = if (light) 0.30f else 0.20f
    val fade = if (light) 0.10f else 0.05f
    val edge = if (light) 0.34f else 0.22f
    val ghost = if (light) 0.14f else 0.10f
    val mark = if (light) lerp(hue, Color.Black, 0.45f) else hue
    val press = rememberPressScale()
    val shape = RoundedCornerShape(18.dp)
    val glyph = categoryIcon(item.itemId)
    Box(
        modifier
            .pressScale(press)
            .height(CategoryTileHeight)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(hue.a(wash), hue.a(fade)),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                )
            )
            .border(1.dp, hue.a(edge), shape)
            .clickable(
                interactionSource = press.interactions,
                indication = null,
                onClick = onClick,
            ),
    ) {
        // The category's own glyph, oversized and pushed off the corner. Offset, not
        // padding, so it costs the layout nothing; the Box's clip is what cuts it.
        Icon(
            glyph, null,
            tint = hue.a(ghost),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 16.dp, y = 16.dp)
                .size(84.dp),
        )
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(hue.a(wash))
                    .border(1.dp, hue.a(edge + 0.08f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(glyph, null, tint = mark, modifier = Modifier.size(16.dp)) }
            Text(
                item.name, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Tall enough for a glyph chip, a name, and the ghost glyph to have somewhere to go. */
internal val CategoryTileHeight = 96.dp

/**
 * The colour this category wears, eased as the album under it changes.
 *
 * Read here rather than passed in, so the whole row of tiles animates together on one
 * spec. A colour, so `effects` — see [rememberAccent], which makes the same argument
 * at more length about why this is not done at the provider.
 */
@Composable
private fun rememberCategoryHue(id: String): Color {
    val target = LocalPalette.current.swatch(categoryHue(id))
    val eased by animateColorAsState(target, Motion.effects(), label = "categoryHue")
    return eased
}

/**
 * Which palette swatch a category takes, by hand rather than by hashing the id.
 *
 * A hash would be stable too, but it would put Artists and Albums — always adjacent,
 * always the first thing seen — on whatever two colours it happened to pick, and
 * often on neighbouring ones. Spacing them by hand is three lines and it is the
 * difference between a set and a coincidence.
 */
private fun categoryHue(id: String): Int = when (id) {
    "artists" -> 0
    "albums" -> 2
    "tracks" -> 4
    "playlists" -> 1
    "starred" -> 3
    "genres" -> 0
    "newest" -> 2
    "random" -> 4
    "radios" -> 1
    "podcasts" -> 3
    "downloads" -> 2
    else -> 0
}

private fun categoryIcon(id: String): ImageVector = when (id) {
    "artists" -> Icons.Default.Person
    "albums" -> Icons.Default.Album
    "tracks" -> Icons.Default.MusicNote
    "playlists" -> Icons.AutoMirrored.Filled.PlaylistPlay
    "radios" -> Icons.Default.Radio
    "podcasts" -> Icons.Default.Podcasts
    "downloads" -> Icons.Default.Download
    "newest" -> Icons.Default.Schedule
    // These three reached the QueueMusic fallback, so on a Subsonic library three of
    // the seven categories wore the same generic glyph.
    "genres" -> Icons.Default.Category
    "starred" -> Icons.Default.Star
    "random" -> Icons.Default.Shuffle
    else -> Icons.AutoMirrored.Filled.QueueMusic
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CoverTile(
    item: MaItem,
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val press = rememberPressScale()
    Column(
        Modifier
            .pressScale(press)
            .then(
                if (onLongPress != null) {
                    Modifier.combinedClickable(
                        interactionSource = press.interactions,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongPress,
                    )
                } else {
                    Modifier.clickable(
                        interactionSource = press.interactions,
                        indication = null,
                        onClick = onClick,
                    )
                }
            )
    ) {
        Box(
            // No elevation shadow. It cost a shadow-casting render node per grid cell
            // — the most-repeated element in the app — to draw something that is very
            // nearly invisible against a true-black background. The hairline border
            // below is what actually separates a cover from the page.
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Ink3)
                .border(1.dp, HairlineSoft, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val art = rememberArtRequest(item.image, pixels = 200)
            if (art != null) {
                AsyncImage(
                    model = art, contentDescription = item.name, contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .sharedArt("art-${item.itemId}-${item.provider}"),
                )
            } else {
                Icon(Icons.Default.Album, null, tint = TextFaint, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.name, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        item.subtitle?.let {
            Text(
                it, color = inkOn(0.38f), fontFamily = AppFont, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One tile in a shelf carousel.
 *
 * A fixed size, unlike [CoverTile], which fills whatever slot the grid hands it. The
 * carousel is a `LazyHorizontalGrid` inside a vertically-scrolling parent, and one of
 * those has to be given a height outright — an unbounded one throws. Deriving that
 * height from a tile that sizes itself to its text would make the number a guess that
 * a two-line album title could invalidate, so the tile is pinned instead and both
 * labels are held to one line.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfTile(
    item: MaItem,
    circular: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val press = rememberPressScale()
    val shape = if (circular) CircleShape else RoundedCornerShape(14.dp)
    Column(
        Modifier
            .pressScale(press)
            .width(ShelfTileWidth)
            .height(ShelfTileHeight)
            .then(
                if (onLongPress != null) {
                    Modifier.combinedClickable(
                        interactionSource = press.interactions,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongPress,
                    )
                } else {
                    Modifier.clickable(
                        interactionSource = press.interactions,
                        indication = null,
                        onClick = onClick,
                    )
                }
            ),
        horizontalAlignment = if (circular) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box(
            // No elevation shadow, for the same reason CoverTile has none: a
            // shadow-casting render node per cell, to draw something very nearly
            // invisible against a true-black background.
            Modifier
                .size(ShelfTileWidth)
                .clip(shape)
                .background(Ink3)
                .border(1.dp, HairlineSoft, shape),
            contentAlignment = Alignment.Center,
        ) {
            val art = rememberArtRequest(item.image, pixels = 240)
            if (art != null) {
                AsyncImage(
                    model = art, contentDescription = item.name, contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .sharedArt("art-${item.itemId}-${item.provider}"),
                )
            } else if (circular) {
                // An artist with no picture gets initials rather than a generic
                // silhouette — the same fallback the artist screen uses.
                GradientAvatar(
                    item.name.firstOrNull()?.uppercase() ?: "?",
                    item.itemId.hashCode(),
                    size = ShelfTileWidth,
                )
            } else {
                Icon(Icons.Default.Album, null, tint = TextFaint, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.name, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = if (circular) TextAlign.Center else null,
            modifier = Modifier.fillMaxWidth(),
        )
        item.subtitle?.let {
            Text(
                it, color = inkOn(0.38f), fontFamily = AppFont, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = if (circular) TextAlign.Center else null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** How wide one carousel tile is. Three and a bit fit across a phone. */
internal val ShelfTileWidth = 116.dp

/** Art, an 8dp gap, then two one-line labels. Fixed, so the carousel's height is too. */
internal val ShelfTileHeight = 158.dp

/** Between the two rows of a carousel. */
internal val ShelfRowGap = 12.dp

/** Between tiles along a carousel. */
internal val ShelfTileGap = 12.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RowCard(
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val press = rememberPressScale()
    Row(
        Modifier
            .pressScale(press)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(inkOn(0.03f))
            .border(1.dp, HairlineSoft, RoundedCornerShape(14.dp))
            .then(
                when {
                    onLongClick != null -> Modifier.combinedClickable(
                        interactionSource = press.interactions,
                        indication = null,
                        onClick = onClick ?: {},
                        onLongClick = onLongClick,
                    )
                    onClick != null -> Modifier.clickable(
                        interactionSource = press.interactions,
                        indication = null,
                        onClick = onClick,
                    )
                    else -> Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun ItemRow(
    item: MaItem,
    viewModel: LibraryViewModel,
    flags: RowFlags,
    onClick: (() -> Unit)? = null,
    onLongPress: ((MaItem) -> Unit)? = null,
    /**
     * One-motion swipe alternative to the long-press sheet's "Add to queue"/"Play
     * next" — track rows only, gated below. Both false (the default) leaves every
     * row swipe-free.
     */
    swipeToQueue: Boolean = false,
) {
    val accent = LocalAccent.current
    val isCategory = item.provider == "__cat__"
    val isDownload = item.provider == "__dl__"
    val isSubsonic = item.provider == "subsonic"
    val isSubsonicTrack = isSubsonic && item.mediaType == "track"
    // Artists and genres resolve to a lot of files; offering to download one by
    // accident from a list row would be a nasty surprise, so only the things a
    // user thinks of as "a download" get the button.
    val downloadable = isSubsonic && item.mediaType in DownloadableTypes
    val onDisk = flags.onDisk

    // A category card opens a list and a download row is already on the phone —
    // neither has anything the actions sheet could offer.
    val longPressable = !isCategory && !isDownload &&
        item.mediaType in LongPressableTypes

    // Only a real track resolves to a single queueable item — a swipe on an
    // album/artist/playlist row has no equivalent "just this one" enqueue.
    val swipeable = swipeToQueue && !isCategory && !isDownload && item.mediaType == "track"

    val card = @Composable {
        RowCard(
            onClick = {
                if (onClick != null) onClick()
                else viewModel.open(item)
            },
            onLongClick = if (longPressable && onLongPress != null) {
                { onLongPress(item) }
            } else null,
        ) {
        when {
            isCategory -> Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(accent.a(0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(categoryIcon(item.itemId), null, tint = accent, modifier = Modifier.size(20.dp)) }

            item.image != null -> {
                val art = rememberArtRequest(item.image, pixels = 120)
                AsyncImage(
                    model = art, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(Ink3),
                )
            }

            item.mediaType == "artist" ->
                GradientAvatar(item.name, item.name.hashCode(), size = 46.dp)

            else -> Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(Glass),
                contentAlignment = Alignment.Center,
            ) { Icon(mediaIcon(item.mediaType), null, tint = TextMuted, modifier = Modifier.size(18.dp)) }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                item.name, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            item.subtitle?.let {
                Text(
                    it, color = inkOn(0.38f), fontFamily = AppFont, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // MA tracks can be auditioned in place; Navidrome has no preview endpoint.
        val isMaTrack = !isCategory && !isDownload && !isSubsonic && item.mediaType == "track"
        if (isMaTrack) {
            Icon(
                if (flags.isPreviewing) Icons.Default.StopCircle else Icons.Default.Headphones,
                if (flags.isPreviewing) "Stop preview" else "Preview",
                tint = if (flags.isPreviewing) accent else TextMuted,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { viewModel.togglePreview(item) },
            )
        }
        // Favourites are a Navidrome star as much as an MA favourite, so the heart
        // belongs on both backends rather than only one — and on albums and artists
        // as much as tracks. `music/favorites/add_item` takes any media item's uri
        // and Subsonic's `star` has an `albumId`/`artistId` for exactly this; the
        // heart was gated to MA *tracks* for no reason either server imposed.
        //
        // Subsonic's `star` has no playlist parameter, so that one really is
        // backend-specific.
        val favouritable = !isCategory && !isDownload && when {
            isSubsonic -> item.mediaType in SubsonicActionTypes
            else -> item.mediaType in MaActionTypes
        }
        if (favouritable) {
            val isFav = flags.isFavorite
            Icon(
                if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                if (isFav) "Remove from favourites" else "Add to favourites",
                tint = if (isFav) accent else TextMuted,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { viewModel.toggleFavorite(item) },
            )
        }

        when {
            // A track already on disk says so instead of offering to fetch it again.
            downloadable && onDisk -> Icon(
                Icons.Default.DownloadDone, "Downloaded", tint = accent,
                modifier = Modifier.size(20.dp),
            )
            downloadable -> Icon(
                Icons.Default.Download,
                if (item.mediaType == "track") "Download" else "Download all",
                tint = TextMuted,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { viewModel.download(item) },
            )
            isDownload -> Icon(
                Icons.Default.Delete, "Delete download", tint = TextMuted,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { viewModel.deleteDownload(item.itemId) },
            )
        }

        if (item.playable || isDownload) {
            Icon(
                Icons.Default.Add, "Add to queue", tint = TextMuted,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { viewModel.play(item, "add") },
            )
        }

        when {
            isCategory || item.browsable -> Icon(Icons.Default.ChevronRight, null, tint = TextFaint, modifier = Modifier.size(16.dp))
            item.playable -> Icon(Icons.Default.PlayArrow, "Play", tint = accent, modifier = Modifier.size(18.dp))
        }
    }
    }
    if (swipeable) {
        SwipeToQueueRow(
            accent = accent,
            onAddToQueue = { viewModel.enqueueTrack(item, "add") },
            onPlayNext = { viewModel.enqueueTrack(item, "next") },
        ) { card() }
    } else {
        card()
    }
}

private fun mediaIcon(mediaType: String): ImageVector = when (mediaType) {
    "artist" -> Icons.Default.Person
    "album" -> Icons.Default.Album
    "playlist" -> Icons.AutoMirrored.Filled.PlaylistPlay
    "radio" -> Icons.Default.Radio
    "podcast", "podcast_episode" -> Icons.Default.Podcasts
    "audiobook", "chapter" -> Icons.AutoMirrored.Filled.MenuBook
    else -> Icons.Default.MusicNote
}

@Composable
internal fun DownloadJobRow(job: DownloadJob, viewModel: LibraryViewModel) {
    val accent = LocalAccent.current
    RowCard {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(Glass),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.MusicNote, null, tint = TextMuted, modifier = Modifier.size(18.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                job.title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (job.failed) "Failed · tap to dismiss" else "Downloading · ${(job.fraction * 100).toInt()}%",
                color = if (job.failed) ErrorRed else accent,
                fontFamily = AppFont, fontSize = 12.sp, maxLines = 1,
            )
        }
        if (job.failed) {
            Icon(
                Icons.Default.Refresh, "Dismiss", tint = ErrorRed,
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable { viewModel.dismissDownload(job.id) },
            )
        } else {
            RingProgress(job.fraction)
        }
    }
}
