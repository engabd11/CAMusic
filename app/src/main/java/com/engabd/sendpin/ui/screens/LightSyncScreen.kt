package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.ha.HaMediaPlayer
import com.engabd.sendpin.ha.LightArea
import com.engabd.sendpin.ha.LightSyncRepository
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.LightSyncViewModel
import kotlin.math.roundToInt

private val ModeFallback = listOf("auto", "subtle", "medium", "high", "intense", "extreme")
private val EffectFallback = listOf("music", "movies", "fireworks")

@Composable
fun LightSyncScreen(onBack: () -> Unit = {}, viewModel: LightSyncViewModel = viewModel()) {
    val accent = LocalAccent.current
    val connected by viewModel.connected.collectAsState()
    val areas by viewModel.areas.collectAsState()
    val area by viewModel.selectedArea.collectAsState()
    val error by viewModel.error.collectAsState()
    val mediaPlayers by viewModel.mediaPlayers.collectAsState()
    val prefillUrl by viewModel.haUrl.collectAsState()
    val prefillToken by viewModel.haToken.collectAsState()

    // Which transport is active — read once. In direct mode the "Follow
    // player" section is hidden (there is only one speaker: this phone).
    val context = androidx.compose.ui.platform.LocalContext.current
    val lsMode by produceState(initialValue = "ha") {
        value = com.engabd.sendpin.data.AppSettings(context.applicationContext).lightSyncMode.first()
    }
    val isDirect = lsMode == "direct"

    val enabled = area?.enabled == true

    Box(Modifier.fillMaxSize().background(Ink)) {
        Bloom(if (enabled) accent else TextFaint, 440.dp, (-40).dp, (-70).dp, if (enabled) 0.42f else 0.16f)

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            // Header.
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleBtn(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    Text("Light Sync", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text("Hue follows the music", color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                StatusPill(connected && enabled)
            }

            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = navBarInset() + 16.dp)) {

                if (!connected) {
                    ConnectCard(
                        prefillUrl = prefillUrl,
                        prefillToken = prefillToken,
                        error = error,
                        onConnect = viewModel::connect,
                    )
                    return@Column
                }

                if (areas.isEmpty()) {
                    EmptyAreas(error) { viewModel.refresh() }
                    return@Column
                }

                val a = area ?: return@Column

                // Master toggle.
                GlassCard(radius = 18.dp, fill = if (enabled) accent.a(0.10f) else Glass) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(if (enabled) accent.a(0.18f) else Glass).border(1.dp, if (enabled) accent.a(0.4f) else Hairline, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Lightbulb, null, tint = if (enabled) accent else TextMuted, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                            Text("Sync lights to music", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(if (enabled) "Reacting to the beat" else "Lights are steady", color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                        AccentSwitch(enabled) { viewModel.setEnabled(it) }
                    }
                }

                if (areas.size > 1) {
                    Spacer(Modifier.height(22.dp))
                    SectionLabel("Entertainment zone")
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        areas.forEach { ar ->
                            AreaChip(
                                name = ar.name,
                                selected = ar.id == a.id,
                                active = ar.enabled,
                                accent = accent,
                            ) { viewModel.selectArea(ar.id) }
                        }
                    }
                }

                // Which player this area follows ("Auto" — whatever is playing).
                // Hidden in direct mode: there is only one speaker (this phone),
                // so there is nothing to choose. Instead, the entertainment area
                // selector is shown here — it changes constantly, just like
                // the HA zone selector does.
                if (!isDirect) {
                    Spacer(Modifier.height(22.dp))
                    SectionLabel("Follow player")
                    Spacer(Modifier.height(10.dp))
                    PlayerRow(
                        selected = a.mediaPlayer,
                        players = mediaPlayers,
                        onSelect = viewModel::setFollowPlayer,
                    )
                } else {
                    // Direct mode: entertainment area picker (from the bridge).
                    val context2 = androidx.compose.ui.platform.LocalContext.current
                    val app = context2.applicationContext as com.engabd.sendpin.SendpinApp
                    val bridge = app.directLightSync.bridgeClient
                    var directConfigs by remember { mutableStateOf(listOf<com.engabd.sendpin.hue.EntertainmentConfig>()) }
                    var directConfigId by remember { mutableStateOf("") }
                    var loadingDirectConfigs by remember { mutableStateOf(false) }
                    val directSettings = com.engabd.sendpin.data.AppSettings(context2.applicationContext)
                    val directScope = rememberCoroutineScope()

                    LaunchedEffect(Unit) {
                        directConfigId = directSettings.hueEntertainmentConfigId.first()
                        val ip = directSettings.hueBridgeIp.first()
                        val key = directSettings.hueAppKey.first()
                        if (ip.isNotBlank() && key.isNotBlank()) {
                            loadingDirectConfigs = true
                            try { directConfigs = bridge.getEntertainmentConfigs(ip, key) } catch (_: Exception) {}
                            loadingDirectConfigs = false
                        }
                    }

                    Spacer(Modifier.height(22.dp))
                    SectionLabel("Entertainment area")
                    Spacer(Modifier.height(10.dp))

                    if (loadingDirectConfigs) {
                        Text("Loading areas from the bridge…", color = TextMuted, fontSize = 13.sp)
                    } else if (directConfigs.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            directConfigs.forEach { cfg ->
                                AreaChip(
                                    name = cfg.name,
                                    selected = cfg.id == directConfigId,
                                    active = cfg.isStreaming,
                                    accent = accent,
                                ) {
                                    directConfigId = cfg.id
                                    directScope.launch { directSettings.setHueConfigId(cfg.id) }
                                }
                            }
                        }
                    } else {
                        Text("No areas found. Create one in the Hue app.", color = TextFaint, fontSize = 13.sp)
                    }
                }

                val modeOptions = a.modeOptions.ifEmpty { ModeFallback }
                Spacer(Modifier.height(22.dp))
                SectionLabel("Intensity")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modeOptions.forEach { opt -> Pill(opt.label(), opt == a.mode) { viewModel.setMode(opt) } }
                }
                // What the selected rung actually does — the ladder's names don't say.
                LightSyncRepository.MODE_BLURBS[a.mode]?.let {
                    Spacer(Modifier.height(9.dp))
                    Text(it, color = TextFaint, style = MaterialTheme.typography.bodySmall)
                }

                // Auto rungs — the intensities Auto may pick from (only when mode == auto).
                if (a.mode == "auto") {
                    Spacer(Modifier.height(14.dp))
                    SectionLabel("Auto can use")
                    Spacer(Modifier.height(9.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LightSyncRepository.AUTO_RUNGS.forEach { rung ->
                            ToggleChip(rung.label(), rung in a.autoLevels) { viewModel.toggleAutoLevel(rung) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Auto spreads these across each song's own dynamic range - the quiet parts sit on the lowest, the biggest moments reach the highest.",
                        color = TextFaint, style = MaterialTheme.typography.bodySmall,
                    )
                }

                val effectOptions = a.effectOptions.ifEmpty { EffectFallback }
                Spacer(Modifier.height(22.dp))
                SectionLabel("Effect")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    effectOptions.forEach { opt -> EffectTile(opt, effectIcon(opt), opt == a.effect) { viewModel.setEffect(opt) } }
                }
                LightSyncRepository.EFFECT_BLURBS[a.effect]?.let {
                    Spacer(Modifier.height(9.dp))
                    Text(it, color = TextFaint, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("Colour")
                Spacer(Modifier.height(10.dp))
                ColourPicker(selected = a.colour, onSelect = viewModel::setColour)

                Spacer(Modifier.height(22.dp))
                SectionLabel("Brightness ceiling")
                Spacer(Modifier.height(10.dp))
                val bSlider = ((a.brightnessPct - 5) / 95f).coerceIn(0f, 1f)
                LabeledSlider(Icons.Default.BrightnessHigh, bSlider, { viewModel.setBrightness((5 + it * 95).roundToInt()) }, "${a.brightnessPct}%")

                Spacer(Modifier.height(22.dp))
                SectionLabel("Timing")
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                        Text("Auto timing", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Calibrate the delay per song", color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    }
                    AccentSwitch(a.autoTiming) { viewModel.setAutoTiming(it) }
                }
                if (!a.autoTiming) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Light offset", color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        OffsetStep("−") { viewModel.changeTiming(-5) }
                        Text("${a.timingMs} ms", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.widthIn(min = 56.dp))
                        OffsetStep("+") { viewModel.changeTiming(5) }
                    }
                    Text(
                        "Positive delays the lights; negative pushes them ahead of the audio.",
                        color = TextFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }

                // Advanced live tunables — the card's knob section.
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Advanced")
                        Spacer(Modifier.height(2.dp))
                        Text("Fine-tune the reaction", color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    }
                    AccentSwitch(a.advanced) { viewModel.setAdvanced(it) }
                }
                if (a.advanced) {
                    Spacer(Modifier.height(12.dp))
                    LightSyncRepository.TUNABLE_DEFS.forEach { (key, label) ->
                        val factor = a.tunables[key] ?: 1f
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                            Text(label, color = TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                            HSlider((factor / 2f).coerceIn(0f, 1f), { viewModel.setTunable(key, (it * 2f)) }, modifier = Modifier.weight(1f))
                            Text("${(factor * 100).roundToInt()}%", color = TextMuted, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.widthIn(min = 40.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun String.label(): String = replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun effectIcon(name: String): ImageVector = when (name) {
    "movies" -> Icons.Default.Movie
    "fireworks" -> Icons.Default.Celebration
    else -> Icons.Default.GraphicEq
}

@Composable
private fun ConnectCard(prefillUrl: String, prefillToken: String, error: String?, onConnect: (String, String) -> Unit) {
    val accent = LocalAccent.current
    var url by remember(prefillUrl) { mutableStateOf(prefillUrl) }
    var token by remember(prefillToken) { mutableStateOf(prefillToken) }

    GlassCard(radius = 18.dp) {
        Column(Modifier.padding(18.dp)) {
            Text("Connect Home Assistant", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Light sync is driven by the Hue Synco integration in Home Assistant. Add your HA URL and a long-lived access token (Profile → Security).",
                color = TextMuted, fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            HaField(url, { url = it }, "HA URL", "http://192.168.0.10:8123", accent)
            Spacer(Modifier.height(12.dp))
            HaField(token, { token = it }, "Long-lived access token", "eyJ…", accent)
            error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = ErrorRed, fontSize = 12.sp) }
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (url.isBlank() || token.isBlank()) Glass else accent)
                    .clickable(enabled = url.isNotBlank() && token.isNotBlank()) { onConnect(url, token) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Connect", color = if (url.isBlank() || token.isBlank()) TextMuted else Ink, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun HaField(value: String, onChange: (String) -> Unit, label: String, placeholder: String, accent: Color) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, placeholder = { Text(placeholder) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        colors = accentTextFieldColors(accent),
    )
}

@Composable
private fun EmptyAreas(error: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Lightbulb, null, tint = TextFaint, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(14.dp))
        Text("No sync areas found", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(error ?: "Set up a Hue Synco entertainment area in Home Assistant.", color = TextMuted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.clip(RoundedCornerShape(100)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(100)).clickable(onClick = onRetry).padding(horizontal = 22.dp, vertical = 11.dp)) {
            Text("Retry", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StatusPill(live: Boolean) {
    val accent = LocalAccent.current
    val c = if (live) accent else TextMuted
    Row(
        Modifier.clip(RoundedCornerShape(100)).background(c.a(0.12f)).border(1.dp, c.a(0.35f), RoundedCornerShape(100)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Text(if (live) "Live" else "Off", color = c, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
private fun EffectTile(name: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Column(
        Modifier.width(84.dp).clip(RoundedCornerShape(15.dp))
            .background(if (selected) accent.a(0.14f) else Glass)
            .border(1.dp, if (selected) accent.a(0.5f) else Hairline, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick).padding(vertical = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (selected) accent else TextMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(9.dp))
        Text(name.label(), color = if (selected) accent else TextSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

/**
 * The colour section: the three live sources, then the preset schemes.
 *
 * Schemes are gradients — Rainbow is seven stops, Honolulu four — so each is
 * previewed as a sweep of its own anchor colours with its name under it. A row
 * of sixteen unlabelled single-colour dots said neither what a scheme was called
 * nor what it would look like.
 */
@Composable
private fun ColourPicker(selected: String?, onSelect: (String) -> Unit) {
    val accent = LocalAccent.current
    val palette = LocalPalette.current

    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // The dynamic sources preview with the *current* album's real colours, so
        // the choice shows what it will actually do to the room.
        SwatchTile(
            colours = listOf(accent, palette.swatch(1), palette.swatch(2)),
            label = "Album art",
            caption = "Weighted",
            icon = Icons.Default.AutoAwesome,
            selected = selected == LightSyncRepository.ALBUM_COLOUR,
        ) { onSelect(LightSyncRepository.ALBUM_COLOUR) }

        SwatchTile(
            colours = listOf(palette.swatch(1), accent),
            label = "Album art",
            caption = "Even",
            icon = Icons.Default.Image,
            selected = selected == LightSyncRepository.ALBUM_COLOUR_V1,
        ) { onSelect(LightSyncRepository.ALBUM_COLOUR_V1) }

        SwatchTile(
            colours = listOf(Color(0xFF33FFC2), Color(0xFF7D40FF), Color(0xFFFF59D1)),
            label = "Song",
            caption = "Harmony",
            icon = Icons.Default.MusicNote,
            selected = selected == LightSyncRepository.SONG_COLOUR,
        ) { onSelect(LightSyncRepository.SONG_COLOUR) }

        LightSyncRepository.PALETTES.forEach { scheme ->
            SwatchTile(
                colours = scheme.colours.map { Color(it) },
                label = scheme.label,
                selected = selected == scheme.key,
            ) { onSelect(scheme.key) }
        }
    }

    // Say what the dynamic sources actually derive from — the labels can't.
    val blurb = when (selected) {
        LightSyncRepository.ALBUM_COLOUR -> "Colours from the cover, each held in proportion to its share of the artwork."
        LightSyncRepository.ALBUM_COLOUR_V1 -> "Colours from the cover, cycled evenly."
        LightSyncRepository.SONG_COLOUR -> "Colours derived from the song's own key and harmony."
        else -> null
    }
    blurb?.let {
        Spacer(Modifier.height(9.dp))
        Text(it, color = TextFaint, style = MaterialTheme.typography.bodySmall)
    }
}

/** One colour choice: a gradient sweep of the palette's own colours, plus its name. */
@Composable
private fun SwatchTile(
    colours: List<Color>,
    label: String,
    selected: Boolean,
    caption: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    // A single stop makes no gradient, so a lone colour is doubled up.
    val stops = if (colours.size >= 2) colours else colours + colours
    Column(
        Modifier.width(64.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape)
                .background(Brush.sweepGradient(stops + stops.first()))
                .border(if (selected) 2.5.dp else 1.dp, if (selected) TextPrimary else Hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                // The dynamic sources carry a mark so they read as sources, not presets.
                Box(Modifier.size(22.dp).clip(CircleShape).background(Ink.a(0.55f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(13.dp))
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            label,
            color = if (selected) accent else TextMuted,
            fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (caption != null) {
            Text(caption, color = TextFaint, fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, maxLines = 1)
        }
    }
}

/** An entertainment zone chip with a live/off indicator dot. */
@Composable
private fun AreaChip(name: String, selected: Boolean, active: Boolean, accent: Color, onClick: () -> Unit) {
    val bg = if (selected) accent.a(0.14f) else Glass
    val border = if (selected) accent.a(0.5f) else HairlineSoft
    val tint = if (selected) accent else TextSecondary
    Row(
        Modifier.clip(RoundedCornerShape(100)).background(bg).border(1.dp, border, RoundedCornerShape(100))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier.size(7.dp).clip(CircleShape)
                .background(if (active) accent else TextFaint),
        )
        Text(name, color = tint, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * Which player the area listens to. A house can have a dozen `media_player`
 * entities, so each card says whether it is playing and what — the repository
 * sorts whatever is live to the front — and Auto leads, since it is the answer
 * most of the time.
 */
@Composable
private fun PlayerRow(selected: String, players: List<HaMediaPlayer>, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayerCard(
            name = "Auto",
            detail = "Whatever is playing",
            icon = Icons.Default.AutoAwesome,
            live = players.any { it.isPlaying },
            selected = selected.isBlank(),
        ) { onSelect("") }

        players.forEach { mp ->
            PlayerCard(
                name = mp.name,
                detail = mp.nowPlaying ?: mp.state.label(),
                icon = Icons.Default.Speaker,
                live = mp.isPlaying,
                selected = selected == mp.entityId,
            ) { onSelect(mp.entityId) }
        }
    }
}

@Composable
private fun PlayerCard(
    name: String,
    detail: String,
    icon: ImageVector,
    live: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.widthIn(min = 150.dp, max = 220.dp).clip(shape)
            .background(if (selected) accent.a(0.14f) else Glass)
            .border(1.dp, if (selected) accent.a(0.5f) else HairlineSoft, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(icon, null, tint = if (selected) accent else TextMuted, modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                name,
                color = if (selected) accent else TextSecondary,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail, color = TextFaint, fontFamily = AppFont, fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (live) Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
    }
}

@Composable
private fun LabeledSlider(icon: ImageVector, value: Float, onChange: (Float) -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Icon(icon, null, tint = TextMuted, modifier = Modifier.size(16.dp))
        HSlider(value, onChange, modifier = Modifier.weight(1f))
        Text(label, color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.widthIn(min = 44.dp))
    }
}

@Composable
private fun OffsetStep(label: String, onClick: () -> Unit) {
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(8.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = TextSecondary, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
    }
}

@Composable
private fun AccentSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Ink, checkedTrackColor = accent, checkedBorderColor = accent,
            uncheckedThumbColor = TextMuted, uncheckedTrackColor = Glass, uncheckedBorderColor = Hairline,
        ),
    )
}

@Composable
private fun CircleBtn(icon: ImageVector, cd: String, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(Glass).border(1.dp, Hairline, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, cd, tint = TextSecondary, modifier = Modifier.size(17.dp))
    }
}
