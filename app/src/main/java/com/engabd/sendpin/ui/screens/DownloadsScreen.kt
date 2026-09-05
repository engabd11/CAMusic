package com.engabd.sendpin.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.engabd.sendpin.download.DownloadJob
import com.engabd.sendpin.download.DownloadedTrack
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything kept on the phone, and what it is costing.
 *
 * The Library's Downloads shelf answers "what can I play right now" and nothing else:
 * it is a browse node like any other, so it cannot be searched independently, cannot
 * be sorted, shows no sizes, and offers no way to retry the download that failed
 * three albums ago. Those are the questions someone opens *downloads* to ask, as
 * opposed to opening the library.
 *
 * ## Why it is shaped like this
 *
 * The first version answered them as a stack of glass cards — in-flight jobs, a storage
 * breakdown, a search box, sort pills, then one card per track — every one of them the
 * same size, the same weight and the same distance apart, with the controls scrolling
 * away as soon as the list moved. Nothing said which of those things was the screen and
 * which were about the screen.
 *
 * So the parts are told apart by what they are:
 *
 *  - **Chrome** — the title, the totals and the search and sort controls — is pinned
 *    above the list. Sorting a list you can no longer see is not a control.
 *  - **The list** is grouped by album, because that is the shape downloads have: they
 *    arrive an album at a time and are deleted an album at a time. Album headings carry
 *    the art, the count and the size; the tracks under them are plain rows, so a record
 *    reads as one block rather than twelve floating cards.
 *  - **Everything else** — the storage breakdown, delete-everything — is out of the
 *    scrolling path: one behind a disclosure, one in the header.
 *
 * Sizes are stat-ed off the main thread and only when the index changes — a listing
 * per row on every recomposition is how a screen like this becomes the slowest in the
 * app.
 */
