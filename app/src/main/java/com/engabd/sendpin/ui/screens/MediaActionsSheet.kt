package com.engabd.sendpin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.library.Ratings
import com.engabd.sendpin.ma.MaItem
import kotlinx.coroutines.launch
import com.engabd.sendpin.ui.design.HideBottomChrome
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.dismissOnDragDown
import com.engabd.sendpin.ui.design.systemNavInset
import com.engabd.sendpin.ui.design.TitleGap
import com.engabd.sendpin.ui.theme.*

/**
 * What to do with one library item, on long-press.
 *
 * Tapping anything has always meant "play this now", which replaces the queue — so
 * queueing something up without losing what's playing had no gesture at all. The
 * album and artist screens grew "add to queue" chips for the release they were
 * showing; this is the same three choices on the one gesture a list row has spare,
 * for any media type.
 *
 * Music Assistant takes an artist, album or playlist uri on `play_media` exactly as
 * it takes a track's, so all three actions mean the same thing whatever [item] is;
 * only the wording changes. On a library this phone plays itself, the library
 * resolves a container to its tracks first.
 *
 * Drawn the same way as the other sheets in this app (`NowPlayingSheet`,
 * `PlayerOptionsSheet`) rather than as a `ModalBottomSheet`, so it matches.
 */
@Composable
fun BoxScope.MediaActionsSheet(
    item: MaItem,
    onClose: () -> Unit,
    /**
     * Null leaves the row out.
     *
     * Nullable because the player's own long-press sheet has no queue actions to
     * offer — it is already looking at the thing that is playing — and passing `{}`
     * for these rendered three rows that read as actions and did nothing.
     */
    onPlayNow: (() -> Unit)? = null,
    /** Null leaves the row out. See [onPlayNow]. */
    onPlayNext: (() -> Unit)? = null,
    /** Null leaves the row out. See [onPlayNow]. */
    onAddToQueue: (() -> Unit)? = null,
    /** Null leaves the row out — nothing on the MA backend can be downloaded. */
    onDownload: (() -> Unit)? = null,
    /** Null leaves the row out. Absent for a playlist — filing one into itself. */
    onAddToPlaylist: (() -> Unit)? = null,
    /**
     * Null leaves the row out. Offered on the Music Assistant backend only, where the
     * server can generate a queue from the item.
     */
    onStartRadio: (() -> Unit)? = null,
    /** Null leaves the row out. Only ever passed for a track's album. */
    onGoToAlbum: (() -> Unit)? = null,
    /** Null leaves the row out. Only ever passed for a track's artist. */
    onGoToArtist: (() -> Unit)? = null,
    /**
     * Null leaves the row out. Only ever passed for the track that is playing, since
     * "more like this" is answered about the record on the deck.
     */
    onMoreLikeThis: (() -> Unit)? = null,
    /** Null leaves the row out. */
    onShare: (() -> Unit)? = null,
    /** Null leaves the row out. Only ever passed for a playlist the server owns. */
    onDelete: (() -> Unit)? = null,
    /** Null leaves the row out. Lets the user correct the Light Sync palette for this album. */
    onEditLightSyncColours: (() -> Unit)? = null,
    /**
     * Null leaves the row out; otherwise it says whether [item] is starred now.
     *
     * The heart on a list row only ever existed on a list row, so anything drawn as a
     * cover tile — which is most albums and every playlist in a library with artwork —
     * could not be starred from the library at all. Here it is, on the gesture every
     * tile already answers.
     */
    isFavourite: Boolean? = null,
    /** Null leaves the row out. See [isFavourite]. */
    onToggleFavourite: (() -> Unit)? = null,
) {
    HideBottomChrome()
    BackHandler(onBack = onClose)

    // Dismiss scrim. Sits inside the caller's Box, so it covers the screen behind.
    Box(
        Modifier
            .matchParentSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClose() }
    )

    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .dismissOnDragDown(onClose)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(Ink2)
            .border(1.dp, Hairline, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)),
    ) {
        // The system inset only: the sheet is drawn over the tab bar and the mini
        // player, and `HideBottomChrome` takes them off screen while it is up.
        Column(Modifier.fillMaxWidth().padding(bottom = systemNavInset() + 12.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(100)).background(Hairline))
            }
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp)) {
                Text(
                    item.name, color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // The type is worth saying out loud: an album and its title track
                // often share a name, and the three actions mean rather different
                // amounts of music depending on which one this is.
                val caption = listOfNotNull(
                    typeLabel(item.mediaType),
                    item.subtitle?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (caption.isNotBlank()) {
                    Text(
                        caption, color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val whole = item.mediaType != "track" && item.mediaType != "radio"
            onPlayNow?.let { play ->
                ActionRow(
                    Icons.Default.PlayArrow, "Play now",
                    if (whole) "Replaces the queue with all of it" else "Replaces the queue",
                ) { onClose(); play() }
            }
            onPlayNext?.let { next ->
                ActionRow(
                    Icons.AutoMirrored.Filled.PlaylistAdd, "Play next",
                    "After the current track",
                ) { onClose(); next() }
            }
            onAddToQueue?.let { queue ->
                ActionRow(
                    Icons.AutoMirrored.Filled.QueueMusic, "Add to queue",
                    "At the end",
                ) { onClose(); queue() }
            }
            onAddToPlaylist?.let { add ->
                ActionRow(
                    Icons.Default.LibraryAdd, "Add to playlist",
                    if (whole) "Every track in it" else "Pick a playlist",
                ) { onClose(); add() }
            }
            onStartRadio?.let { radio ->
                // The deliberate form of what the Radio Mode setting used to do to
                // every tap. Said out loud here, because a generated queue is a
                // different thing from the item that was pressed.
                ActionRow(
                    Icons.Default.Radio, "Start radio",
                    "Plays this, then more like it",
                ) { onClose(); radio() }
            }
            onGoToAlbum?.let { go ->
                ActionRow(Icons.Default.Album, "Go to album", item.album ?: "Open the album") { onClose(); go() }
            }
            onGoToArtist?.let { go ->
                ActionRow(Icons.Default.Person, "Go to artist", item.subtitle ?: "Open the artist") { onClose(); go() }
            }
            onMoreLikeThis?.let { more ->
                ActionRow(
                    Icons.Default.GraphicEq, "More like this",
                    "Tracks that sound like this one",
                ) { onClose(); more() }
            }
            RatingRow(item)
            if (isFavourite != null && onToggleFavourite != null) {
                ActionRow(
                    if (isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (isFavourite) "Remove from favourites" else "Add to favourites",
                    if (whole) "Keeps it on the Starred page" else "Keeps it on the Starred page",
                ) { onClose(); onToggleFavourite() }
            }
            onShare?.let { share ->
                ActionRow(Icons.Default.Share, "Share", item.name) { onClose(); share() }
            }
            onDownload?.let { dl ->
                ActionRow(
                    Icons.Default.Download, "Download",
                    if (whole) "Every track, for offline" else "For offline",
                ) { onClose(); dl() }
            }
            onEditLightSyncColours?.let { edit ->
                ActionRow(
                    Icons.Default.Palette, "Light Sync colours",
                    "Change the colours this album lights the room with",
                ) { onClose(); edit() }
            }
            onDelete?.let { del ->
                var confirming by remember { mutableStateOf(false) }
                // Confirm in place rather than stacking a dialog on a sheet: this
                // deletes on the server, for every client, and cannot be undone.
                ActionRow(
                    Icons.Default.DeleteOutline,
                    if (confirming) "Tap again to delete" else "Delete playlist",
                    if (confirming) "This can't be undone" else "Removes it from the server",
                    tint = if (confirming) ErrorRed else null,
                ) { if (confirming) { onClose(); del() } else confirming = true }
            }
        }
    }
}

/**
 * Five stars, on every sheet whose item comes from a library that keeps ratings —
 * Navidrome and other Subsonic servers, Plex, and MPD's sticker database. Absent
 * everywhere else, rather than drawn and inert.
 *
 * Reads the active library itself (see [Ratings]) so each of the five screens that
 * open this sheet gets it without threading one more callback through them. Tapping
 * the star that is already the rating clears it, the way Navidrome's own UI does.
 * The sheet stays open: a rating is something one adjusts, not a command.
 */
@Composable
private fun RatingRow(item: MaItem) {
    val context = LocalContext.current
    val app = context.applicationContext as? SendpinApp ?: return
    val source by app.musicSource.collectAsState()
    val rater = Ratings.rater(source, item) ?: return
    val scope = rememberCoroutineScope()
    var stars by remember(item.provider, item.itemId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(rater, item.provider, item.itemId) { stars = Ratings.current(rater, item) }
    val accent = LocalAccent.current

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Default.Star, null, tint = accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text("Rating", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            Text(
                when (val n = stars) {
                    null -> "Checking…"
                    0 -> "Not rated"
                    1 -> "1 star"
                    else -> "$n stars"
                },
                color = TextFaint, fontFamily = AppFont, fontSize = 11.sp,
            )
        }
        Row {
            for (n in 1..5) {
                val on = (stars ?: 0) >= n
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(100))
                        .semantics { selected = stars == n }
                        .clickable(
                            role = Role.Button,
                            onClickLabel = if (stars == n) "Clear rating" else "Rate $n",
                        ) {
                            val before = stars ?: 0
                            val next = if (before == n) 0 else n
                            stars = next
                            scope.launch {
                                try {
                                    Ratings.set(rater, item, next)
                                } catch (e: Exception) {
                                    stars = before
                                    android.widget.Toast.makeText(
                                        context, e.message ?: "Couldn't save the rating",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (on) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (n == 1) "1 star" else "$n stars",
                        tint = if (on) accent else TextMuted,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/** How a listener would name a media type, or null for one not worth captioning. */
private fun typeLabel(mediaType: String): String? = when (mediaType) {
    "album" -> "Album"
    "artist" -> "Artist"
    "playlist" -> "Playlist"
    "radio" -> "Radio"
    "podcast" -> "Podcast"
    "podcast_episode" -> "Episode"
    "audiobook" -> "Audiobook"
    "chapter" -> "Chapter"
    "track" -> null          // a row in a track list is obviously a track
    else -> null
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = tint ?: accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, color = TextFaint, fontFamily = AppFont, fontSize = 11.sp)
        }
    }
}
