package com.engabd.sendpin.ui.screens

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import coil.compose.AsyncImage
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.AlbumDetailViewModel

/**
 * The album detail screen — a Symphonium-style view where the album art leads,
 * tinted by its own palette, and the track list sits below with numbers,
 * durations and a now-playing equalizer.
 *
 * The whole screen is wrapped in the album's own [AlbumPalette] so the bloom,
 * accent and glows all come from the cover — not the now-playing track. This is
 * the first screen in the app to use per-album palette extraction, and it's what
 * makes it feel different from the flat browse stack: every album has its own
 * colour world.
 */
@Composable
fun AlbumDetailScreen(
    itemId: String,
    provider: String,
    name: String,
    artUrl: String?,
    onBack: () -> Unit,
    onArtistClick: (String, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val viewModel: AlbumDetailViewModel = viewModel(
        factory = AlbumDetailViewModelFactory(
            context.applicationContext as Application,
            itemId, provider, name, artUrl,
        )
    )

    val album by viewModel.album.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val albumDownloaded by viewModel.allDownloaded.collectAsState()
    val accent = LocalAccent.current
    val snackbar = remember { SnackbarHostState() }
    // The track whose long-press menu is open, if any.
    var actionsFor by remember { mutableStateOf<MaItem?>(null) }

    LaunchedEffect(Unit) { viewModel.toast.collect { snackbar.showSnackbar(it) } }
    BackHandler { onBack() }

    // The album's own palette — the whole screen is tinted by the cover.
    val albumArt = album?.image ?: artUrl
    val albumPalette = rememberAlbumPalette(albumArt)

    CompositionLocalProvider(
        LocalAccent provides albumPalette.accent,
        LocalPalette provides albumPalette,
    ) {
        Box(Modifier.fillMaxSize().background(Ink)) {
            MeltBackdrop(albumArt, intensity = 0.7f)

            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                // Header
                AlbumHeader(
                    albumName = album?.name ?: name,
                    albumArt = albumArt,
                    onBack = onBack,
                )

                // Body
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        bottom = navBarInset() + 24.dp,
                    ),
                ) {
                    // Album hero: art + title + artist + metadata + actions
                    item {
                        AlbumHero(
                            album = album,
                            albumName = album?.name ?: name,
                            artUrl = albumArt,
                            trackCount = tracks.size,
                            totalDuration = tracks.sumOf { it.duration ?: 0 },
                            onPlayAll = viewModel::playAll,
                            onShuffle = viewModel::shuffleAll,
                            onAddToQueue = viewModel::addToQueue,
                            onArtistClick = onArtistClick,
                            onDownload = if (viewModel.canDownload) viewModel::downloadAll else null,
                            downloaded = albumDownloaded,
                            onFavorite = viewModel::toggleAlbumFavorite,
                        )
                    }

                    // Track list
                    if (loading && tracks.isEmpty()) {
                        items(6) { SkeletonTrackRow() }
                    } else if (error != null && tracks.isEmpty()) {
                        item { ErrorState(error!!) { viewModel.loadAlbum() } }
                    } else if (tracks.isEmpty()) {
                        item { EmptyState("No tracks", "This album appears to be empty.") }
                    } else {
                        // Multi-disc grouping: when tracks span 2+ discs, render
                        // a "Disc N" header between groups. A single disc (or all
                        // null/1) renders flat as before.
                        val discGroups = tracks.groupBy { it.discNumber ?: 1 }
                        val multiDisc = discGroups.size > 1
                        var runningIndex = 0
                        discGroups.forEach { (disc, discTracks) ->
                            if (multiDisc) {
                                item(key = "disc:$disc") {
                                    DiscHeader(disc = disc, accent = albumPalette.accent)
                                }
                            }
                            itemsIndexed(discTracks, key = { i, t -> "track:$disc:$i:${t.itemId}" }) { offset, track ->
                                val index = runningIndex + offset
                                TrackRow(
                                    track = track,
                                    index = index,
                                    accent = albumPalette.accent,
                                    onPlay = { viewModel.playTrack(track) },
                                    onFavorite = { viewModel.toggleFavorite(track) },
                                    onLongPress = { actionsFor = track },
                                )
                            }
                            runningIndex += discTracks.size
                        }
                    }
                }
            }

            // Snackbar
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = navBarInset() + 8.dp, start = 16.dp, end = 16.dp),
            ) { data ->
                Snackbar(
                    containerColor = Ink3, contentColor = TextPrimary,
                    shape = RoundedCornerShape(14.dp),
                ) { Text(data.visuals.message, fontFamily = AppFont, fontSize = 13.sp) }
            }

            // Long-press on a track: queue it without losing what's playing.
            actionsFor?.let { picked ->
                MediaActionsSheet(
                    item = picked,
                    onClose = { actionsFor = null },
                    onPlayNow = { viewModel.playTrack(picked) },
                    onPlayNext = { viewModel.enqueueTrack(picked, "next") },
                    onAddToQueue = { viewModel.enqueueTrack(picked, "add") },
                )
            }
        }
    }
}