@Composable
fun DownloadsScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val jobs by viewModel.downloadJobs.collectAsStateWithLifecycle()

    // Retry replies land here. Without a collector a retry that cannot run — no
    // connection, Wi-Fi only, a track the server no longer has — just made the failed
    // row disappear, which reads as success.
    LaunchedEffect(Unit) {
        viewModel.playerToast.collect {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(DownloadSort.ADDED) }
    var showStorage by rememberSaveable { mutableStateOf(false) }

    var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(downloads) {
        sizes = withContext(Dispatchers.IO) { viewModel.downloadSizes() }
    }

    val shown = remember(downloads, query, sort, sizes) {
        val filtered = downloads.filter { it.matches(query) }
        // "Recent" is not a comparator — the index is appended to, so its own order
        // already is oldest-first and newest-first is simply the reverse of it. A
        // comparator that returns 0 for every pair would have relied on sort stability
        // to do nothing, which is a confusing way to spell `asReversed`.
        when (sort) {
            DownloadSort.ADDED -> filtered.asReversed()
            DownloadSort.ALBUM -> filtered.sortedWith(
                compareBy<DownloadedTrack, String>(String.CASE_INSENSITIVE_ORDER) { it.album.orEmpty() }
                    .thenBy { it.discNumber ?: 0 }
                    .thenBy { it.trackNumber ?: Int.MAX_VALUE }
            )
            DownloadSort.TITLE -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            DownloadSort.ARTIST -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist.orEmpty() })
            DownloadSort.SIZE -> filtered.sortedByDescending { sizes[it.id] ?: 0L }
        }
    }
    // Grouped for the two orders where an album is still an album. Sorting by title or
    // by size is a deliberate request for a flat list — grouping it back up would undo
    // the sort the user just asked for.
    val albums = remember(shown, sizes, sort) {
        if (sort.grouped) downloadAlbums(shown, sizes) else emptyList()
    }
    val totalBytes = remember(sizes) { sizes.values.sum() }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            DownloadsHeader(
                trackCount = downloads.size,
                totalBytes = totalBytes,
                onBack = onBack,
                onDeleteAll = viewModel::deleteAllDownloads.takeIf { downloads.isNotEmpty() },
            )

            // Pinned, not scrolled: the point of a sort control is to reach it while
            // looking at what it sorts.
            if (downloads.isNotEmpty()) {
                Controls(query, { query = it }, sort, { sort = it })
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 18.dp, end = 18.dp, top = 8.dp, bottom = navBarInset() + 16.dp,
                ),
            ) {
                if (downloads.isEmpty() && jobs.isEmpty()) {
                    item("empty") { NothingDownloaded() }
                    return@LazyColumn
                }

                // ── In flight ────────────────────────────────────────────
                if (jobs.isNotEmpty()) {
                    item("jobs-header") { SectionLabel("In progress") }
                    // Every list on this screen empties itself while it is being looked
                    // at — a job finishes and leaves, a track is deleted, the sort or
                    // the search box reorders what is left. Each of those was a redraw:
                    // the row vanished and everything below it was somewhere new on the
                    // next frame. `animateItem` gives all three the same treatment —
                    // the row fades where it stood, and the rest close the gap.
                    items(
                        jobs,
                        key = { "job-${it.id}" },
                        contentType = { "job" },
                    ) { job ->
                        Box(Modifier.animateItem(placementSpec = Motion.itemPlacement())) {
                            JobRow(
                                job = job,
                                onRetry = { viewModel.retryDownload(job.id) },
                                onDismiss = { viewModel.dismissDownload(job.id) },
                            )
                        }
                    }
                    item("jobs-gap") { Spacer(Modifier.height(14.dp)) }
                }

                if (downloads.isEmpty()) return@LazyColumn

                if (shown.isEmpty()) {
                    item("no-match") {
                        Text(
                            "Nothing matches \"$query\"",
                            color = TextFaint, fontFamily = AppFont,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }

                if (sort.grouped) {
                    albums.forEach { album ->
                        item(key = "album-${album.key}", contentType = "albumHeader") {
                            AlbumHeader(
                                album = album,
                                onPlay = { viewModel.playDownloads(album.tracks) },
                                onDelete = { album.tracks.forEach { viewModel.deleteDownload(it.id) } },
                            )
                        }
                        items(album.tracks, key = { it.id }, contentType = { "track" }) { track ->
                            Box(Modifier.animateItem(placementSpec = Motion.itemPlacement())) {
                                TrackRow(
                                    track = track,
                                    sizeBytes = sizes[track.id] ?: 0L,
                                    showTrackNumber = true,
                                    // Rebuilt as a library item so it routes through the download play
                                    // context, which plays from disk rather than reaching for a
                                    // server that may not be there.
                                    onPlay = { viewModel.playDownload(track) },
                                    onDelete = { viewModel.deleteDownload(track.id) },
                                )
                            }
                        }
                        item(key = "album-gap-${album.key}", contentType = "gap") { Spacer(Modifier.height(16.dp)) }
                    }
                } else {
                    items(shown, key = { it.id }, contentType = { "track" }) { track ->
                        Box(Modifier.animateItem(placementSpec = Motion.itemPlacement())) {
                            TrackRow(
                                track = track,
                                sizeBytes = sizes[track.id] ?: 0L,
                                showTrackNumber = false,
                                subtitleAlbum = true,
                                onPlay = { viewModel.playDownload(track) },
                                onDelete = { viewModel.deleteDownload(track.id) },
                            )
                        }
                    }
                }

                // ── Storage, on request ──────────────────────────────────
                item("storage") {
                    StorageSection(
                        downloads = downloads,
                        sizes = sizes,
                        totalBytes = totalBytes,
                        expanded = showStorage,
                        onToggle = { showStorage = !showStorage },
                    )
                }
            }
        }
    }
}

// ── Header and controls ───────────────────────────────────────────────────

/**
 * The title, the totals, and the one destructive action.
 *
 * Delete-everything used to sit at the bottom of the scroll, one flick past the last
 * track — which is both hard to find on purpose and easy to find by accident. Up here
 * it is where the screen's own actions belong, and still behind two taps.
 */
