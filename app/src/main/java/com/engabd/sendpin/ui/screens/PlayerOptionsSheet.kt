package com.engabd.sendpin.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.audio.ExclusiveOutput
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel
import kotlin.math.roundToInt

/** Speeds worth having a one-tap button for; the slider covers everything else. */
private val SpeedPresets = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * The player's own settings, as opposed to the track's: power, how fast it plays,
 * and whether it keeps the queue topped up once it runs dry. Small enough to sit in a
 * card at the bottom of Now Playing rather than take a screen.
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
                } else {
                    // The same promise on the Navidrome path, kept by different means:
                    // no server to hand the queue to, so the app picks the next few
                    // tracks out of the library itself and appends them before the last
                    // one ends. It was a switch buried in Settings › Audio, two screens
                    // from the queue it governs; it belongs next to the player, where
                    // MA's version of it already lives.
                    OptionRow(
                        icon = Icons.Default.Radio,
                        title = "Keep the music going",
                        // Says which of the two it will do, because the shuffle
                        // button beside the play control is what decides — and a
                        // switch whose behaviour depends on another control is worth
                        // naming that control in.
                        subtitle = if (st.shuffle) "Carry on with random songs from the library"
                        else "Carry on through the album, then the next one",
                        checked = st.radioMode,
                        onChange = { viewModel.toggleRadioMode() },
                    )
                }

                // Vinyl and lo-fi both run inside LocalPlayer's own processor
                // chain (VinylNoiseProcessor / LoFiProcessor), not in anything
                // Music Assistant streams — same reason "Don't stop the music"
                // is MA-only above and "Keep the music going" is the local
                // stand-in for it: offering these on the MA path would be
                // switches that silently did nothing to the player actually
                // making the sound, which is the exact mistake the comment on
                // that branch calls out. Local session only, for the same
                // reason.
                if (st.isLocalSession) {
                    val exclusiveReason = ExclusiveOutput.soundModes.reason
                    val soundModesDisabled = st.exclusiveOutputOn
                    val disabledSubtitle = "Exclusive output is on. $exclusiveReason Turn it off in " +
                        "Settings > Playback & audio > Output to use this again."

                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SoundModeOption(
                            icon = Icons.Default.Album,
                            title = "Vinyl",
                            subtitle = if (soundModesDisabled) disabledSubtitle
                            else "Crackle, dust and rumble over the top of the music",
                            enabled = !soundModesDisabled,
                            checked = st.vinylNoiseConfig.enabled,
                            intensity = st.vinylNoiseConfig.intensity,
                            onCheckedChange = viewModel::setVinylNoiseEnabled,
                            onIntensityChange = viewModel::setVinylNoiseIntensity,
                        )
                        SoundModeOption(
                            icon = Icons.Default.GraphicEq,
                            title = "Lo-fi",
                            subtitle = if (soundModesDisabled) disabledSubtitle
                            else "Bitcrusher, saturation and a low-pass roll-off",
                            enabled = !soundModesDisabled,
                            checked = st.loFiConfig.enabled,
                            intensity = st.loFiConfig.intensity,
                            onCheckedChange = viewModel::setLoFiEnabled,
                            onIntensityChange = viewModel::setLoFiIntensity,
                        )
                    }
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
            DisclosureChevron(expanded = open, size = 18.dp)
        }

        // The list grows out of the row that asked for it rather than appearing whole.
        // It used to be a bare `if (open)`, which puts several rows on screen between
        // two frames — and because this list is fetched, "open" and "arrived" are
        // different moments, so the sheet also jumped a second time when the versions
        // came back. Expanding covers both: the panel takes its height smoothly on the
        // way open and again when the rows land.
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(Motion.contentSize()) + fadeIn(Motion.effects()),
            exit = shrinkVertically(Motion.contentSize()) + fadeOut(Motion.effects()),
        ) {
            // The enclosing Column's `spacedBy` reaches its own children, and this
            // whole block is now one of them — so the gaps between version rows have
            // to be restated here or they collapse to nothing.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
}

@Composable
private fun OptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon, null,
            tint = if (!enabled) TextFaint else if (checked) accent else TextMuted,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(title, color = if (enabled) TextPrimary else TextMuted, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, color = TextFaint, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink, checkedTrackColor = accent, checkedBorderColor = accent,
                uncheckedThumbColor = TextMuted, uncheckedTrackColor = Glass, uncheckedBorderColor = Hairline,
            ),
        )
    }
}

/**
 * A [OptionRow] toggle plus the intensity slider beneath it, for Vinyl and
 * Lo-fi in the "Sound modes" block above. Shares the `HSlider` + value-label
 * idiom the "Playback speed" `Column` below uses, rather than inventing a
 * second one for two rows that want the same shape.
 *
 * The slider stays visible whenever the mode is [checked], even when
 * [enabled] is false (Exclusive output is on) — same reasoning as
 * [OptionRow] itself staying visible rather than disappearing: the setting
 * underneath is still real and still worth showing, it just isn't reaching
 * the signal right now.
 *
 * The drag is held locally and written once on release, the pattern
 * `EffectsScreen`'s level slider already uses. [onIntensityChange] lands in
 * DataStore, which is a serialise and a disk write; firing it on every frame
 * of a drag would spend dozens of those to reach one value the listener
 * actually wanted. The processors read the stored config on the next buffer
 * either way, so committing on release costs nothing audible.
 */
@Composable
private fun SoundModeOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    checked: Boolean,
    intensity: Float,
    onCheckedChange: (Boolean) -> Unit,
    onIntensityChange: (Float) -> Unit,
) {
    val accent = LocalAccent.current
    // Null except while a finger is down: the stored value is the truth the rest
    // of the time, so an intensity changed from anywhere else still shows here.
    var drag by remember(title) { mutableStateOf<Float?>(null) }
    val shown = drag ?: intensity

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OptionRow(
            icon = icon, title = title, subtitle = subtitle,
            checked = checked, enabled = enabled, onChange = onCheckedChange,
        )
        if (checked) {
            Column(
                Modifier.padding(start = 29.dp).alpha(if (enabled) 1f else 0.5f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Intensity", color = TextFaint, fontFamily = AppFont,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(shown * 100).roundToInt()}%", color = accent, fontFamily = MonoFont,
                        fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    )
                }
                HSlider(
                    value = shown,
                    onChange = { if (enabled) drag = it },
                    onCommit = { if (enabled) { drag = null; onIntensityChange(it) } },
                )
            }
        }
    }
}

/** Snap to the nearest 0.05 so dragging lands on speakable values. */
private fun round05(v: Float) = ((v * 20f).roundToInt() / 20f).coerceIn(0.5f, 3f)

/** 1.0 → "1", 1.25 → "1.25". */
private fun trim(v: Float): String =
    if (kotlin.math.abs(v - v.roundToInt()) < 0.01f) v.roundToInt().toString()
    else v.toString().trimEnd('0').trimEnd('.')
