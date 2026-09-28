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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
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

    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // The track whose long-press menu is open, if any.
    var actionsFor by remember { mutableStateOf<MaItem?>(null) }
    // Whether the "keep it as a playlist?" chooser is up — see [DownloadChoiceDialog].
    var downloadChoice by remember { mutableStateOf(false) }
    // Edit mode: rows gain move and remove controls. See PlaylistDetailViewModel's
    // editing section for what each does against the server.
    var editing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    BackHandler(enabled = editing) { editing = false }

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
                    if ((viewModel.canEdit && tracks.isNotEmpty()) || editing) {
                        Spacer(Modifier.width(12.dp))
                        IconChip(
                            if (editing) Icons.Default.Check else Icons.Default.Edit,
                            if (editing) "Done editing" else "Edit playlist",
                            active = editing,
                            onClick = { editing = !editing },
                        )
                    }
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
                            coverSeed = itemId.hashCode(),
                            trackCount = tracks.size,
                            totalDuration = tracks.sumOf { it.duration ?: 0 },
                            onPlayAll = viewModel::playAll,
                            onShuffle = viewModel::shuffleAll,
                            onAddToQueue = viewModel::addToQueue,
                            onFavorite = if (provider == SubsonicClient.PROVIDER) null
                            else viewModel::togglePlaylistFavorite,
                            favorite = playlist?.favorite == true,
                            // This screen had no download control at all, which is
                            // the one place a user would look for one.
                            onDownload = if (viewModel.downloadable && tracks.isNotEmpty()) {
                                { downloadChoice = true }
                            } else null,
                        )
                    }

                    if (loading && tracks.isEmpty()) {
                        items(6, contentType = { "skeleton" }) { SkeletonTrackRow() }
                    } else if (error != null && tracks.isEmpty()) {
                        item { ErrorState(error!!) { viewModel.loadPlaylist() } }
                    } else if (tracks.isEmpty()) {
                        item { EmptyState("No tracks", "This playlist is empty.") }
                    } else {
                        // Index in the key, not the item id alone: a playlist is
                        // allowed to hold the same track twice, and a duplicate key
                        // is a hard crash in a lazy list.
                        if (editing && viewModel.canRename) {
                            item(key = "rename", contentType = "rename") {
                                Row(
                                    Modifier.fillMaxWidth().clickable { renaming = true }
                                        .padding(horizontal = 20.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Icon(Icons.Default.DriveFileRenameOutline, null, tint = playlistPalette.accent, modifier = Modifier.size(20.dp))
                                    Text("Rename playlist", color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                            }
                        }
                        if (editing) {
                            itemsIndexed(
                                tracks,
                                key = { i, t -> "edit:$i:${t.itemId}" },
                                contentType = { _, _ -> "editTrack" },
                            ) { index, track ->
                                EditTrackRow(
                                    track = track,
                                    index = index,
                                    last = index == tracks.lastIndex,
                                    onUp = { viewModel.move(index, index - 1) },
                                    onDown = { viewModel.move(index, index + 1) },
                                    onRemove = { viewModel.removeAt(index) },
                                )
                            }
                        } else itemsIndexed(
                            tracks,
                            key = { i, t -> "track:$i:${t.itemId}" },
                            contentType = { _, _ -> "track" },
                        ) { index, track ->
                            TrackRow(
                                track = track,
                                index = index,
                                accent = playlistPalette.accent,
                                onPlay = { viewModel.playTrack(track) },
                                onFavorite = { viewModel.toggleFavorite(track) },
                                onLongPress = { actionsFor = track },
                                onAddToQueue = { viewModel.enqueueTrack(track, "add") },
                                onPlayNext = { viewModel.enqueueTrack(track, "next") },
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

            if (renaming) {
                RenamePlaylistDialog(
                    current = playlist?.name ?: name,
                    onDismiss = { renaming = false },
                    onRename = { renaming = false; viewModel.rename(it) },
                )
            }

            if (downloadChoice) {
                DownloadChoiceDialog(
                    playlistName = playlist?.name ?: name,
                    onDismiss = { downloadChoice = false },
                    onChoose = { keepPlaylist ->
                        downloadChoice = false
                        viewModel.download(keepPlaylist)
                    },
                )
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
    /**
     * Which colours a cover-less playlist is generated in. The playlist's own id, so
     * it is the same cover here as on the tile that was tapped to get here — see
     * [com.engabd.sendpin.ui.design.GeneratedCover].
     */
    coverSeed: Int,
    trackCount: Int,
    totalDuration: Int,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onAddToQueue: () -> Unit,
    /** Null on Navidrome, whose `star` has no playlist parameter. */
    onFavorite: (() -> Unit)? = null,
    favorite: Boolean = false,
    /** Null where the library cannot hand the files over — Downloads and MA. */
    onDownload: (() -> Unit)? = null,
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
                // The same generated cover the library tiles use, so a playlist with no
                // artwork is recognisably the same playlist here as it was in the grid
                // you tapped. See [com.engabd.sendpin.ui.design.GeneratedCover].
                GeneratedCover(
                    coverSeed,
                    Icons.AutoMirrored.Filled.QueueMusic,
                    Modifier.fillMaxSize().shadow(24.dp, RoundedCornerShape(16.dp)),
                    RoundedCornerShape(16.dp),
                )
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
            onDownload?.let {
                IconChip(Icons.Default.Download, "Download for offline", onClick = it)
            }
        }
    }
}

/**
 * One row in edit mode: the track, and the three things that can be done to it.
 * Buttons rather than drag handles, so a reorder is possible with TalkBack and with
 * one thumb; each is a full 48 dp target.
 */
@Composable
private fun EditTrackRow(
    track: MaItem,
    index: Int,
    last: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${index + 1}", color = TextMuted, fontFamily = AppFont,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.width(28.dp), textAlign = TextAlign.End,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                track.name, color = TextPrimary, fontFamily = AppFont,
                style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            track.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = TextMuted, fontFamily = AppFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onUp, enabled = index > 0) {
            Icon(Icons.Default.ArrowUpward, "Move ${track.name} up", tint = if (index > 0) TextSecondary else TextFaint)
        }
        IconButton(onClick = onDown, enabled = !last) {
            Icon(Icons.Default.ArrowDownward, "Move ${track.name} down", tint = if (!last) TextSecondary else TextFaint)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.RemoveCircleOutline, "Remove ${track.name} from the playlist", tint = ErrorRed)
        }
    }
}

@Composable
private fun RenamePlaylistDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    // Opened focused, with the cursor after the current name — a rename is usually an
    // edit of the name, and a cursor at the start put typing in front of it.
    var text by remember {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(current, androidx.compose.ui.text.TextRange(current.length)))
    }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink2,
        title = { Text("Rename playlist", color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(text.text) }, enabled = text.text.isNotBlank()) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}