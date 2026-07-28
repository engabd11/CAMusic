package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    onOpenLightSync: () -> Unit = {},
    onBrowse: () -> Unit = {},
) {
    val st by viewModel.state.collectAsState()
    val connected by viewModel.connected.collectAsState()

    // The palette follows the player being shown, which may differ from the app-wide one.
    val palette = rememberAlbumPalette(st.artworkUrl)
    val accent = palette.accent

    // Smoothly interpolate the position between 2s polls.
    var pos by remember { mutableStateOf(0L) }
    LaunchedEffect(st.positionMs, st.isPlaying, st.title) {
        pos = st.positionMs
        while (st.isPlaying) { kotlinx.coroutines.delay(500); pos += 500 }
    }
    val dur = st.durationMs
    val progress = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f

    var liked by remember(st.title) { mutableStateOf(false) }

    CompositionLocalProvider(LocalAccent provides accent, LocalPalette provides palette) {
        Box(Modifier.fillMaxSize().background(Ink)) {
            // The screen keeps its shape whether or not anything is playing: an
            // idle player shows the last track it had (dimmed, with a notice)
            // rather than swapping the whole screen for an empty state.
            MeltBackdrop(st.artworkUrl, intensity = if (st.idle) 0.5f else 1f)

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

                Spacer(Modifier.height(16.dp))

                // Secondary actions, with the quality badge sitting at the centre of them.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconChip(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist")
                    IconChip(Icons.Default.Album, "Go to album")
                    QualityChip(st.quality)
                    IconChip(Icons.AutoMirrored.Filled.QueueMusic, "Queue")
                    IconChip(Icons.Default.Lightbulb, "Light sync", active = true, onClick = onOpenLightSync)
                    IconChip(
                        if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        "Like", active = liked,
                    ) { liked = !liked }
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

                HSlider(progress, { f -> pos = (f * dur).toLong(); viewModel.seekTo(f) })
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