@Composable
private fun DownloadsHeader(
    trackCount: Int,
    totalBytes: Long,
    onBack: () -> Unit,
    onDeleteAll: (() -> Unit)?,
) {
    var confirming by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary,
            modifier = Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onBack),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                "Downloads", color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, letterSpacing = (-0.5).sp,
            )
            Text(
                if (trackCount == 0) "Nothing on this phone yet"
                else "$trackCount ${if (trackCount == 1) "track" else "tracks"} · ${bytes(totalBytes)}",
                color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
            )
        }
        onDeleteAll?.let { delete ->
            IconChip(
                if (confirming) Icons.Default.DeleteForever else Icons.Default.DeleteSweep,
                if (confirming) "Tap again to delete every download" else "Delete all downloads",
                tint = if (confirming) ErrorRed else TextMuted,
                active = confirming,
            ) {
                if (confirming) { delete(); confirming = false } else confirming = true
            }
        }
    }
}

@Composable
private fun Controls(
    query: String,
    onQuery: (String) -> Unit,
    sort: DownloadSort,
    onSort: (DownloadSort) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SearchBox(query, onQuery)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DownloadSort.entries.forEach { s -> Pill(s.label, s == sort) { onSort(s) } }
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun NothingDownloaded() {
    GlassCard(radius = 16.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Nothing downloaded",
                color = TextPrimary, fontFamily = AppFont,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Long-press a track, album or playlist in the library and choose " +
                    "Download, or tap the download chip on the player while " +
                    "something is playing. Downloads are the original files, so " +
                    "they play at full quality with no network at all.",
                color = TextFaint, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ── Sorting and grouping ──────────────────────────────────────────────────

/**
 * How the list is ordered.
 *
 * "Recently added" is the default because the index is appended to, so its own order
 * already *is* that — and because the download someone is looking for is nearly always
 * the one they just made.
 *
 * [grouped] marks the orders where an album is still contiguous, and so can carry a
 * heading. Sorting by title or by size deliberately cuts across albums; heading those
 * would produce a "Rain Dogs (1 track)" block per row.
 */
internal enum class DownloadSort(val label: String, val grouped: Boolean) {
    ADDED("Recent", grouped = true),
    ALBUM("Album", grouped = true),
    TITLE("Title", grouped = false),
    ARTIST("Artist", grouped = false),
    SIZE("Largest", grouped = false),
}

/** One downloaded album: its tracks, in their own order, and what it costs. */
internal data class DownloadAlbum(
    val key: String,
    val title: String,
    val artist: String?,
    val artUri: String?,
    val tracks: List<DownloadedTrack>,
    val bytes: Long,
)

/**
 * [tracks] gathered into the albums they came from, keeping the order they arrive in.
 *
 * Grouped by album *id* where there is one, so two records with the same title by
 * different artists stay apart, and by name otherwise — a download made before ids were
 * recorded still groups with its neighbours. Album order follows the incoming list, so
 * whatever sort produced it still decides what is at the top; only the tracks within an
 * album are re-ordered, into disc and track order, because that is the one order an
 * album has of its own.
 */
internal fun downloadAlbums(
    tracks: List<DownloadedTrack>,
    sizes: Map<String, Long> = emptyMap(),
): List<DownloadAlbum> =
    tracks
        .groupBy { track ->
            track.albumId?.takeIf { it.isNotBlank() }
                ?: track.album?.takeIf { it.isNotBlank() }
                ?: SINGLES
        }
        .map { (key, group) ->
            DownloadAlbum(
                key = key,
                title = group.firstNotNullOfOrNull { it.album?.takeIf { a -> a.isNotBlank() } }
                    ?: "Singles",
                artist = group.firstNotNullOfOrNull { it.artist?.takeIf { a -> a.isNotBlank() } },
                artUri = group.firstNotNullOfOrNull { it.artUri },
                tracks = group.sortedWith(
                    compareBy<DownloadedTrack> { it.discNumber ?: 0 }
                        .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                ),
                bytes = group.sumOf { sizes[it.id] ?: 0L },
            )
        }

/** The bucket for downloads that belong to no album — singles, one-off tracks. */
private const val SINGLES = "__singles__"

private fun DownloadedTrack.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()
    return title.contains(q, true) ||
        artist.orEmpty().contains(q, true) ||
        album.orEmpty().contains(q, true)
}

// ── Pieces ────────────────────────────────────────────────────────────────

/**
 * An album's heading: what it is, what it costs, and the two things you do to a whole
 * record — play it, or get rid of it.
 *
 * Deleting a downloaded album used to mean deleting twelve tracks one confirmation at a
 * time, which is the actual reason people ended up using "Delete all downloads".
 */
@Composable
private fun AlbumHeader(album: DownloadAlbum, onPlay: () -> Unit, onDelete: () -> Unit) {
    var confirming by remember(album.key) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Glass),
            contentAlignment = Alignment.Center,
        ) {
            if (album.artUri != null) {
                AsyncImage(
                    model = album.artUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Default.Album, null, tint = TextMuted, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                album.title, color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    album.artist,
                    "${album.tracks.size} ${if (album.tracks.size == 1) "track" else "tracks"}",
                    bytes(album.bytes).takeIf { album.bytes > 0 },
                ).joinToString(" · "),
                color = TextMuted, fontFamily = AppFont, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconChip(
            if (confirming) Icons.Default.DeleteForever else Icons.Default.DeleteOutline,
            if (confirming) "Tap again to delete this album" else "Delete this album",
            tint = if (confirming) ErrorRed else TextMuted,
        ) {
            if (confirming) onDelete() else confirming = true
        }
    }
}

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink3)
            .border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.Search, null, tint = TextMuted, modifier = Modifier.size(17.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = TextPrimary, fontFamily = AppFont, fontSize = 14.sp,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        "Search downloads", color = TextFaint,
                        fontFamily = AppFont, fontSize = 14.sp,
                    )
                }
                inner()
            },
        )
        if (query.isNotEmpty()) {
            Icon(
                Icons.Default.Close, "Clear", tint = TextMuted,
                modifier = Modifier.size(17.dp).clip(CircleShape).clickable { onChange("") },
            )
        }
    }
}