// --- header ---------------------------------------------------------------

@Composable
private fun AlbumHeader(albumName: String, albumArt: String?, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary,
            modifier = Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onBack),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            albumName, color = TextPrimary, fontFamily = AppFont,
            fontWeight = FontWeight.ExtraBold, fontSize = 18.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// --- hero (art + title + metadata + actions) ------------------------------

@Composable
private fun AlbumHero(
    album: MaItem?,
    albumName: String,
    artUrl: String?,
    trackCount: Int,
    totalDuration: Int,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onAddToQueue: () -> Unit,
    onArtistClick: (String, String) -> Unit = { _, _ -> },
    onDownload: (() -> Unit)? = null,
    downloaded: Boolean = false,
    onFavorite: () -> Unit = {},
) {
    val accent = LocalAccent.current

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Album art — large, centered, with a palette glow behind it
        Box(
            Modifier.size(220.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Glow behind the art
            CastGlow(accent, RoundedCornerShape(16.dp), blurRadius = 40.dp, alpha = 0.35f, offsetY = 12.dp)
            // The cover
            val art = rememberArtRequest(artUrl, pixels = 600)
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = "Album art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .shadow(24.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp)),
                )
            } else {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Ink3),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Album, null, tint = TextFaint, modifier = Modifier.size(48.dp)) }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Album title
        Text(
            albumName,
            color = TextPrimary, fontFamily = AppFont,
            fontWeight = FontWeight.ExtraBold, fontSize = 22.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        // Artist — tappable to navigate to the artist detail screen
        album?.subtitle?.let { artist ->
            if (artist.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    artist,
                    color = accent, fontFamily = AppFont,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable {
                        // The subtitle is a display credit, not an id — and a joined
                        // one ("Wilco, Billy Bragg") when an album has several
                        // artists. Hand over the first name; the artist screen
                        // searches for it, since MaItem carries no artist id.
                        onArtistClick(artist.substringBefore(",").trim(), album.provider)
                    },
                )
            }
        }

        // Metadata line: year · genre · track count · total duration
        val metaParts = buildList {
            album?.year?.let { add(it.toString()) }
            album?.genres?.takeIf { it.isNotEmpty() }?.let { add(it.take(2).joinToString(", ")) }
            if (trackCount > 0) add("$trackCount tracks")
            if (totalDuration > 0) add(formatDuration(totalDuration))
        }
        if (metaParts.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                metaParts.joinToString("  ·  "),
                color = TextMuted, fontFamily = AppFont,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }

        // Action row: Play + Shuffle + Add to queue
        Spacer(Modifier.height(20.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Play button — the accent disc
            PlayButton(playing = false, size = 56.dp, onClick = onPlayAll)
            // Shuffle
            IconChip(Icons.Default.Shuffle, "Shuffle", onClick = onShuffle)
            // Add to queue
            IconChip(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue", onClick = onAddToQueue)
            // The album itself, not its tracks. MA's `favorites/add_item` takes any
            // media item's uri and Subsonic's `star` has an `albumId`.
            IconChip(
                if (album?.favorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                if (album?.favorite == true) "Remove from favourites" else "Add to favourites",
                onClick = onFavorite,
            )
            // Offline. Navidrome only — Music Assistant streams rather than handing
            // over the file, so there is nothing here to take with you.
            onDownload?.let {
                IconChip(
                    if (downloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                    if (downloaded) "Downloaded" else "Download album",
                    onClick = it,
                )
            }
        }
    }
}

// --- track row ------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TrackRow(
    track: MaItem,
    index: Int,
    accent: Color,
    onPlay: () -> Unit,
    onFavorite: (() -> Unit)? = null,
    /** Long-press opens the queue actions. Null leaves the row tap-only. */
    onLongPress: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPlay, onLongClick = onLongPress)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Track number or equalizer (for now just the number — equalizer needs now-playing state)
        Text(
            "${track.trackNumber ?: (index + 1)}",
            color = TextMuted, fontFamily = AppFont,
            fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.width(28.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )

        // Title + artist
        Column(Modifier.weight(1f)) {
            Text(
                track.name,
                color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.Bold, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            track.subtitle?.takeIf { it.isNotBlank() }?.let { artist ->
                Text(
                    artist,
                    color = TextMuted, fontFamily = AppFont,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Duration
        track.duration?.let { dur ->
            if (dur > 0) {
                Text(
                    formatDuration(dur),
                    color = TextFaint, fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
            }
        }

        // Favorite. Shown hollow when it isn't one: rendering the heart only for a
        // track already favourited meant the handler was reachable to *un*-favourite
        // and nothing else, so a track could never be favourited from this screen.
        onFavorite?.let { fav ->
            Icon(
                if (track.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                if (track.favorite) "Remove from favourites" else "Add to favourites",
                tint = if (track.favorite) accent else TextMuted,
                modifier = Modifier.size(16.dp).clip(CircleShape).clickable(onClick = fav),
            )
        }
    }
}

// --- multi-disc header ---------------------------------------------------

/**
 * A "Disc N" header that sits between track groups on a multi-disc album.
 * Only rendered when the album has 2+ distinct disc numbers.
 */
@Composable
private fun DiscHeader(disc: Int, accent: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .width(3.dp).height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent.a(0.6f)),
        )
        Text(
            "Disc $disc",
            color = accent,
            fontFamily = AppFont,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.5.sp,
        )
    }
}

// --- states ---------------------------------------------------------------

@Composable
internal fun SkeletonTrackRow() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(28.dp).height(14.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.a(0.06f)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.fillMaxWidth(0.55f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.a(0.07f)))
            Box(Modifier.fillMaxWidth(0.32f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.a(0.05f)))
        }
    }
}

