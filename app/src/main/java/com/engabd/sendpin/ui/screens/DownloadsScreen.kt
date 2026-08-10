package com.engabd.sendpin.ui.screens

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
 * Sizes are stat-ed off the main thread and only when the index changes — a listing
 * per row on every recomposition is how a screen like this becomes the slowest in the
 * app.
 */
@Composable
fun DownloadsScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
) {
    val accent = LocalAccent.current
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
    var confirmDeleteAll by remember { mutableStateOf(false) }

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
            DownloadSort.TITLE -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            DownloadSort.ARTIST -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist.orEmpty() })
            // Within an album, disc and track order — an album sorted alphabetically
            // by song title is not an album.
            DownloadSort.ALBUM -> filtered.sortedWith(
                compareBy<DownloadedTrack, String>(String.CASE_INSENSITIVE_ORDER) { it.album.orEmpty() }
                    .thenBy { it.trackNumber ?: Int.MAX_VALUE }
            )
            DownloadSort.SIZE -> filtered.sortedByDescending { sizes[it.id] ?: 0L }
        }
    }
    val totalBytes = remember(sizes) { sizes.values.sum() }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
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
                        if (downloads.isEmpty()) "Nothing on this phone yet"
                        else "${downloads.size} ${if (downloads.size == 1) "track" else "tracks"} · ${bytes(totalBytes)}",
                        color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
                    )
                }
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = navBarInset() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (downloads.isEmpty() && jobs.isEmpty()) {
                    item("empty") {
                        GlassCard(radius = 16.dp) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Nothing downloaded",
                                    color = TextPrimary, fontFamily = AppFont,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    "Long-press a track, album or playlist in the library and choose " +
                                        "Download — or tap the download chip on the player while " +
                                        "something is playing. Downloads are the original files, so " +
                                        "they play at full quality with no network at all.",
                                    color = TextFaint, style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    return@LazyColumn
                }

                // ── In flight ────────────────────────────────────────────
                if (jobs.isNotEmpty()) {
                    item("jobs-header") { SectionLabel("In progress") }
                    items(jobs, key = { "job-${it.id}" }) { job ->
                        JobRow(
                            job = job,
                            onRetry = { viewModel.retryDownload(job.id) },
                            onDismiss = { viewModel.dismissDownload(job.id) },
                        )
                    }
                }

                // ── Storage ──────────────────────────────────────────────
                if (downloads.isNotEmpty()) {
                    item("storage") { StorageCard(downloads, sizes, totalBytes) }

                    // ── Search + sort ────────────────────────────────────
                    item("controls") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SearchBox(query) { query = it }
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                DownloadSort.entries.forEach { s ->
                                    Pill(s.label, s == sort) { sort = s }
                                }
                            }
                        }
                    }

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

                    items(shown, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            sizeBytes = sizes[track.id] ?: 0L,
                            // Rebuilt as a library item so it routes through the download play
                            // context, which plays from disk rather than reaching for a
                            // server that may not be there.
                            onPlay = { viewModel.playDownload(track) },
                            onDelete = { viewModel.deleteDownload(track.id) },
                        )
                    }

                    item("delete-all") {
                        Spacer(Modifier.height(4.dp))
                        DangerButton(
                            if (confirmDeleteAll) "Tap again to delete all ${downloads.size}"
                            else "Delete all downloads",
                        ) {
                            if (confirmDeleteAll) {
                                viewModel.deleteAllDownloads(); confirmDeleteAll = false
                            } else confirmDeleteAll = true
                        }
                    }
                }
            }
        }
    }
}

// ── Sorting ───────────────────────────────────────────────────────────────

/**
 * How the list is ordered.
 *
 * "Recently added" is the default because the index is appended to, so its own order
 * already *is* that — and because the download someone is looking for is nearly
 * always the one they just made.
 */
private enum class DownloadSort(val label: String) {
    ADDED("Recent"),
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    SIZE("Largest")
}

private fun DownloadedTrack.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()
    return title.contains(q, true) ||
        artist.orEmpty().contains(q, true) ||
        album.orEmpty().contains(q, true)
}

// ── Pieces ────────────────────────────────────────────────────────────────

/**
 * Where the space has gone, by album.
 *
 * A single total answers "am I near the limit" and nothing else. The reason anyone
 * looks at this screen is to find the thing worth deleting, and that is nearly always
 * one hi-res record rather than a hundred small ones.
 */
@Composable
private fun StorageCard(
    downloads: List<DownloadedTrack>,
    sizes: Map<String, Long>,
    totalBytes: Long,
) {
    val accent = LocalAccent.current
    val byAlbum = remember(downloads, sizes) {
        downloads
            .groupBy { it.album?.takeIf { a -> a.isNotBlank() } ?: "Singles" }
            .map { (album, tracks) -> album to tracks.sumOf { sizes[it.id] ?: 0L } }
            .sortedByDescending { it.second }
            .take(5)
    }

    GlassCard(radius = 16.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Storage", color = TextPrimary, fontFamily = AppFont,
                style = MaterialTheme.typography.titleLarge,
            )
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
                    // the whole point, and nobody reads five percentages against
                    // each other.
                    val fraction = if (totalBytes > 0) albumBytes.toFloat() / totalBytes else 0f
                    Box(
                        Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(100)).background(Glass),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                                .clip(RoundedCornerShape(100)).background(accent),
                        )
                    }
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
    GlassCard(radius = 14.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Glass),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (job.failed) Icons.Default.ErrorOutline else Icons.Default.Downloading,
                    null,
                    tint = if (job.failed) ErrorRed else accent,
                    modifier = Modifier.size(18.dp),
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
}

@Composable
private fun TrackRow(
    track: DownloadedTrack,
    sizeBytes: Long,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirming by remember(track.id) { mutableStateOf(false) }
    GlassCard(radius = 14.dp) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Glass),
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
                    Icon(Icons.Default.MusicNote, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                Text(
                    track.title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        track.artist?.takeIf { it.isNotBlank() },
                        track.album?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { "Unknown" },
                    color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
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
}

@Composable
private fun DangerButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(ErrorRed.a(0.14f))
            .border(1.dp, ErrorRed.a(0.55f), RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = ErrorRed, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
    }
}

private fun bytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1_000_000_000.0)
    b >= 1_000_000 -> "%.0f MB".format(b / 1_000_000.0)
    b >= 1_000 -> "%.0f KB".format(b / 1_000.0)
    else -> "$b B"
}
