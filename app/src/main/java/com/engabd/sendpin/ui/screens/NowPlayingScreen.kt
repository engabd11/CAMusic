package com.engabd.sendpin.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.audio.StreamQuality
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel

/**
 * The album, and everything else out of its way. Art fills the panel edge to
 * edge as a blurred wash that resolves to true #000 before the bottom, so on an
 * OLED screen the cover has no frame — it just stops. Every accent on the
 * screen (controls, glows, badge, scrubber) is sampled from that same cover.
 */
@Composable
fun NowPlayingScreen(
    viewModel: NowPlayingViewModel = viewModel(),
    onOpenSpeakers: () -> Unit = {},
    onBrowse: () -> Unit = {},
) {
    val st by viewModel.state.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val favorite by viewModel.favorite.collectAsState()
    val currentItem by viewModel.currentItem.collectAsState()
    val context = LocalContext.current

    // Which overlay is open, if any. Null = just the player.
    var panel by remember { mutableStateOf<Panel?>(null) }
    var options by remember { mutableStateOf(false) }
    // Lyrics are a *mode* of the player, not an overlay: they take the cover's place.
    var showLyrics by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.toast.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    // App.kt already derives the palette from this same artwork and provides it to
    // the whole tree, so the rest of the app is tinted to match. Re-extracting here
    // would just do the same clustering work twice.
    val palette = LocalPalette.current
    val accent = palette.accent

    // Smoothly interpolate the position between 2s polls. `scrubbing` freezes the
    // interpolation so a poll landing mid-drag can't fight the finger.
    var pos by remember { mutableStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    LaunchedEffect(st.positionMs, st.isPlaying, st.title, scrubbing) {
        if (scrubbing) return@LaunchedEffect
        pos = st.positionMs
        while (st.isPlaying) { kotlinx.coroutines.delay(250); pos += 250 }
    }
    val dur = st.durationMs
    val progress = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f

    CompositionLocalProvider(LocalAccent provides accent, LocalPalette provides palette) {
        Box(Modifier.fillMaxSize().background(Ink)) {
            // The screen keeps its shape whether or not anything is playing: an
            // idle player shows the last track it had (dimmed, with a notice)
            // rather than swapping the whole screen for an empty state.
            MeltBackdrop(st.artworkUrl, intensity = if (st.idle) 0.5f else 1f)

            // Source badge at the top-right corner — MA or Navidrome.
            if (st.source.isNotBlank()) {
                SourceBadge(
                    source = st.source,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 16.dp),
                )
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 14.dp)
                    .padding(top = 8.dp, bottom = navBarInset() + 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!connected) OfflineBanner()

                TopBar(
                    playerName = st.playerName,
                    isSelf = st.isSelf,
                    groupSize = st.groupSize,
                    onOpenSpeakers = onOpenSpeakers,
                )

                Spacer(Modifier.height(4.dp))

                // Album art fills the available space, shrinking on small screens
                // and growing on large ones — no scroll needed.
                if (showLyrics) {
                    LyricsPane(
                        viewModel = viewModel,
                        positionMs = pos,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    AlbumArt(
                        url = st.artworkUrl,
                        glow = palette.swatch(0),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .alpha(if (st.idle) 0.55f else 1f),
                        glowAlpha = if (st.idle) 0.18f else 0.45f,
                        placeholder = Icons.AutoMirrored.Filled.QueueMusic,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Secondary actions — track-scoped chips only; the quality badge
                // has moved to the transport row (between play and the seek bar).
                Row(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconChip(Icons.Default.Lyrics, "Lyrics", active = showLyrics) {
                        showLyrics = !showLyrics
                    }
                    IconChip(Icons.AutoMirrored.Filled.QueueMusic, "Queue", active = panel == Panel.QUEUE) {
                        panel = if (panel == Panel.QUEUE) null else Panel.QUEUE
                    }
                    IconChip(Icons.Default.Tune, "Player options", active = options) { options = !options }
                    // Disabled until MA tells us which library item is playing —
                    // without an item_id there is nothing to favourite.
                    IconChip(
                        if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        if (favorite) "Remove from favourites" else "Add to favourites",
                        active = favorite,
                        tint = if (currentItem == null) TextFaint else null,
                        onClick = if (currentItem == null) null else ({ viewModel.toggleFavorite() }),
                    )
                }

                Spacer(Modifier.height(14.dp))

                if (st.idle) {
                    IdleNotice(st.playerName, st.blank, onBrowse)
                    Spacer(Modifier.height(12.dp))
                }

                Text(
                    if (st.blank) "Nothing playing" else st.title,
                    color = if (st.idle) TextSecondary else TextPrimary,
                    fontFamily = AppFont, fontWeight = FontWeight.ExtraBold,
                    fontSize = 27.sp, letterSpacing = (-0.5).sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                if (st.artist.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        st.artist, color = Color.White.a(0.62f), fontFamily = AppFont,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
                if (st.album.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        st.album, color = TextFaint, fontFamily = AppFont, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(20.dp))

                HSlider(
                    progress,
                    onChange = { f -> scrubbing = true; pos = (f * dur).toLong() },
                    onCommit = { f -> pos = (f * dur).toLong(); scrubbing = false; viewModel.seekTo(f) },
                    label = { f -> fmtTime((f * dur).toLong()) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TimeText(fmtTime(pos))
                    TimeText(if (dur > 0) fmtTime(dur) else "--:--")
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TransportIcon(Icons.Default.Shuffle, "Shuffle", 20.dp, st.shuffle) { viewModel.toggleShuffle() }
                    TransportIcon(Icons.Default.SkipPrevious, "Previous", 26.dp) { viewModel.previous() }
                    PlayButton(st.isPlaying) { viewModel.playPause() }
                    // Quality badge sits between play and next — the centre of attention.
                    TappableQualityChip(playing = st.quality, source = st.sourceQuality)
                    TransportIcon(Icons.Default.SkipNext, "Next", 26.dp) { viewModel.next() }
                    TransportIcon(
                        if (st.repeatMode == "one") Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Repeat", 20.dp, st.repeatMode != "off",
                    ) { viewModel.cycleRepeat() }
                }

                Spacer(Modifier.height(18.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, "Volume", tint = TextMuted, modifier = Modifier.size(16.dp))
                    HSlider(
                        st.volume.coerceIn(0f, 1f), { viewModel.setVolume(it) },
                        modifier = Modifier.weight(1f), knob = 12.dp, accented = false,
                    )
                    Text(
                        "${(st.volume * 100).toInt()}", color = TextMuted, fontFamily = MonoFont,
                        fontWeight = FontWeight.Bold, fontSize = 11.sp,
                        modifier = Modifier.width(22.dp), textAlign = TextAlign.End,
                    )
                }
            }

            // The overlays sit in the same Box as the player, so the album wash
            // still reads behind their top edge. Anything still visible above a
            // sheet dismisses it, and so does system Back — otherwise Back would
            // leave the screen entirely with a panel open over it.
            if (panel != null || options) {
                BackHandler { panel = null; options = false }
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { panel = null; options = false }
                )
            }
            if (panel != null) {
                NowPlayingSheet(onClose = { panel = null }, viewModel = viewModel)
            }
            if (options) {
                PlayerOptionsSheet(onClose = { options = false }, viewModel = viewModel)
            }
        }
    }
}

/**
 * The split player control: a glass half naming what's playing where, welded to
 * an accent half that opens the speaker picker. The design's minimize/overflow
 * icons are dropped — this app is tab-based (nothing to minimize to) and their
 * menu items already live in the chip row under the cover.
 */
@Composable
private fun TopBar(playerName: String, isSelf: Boolean, groupSize: Int, onOpenSpeakers: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(topStart = 100.dp, bottomStart = 100.dp))
                    .background(GlassStrong)
                    .clickable(onClick = onOpenSpeakers)
                    .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (isSelf) Icons.Default.Smartphone else Icons.Default.Speaker, null,
                    tint = Color.White.a(0.85f), modifier = Modifier.size(14.dp),
                )
                Text(
                    if (groupSize > 1) "$playerName ($groupSize)" else playerName,
                    color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(topEnd = 100.dp, bottomEnd = 100.dp))
                    .background(accent)
                    .clickable(onClick = onOpenSpeakers)
                    .padding(horizontal = 13.dp, vertical = 9.dp),
            ) { Icon(Icons.Default.Link, "Speakers", tint = Ink, modifier = Modifier.size(14.dp)) }
        }
    }
}

