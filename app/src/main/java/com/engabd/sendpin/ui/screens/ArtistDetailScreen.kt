package com.engabd.sendpin.ui.screens

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.ArtistDetailViewModel
import com.engabd.sendpin.ui.viewmodel.ArtistDetailViewModelFactory

/** A section label used between content blocks. */
@Composable
private fun Shelf(text: String) {
    Box(Modifier.padding(top = 12.dp, bottom = 2.dp)) { SectionLabel(text) }
}

/**
 * The artist's biography, from the server's own metadata (Navidrome fills this
 * from last.fm via `getArtistInfo2`). Clamped to four lines and expanded on tap —
 * these run to several paragraphs, and the albums are what the screen is for.
 */
@Composable
private fun Biography(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Shelf("About")
        Text(
            text,
            color = TextMuted,
            fontFamily = AppFont,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
}

/**
 * The artist detail screen — a page that leads with the artist's image (or a
 * gradient avatar when none), shows their albums as a cover grid, and lists top
 * tracks below. Like [AlbumDetailScreen], the whole screen is tinted by the
 * artist's palette via [rememberAlbumPalette].
 *
 * Tapping an album navigates to [AlbumDetailScreen] via [onAlbumClick].
 */
@Composable
fun ArtistDetailScreen(
    itemId: String,
    provider: String,
    name: String,
    artUrl: String?,
    onBack: () -> Unit,
    onAlbumClick: (MaItem) -> Unit,
    /** [itemId] is a placeholder and [name] is the real key — see the album screen. */
    resolveByName: Boolean = false,
) {
    val context = LocalContext.current
    val viewModel: ArtistDetailViewModel = viewModel(
        factory = ArtistDetailViewModelFactory(
            context.applicationContext as Application,
            itemId, provider, name, artUrl, resolveByName,
        )
    )

    val artist by viewModel.artist.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val downloaded by viewModel.allDownloaded.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val biography by viewModel.biography.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var actionsFor by remember { mutableStateOf<MaItem?>(null) }

    LaunchedEffect(Unit) { viewModel.toast.collect { snackbar.showSnackbar(it) } }
    BackHandler { onBack() }

    val artistArt = artist?.image ?: artUrl
    val artistPalette = rememberAlbumPalette(artistArt)

    CompositionLocalProvider(
        LocalAccent provides artistPalette.accent,
        LocalPalette provides artistPalette,
    ) {
        Box(Modifier.fillMaxSize().background(Ink)) {
            MeltBackdrop(artistArt, intensity = 0.5f)

            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                // Header
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
                        artist?.name ?: name, color = TextPrimary, fontFamily = AppFont,
                        fontWeight = FontWeight.ExtraBold, fontSize = 18.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = navBarInset() + 24.dp),
                ) {
                    // Artist hero
                    item {
                        ArtistHero(
                            artist = artist, artUrl = artistArt, albumCount = albums.size,
                            onPlayAll = viewModel::playAll,
                            onShuffle = viewModel::shuffleAll,
                            onAddToQueue = viewModel::addToQueue,
                            onDownload = if (viewModel.canDownload) viewModel::downloadAll else null,
                            downloaded = downloaded,
                            onFavorite = viewModel::toggleFavorite,
                        )
                    }

                    if (loading && albums.isEmpty()) {
                        items(4) { SkeletonTrackRow() }
                        return@LazyColumn
                    }

                    if (error != null && albums.isEmpty()) {
                        item { ErrorState(error!!) { viewModel.loadArtist() } }
                        return@LazyColumn
                    }

                    // Biography, when the server has one (Navidrome's getArtistInfo2).
                    biography?.takeIf { it.isNotBlank() }?.let { bio ->
                        item(key = "bio") { Biography(bio) }
                    }

                    // Albums. Keyed by index as well as id: MA numbers library items
                    // per media type and can answer the same album twice, and a
                    // duplicate key is a hard crash in a lazy list.
                    if (albums.isNotEmpty()) {
                        item { Shelf("Albums") }
                        itemsIndexed(albums, key = { i, a -> "album:$i:${a.itemId}" }) { _, album ->
                            AlbumRow(
                                album = album,
                                accent = artistPalette.accent,
                                onClick = { onAlbumClick(album) },
                                onLongPress = { actionsFor = album },
                            )
                        }
                    } else if (!loading) {
                        item { EmptyState("No albums", "This artist has no albums in your library.") }
                    }
                }
            }

            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = navBarInset() + 8.dp, start = 16.dp, end = 16.dp),
            ) { data ->
                Snackbar(containerColor = Ink3, contentColor = TextPrimary, shape = RoundedCornerShape(14.dp)) {
                    Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Long-press an album: queue it without leaving the artist.
            actionsFor?.let { picked ->
                MediaActionsSheet(
                    item = picked,
                    onClose = { actionsFor = null },
                    onPlayNow = { viewModel.playAlbum(picked, "replace") },
                    onPlayNext = { viewModel.playAlbum(picked, "next") },
                    onAddToQueue = { viewModel.playAlbum(picked, "add") },
                    onDownload = if (viewModel.canDownload) {
                        { viewModel.downloadAlbum(picked) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun ArtistHero(
    artist: MaItem?,
    artUrl: String?,
    albumCount: Int,
    onPlayAll: () -> Unit = {},
    onShuffle: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    /** Null on Music Assistant, which streams rather than handing over the file. */
    onDownload: (() -> Unit)? = null,
    downloaded: Boolean = false,
    onFavorite: () -> Unit = {},
) {
    val accent = LocalAccent.current

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Artist image — circular, or gradient avatar if no art
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            CastGlow(accent, CircleShape, blurRadius = 36.dp, alpha = 0.3f)
            val art = rememberArtRequest(artUrl, pixels = 400)
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = "Artist image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().shadow(20.dp, CircleShape).clip(CircleShape),
                )
            } else {
                GradientAvatar(artist?.name ?: "?", 0, size = 180.dp)
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            artist?.name ?: "Artist",
            color = TextPrimary, fontFamily = AppFont,
            fontWeight = FontWeight.ExtraBold, fontSize = 24.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )

        // Metadata: album count · genres
        val metaParts = buildList {
            if (albumCount > 0) add("$albumCount albums")
            artist?.genres?.takeIf { it.isNotEmpty() }?.let { add(it.take(2).joinToString(", ")) }
        }
        if (metaParts.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                metaParts.joinToString("  ·  "),
                color = TextMuted, fontFamily = AppFont,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayButton(playing = false, size = 56.dp, onClick = onPlayAll)
            IconChip(Icons.Default.Shuffle, "Shuffle", onClick = onShuffle)
            IconChip(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue", onClick = onAddToQueue)
            // Both backends can favourite an artist — MA takes the uri on
            // `favorites/add_item`, Subsonic takes `artistId` on `star`.
            IconChip(
                if (artist?.favorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                if (artist?.favorite == true) "Remove from favourites" else "Add to favourites",
                onClick = onFavorite,
            )
            // Every album this artist has, taken offline in one go.
            onDownload?.let {
                IconChip(
                    if (downloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                    if (downloaded) "Downloaded" else "Download discography",
                    onClick = it,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumRow(album: MaItem, accent: Color, onClick: () -> Unit, onLongPress: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(
                if (onLongPress != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress)
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Album thumbnail
        val art = rememberArtRequest(album.image, pixels = 120)
        if (art != null) {
            AsyncImage(
                model = art, contentDescription = album.name, contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Ink3),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Album, null, tint = TextFaint, modifier = Modifier.size(20.dp)) }
        }

        Column(Modifier.weight(1f)) {
            Text(
                album.name, color = TextPrimary, fontFamily = AppFont,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            album.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = TextMuted, fontFamily = AppFont, fontSize = 12.sp, maxLines = 1)
            }
        }

        Icon(Icons.Default.ChevronRight, null, tint = TextFaint, modifier = Modifier.size(18.dp))
    }
}