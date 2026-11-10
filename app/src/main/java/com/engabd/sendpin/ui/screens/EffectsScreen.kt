package com.engabd.sendpin.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Cottage
import androidx.compose.material.icons.filled.EmojiObjects
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.R
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.EffectsViewModel
import kotlin.math.roundToInt

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
    val starting by viewModel.starting.collectAsStateWithLifecycle()
    val soundMode by viewModel.soundMode.collectAsStateWithLifecycle()
    val overMusic by viewModel.overMusic.collectAsStateWithLifecycle()
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val brightness by viewModel.brightness.collectAsStateWithLifecycle()
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
        // The selected scene, softly, behind the top of the page: brighter while it runs.
        val backdrop = (running ?: expanded)?.let { AmbienceEffect.fromWire(it) }
        Crossfade(backdrop, animationSpec = tween(700), label = "backdrop") { e ->
            if (e != null) {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    Image(
                        painterResource(ambienceImage(e)), null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur(28.dp)
                            .alpha(if (running != null) 0.55f else 0.30f),
                    )
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Ink))))
                }
            } else {
                Bloom(TextFaint, 440.dp, (-40).dp, (-70).dp, 0.16f)
            }
        }

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleBtn(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    Text("Scenes", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text(
                        "Your room, in light and sound",
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
                    "Each scene plays a real recording and lights every lamp in the room " +
                        "from it. Thunder flashes where it came from, a fire breathes in its " +
                        "corner, and the calm scenes drift on their own. Tap one to begin.",
                    color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp,
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
                                starting = starting == e.wire,
                                modifier = Modifier.weight(1f),
                            ) {
                                when {
                                    starting == e.wire -> Unit     // already on its way
                                    running == e.wire -> viewModel.stop()
                                    else -> viewModel.start(e)
                                }
                            }
                        }
                        // Keeps the last row's single tile at half width rather than
                        // letting it stretch across the page.
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(16.dp))
            }

            // The scene's controls, at the bottom where a thumb is, as soon as a scene is
            // picked: brightness and intensity always, the rest a tap away. They used to sit
            // below the whole grid, a long scroll from the tile that started the scene.
            (running ?: expanded)?.let { AmbienceEffect.fromWire(it) }?.let { e ->
                val wire = e.wire
                val isRunning = running == wire
                var more by remember { mutableStateOf(false) }
                GlassCard(
                    radius = 18.dp,
                    fill = if (isRunning) accent.a(0.12f) else Glass,
                    modifier = Modifier.padding(horizontal = 18.dp)
                        .padding(bottom = navBarInset() + 12.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                                Text(e.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(
                                    when {
                                        starting == wire -> "Starting…"
                                        isRunning -> remaining?.let { "Stops in ${formatRemaining(it)}" } ?: "Running"
                                        else -> "Not playing"
                                    },
                                    color = TextMuted, fontSize = 12.sp,
                                )
                            }
                            CircleBtn(
                                if (more) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                if (more) "Fewer settings" else "More settings",
                            ) { more = !more }
                            Spacer(Modifier.width(8.dp))
                            if (isRunning) {
                                CircleBtn(Icons.Default.Stop, "Stop") { viewModel.stop() }
                            } else {
                                CircleBtn(Icons.Default.PlayArrow, "Play") {
                                    if (starting != wire) viewModel.start(e)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))

                        var bDrag by remember { mutableStateOf<Float?>(null) }
                        val b = bDrag ?: (brightness / 100f)
                        PanelSlider(
                            "Brightness", b,
                            onChange = { bDrag = it.coerceAtLeast(0.05f) },
                            onCommit = { bDrag = null; viewModel.setBrightness((it * 100).toInt()) },
                        )
                        // Intensity is how lively the scene is; brightness stays where it is.
                        var drag by remember(wire) { mutableStateOf<Float?>(null) }
                        val value = drag ?: (intensities[wire] ?: 0.5f)
                        PanelSlider(
                            "Intensity", value,
                            onChange = { drag = it },
                            onCommit = { drag = null; viewModel.setIntensity(e, it) },
                        )

                        if (more) {
                            Column(
                                Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                                    .padding(top = 8.dp),
                            ) {
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
                                Text("Volume", color = TextSecondary, fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                var vDrag by remember(wire) { mutableStateOf<Float?>(null) }
                                val v = vDrag ?: (volume / 100f)
                                LabeledSliderRow(
                                    value = v,
                                    label = "${(v * 100).roundToInt()}%",
                                    onChange = { vDrag = it },
                                    onCommit = { vDrag = null; viewModel.setVolume((it * 100).roundToInt()) },
                                )

                                Spacer(Modifier.height(14.dp))
                                Text("Play over music", color = TextSecondary, fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Pill("Under the music", overMusic) { viewModel.setOverMusic(true) }
                                    Pill("Instead of it", !overMusic) { viewModel.setOverMusic(false) }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (overMusic)
                                        "A record keeps playing and the bed sits underneath it, " +
                                            "quietly. Nothing is paused and nothing is taken over."
                                    else
                                        "Starting a show stops whatever is playing and takes the " +
                                            "output, which is what a show playing on its own wants.",
                                    color = TextFaint, fontSize = 11.sp, lineHeight = 15.sp,
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
            }
        }
    }
}

@Composable
private fun EffectCard(
    effect: AmbienceEffect,
    running: Boolean,
    starting: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(18.dp)
    // A slow breath on the running scene's border, the room's own pace rather than a blink.
    val breath = if (running) {
        rememberInfiniteTransition(label = "breath").animateFloat(
            0.45f, 1f,
            infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breath",
        ).value
    } else 0f
    Box(
        modifier.aspectRatio(0.82f).clip(shape)
            .border(
                if (running || starting) 2.dp else 1.dp,
                when { running -> accent.a(breath); starting -> accent.a(0.5f); else -> Hairline },
                shape,
            )
            .clickable(onClick = onClick),
    ) {
        Image(
            painterResource(ambienceImage(effect)), null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.35f to Color.Transparent, 1f to Ink.a(0.92f)),
            ),
        )
        if (running || starting) {
            Text(
                if (starting) "Starting…" else "Playing",
                color = Ink, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                    .clip(RoundedCornerShape(100)).background(accent)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                effect.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                effect.blurb, color = TextSecondary, fontSize = 11.sp, lineHeight = 14.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A labelled slider row for the bottom panel: name, slider, value. */
@Composable
private fun PanelSlider(label: String, value: Float, onChange: (Float) -> Unit, onCommit: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(76.dp))
        Box(Modifier.weight(1f)) {
            LabeledSliderRow(value = value, label = "${(value * 100).toInt()}%", onChange = onChange, onCommit = onCommit)
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
    AmbienceEffect.CANDLELIGHT -> Icons.Default.EmojiObjects
    AmbienceEffect.FOREST_DAWN -> Icons.Default.Forest
    AmbienceEffect.TIN_ROOF_RAIN -> Icons.Default.Cottage
    AmbienceEffect.GOLDEN_HOUR -> Icons.Default.WbTwilight
}

/** The picture for each scene. Internal for the same reason as [ambienceIcon]. */
internal fun ambienceImage(e: AmbienceEffect): Int = when (e) {
    AmbienceEffect.FIREWORKS -> R.drawable.ambience_fireworks
    AmbienceEffect.FIREWORKS_2 -> R.drawable.ambience_fireworks_2
    AmbienceEffect.THUNDERSTORM -> R.drawable.ambience_thunderstorm
    AmbienceEffect.THUNDERSTORM_2 -> R.drawable.ambience_thunderstorm_2
    AmbienceEffect.UNDERWATER -> R.drawable.ambience_underwater
    AmbienceEffect.FIREPLACE -> R.drawable.ambience_fireplace
    AmbienceEffect.LIGHT_TRAIN -> R.drawable.ambience_light_train
    AmbienceEffect.AURORA -> R.drawable.ambience_aurora
    AmbienceEffect.COASTAL_RAIN -> R.drawable.ambience_coastal_rain
    AmbienceEffect.CANDLELIGHT -> R.drawable.ambience_candlelight
    AmbienceEffect.FOREST_DAWN -> R.drawable.ambience_forest_dawn
    AmbienceEffect.TIN_ROOF_RAIN -> R.drawable.ambience_tin_roof_rain
    AmbienceEffect.GOLDEN_HOUR -> R.drawable.ambience_golden_hour
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
