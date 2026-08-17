package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.ui.theme.*

/**
 * The driving bar: three controls, very large, over whatever is on screen.
 *
 * These sizes are requirements rather than taste. Android Auto's driver-distraction
 * guidance is the reference point, not phone-sized touch targets — 48dp is the
 * minimum for someone *looking at their phone*, and the whole premise here is
 * someone who is not. Three controls at most, because anything that invites reading
 * invites looking away; a title that truncates rather than scrolls, for the same
 * reason.
 *
 * Shared by both window mechanisms in shape but only rendered by the overlay path:
 * Picture-in-Picture cannot host a composable, and expresses the same three actions
 * as `RemoteAction`s instead. That is one of the limits E1 exists to lift. Shuffle
 * is a fourth control on top of those three, and stays overlay-only for the same
 * reason: PiP's `RemoteAction` budget is 3, already spent on transport.
 */
@Composable
fun DrivingBar(
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val app = SendpinApp.instance
    val maNow by app.maNowPlaying.now.collectAsStateWithLifecycle()
    val localTrack by app.localPlayer.current.collectAsStateWithLifecycle()
    val localPlaying by app.localPlayer.playing.collectAsStateWithLifecycle()
    val ownerState by app.playbackOwner.state.collectAsStateWithLifecycle()
    val localShuffle by app.localPlayer.shuffle.collectAsStateWithLifecycle()
    val maShuffle by app.maNowPlaying.shuffleActive.collectAsStateWithLifecycle()

    val isLocal = ownerState.sessionOwner == com.engabd.sendpin.service.PlaybackOwner.Who.LOCAL
    val title = if (isLocal) localTrack?.title.orEmpty() else maNow?.title.orEmpty()
    val playing = if (isLocal) localPlaying else ownerState.sendspinPlaying || maNow?.isPlaying == true
    val shuffleOn = if (isLocal) localShuffle else maShuffle

    // Which edge the bar sits on. A cradle's position varies, and the bar must never
    // permanently cover the map's own controls — so a vertical drag moves it, and
    // there is somewhere else for it to go.
    var atBottom by remember { mutableStateOf(true) }

    Box(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dy ->
                    if (dy < -8f) atBottom = false
                    if (dy > 8f) atBottom = true
                }
            },
        contentAlignment = if (atBottom) Alignment.BottomCenter else Alignment.TopCenter,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(22.dp))
                // Opaque, not glass. Everything else in this app is translucent over
                // its own backdrop; this one sits over a map, and a control whose
                // contrast depends on what is behind it is exactly wrong here.
                .background(Ink)
                .border(1.dp, HairlineSoft, RoundedCornerShape(22.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DrivingButton(Icons.Default.SkipPrevious, "Previous track", onPrevious)
            DrivingButton(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (playing) "Pause" else "Play",
                onPlayPause,
                accent = true,
            )
            DrivingButton(Icons.Default.SkipNext, "Next track", onNext)

            // The title, and nothing else. No artist, no album, no progress: each
            // would be another thing worth reading, and reading is the cost this
            // whole surface exists to avoid. One line, truncated — deliberately not
            // a marquee, because movement in peripheral vision while driving is the
            // worst possible place to put it.
            Text(
                title.ifBlank { "Nothing playing" },
                color = if (title.isBlank()) TextMuted else TextPrimary,
                fontFamily = AppFont,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )

            // Smaller than the transport row on purpose: three 76dp targets already
            // claim most of a compact phone's width, and a fourth at the same size
            // would leave the title no room to say anything before truncating.
            // Still well past the 48dp a glance-and-tap can manage.
            Icon(
                Icons.Default.Shuffle,
                if (shuffleOn) "Shuffle on" else "Shuffle off",
                tint = if (shuffleOn) LocalAccentOrDefault() else TextMuted,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (shuffleOn) Ink3 else Color.Transparent)
                    .clickable(onClick = onShuffle)
                    .padding(11.dp),
            )

            // Small, and the only small target here, because dismissing by accident
            // is much worse than having to aim for it.
            Icon(
                Icons.Default.Close,
                "Hide the driving bar",
                tint = TextMuted,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss)
                    .padding(7.dp),
            )
        }
    }
}

/**
 * One transport target, sized for a glance rather than a look.
 *
 * 76dp against the platform's 48dp minimum. That minimum describes a target someone
 * is aiming at with their eyes on it; this one is aimed at with peripheral vision,
 * at a phone that is moving, by someone whose attention belongs on the road.
 */
@Composable
private fun DrivingButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    accent: Boolean = false,
) {
    val tint = if (accent) LocalAccentOrDefault() else TextPrimary
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(if (accent) Ink3 else Ink2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(40.dp))
    }
}

/**
 * The album accent, or the app default.
 *
 * This bar renders outside the app's theme — it is a service-hosted window with no
 * `SendspinTheme` above it — so `LocalAccent` is at its default here rather than at
 * the current album's. That is fine and arguably right: a control that changes
 * colour with the artwork is a control that looks different every track, and
 * consistency is worth more than personality on this particular surface.
 */
@Composable
private fun LocalAccentOrDefault(): Color = DefaultAccent