@Composable
internal fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Glass)
                .border(1.dp, Hairline, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Album, null, tint = TextFaint, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.height(14.dp))
        Text(title, color = TextSecondary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text(body, color = TextMuted, fontFamily = AppFont, fontSize = 12.sp)
    }
}

@Composable
internal fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(ErrorRed.a(0.10f))
                .border(1.dp, ErrorRed.a(0.28f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.ErrorOutline, null, tint = ErrorRed, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.height(14.dp))
        Text("Couldn't load", color = TextSecondary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text(message, color = TextMuted, fontFamily = AppFont, fontSize = 12.sp, maxLines = 3)
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.clip(RoundedCornerShape(100)).background(Glass)
                .border(1.dp, Hairline, RoundedCornerShape(100))
                .clickable(onClick = onRetry).padding(horizontal = 20.dp, vertical = 10.dp),
        ) { Text("Retry", color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }
}

// --- helpers --------------------------------------------------------------

/** Format seconds as M:SS or H:MM:SS. */
fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "0:00"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "$m:${s.toString().padStart(2, '0')}"
}

// --- ViewModel factory ----------------------------------------------------

/** Passes navigation arguments into the AlbumDetailViewModel constructor. */
class AlbumDetailViewModelFactory(
    private val app: Application,
    private val itemId: String,
    private val provider: String,
    private val name: String,
    private val art: String?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        AlbumDetailViewModel(app, itemId, provider, name, art) as T
}