package com.engabd.sendpin.ui.screens

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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
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
 * The press-scale is the same gesture feedback the cover tiles give, on the same
 * `spatialFast` token: these sit in the same grid as the artwork, and a card that
 * stayed inert while its neighbours responded read as the one that had not loaded.
 * Scale only — never alpha, which belongs on an `effects` spec (see [Motion]).
 */
@Composable
internal fun CategoryCard(item: MaItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = Motion.spatialFast(),
        label = "categoryPress",
    )
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(15.dp))
            .background(inkOn(0.035f))
            .border(1.dp, HairlineSoft, RoundedCornerShape(15.dp))
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(accent.a(0.12f))
                .border(1.dp, accent.a(0.22f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(categoryIcon(item.itemId), null, tint = accent, modifier = Modifier.size(18.dp)) }
        Text(
            item.name, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Icon(Icons.Default.ChevronRight, null, tint = TextFaint, modifier = Modifier.size(16.dp))
    }
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
    else -> Icons.AutoMirrored.Filled.QueueMusic
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CoverTile(
    item: MaItem,
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        if (onLongPress != null) {
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress)
        } else {
            Modifier.clickable(onClick = onClick)
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RowCard(
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(inkOn(0.03f))
            .border(1.dp, HairlineSoft, RoundedCornerShape(14.dp))
            .then(
                when {
                    onLongClick != null ->
                        Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
                    onClick != null -> Modifier.clickable(onClick = onClick)
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
