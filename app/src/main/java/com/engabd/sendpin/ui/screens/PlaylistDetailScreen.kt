package com.engabd.sendpin.ui.screens

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.engabd.sendpin.subsonic.SubsonicClient
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.PlaylistDetailViewModel
import com.engabd.sendpin.ui.viewmodel.PlaylistDetailViewModelFactory

/**
 * The playlist detail screen — same layout language as [AlbumDetailScreen] but
 * for playlists: playlist art, title, track count + duration, Play/Shuffle/
 * Add to Queue, and a track list.
 */
@Composable
fun PlaylistDetailScreen(
    itemId: String,
    provider: String,
    name: String,
    artUrl: String?,
    onBack: () -> Unit,
    onAlbumClick: (MaItem) -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: PlaylistDetailViewModel = viewModel(
        factory = PlaylistDetailViewModelFactory(
            context.applicationContext as Application,
            itemId, provider, name, artUrl,
        )
    )

    val playlist by viewModel.playlist.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    // The track whose long-press menu is open, if any.
    var actionsFor by remember { mutableStateOf<MaItem?>(null) }

    LaunchedEffect(Unit) { viewModel.toast.collect { snackbar.showSnackbar(it) } }
    BackHandler { onBack() }

    val playlistArt = playlist?.image ?: artUrl
    val playlistPalette = rememberAlbumPalette(playlistArt)

    CompositionLocalProvider(
        LocalAccent provides playlistPalette.accent,
        LocalPalette provides playlistPalette,
    ) {
        Box(Modifier.fillMaxSize().background(Ink)) {
            MeltBackdrop(playlistArt, intensity = 0.5f)

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
                        playlist?.name ?: name, color = TextPrimary, fontFamily = AppFont,
                        fontWeight = FontWeight.ExtraBold, fontSize = 18.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = navBarInset() + 24.dp),
                ) {
                    // Hero
                    item {
                        PlaylistHero(
                            name = playlist?.name ?: name,
                            artUrl = playlistArt,
                            trackCount = tracks.size,
                            totalDuration = tracks.sumOf { it.duration ?: 0 },
                            onPlayAll = viewModel::playAll,
                            onShuffle = viewModel::shuffleAll,
                            onAddToQueue = viewModel::addToQueue,
                            onFavorite = if (provider == SubsonicClient.PROVIDER) null
                            else viewModel::togglePlaylistFavorite,
                            favorite = playlist?.favorite == true,
                        )
                    }

                    if (loading && tracks.isEmpty()) {
                        items(6) { SkeletonTrackRow() }
                    } else if (error != null && tracks.isEmpty()) {
                        item { ErrorState(error!!) { viewModel.loadPlaylist() } }
                    } else if (tracks.isEmpty()) {
                        item { EmptyState("No tracks", "This playlist is empty.") }
                    } else {
                        // Index in the key, not the item id alone: a playlist is
                        // allowed to hold the same track twice, and a duplicate key
                        // is a hard crash in a lazy list.
                        itemsIndexed(tracks, key = { i, t -> "track:$i:${t.itemId}" }) { index, track ->
                            TrackRow(
                                track = track,
                                index = index,
                                accent = playlistPalette.accent,
                                onPlay = { viewModel.playTrack(track) },
                                onFavorite = { viewModel.toggleFavorite(track) },
                                onLongPress = { actionsFor = track },
                            )
                        }
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

@Composable
private fun PlaylistHero(
    name: String,
    artUrl: String?,
    trackCount: Int,
    totalDuration: Int,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onAddToQueue: () -> Unit,
    /** Null on Navidrome, whose `star` has no playlist parameter. */
    onFavorite: (() -> Unit)? = null,
    favorite: Boolean = false,
) {
    val accent = LocalAccent.current

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            CastGlow(accent, RoundedCornerShape(16.dp), blurRadius = 40.dp, alpha = 0.3f, offsetY = 12.dp)
            val art = rememberArtRequest(artUrl, pixels = 500)
            if (art != null) {
                AsyncImage(
                    model = art, contentDescription = "Playlist art", contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().shadow(24.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp)),
                )
            } else {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Ink3),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = TextFaint, modifier = Modifier.size(48.dp)) }
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            name, color = TextPrimary, fontFamily = AppFont,
            fontWeight = FontWeight.ExtraBold, fontSize = 22.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )

        val metaParts = buildList {
            if (trackCount > 0) add("$trackCount tracks")
            if (totalDuration > 0) add(formatDuration(totalDuration))
        }
        if (metaParts.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(metaParts.joinToString("  ·  "), color = TextMuted, fontFamily = AppFont, fontSize = 12.sp)
        }

        Spacer(Modifier.height(20.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayButton(playing = false, size = 56.dp, onClick = onPlayAll)
            IconChip(Icons.Default.Shuffle, "Shuffle", onClick = onShuffle)
            IconChip(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue", onClick = onAddToQueue)
            onFavorite?.let {
                IconChip(
                    if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (favorite) "Remove from favourites" else "Add to favourites",
                    onClick = it,
                )
            }
        }
    }
}