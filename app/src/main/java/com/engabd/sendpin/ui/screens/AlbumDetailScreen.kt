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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel

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
    /** Open another album — the related shelf's rows. */
    onAlbumClick: (MaItem) -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: AlbumDetailViewModel = viewModel(
        factory = AlbumDetailViewModelFactory(
            context.applicationContext as Application,
            itemId, provider, name, artUrl,
        )
    )

    val album by viewModel.album.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val albumDownloaded by viewModel.allDownloaded.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    val snackbar = remember { SnackbarHostState() }
    // The track whose long-press menu is open, if any.
    var actionsFor by remember { mutableStateOf<MaItem?>(null) }

    LaunchedEffect(Unit) { viewModel.toast.collect { snackbar.showSnackbar(it) } }
    BackHandler { onBack() }

    // The album's own palette — the whole screen is tinted by the cover.
    val albumArt = album?.image ?: artUrl
    val albumPalette = rememberAlbumPalette(albumArt)

    // Scans over the track list, hoisted out of the LazyColumn body below. That body is
    // a LazyListScope receiver rather than a composable, so it has no `remember` — a
    // groupBy and a sumOf written inside it are redone in full every time the list
    // rebuilds, which is on every state change this screen has.
    val totalDuration = remember(tracks) { tracks.sumOf { it.duration ?: 0 } }
    // Multi-disc grouping: when tracks span 2+ discs, a "Disc N" header goes between
    // groups. A single disc (or all null/1) renders flat as before.
    val discGroups = remember(tracks) { tracks.groupBy { it.discNumber ?: 1 } }

    val musicBrainzId by viewModel.musicBrainzId.collectAsStateWithLifecycle()
    val related by viewModel.related.collectAsStateWithLifecycle()
    val relatedTitle by viewModel.relatedTitle.collectAsStateWithLifecycle()

    // The facts worth stating about a record, gathered once rather than inside the
    // LazyListScope body (which is not composable and has no `remember`).
    val facts = remember(album, tracks, totalDuration, discGroups) {
        val a = album
        buildList {
            a?.year?.takeIf { it > 0 }?.let { add("Released" to it.toString()) }
            a?.genres?.takeIf { it.isNotEmpty() }?.let { add("Genre" to it.take(3).joinToString(", ")) }
            if (discGroups.size > 1) add("Discs" to discGroups.size.toString())
            if (tracks.isNotEmpty()) {
                add("Tracks" to tracks.size.toString())
                add("Length" to formatDuration(totalDuration))
            }
            // What the record is actually stored as — the audiophile question, and
            // already on every track row individually.
            tracks.mapNotNull { it.audioFormat?.quality?.shortLabel }.distinct()
                .takeIf { it.isNotEmpty() }
                ?.let { add("Format" to it.joinToString(", ")) }
        }
    }

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
                            sharedArtKey = "art-$itemId-$provider",
                            trackCount = tracks.size,
                            totalDuration = totalDuration,
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
                        items(6, contentType = { "skeleton" }) { SkeletonTrackRow() }
                    } else if (error != null && tracks.isEmpty()) {
                        item { ErrorState(error!!) { viewModel.loadAlbum() } }
                    } else if (tracks.isEmpty()) {
                        item { EmptyState("No tracks", "This album appears to be empty.") }
                    } else {
                        val multiDisc = discGroups.size > 1
                        var runningIndex = 0
                        discGroups.forEach { (disc, discTracks) ->
                            if (multiDisc) {
                                item(key = "disc:$disc", contentType = "discHeader") {
                                    DiscHeader(disc = disc, accent = albumPalette.accent)
                                }
                            }
                            itemsIndexed(
                                discTracks,
                                key = { i, t -> "track:$disc:$i:${t.itemId}" },
                                contentType = { _, _ -> "track" },
                            ) { offset, track ->
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

                    // ── About ────────────────────────────────────────────────
                    // After the songs, not before them: nobody opens a record to
                    // read about it first. The notes were Navidrome-only until now
                    // for want of one assignment on the MA path.
                    if (!notes.isNullOrBlank() || facts.isNotEmpty()) {
                        item(key = "about") {
                            AboutAlbum(
                                notes = notes,
                                facts = facts,
                                musicBrainzId = musicBrainzId,
                            )
                        }
                    }

                    // ── Related ──────────────────────────────────────────────
                    // Hidden entirely when there is nothing to say, rather than a
                    // heading over an empty row.
                    (related as? NowPlayingViewModel.Load.Ready)?.value
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { albums ->
                            item(key = "related") {
                                RelatedAlbums(
                                    title = relatedTitle,
                                    albums = albums,
                                    onClick = onAlbumClick,
                                )
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
                ) { Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium) }
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
    sharedArtKey: String,
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
                    // The route carries the artwork URL, so the hero renders the same
                    // image the tile was showing as the screen slides in, rather than
                    // flashing empty while the album request lands.
                    modifier = Modifier
                        .fillMaxSize()
                        .sharedArt(sharedArtKey)
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

        // Action row. Play sits in the middle rather than at the head: it is the one
        // thing on this screen with a coloured disc and a glow under it, so putting it
        // at the end of a row that reads left-to-right buried the loudest control
        // behind the quiet ones. Third of five on Navidrome, third of four on Music
        // Assistant — the same place either way, so it does not move when the backend
        // does.
        Spacer(Modifier.height(20.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconChip(Icons.Default.Shuffle, "Shuffle", onClick = onShuffle)
            IconChip(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue", onClick = onAddToQueue)

            PlayButton(playing = false, size = 56.dp, onClick = onPlayAll)

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
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.width(28.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )

        // Title + artist
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                track.name,
                color = TextPrimary, fontFamily = AppFont,
                style = MaterialTheme.typography.titleLarge,
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

        // What the file actually is: `FLAC 48/24`, `MP3 320k`. Both backends fill it —
        // Music Assistant from `provider_mappings[].audio_format`, Navidrome from
        // `suffix`/`contentType` — so a mixed library shows the difference between the
        // FLAC and the MP3 of the same album without opening either, and between the
        // 44.1/16 rip and the hi-res one.
        //
        // Lossless earns the accent. Rate and depth are the meaningful pair there;
        // for a lossy codec they are not, so it shows its bitrate instead.
        track.audioFormat?.quality?.let { q ->
            Text(
                q.shortLabel,
                color = if (q.lossless) accent.a(0.75f) else TextFaint,
                fontFamily = MonoFont,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                maxLines = 1,
            )
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

// --- liner notes ---------------------------------------------------------

/**
 * The album's notes, from the server's own metadata (Navidrome fills this from
 * last.fm via `getAlbumInfo2`). Clamped to three lines and expanded on tap — the
 * track list is what the screen is for, and these can run long.
 */
@Composable
private fun AlbumNotes(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text,
        color = TextMuted,
        fontFamily = AppFont,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

/**
 * What the record is, under what it sounds like.
 *
 * Sits after the track list because that is the order the questions arrive in: play
 * it, then wonder about it. The facts grid is the part that is genuinely new — year
 * and genre were buried in one comma-joined line in the hero, and the formats the
 * album is *stored* in were only visible per track.
 */
@Composable
private fun AboutAlbum(notes: String?, facts: List<Pair<String, String>>, musicBrainzId: String?) {
    Column(Modifier.padding(top = 26.dp)) {
        Box(Modifier.padding(horizontal = 20.dp)) { SectionLabel("About") }
        Spacer(Modifier.height(10.dp))
        if (!notes.isNullOrBlank()) {
            AlbumNotes(notes)
            Spacer(Modifier.height(6.dp))
        }
        facts.forEach { (label, value) ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    label, color = TextFaint, fontFamily = AppFont,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    modifier = Modifier.width(72.dp),
                )
                Text(
                    value, color = TextSecondary, fontFamily = AppFont, fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        musicBrainzId?.takeIf { it.isNotBlank() }?.let { mbid ->
            val uri = LocalUriHandler.current
            Text(
                "Look up on MusicBrainz",
                color = LocalAccent.current, fontFamily = AppFont,
                fontWeight = FontWeight.Bold, fontSize = 12.sp,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .clickable { runCatching { uri.openUri("https://musicbrainz.org/release/$mbid") } },
            )
        }
    }
}

/**
 * A shelf of covers to go to next.
 *
 * The title is passed in rather than fixed, because what this row *is* depends on
 * which rung of the fallback answered — the artist's other records, artists filed
 * next to them, or the same genre. Calling all three "Related" would flatten a real
 * difference in how strong the claim is.
 */
@Composable
private fun RelatedAlbums(title: String, albums: List<MaItem>, onClick: (MaItem) -> Unit) {
    Column(Modifier.padding(top = 26.dp)) {
        Box(Modifier.padding(horizontal = 20.dp)) { SectionLabel(title) }
        Spacer(Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(albums, key = { it.provider + "|" + it.itemId }) { album ->
                Column(
                    Modifier.width(124.dp).clickable { onClick(album) },
                    verticalArrangement = Arrangement.spacedBy(TitleGap),
                ) {
                    val art = rememberArtRequest(album.image, pixels = 320)
                    Box(
                        Modifier
                            .size(124.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Glass),
                    ) {
                        if (art != null) {
                            AsyncImage(
                                model = art,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        album.name, color = TextPrimary, fontFamily = AppFont,
                        fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp,
                    )
                    album.subtitle?.let {
                        Text(
                            it, color = TextFaint, fontFamily = AppFont, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
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
        Box(Modifier.width(28.dp).height(14.dp).clip(RoundedCornerShape(4.dp)).background(inkOn(0.06f)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.fillMaxWidth(0.55f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(inkOn(0.07f)))
            Box(Modifier.fillMaxWidth(0.32f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(inkOn(0.05f)))
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
        Text(title, color = TextSecondary, style = MaterialTheme.typography.titleLarge)
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
        Text("Couldn't load", color = TextSecondary, style = MaterialTheme.typography.titleLarge)
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