package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel
import kotlin.math.roundToInt

/** Speeds worth having a one-tap button for; the slider covers everything else. */
private val SpeedPresets = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * The player's own settings, as opposed to the track's: power, how fast it plays,
 * and whether MA keeps the queue topped up. Small enough to sit in a card at the
 * bottom of Now Playing rather than take a screen.
 */
@Composable
fun BoxScope.PlayerOptionsSheet(onClose: () -> Unit, viewModel: NowPlayingViewModel) {
    HideBottomChrome()
    val st by viewModel.state.collectAsStateWithLifecycle()
    val accent = LocalAccent.current

    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            // Swallow taps. Without this, a tap on the sheet's own background
            // passes through to the dismiss scrim underneath and closes it —
            // including taps that merely miss a switch by a few dp.
            .dismissOnDragDown(onClose)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(Ink2)
            .border(1.dp, Hairline, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = systemNavInset() + 12.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(100)).background(Hairline))
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    st.playerName, color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Glass).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Close, "Close", tint = TextSecondary, modifier = Modifier.size(16.dp)) }
            }

            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {

                // Power — only for players that actually expose a switch.
                if (st.canPower) {
                    OptionRow(
                        icon = Icons.Default.PowerSettingsNew,
                        title = "Power",
                        subtitle = if (st.powered) "On" else "Off",
                        checked = st.powered,
                        onChange = { viewModel.setPower(it) },
                    )
                }

                // Every copy of this track across every provider — the same song as a
                // lossy stream and as a FLAC on a NAS. MA only: it is MA that knows
                // about more than one library at a time.
                if (!st.isLocalSession && st.hasTrack) {
                    VersionPicker(viewModel)
                }

                // Music Assistant only. `player_queues/dont_stop_the_music` is a
                // property of an MA queue, generated from MA's own similarity model —
                // Navidrome has no equivalent, and showing the switch there offered a
                // setting that silently did nothing to the player making the sound.
                // The two backends share a UI but not an API; the badge top-right says
                // which one is live, and the controls now agree with it.
                if (!st.isLocalSession) {
                    OptionRow(
                        icon = Icons.Default.AllInclusive,
                        title = "Don't stop the music",
                        subtitle = "Keep playing similar tracks when the queue runs out",
                        checked = st.dontStopTheMusic,
                        onChange = { viewModel.toggleDontStopTheMusic() },
                    )
                }

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Speed, null, tint = TextMuted, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Playback speed", color = TextPrimary, fontFamily = AppFont,
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${trim(st.playbackSpeed)}×", color = accent, fontFamily = MonoFont,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // The command takes 0.5–3.0; the slider maps that range linearly.
                    HSlider(
                        ((st.playbackSpeed - 0.5f) / 2.5f).coerceIn(0f, 1f),
                        { viewModel.setPlaybackSpeed(round05(0.5f + it * 2.5f)) },
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpeedPresets.forEach { s ->
                            ToggleChip("${trim(s)}×", kotlin.math.abs(st.playbackSpeed - s) < 0.01f) {
                                viewModel.setPlaybackSpeed(s)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Applies to this player's queue - useful for audiobooks and podcasts.",
                        color = TextFaint, fontFamily = AppFont, fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

/**
 * Which copy of this track to play.
 *
 * The same song usually exists more than once — a lossy stream and a FLAC on a NAS —
 * and Music Assistant has always been able to list them (`music/tracks/track_versions`,
 * implemented and uncalled). This is the control that speaks to why anyone runs a
 * local library next to a streaming one.
 *
 * Collapsed until asked, because it costs a round trip and most of the time the answer
 * is "one". A single version means the row says so and offers nothing to tap.
 */
@Composable
private fun VersionPicker(viewModel: NowPlayingViewModel) {
    val accent = LocalAccent.current
    val load by viewModel.versions.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable {
                open = !open
                if (open && load is NowPlayingViewModel.Load.Idle) viewModel.loadVersions()
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.LibraryMusic, null, tint = TextMuted, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                Text("Other versions", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                Text(
                    when (val l = load) {
                        is NowPlayingViewModel.Load.Ready ->
                            if (l.value.isEmpty()) "This is the only copy in your library"
                            else "${l.value.size} other ${if (l.value.size == 1) "copy" else "copies"} of this track"
                        is NowPlayingViewModel.Load.Failed -> l.message
                        NowPlayingViewModel.Load.Loading -> "Looking…"
                        else -> "Play this song from a different source"
                    },
                    color = TextFaint, style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(
                if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, tint = TextMuted, modifier = Modifier.size(18.dp),
            )
        }

        if (open) {
            (load as? NowPlayingViewModel.Load.Ready)?.value?.forEach { version ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(Glass)
                        .clickable { viewModel.playVersion(version) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                        Text(
                            // The provider is the useful identity here — the name is
                            // the same on every row by definition.
                            version.providerDomains.firstOrNull()?.replaceFirstChar { it.uppercase() }
                                ?: version.name,
                            color = TextPrimary, fontFamily = AppFont,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        )
                        version.audioFormat?.quality?.label?.let {
                            Text(it, color = TextMuted, fontFamily = MonoFont, fontSize = 11.sp)
                        }
                    }
                    Icon(Icons.Default.PlayArrow, "Play this version", tint = accent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (checked) accent else TextMuted, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, color = TextFaint, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink, checkedTrackColor = accent, checkedBorderColor = accent,
                uncheckedThumbColor = TextMuted, uncheckedTrackColor = Glass, uncheckedBorderColor = Hairline,
            ),
        )
    }
}

/** Snap to the nearest 0.05 so dragging lands on speakable values. */
private fun round05(v: Float) = ((v * 20f).roundToInt() / 20f).coerceIn(0.5f, 3f)

/** 1.0 → "1", 1.25 → "1.25". */
private fun trim(v: Float): String =
    if (kotlin.math.abs(v - v.roundToInt()) < 0.01f) v.roundToInt().toString()
    else v.toString().trimEnd('0').trimEnd('.')