/** The quality badge, or a neutral placeholder until the stream reports itself. */
@Composable
private fun QualityChip(q: StreamQuality?) {
    if (q == null) QualityPill("—", lossless = false)
    else QualityPill(q.label, hiRes = q.hiRes, lossless = q.lossless)
}

/**
 * The tappable quality badge: shows what's actually playing, and tapping reveals
 * a popup with both the original source format and the playing (decoded) format.
 */
@Composable
fun TappableQualityChip(playing: StreamQuality?, source: StreamQuality?) {
    var showDetail by remember { mutableStateOf(false) }
    val accent = LocalAccent.current

    Box {
        Box(Modifier.clickable { showDetail = true }) {
            if (playing == null) QualityPill("—", lossless = false)
            else QualityPill(playing.label, hiRes = playing.hiRes, lossless = playing.lossless)
        }

        if (showDetail) {
            BackHandler { showDetail = false }
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showDetail = false }
            )
            QualityDetailPopup(
                playing = playing,
                source = source,
                onDismiss = { showDetail = false },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** A small popup showing both the original source and the playing format. */
@Composable
private fun QualityDetailPopup(
    playing: StreamQuality?,
    source: StreamQuality?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Ink2)
            .border(1.dp, Hairline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Quality",
            color = TextFaint, fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 11.sp, letterSpacing = 1.sp,
        )
        QualityRow("Playing", playing, accent)
        QualityRow("Source", source, accent)
        if (playing != null && source != null && playing.label != source.label) {
            Text(
                "Transcoded from ${source.label} to ${playing.label}",
                color = TextMuted, fontFamily = AppFont, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun QualityRow(label: String, q: StreamQuality?, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (q?.lossless == true) accent else TextMuted))
        Text(
            label,
            color = TextMuted, fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 12.sp, modifier = Modifier.width(50.dp),
        )
        Text(
            q?.label ?: "—",
            color = TextPrimary, fontFamily = MonoFont, fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

/** A small badge at the corner indicating the source (MA or Navidrome). */
@Composable
fun SourceBadge(source: String, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val isMa = source == "MA"
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Glass)
            .border(1.dp, HairlineSoft, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier.size(5.dp).clip(CircleShape)
                .background(if (isMa) accent else Color(0xFF5EC8C0))
        )
        Text(
            source,
            color = TextSecondary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun TransportIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    cd: String,
    size: androidx.compose.ui.unit.Dp,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    Box(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (active) Bloom(accent, size * 1.8f, 0.dp, 0.dp, 0.5f)
        Icon(icon, cd, tint = if (active) accent else Color.White.a(0.9f), modifier = Modifier.size(size))
    }
}

@Composable
private fun TimeText(text: String) {
    Text(text, color = TextMuted, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
}

private fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun OfflineBanner() {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(WarnAmber.a(0.14f)).border(1.dp, WarnAmber.a(0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.CloudOff, null, tint = WarnAmber, modifier = Modifier.size(15.dp))
        Text(
            "Reconnecting…", color = Color(0xFFF2C574), fontFamily = AppFont,
            fontWeight = FontWeight.Bold, fontSize = 12.sp,
        )
    }
}

/**
 * Shown in place of nothing when the player is quiet. It sits inside the normal
 * layout so the screen never changes shape — the art above it is the last thing
 * that played, dimmed, and everything else stays where the user left it.
 */
@Composable
private fun IdleNotice(playerName: String, blank: Boolean, onBrowse: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(100))
            .background(Glass)
            .border(1.dp, Hairline, RoundedCornerShape(100))
            .clickable(onClick = onBrowse)
            .padding(start = 14.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.PauseCircleOutline, null, tint = TextMuted, modifier = Modifier.size(15.dp))
        Text(
            if (blank) "Nothing playing on $playerName — browse" else "Nothing playing — browse",
            color = TextSecondary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
