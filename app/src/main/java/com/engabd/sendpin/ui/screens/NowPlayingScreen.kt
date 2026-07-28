package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.PlayerViewModel

@Composable
fun NowPlayingScreen(
    viewModel: PlayerViewModel = viewModel(),
    onOpenSpeakers: () -> Unit = {},
    onOpenLightSync: () -> Unit = {},
    onBrowse: () -> Unit = {},
) {
    val title by viewModel.trackTitle.collectAsState()
    val artist by viewModel.artist.collectAsState()
    val album by viewModel.album.collectAsState()
    val art by viewModel.artworkUrl.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val volume by viewModel.volume.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val format by viewModel.currentFormat.collectAsState()

    val accent = LocalAccent.current
    val hasTrack = title.isNotBlank()

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (!hasTrack) {
            IdlePlayer(accent, onBrowse)
            return@Box
        }

        // Blurred album backdrop + darkening gradient.
        if (art != null) {
            AsyncImage(
                model = art, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(48.dp).alpha(0.55f),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Ink.a(0.28f), 0.46f to Ink.a(0.66f), 0.9f to Ink,
                )
            )
        )

        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!connected) OfflineBanner()

            // Top bar: back, group pill, overflow.
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.KeyboardArrowDown, "Minimize", tint = TextSecondary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(100)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(100))
                        .clickable(onClick = onOpenSpeakers).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.Speaker, null, tint = TextPrimary, modifier = Modifier.size(15.dp))
                    Text("This phone", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.MoreVert, "More", tint = TextSecondary, modifier = Modifier.size(22.dp))
            }

            Spacer(Modifier.height(8.dp))

            // Cover.
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Ink2),
                contentAlignment = Alignment.Center,
            ) {
                if (art != null) {
                    AsyncImage(model = art, contentDescription = "Album art", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.MusicNote, null, tint = TextFaint, modifier = Modifier.size(64.dp))
                }
            }

            Spacer(Modifier.height(18.dp))

            // Action chips row (quality + light sync).
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostChip(Icons.Default.Add, "Add")
                QualityBadge(format.ifBlank { "—" })
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Glass)
                        .border(1.dp, Hairline, RoundedCornerShape(11.dp)).clickable(onClick = onOpenLightSync),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Lightbulb, "Light sync", tint = accent, modifier = Modifier.size(17.dp)) }
                GhostChip(Icons.Default.FavoriteBorder, "Like")
            }

            Spacer(Modifier.height(18.dp))

            // Title / artist / album.
            Text(title, color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (artist.isNotBlank()) Text(artist, color = TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (album.isNotBlank()) Text(album, color = TextFaint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

            Spacer(Modifier.height(16.dp))

            // Scrubber (visual — Sendspin position isn't exposed yet).
            var progress by remember { mutableStateOf(0.42f) }
            HSlider(progress, { progress = it })
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1:47", color = TextMuted, fontFamily = MonoFont, fontSize = 11.sp)
                Text("4:12", color = TextMuted, fontFamily = MonoFont, fontSize = 11.sp)
            }

            Spacer(Modifier.height(14.dp))

            // Transport.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shuffle, "Shuffle", tint = TextMuted, modifier = Modifier.size(20.dp))
                Icon(Icons.Default.SkipPrevious, "Previous", tint = TextPrimary, modifier = Modifier.size(30.dp))
                Box(
                    Modifier.size(64.dp).shadow(22.dp, CircleShape, ambientColor = accent, spotColor = accent)
                        .clip(CircleShape).background(accent).clickable { viewModel.onPlayPause() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = Ink, modifier = Modifier.size(30.dp)) }
                Icon(Icons.Default.SkipNext, "Next", tint = TextPrimary, modifier = Modifier.size(30.dp))
                Icon(Icons.Default.Repeat, "Repeat", tint = accent, modifier = Modifier.size(20.dp))
            }

            Spacer(Modifier.height(16.dp))

            // Volume.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Icon(Icons.Default.VolumeUp, "Volume", tint = TextMuted, modifier = Modifier.size(16.dp))
                HSlider(volume.coerceIn(0f, 1f), { viewModel.onVolumeChange(it) }, modifier = Modifier.weight(1f))
                Text("${(volume * 100).toInt()}", color = TextMuted, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun GhostChip(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String) {
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, cd, tint = TextSecondary, modifier = Modifier.size(16.dp)) }
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
        Text("Offline — playing downloads", color = Color(0xFFF2C574), fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun IdlePlayer(accent: Color, onBrowse: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Bloom(accent, 420.dp, 20.dp, (-40).dp, 0.28f)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(34.dp)) {
            Box(
                Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.QueueMusic, null, tint = TextFaint, modifier = Modifier.size(34.dp)) }
            Spacer(Modifier.height(22.dp))
            Text("Sendspin is quiet", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Nothing playing right now. Pick something from your library to fill the room.",
                color = TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 260.dp),
            )
            Spacer(Modifier.height(26.dp))
            Box(
                Modifier.clip(RoundedCornerShape(100)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(100))
                    .clickable(onClick = onBrowse).padding(horizontal = 24.dp, vertical = 13.dp),
            ) { Text("Browse library", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        }
    }
}
