package com.engabd.sendpin.ui.screens

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    val topTracks by viewModel.topTracks.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }

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

            Column(Modifier.fillMaxSize()) {
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
                    item { ArtistHero(artist = artist, artUrl = artistArt, albumCount = albums.size) }

                    if (loading && albums.isEmpty() && topTracks.isEmpty()) {
                        items(4) { SkeletonTrackRow() }
                        return@LazyColumn
                    }

                    if (error != null && albums.isEmpty()) {
                        item { ErrorState(error!!) { viewModel.loadArtist() } }
                        return@LazyColumn
                    }

                    // Top tracks
                    if (topTracks.isNotEmpty()) {
                        item { Shelf("Top tracks") }
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PlayButton(playing = false, size = 48.dp, onClick = viewModel::shuffleTopTracks)
                                Text("Shuffle top tracks", color = TextSecondary, fontFamily = AppFont, fontSize = 13.sp)
                            }
                        }
                        itemsIndexed(topTracks, key = { _, t -> t.itemId }) { index, track ->
                            TrackRow(
                                track = track,
                                index = index,
                                accent = artistPalette.accent,
                                onPlay = { viewModel.playTrack(track) },
                            )
                        }
                    }

                    // Albums
                    if (albums.isNotEmpty()) {
                        item { Shelf("Albums") }
                        items(albums, key = { it.itemId }) { album ->
                            AlbumRow(album = album, accent = artistPalette.accent, onClick = { onAlbumClick(album) })
                        }
                    } else if (!loading && topTracks.isEmpty()) {
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
                    Text(data.visuals.message, fontFamily = AppFont, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ArtistHero(artist: MaItem?, artUrl: String?, albumCount: Int) {
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
    }
}

@Composable
private fun AlbumRow(album: MaItem, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
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
                fontWeight = FontWeight.Bold, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            album.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = TextMuted, fontFamily = AppFont, fontSize = 12.sp, maxLines = 1)
            }
        }

        Icon(Icons.Default.ChevronRight, null, tint = TextFaint, modifier = Modifier.size(18.dp))
    }
}