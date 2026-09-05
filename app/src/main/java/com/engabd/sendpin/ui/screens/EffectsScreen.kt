package com.engabd.sendpin.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.EffectsViewModel

/**
 * Ambience effects: lighting shows with their own sound, independent of music.
 *
 * A full-screen destination reached from the Lights tab rather than a fifth bottom tab.
 * Effects is a sibling of Light Sync, not a peer of Library, and `sectionOf` in `App.kt`
 * already has the idiom for a route that lights up another tab.
 *
 * Tapping a tile starts it immediately. There is no separate play button because there
 * is nothing to configure first — everything below the tile can be changed while it runs.
 */
@Composable
fun EffectsScreen(onBack: () -> Unit, viewModel: EffectsViewModel) {
    val accent = LocalAccent.current
    val running by viewModel.running.collectAsStateWithLifecycle()
    val soundMode by viewModel.soundMode.collectAsStateWithLifecycle()
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val intensities by viewModel.intensities.collectAsStateWithLifecycle()
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val sleepMinutes by viewModel.sleepMinutes.collectAsStateWithLifecycle()
    val remaining by viewModel.remainingS.collectAsStateWithLifecycle()

    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.toast.collect { toast = it } }
    // Toasts clear themselves: an error left on screen for the life of the show
    // read as a permanent state the screen could not recover from, when every
    // message it carries is about a moment that has already passed.
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(4_000)
            toast = null
        }
    }

    // Which tile has its controls open. The running one by default, so starting a show
    // reveals its settings without a second tap.
    var expanded by remember { mutableStateOf<String?>(null) }
    // Failing that, the last effect started - which is what `effectsLast` has always
    // claimed to be for and, until now, was written for and never read. Guarded on
    // `expanded == null` so it only ever seeds the first composition: collapsing a
    // tile by hand must not have it spring back open.
    val lastEffect by viewModel.lastEffect.collectAsStateWithLifecycle()
    LaunchedEffect(lastEffect) { if (expanded == null) expanded = lastEffect }
    LaunchedEffect(running) { if (running != null) expanded = running }

    var pickingFor by remember { mutableStateOf<AmbienceEffect?>(null) }
    val pickClip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> pickingFor?.let { viewModel.setClip(it, uri) }; pickingFor = null }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Bloom(if (running != null) accent else TextFaint, 440.dp, (-40).dp, (-70).dp,
            if (running != null) 0.42f else 0.16f)

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleBtn(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Text("Effects", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                        ExperimentalBadge()
                    }
                    Text(
                        "Atmosphere, not a reaction to music",
                        color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    )
                }
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp).padding(bottom = navBarInset() + 16.dp),
            ) {
                toast?.let {
                    Text(it, color = ErrorRed, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                }

                Text(
                    "Experimental: these shows drive the bridge directly and take the room " +
                        "off the music while they run. Expect rough edges — timings, levels " +
                        "and the sound mix are all still being tuned.",
                    color = WarnAmber, fontSize = 12.sp, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))

                Text(
                    "Each effect plays a real recording and lights the room from it. The " +
                        "app listens to the sound it is playing, so the flash lands on the " +
                        "thunderclap, from the side it came from, and a far-off rumble " +
                        "washes the room instead of cracking it.",
                    color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))
                // The two kinds of effect, stated plainly: which ones follow the sound
                // and which ones merely live alongside it. The distinction is the
                // design, not a footnote - a storm is its thunder, an aurora is not
                // its pad.
                Text(
                    "Storms, fireworks and the fireplace react to what you hear - a clap " +
                        "flashes where it came from. The aurora, underwater and light train " +
                        "are their own scene: their sound is a background, and the lights " +
                        "drift on their own no matter what plays.",
                    color = TextFaint, fontSize = 11.sp, lineHeight = 15.sp,
                )
                Spacer(Modifier.height(16.dp))

                // Two columns of tiles, built as rows of two rather than a LazyGrid: the
                // page already scrolls, and a nested lazy grid inside a scrolling column
                // has to be given a fixed height it cannot know.
                AmbienceEffect.entries.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { e ->
                            EffectCard(
                                effect = e,
                                running = running == e.wire,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (running == e.wire) viewModel.stop() else viewModel.start(e)
                            }
                        }
                        // Keeps the last row's single tile at half width rather than
                        // letting it stretch across the page.
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                }

                expanded?.let { wire ->
                    AmbienceEffect.fromWire(wire)?.let { e ->
                        Spacer(Modifier.height(8.dp))
                        SectionLabel(e.title)
                        Spacer(Modifier.height(10.dp))
                        GlassCard(radius = 18.dp) {
                            Column(Modifier.padding(16.dp)) {
                                Text(e.blurb, color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp)
                                Spacer(Modifier.height(14.dp))

                                Text("Intensity", color = TextSecondary, fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                var drag by remember(wire) { mutableStateOf<Float?>(null) }
                                val value = drag ?: (intensities[wire] ?: 0.5f)
                                LabeledSliderRow(
                                    value = value,
                                    label = "${(value * 100).toInt()}%",
                                    onChange = { drag = it },
                                    onCommit = { drag = null; viewModel.setIntensity(e, it) },
                                )

                                Spacer(Modifier.height(16.dp))
                                Text("Sound", color = TextSecondary, fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Pill("Default", soundMode == "synth") { viewModel.setSoundMode("synth") }
                                    Pill("My clip", soundMode == "clip") { viewModel.setSoundMode("clip") }
                                    Pill("Off", soundMode == "off") { viewModel.setSoundMode("off") }
                                }

                                if (soundMode == "clip") {
                                    Spacer(Modifier.height(10.dp))
                                    val clip = clips[wire]
                                    Text(
                                        clip?.substringAfterLast('/')?.let { "Using $it" }
                                            ?: "No clip chosen — falls back to the synth",
                                        color = TextFaint, fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Pill("Choose file", false) {
                                            pickingFor = e
                                            pickClip.launch(arrayOf("audio/*"))
                                        }
                                        if (clip != null) Pill("Clear", false) { viewModel.setClip(e, null) }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    // This used to have to say the opposite — that the
                                    // lights followed the script and not the file — because
                                    // a clip ran on its own clock with nothing analysing
                                    // it. Now the same tap the music show uses is on this
                                    // player too, so any file the listener picks drives
                                    // the room.
                                    Text(
                                        "Your clip plays as the bed, and the lights follow it — " +
                                            "the effect reacts to whatever it can hear.",
                                        color = TextFaint, fontSize = 11.sp, lineHeight = 15.sp,
                                    )
                                }

                                if (soundMode != "off") {
                                    Spacer(Modifier.height(14.dp))
                                    Text("Level", color = TextSecondary, fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(6.dp))
                                    var vDrag by remember(wire) { mutableStateOf<Float?>(null) }
                                    val v = vDrag ?: (volume / 100f)
                                    LabeledSliderRow(
                                        value = v,
                                        label = "${(v * 100).toInt()}%",
                                        onChange = { vDrag = it },
                                        onCommit = { vDrag = null; viewModel.setVolume((it * 100).toInt()) },
                                    )
                                }

                                Spacer(Modifier.height(16.dp))
                                Text("Sleep timer", color = TextSecondary, fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "A show is a 60 Hz render loop and a running synth. The " +
                                        "timer is here so it cannot quietly run all night.",
                                    color = TextFaint, fontSize = 11.sp, lineHeight = 15.sp,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    listOf(0 to "Off", 15 to "15m", 30 to "30m", 60 to "1h", 120 to "2h")
                                        .forEach { (m, label) ->
                                            Pill(label, sleepMinutes == m) { viewModel.setSleepMinutes(m) }
                                        }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }

            // The running bar. Always reachable, so a show can be stopped without
            // hunting for the tile that started it.
            if (running != null) {
                val title = AmbienceEffect.fromWire(running)?.title ?: "Ambience"
                GlassCard(
                    radius = 18.dp,
                    fill = accent.a(0.12f),
                    modifier = Modifier.padding(horizontal = 18.dp)
                        .padding(bottom = navBarInset() + 12.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                remaining?.let { "Stops in ${formatRemaining(it)}" } ?: "Running",
                                color = TextMuted, fontSize = 12.sp,
                            )
                        }
                        CircleBtn(Icons.Default.Stop, "Stop") { viewModel.stop() }
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectCard(
    effect: AmbienceEffect,
    running: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    GlassCard(
        modifier = modifier,
        radius = 18.dp,
        fill = if (running) accent.a(0.14f) else Glass,
    ) {
        Column(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (running) accent.a(0.20f) else Glass)
                    .border(1.dp, if (running) accent.a(0.45f) else Hairline, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ambienceIcon(effect), null,
                    tint = if (running) accent else TextMuted,
                    modifier = Modifier.size(19.dp),
                )
            }
            Text(
                effect.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                effect.blurb, color = TextFaint, fontSize = 11.sp, lineHeight = 15.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A slider with its value beside it, matching the Lights tab's controls. */
@Composable
private fun LabeledSliderRow(
    value: Float,
    label: String,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            androidx.compose.material3.Slider(
                value = value.coerceIn(0f, 1f),
                onValueChange = onChange,
                onValueChangeFinished = { onCommit(value.coerceIn(0f, 1f)) },
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = LocalAccent.current,
                    activeTrackColor = LocalAccent.current,
                    inactiveTrackColor = Hairline,
                ),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = TextMuted, fontSize = 12.sp, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * The glyph for each ambience effect.
 *
 * Internal rather than private since the Light Sync page draws the catalogue on its
 * own tile: two lists of icons for the same nine effects would drift the first time
 * one was added.
 */
internal fun ambienceIcon(e: AmbienceEffect): ImageVector = when (e) {
    AmbienceEffect.FIREWORKS, AmbienceEffect.FIREWORKS_2 -> Icons.Default.Celebration
    AmbienceEffect.THUNDERSTORM, AmbienceEffect.THUNDERSTORM_2 -> Icons.Default.Thunderstorm
    AmbienceEffect.UNDERWATER -> Icons.Default.Water
    AmbienceEffect.FIREPLACE -> Icons.Default.LocalFireDepartment
    AmbienceEffect.LIGHT_TRAIN -> Icons.Default.Train
    AmbienceEffect.AURORA -> Icons.Default.AutoAwesome
    AmbienceEffect.COASTAL_RAIN -> Icons.Default.WaterDrop
}

private fun formatRemaining(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    // Below ten minutes the seconds matter - "9m" covering anything from 9:59 to
    // 9:00 made the last minute of a show look stuck. Above that, minutes only.
    return when {
        m >= 10 -> "${m}m"
        m >= 1 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