@Composable
private fun JobRow(job: DownloadJob, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Glass),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (job.failed) Icons.Default.ErrorOutline else Icons.Default.Downloading,
                null,
                tint = if (job.failed) ErrorRed else accent,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                job.title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (job.failed) "Failed" else "Downloading · ${(job.fraction * 100).toInt()}%",
                color = if (job.failed) ErrorRed else TextMuted,
                fontFamily = AppFont, fontSize = 12.sp, maxLines = 1,
            )
        }
        if (job.failed) {
            // Retry first — dismissing a failure without offering another go was
            // the whole gap. Both, because a track the server no longer has should
            // be dismissable rather than retried for ever.
            IconChip(Icons.Default.Refresh, "Try again", onClick = onRetry)
            IconChip(Icons.Default.Close, "Dismiss", tint = TextMuted, onClick = onDismiss)
        } else {
            RingProgress(job.fraction)
        }
    }
}

/**
 * One track.
 *
 * A row rather than a card: under an album heading, twelve cards read as twelve
 * unrelated things, and the whole point of the grouping is that they are one thing.
 * [showTrackNumber] is on inside an album, where the number places the song, and off in
 * a flat list, where it would be a column of unrelated digits.
 */
@Composable
private fun TrackRow(
    track: DownloadedTrack,
    sizeBytes: Long,
    showTrackNumber: Boolean,
    subtitleAlbum: Boolean = false,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirming by remember(track.id) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showTrackNumber) {
            Text(
                track.trackNumber?.toString() ?: "·",
                color = TextFaint, fontFamily = MonoFont, fontSize = 12.sp,
                modifier = Modifier.width(22.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        } else {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(Glass),
                contentAlignment = Alignment.Center,
            ) {
                if (track.artUri != null) {
                    AsyncImage(
                        model = track.artUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Default.MusicNote, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                track.title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // Inside an album the artist and album are already overhead; a flat list has
            // no heading to carry them, so the row does.
            val subtitle = if (subtitleAlbum) {
                listOfNotNull(
                    track.artist?.takeIf { it.isNotBlank() },
                    track.album?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
            } else {
                track.artist.orEmpty()
            }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle, color = TextMuted, fontFamily = AppFont, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // The format the file actually is, which is the reason it was kept.
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            track.format?.quality?.shortLabel?.let {
                Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
            }
            Text(bytes(sizeBytes), color = TextFaint, fontFamily = MonoFont, fontSize = 10.sp)
        }
        IconChip(
            if (confirming) Icons.Default.DeleteForever else Icons.Default.DeleteOutline,
            if (confirming) "Tap again to delete" else "Delete",
            tint = if (confirming) ErrorRed else TextMuted,
        ) {
            if (confirming) onDelete() else confirming = true
        }
    }
}

/**
 * Where the space has gone, by album.
 *
 * A single total answers "am I near the limit" and nothing else. The reason anyone
 * looks at this is to find the thing worth deleting, and that is nearly always one
 * hi-res record rather than a hundred small ones.
 *
 * Behind a disclosure at the foot of the list rather than pinned above it: it is the
 * question you ask once, when the phone is full, and it used to sit between the
 * downloads in flight and the downloads themselves.
 */
@Composable
private fun StorageSection(
    downloads: List<DownloadedTrack>,
    sizes: Map<String, Long>,
    totalBytes: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val accent = LocalAccent.current
    val byAlbum = remember(downloads, sizes, expanded) {
        if (!expanded) emptyList() else downloads
            .groupBy { it.album?.takeIf { a -> a.isNotBlank() } ?: "Singles" }
            .map { (album, tracks) -> album to tracks.sumOf { sizes[it.id] ?: 0L } }
            .sortedByDescending { it.second }
            .take(8)
    }

    Column(Modifier.padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.PieChart, null, tint = TextMuted, modifier = Modifier.size(17.dp))
            Text(
                "Storage · ${bytes(totalBytes)}",
                color = TextSecondary, fontFamily = AppFont,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            DisclosureChevron(
                expanded = expanded,
                contentDescription = if (expanded) "Hide the breakdown" else "Show the breakdown",
            )
        }
        // Spelled out rather than left to AnimatedVisibility's defaults, which are
        // `expandIn`/`shrinkOut` on a generic tween: the breakdown only ever grows
        // downward, and the app's own spatial spring is what every other panel on
        // this screen opens on.
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(Motion.contentSize()) + fadeIn(Motion.effects()),
            exit = shrinkVertically(Motion.contentSize()) + fadeOut(Motion.effects()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                byAlbum.forEach { (album, albumBytes) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                album, color = TextSecondary, fontFamily = AppFont, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(bytes(albumBytes), color = TextMuted, fontFamily = MonoFont, fontSize = 11.sp)
                        }
                        // A bar rather than a percentage: the comparison between rows is
                        // the whole point, and nobody reads eight percentages against
                        // each other.
                        MeterBar(
                            fraction = if (totalBytes > 0) albumBytes.toFloat() / totalBytes else 0f,
                            height = 3.dp,
                            color = accent,
                        )
                    }
                }
                val albumCount = downloads.map { it.album.orEmpty() }.distinct().size
                if (albumCount > byAlbum.size) {
                    Text(
                        "Largest ${byAlbum.size} of $albumCount albums",
                        color = TextFaint, fontFamily = AppFont, fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

private fun bytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1_000_000_000.0)
    b >= 1_000_000 -> "%.0f MB".format(b / 1_000_000.0)
    b >= 1_000 -> "%.0f KB".format(b / 1_000.0)
    else -> "$b B"
}
