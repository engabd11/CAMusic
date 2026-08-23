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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ModeFallback = listOf("auto", "subtle", "medium", "high", "intense", "extreme")
private val EffectFallback = listOf("music", "movies", "fireworks")

/** The intensity wire value that hands the choice to the picker. */
private const val AUTO_INTENSITY = com.engabd.sendpin.hue.INTENSITY_AUTO

/**
 * What the direct engine renders when the stored scheme is one it can't derive
 * (album art, song). Mirrors `FALLBACK_SCHEME` in `SyncoEngine.kt` — kept in step
 * so the direct screen shows the palette the room will actually be lit with.
 */
private val FALLBACK_COLOUR = com.engabd.sendpin.hue.ColorScheme.SUNSET.wire

/**
 * Picks the Light Sync screen for the active transport.
 *
 * The two are entirely separate: the HA path is driven by Home Assistant's
 * entities, the direct path by this phone's own settings and the bridge. The
 * split lives here rather than inside one screen so that [LightSyncViewModel] —
 * which opens an HA WebSocket the moment it is constructed — is never built for a
 * direct-mode user who may have no Home Assistant at all.
 *
 * Nothing renders until the transport is known, so the HA screen can't flash up
 * for a frame in front of a direct-mode user.
 *
 * Collected rather than read once. The transport follows the selected library,
 * so a one-shot read leaves this screen rendering the transport that was current
 * when it entered composition — switch library with the Lights tab already open
 * and it keeps showing the old one, which reads as the switch having failed.
 */
@Composable
fun LightSyncScreen(
    onBack: () -> Unit = {},
    /**
     * The *shared* Light Sync view model, hoisted to the Activity in `App.kt`.
     *
     * Passed in rather than resolved with `viewModel()` here, and that is the whole
     * fix for the tab flashing on every visit. Inside a NavHost destination the
     * default owner is the back-stack entry, and the tab bar navigates with
     * `popUpTo(saveState = true)` — which keeps the entry's saved state and clears
     * its ViewModelStore. So leaving the tab ran `onCleared`, dropping the Home
     * Assistant socket, and coming back built a new model that reconnected and
     * re-discovered from nothing: an empty list, a moment of "couldn't reach Home
     * Assistant" while the socket came up, and then the areas appearing. Owned by
     * the Activity, the connection and the areas simply outlive the visit.
     */
    viewModel: LightSyncViewModel = viewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember(context) {
        com.engabd.sendpin.data.AppSettings(context.applicationContext)
    }
    val lsMode by settings.lightSyncMode.collectAsState(initial = null)
    when (lsMode) {
        null -> Box(Modifier.fillMaxSize().background(Ink))
        "direct" -> DirectLightSyncScreen(onBack)
        else -> HaLightSyncScreen(onBack, viewModel)
    }
}

@Composable
private fun HaLightSyncScreen(onBack: () -> Unit, viewModel: LightSyncViewModel) {
    // The 5 s Home Assistant poll runs only while this screen is on screen. The
    // model now outlives the visit, which is the point — but a poll that outlived it
    // too would be a WebSocket round trip every five seconds for a tab nobody is
    // looking at.
    DisposableEffect(viewModel) {
        viewModel.setScreenVisible(true)
        onDispose { viewModel.setScreenVisible(false) }
    }
    val accent = LocalAccent.current
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val areas by viewModel.areas.collectAsStateWithLifecycle()
    val area by viewModel.selectedArea.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val mediaPlayers by viewModel.mediaPlayers.collectAsStateWithLifecycle()
    val prefillUrl by viewModel.haUrl.collectAsStateWithLifecycle()
    val prefillToken by viewModel.haToken.collectAsStateWithLifecycle()

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
                Spacer(Modifier.height(22.dp))
                SectionLabel("Follow player")
                Spacer(Modifier.height(10.dp))
                PlayerRow(
                    selected = a.mediaPlayer,
                    players = mediaPlayers,
                    onSelect = viewModel::setFollowPlayer,
                )

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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "Auto spreads these across the song's own range.",
                            color = TextFaint, style = MaterialTheme.typography.bodySmall,
                        )
                        InfoChip(
                            "Auto levels",
                            "The quiet parts of a song sit on the lowest level you have " +
                                "allowed, and its biggest moments reach the highest. It is " +
                                "measured against that song's own dynamic range rather than an " +
                                "absolute loudness, so a quiet acoustic record still gets a " +
                                "full show instead of sitting at the bottom of the range all " +
                                "evening.\n\nTip: pick two or three neighbouring levels rather " +
                                "than all of them. Allowing the whole range means every song " +
                                "uses the whole range, which flattens the difference between " +
                                "them.",
                            Modifier.heightIn(0.dp),
                        )
                    }
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
                // Live value while dragging, stored value otherwise — the same shape
                // the direct path uses below. Without it the trailing "%" lagged the
                // thumb by a debounce plus a WebSocket round-trip, so the number read
                // as stuck while the finger moved.
                var haBrightnessDrag by remember { mutableStateOf<Int?>(null) }
                val haBrightness = haBrightnessDrag ?: a.brightnessPct
                val bSlider = ((haBrightness - 5) / 95f).coerceIn(0f, 1f)
                LabeledSlider(
                    icon = Icons.Default.BrightnessHigh,
                    value = bSlider,
                    onChange = {
                        val pct = (5 + it * 95).roundToInt()
                        haBrightnessDrag = pct
                        // Still previewed live: the view model debounces this to 200 ms
                        // and holds the optimistic value for three seconds, so the room
                        // follows the finger without the poll fighting it.
                        viewModel.setBrightness(pct)
                    },
                    onCommit = {
                        val pct = (5 + it * 95).roundToInt()
                        haBrightnessDrag = null
                        viewModel.setBrightness(pct)
                    },
                    trailing = "$haBrightness%",
                )

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
                        "Positive delays the lights, negative pushes them ahead of the " +
                            "speaker. Which way you need depends on whether your speaker " +
                            "buffers more audio than the bridge does, and there is no way to " +
                            "know that without trying it.\n\nTip: play something with a hard, " +
                            "obvious kick and sit where you normally listen. Move in 20 ms " +
                            "steps until the flash and the thump stop arriving separately. Most " +
                            "setups land somewhere between 0 and 150 ms.",
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
                    // The Home Assistant path's list, deliberately *not* the direct
                    // path's. `LightSyncViewModel` posts this map verbatim to
                    // syncoV2's `hue_music_sync.set_options` service, and that
                    // integration has no `cohesion` option to receive — so the two
                    // lists must stay separate.
                    var haDraft by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
                    LightSyncRepository.TUNABLE_DEFS.forEach { (key, label) ->
                        val factor = haDraft[key] ?: a.tunables[key] ?: 1f
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                            Text(label, color = TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                            HSlider(
                                value = (factor / 2f).coerceIn(0f, 1f),
                                onChange = {
                                    haDraft = haDraft + (key to (it * 2f))
                                    viewModel.setTunable(key, it * 2f)
                                },
                                onCommit = {
                                    haDraft = haDraft - key
                                    viewModel.setTunable(key, it * 2f)
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Text("${(factor * 100).roundToInt()}%", color = TextMuted, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.widthIn(min = 40.dp))
                        }
                        // The same explanations the direct path shows. They describe the
                        // effect rather than the transport, and the six keys here are a
                        // subset of the seven there, so one map serves both.
                        com.engabd.sendpin.hue.SyncoEngine.TUNABLE_BLURBS[key]?.let { blurb ->
                            Text(
                                blurb,
                                color = TextFaint,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(start = 107.dp, bottom = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Light Sync driven straight from this phone to the Hue Bridge — no Home Assistant.
 *
 * A separate screen from the HA one rather than a branch inside it, because it
 * shares none of its state: there is no HA connection to gate on, no HA areas to
 * discover, and one speaker (this phone) instead of a choice of them. Every
 * control writes [com.engabd.sendpin.data.AppSettings]; [DirectLightSync] watches
 * those and applies them to the running engine, so nothing here needs a handle on
 * the engine itself.
 *
 * Auto intensity is deliberately absent: the picker is not ported yet, and an
 * "Auto" pill that silently resolved to High would be worse than no pill.
 */
@Composable
private fun DirectLightSyncScreen(onBack: () -> Unit) {
    val accent = LocalAccent.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as com.engabd.sendpin.SendpinApp
    val settings = remember { com.engabd.sendpin.data.AppSettings(context.applicationContext) }
    val direct = app.directLightSync
    val scope = rememberCoroutineScope()

    val live by direct.active.collectAsStateWithLifecycle()
    val syncError by direct.error.collectAsStateWithLifecycle()
    // What the show is *actually* doing, rather than whether the bridge session is
    // open. Those two used to be the same sentence, which is how a queue playing on a
    // remote speaker — where this phone decodes nothing at all — reported "Reacting to
    // the beat" over a room full of idle lamps.
    val framesFresh by direct.framesFresh.collectAsStateWithLifecycle()
    val scanState by app.scanFrameSource.state.collectAsStateWithLifecycle()
    val feed by remember { derivedStateOf { app.activeLightSyncSource.value.feed } }
    val maNow by app.maNowPlaying.now.collectAsStateWithLifecycle()
    val remoteSpeaker = maNow?.takeIf { !it.isSelf }?.playerName
    val enabled by settings.lightSyncEnabled.collectAsState(initial = false)
    val intensity by settings.lightSyncIntensity.collectAsState(initial = "high")
    val effect by settings.lightSyncEffect.collectAsState(initial = "music")
    val autoLevels by settings.lightSyncAutoLevels.collectAsState(initial = listOf("subtle", "medium", "high"))
    val colour by settings.lightSyncColor.collectAsState(initial = "album_art_v2")
    val brightnessPct by settings.lightSyncBrightness.collectAsState(initial = 100)
    val advanced by settings.lightSyncAdvanced.collectAsState(initial = false)
    val spatial by settings.lightSyncSpatial.collectAsState(initial = false)
    val musicDna by settings.musicDnaEnabled.collectAsState(initial = false)
    val emotionalArc by settings.emotionalArcEnabled.collectAsState(initial = false)
    val phantomStage by settings.phantomStageEnabled.collectAsState(initial = false)
    val phoneConductor by settings.phoneConductorEnabled.collectAsState(initial = false)
    val tunables by settings.lightSyncTunables.collectAsState(initial = emptyMap())
    val bridgeIp by settings.hueBridgeIp.collectAsState(initial = "")
    val configId by settings.hueEntertainmentConfigId.collectAsState(initial = "")
    val speakerOffsetMs by settings.lightSyncSpeakerOffsetMs.collectAsState(initial = 0)
    val captureEnabled by settings.captureOtherApps.collectAsState(initial = false)
    val captureState by com.engabd.sendpin.capture.PlaybackCapture.state.collectAsStateWithLifecycle()

    // Entertainment areas, read from the process-scoped [DirectLightSync] rather than
    // fetched here. This screen is a NavHost destination, so screen-local state is
    // dropped every time the tab is left — the list emptied, a spinner appeared and
    // the bridge was queried again on every single visit, which read as the areas
    // being rediscovered for no reason. They are loaded at startup and reloaded when
    // the bridge settings change, which is when they can actually differ.
    val configs by direct.entertainmentConfigs.collectAsStateWithLifecycle()
    val loadingConfigs by direct.configsLoading.collectAsStateWithLifecycle()
    val configError by direct.configsError.collectAsStateWithLifecycle()

    val showStatus = com.engabd.sendpin.hue.ShowStatusRules.statusFor(
        enabled = enabled,
        sessionOpen = live,
        feed = feed,
        framesFresh = framesFresh,
        scanState = scanState,
        captureBlocked = captureState == com.engabd.sendpin.capture.PlaybackCapture.State.BLOCKED,
    )

    Box(Modifier.fillMaxSize().background(Ink)) {
        Bloom(if (live) accent else TextFaint, 440.dp, (-40).dp, (-70).dp, if (live) 0.42f else 0.16f)

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleBtn(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    Text("Light Sync", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text("Straight to the bridge", color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                StatusPill(live)
            }

            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = navBarInset() + 16.dp)) {

                if (bridgeIp.isBlank()) {
                    NoBridgeCard()
                    return@Column
                }

                // Master toggle. Streaming also needs something playing on this
                // phone — either the local player, or Music Assistant playing to this
                // phone through the ExoPlayer engine, which is the one that carries
                // the analysis tap. See SendpinApp's direct-sync gate.
                GlassCard(radius = 18.dp, fill = if (enabled) accent.a(0.10f) else Glass) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(if (enabled) accent.a(0.18f) else Glass).border(1.dp, if (enabled) accent.a(0.4f) else Hairline, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Lightbulb, null, tint = if (enabled) accent else TextMuted, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                            Text("Sync lights to music", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                showStatusText(showStatus, remoteSpeaker),
                                color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                            )
                        }
                        AccentSwitch(enabled) { on -> scope.launch { settings.setLightSyncEnabled(on) } }
                    }
                }

                syncError?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = ErrorRed, fontSize = 12.sp)
                }

                // Required by the Hue Entertainment API best practices: an app
                // producing fast-changing light effects has to warn about them.
                // Shown here rather than once behind a dismiss, because the
                // intensity rungs are right below it and two of them relax or
                // bypass the WCAG flash limiter.
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.WarningAmber, null,
                        tint = TextFaint, modifier = Modifier.size(15.dp).padding(top = 1.dp),
                    )
                    Text(
                        "Light Sync produces fast-changing light effects. These may trigger " +
                            "seizures in people with photosensitive epilepsy, including those " +
                            "with no history of it. Intense and Extreme flash hardest.",
                        color = TextFaint, style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("Entertainment area")
                Spacer(Modifier.height(10.dp))
                when {
                    loadingConfigs -> Text("Loading areas from the bridge…", color = TextMuted, fontSize = 13.sp)
                    configs.isNotEmpty() -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        configs.forEach { cfg ->
                            AreaChip(
                                name = cfg.name,
                                selected = cfg.id == configId,
                                active = cfg.isStreaming,
                                accent = accent,
                            ) { scope.launch { settings.setHueConfigId(cfg.id) } }
                        }
                    }
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            configError ?: "No areas found. Create one in the Hue app.",
                            color = if (configError != null) ErrorRed else TextFaint, fontSize = 13.sp,
                        )
                        // The list no longer reloads on every visit, so the one case
                        // that used to be fixed by leaving the tab and coming back —
                        // the bridge was briefly unreachable — needs a way to ask.
                        Text(
                            "Ask the bridge again",
                            color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(100))
                                .clickable { direct.refreshEntertainmentConfigs() }
                                .padding(vertical = 4.dp),
                        )
                    }
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("Intensity")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Auto is not a rung — it is a choice between rungs, resolved
                    // per frame from the music's character. The rest come straight
                    // off the engine's enum, so the pills can never offer a rung
                    // the engine doesn't have.
                    Pill("Auto", intensity == AUTO_INTENSITY) {
                        scope.launch { settings.setLightSyncIntensity(AUTO_INTENSITY) }
                    }
                    com.engabd.sendpin.hue.SyncMode.entries.forEach { m ->
                        Pill(m.wire.label(), m.wire == intensity) {
                            scope.launch { settings.setLightSyncIntensity(m.wire) }
                        }
                    }
                }
                LightSyncRepository.MODE_BLURBS[intensity]?.let {
                    Spacer(Modifier.height(9.dp))
                    Text(it, color = TextFaint, style = MaterialTheme.typography.bodySmall)
                }

                if (intensity == AUTO_INTENSITY) {
                    Spacer(Modifier.height(9.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "Follows the music's character, not just its volume.",
                            color = TextFaint, style = MaterialTheme.typography.bodySmall,
                        )
                        InfoChip(
                            "Auto intensity",
                            "A chill track tops out low however loud its chorus gets. Only " +
                                "a genuinely heavy one reaches the top of what you allow below, " +
                                "so the lights tell you something about the music rather than " +
                                "tracking the volume knob.\n\nTip: if everything feels too " +
                                "tame, raise the ceiling below instead of turning Auto off. " +
                                "Auto still picks the level, it just has further to go.",
                            Modifier.heightIn(0.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Auto may use", color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.engabd.sendpin.hue.SyncMode.entries.forEach { m ->
                            val on = m.wire in autoLevels
                            Pill(m.wire.label(), on) {
                                // Never let the selection empty out — Auto with
                                // nothing to choose from has no answer to give.
                                val next = if (on) autoLevels - m.wire else autoLevels + m.wire
                                if (next.isNotEmpty()) {
                                    scope.launch { settings.setLightSyncAutoLevels(next) }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("Effect")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Off the engine's own enum, so the tiles can only offer what
                    // it renders. There is no Movies here: the direct path drives
                    // lights from this phone's music, with no video to accompany.
                    com.engabd.sendpin.hue.SyncEffect.entries.forEach { e ->
                        EffectTile(e.wire, effectIcon(e.wire), e.wire == effect) {
                            scope.launch { settings.setLightSyncEffect(e.wire) }
                        }
                    }
                }
                LightSyncRepository.EFFECT_BLURBS[effect]?.let {
                    Spacer(Modifier.height(9.dp))
                    Text(it, color = TextFaint, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("Colour")
                Spacer(Modifier.height(10.dp))
                // Every dynamic source works on the direct path now.
                ColourPicker(selected = colour, showDynamic = true, songFromBeats = true) { scheme ->
                    scope.launch { settings.setLightSyncColor(scheme) }
                }

                // Only while the scan-driven show is the one running. It has no
                // equivalent of the live path's measured [AudioLead] — there is no sink
                // here to measure — and the latency from Music Assistant out to a cast
                // group or a networked amp is device-specific and never reported. There
                // is no way to work it out; there is only asking.
                if (feed == com.engabd.sendpin.hue.LightSyncFeed.SCAN_REMOTE) {
                    Spacer(Modifier.height(22.dp))
                    SectionLabel("Speaker offset")
                    Spacer(Modifier.height(10.dp))
                    var offsetDrag by remember { mutableStateOf<Int?>(null) }
                    val shownOffset = offsetDrag ?: speakerOffsetMs
                    LabeledSlider(
                        icon = Icons.Default.Speaker,
                        value = ((shownOffset + OFFSET_RANGE_MS) / (2f * OFFSET_RANGE_MS)).coerceIn(0f, 1f),
                        onChange = { offsetDrag = offsetFromSlider(it) },
                        onCommit = {
                            val ms = offsetFromSlider(it)
                            offsetDrag = null
                            scope.launch { settings.setLightSyncSpeakerOffsetMs(ms) }
                        },
                        trailing = "$shownOffset ms",
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "Nudge until the beat lands with the sound in the room.",
                            color = TextFaint, style = MaterialTheme.typography.bodySmall,
                        )
                        InfoChip(
                            "Speaker offset",
                            "Positive delays the lights; negative pushes them ahead of the " +
                                "speaker. Which way you need depends on whether the speaker " +
                                "buffers more than the bridge does.",
                            Modifier.heightIn(0.dp),
                        )
                    }
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("Other apps")
                Spacer(Modifier.height(10.dp))
                CaptureCard(
                    enabled = captureEnabled,
                    state = captureState,
                    accent = accent,
                    onEnable = { on ->
                        scope.launch { settings.setCaptureOtherApps(on) }
                        if (on) com.engabd.sendpin.capture.PlaybackCapture.requestStart(context)
                        else com.engabd.sendpin.capture.PlaybackCapture.stop(context)
                    },
                    onRetry = { com.engabd.sendpin.capture.PlaybackCapture.requestStart(context) },
                )

                Spacer(Modifier.height(22.dp))
                SectionLabel("Brightness ceiling")
                Spacer(Modifier.height(10.dp))
                // Live value while dragging, stored value otherwise. Same reasoning as
                // the tunables below: a DataStore write per pointer move recomposed the
                // screen on every frame, which is what made these sliders judder.
                var brightnessDrag by remember { mutableStateOf<Int?>(null) }
                val shownBrightness = brightnessDrag ?: brightnessPct
                val bSlider = ((shownBrightness - 5) / 95f).coerceIn(0f, 1f)
                LabeledSlider(
                    icon = Icons.Default.BrightnessHigh,
                    value = bSlider,
                    onChange = {
                        val pct = (5 + it * 95).roundToInt()
                        brightnessDrag = pct
                        direct.previewBrightness(pct)
                    },
                    onCommit = {
                        val pct = (5 + it * 95).roundToInt()
                        brightnessDrag = null
                        scope.launch { settings.setLightSyncBrightness(pct) }
                    },
                    trailing = "$shownBrightness%",
                )

                // Advanced live tunables — same six factors as the Home Assistant path.
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Advanced")
                        Spacer(Modifier.height(2.dp))
                        Text("Fine-tune the reaction", color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    }
                    AccentSwitch(advanced) { on -> scope.launch { settings.setLightSyncAdvanced(on) } }
                }
                if (advanced) {
                    Spacer(Modifier.height(12.dp))
                    // What the finger is currently doing, overlaid on what is stored.
                    // Held here rather than written through on every frame: each write
                    // re-serialised the whole map, re-emitted the settings Flow, and
                    // came back round through DirectLightSync.observeSettings to set
                    // the identical values again — sixty times a second, per slider.
                    // The lights still track the finger, via previewTunables.
                    var draft by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
                    val shown = tunables + draft

                    // The direct path's own list, which carries `cohesion`. The HA
                    // screen keeps `LightSyncRepository.TUNABLE_DEFS`, because that map
                    // is posted verbatim to syncoV2's `set_options` service and the
                    // integration has no such option to receive.
                    com.engabd.sendpin.hue.SyncoEngine.TUNABLE_DEFS.forEach { (key, label) ->
                        val factor = shown[key] ?: 1f
                        val isDefault = kotlin.math.abs(factor - 1f) < 0.005f
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                        ) {
                            Text(label, color = TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                            HSlider(
                                value = (factor / 2f).coerceIn(0f, 1f),
                                onChange = {
                                    val next = draft + (key to (it * 2f))
                                    draft = next
                                    direct.previewTunables(tunables + next)
                                },
                                onCommit = {
                                    val committed = tunables + (key to (it * 2f))
                                    draft = draft - key
                                    scope.launch { settings.setLightSyncTunables(committed) }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${(factor * 100).roundToInt()}%",
                                color = TextMuted, fontFamily = MonoFont, fontWeight = FontWeight.Bold,
                                fontSize = 11.sp, modifier = Modifier.widthIn(min = 40.dp),
                            )
                            // 100% is the neutral multiplier — `withTunables` treats a
                            // missing key as 1f — so resetting is removing the entry,
                            // not storing a value. Greyed out when already there, so the
                            // row also reads as "this one has been changed".
                            ResetStep(enabled = !isDefault, description = "Reset $label") {
                                draft = draft - key
                                val next = tunables - key
                                direct.previewTunables(next)
                                scope.launch { settings.setLightSyncTunables(next) }
                            }
                        }
                        // What this one actually does, and what each direction costs.
                        // Seven unlabelled percentage sliders is a panel you can only
                        // learn by moving one and watching the room.
                        com.engabd.sendpin.hue.SyncoEngine.TUNABLE_BLURBS[key]?.let { blurb ->
                            Text(
                                blurb,
                                color = TextFaint,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(start = 107.dp, bottom = 10.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    val anyChanged = com.engabd.sendpin.hue.SyncoEngine.TUNABLE_DEFS.any { (k, _) ->
                        kotlin.math.abs((shown[k] ?: 1f) - 1f) >= 0.005f
                    }
                    Text(
                        "Reset all",
                        color = if (anyChanged) accent else TextFaint,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = anyChanged) {
                                draft = emptyMap()
                                direct.previewTunables(emptyMap())
                                scope.launch { settings.setLightSyncTunables(emptyMap()) }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )

                    // Room gestures. Under Advanced rather than beside the colour
                    // picker because it is the one control here that can do nothing
                    // at all: a track with no stereo movement and no swell in it
                    // looks exactly the same either way, and that is the correct
                    // behaviour rather than a fault.
                    Spacer(Modifier.height(20.dp))
                    FeatureRow(
                        title = "Room gestures",
                        gist = "Let a sweep or a swell travel across the room.",
                        info = "When a sound sweeps across the stereo field, or a swell rises with " +
                            "no beat under it, the light travels with it: along a line of " +
                            "lamps, or around a room with lamps in the corners.\n\nRooms with " +
                            "only a couple of lamps get a soft brightness swell instead, since " +
                            "there is nowhere for the light to travel to.\n\nMost songs have " +
                            "neither a sweep nor a swell in them and stay exactly as they are. " +
                            "That is the correct behaviour rather than a fault.\n\nTip: it " +
                            "shows itself best on ambient, film scores and anything with a long " +
                            "build. On a four-to-the-floor dance track you will barely notice " +
                            "it.",
                        checked = spatial,
                    ) { on -> scope.launch { settings.setLightSyncSpatial(on) } }
                }

                // Four additive light-show layers — see `docs/creative-light-shows.md`.
                // Outside the Advanced gate, deliberately: these are the
                // headline features this screen exists to show off, not
                // obscure tunables someone has to go looking for.
                Spacer(Modifier.height(22.dp))
                SectionLabel("Light show layers")
                Spacer(Modifier.height(2.dp))
                Text(
                    "Extra, off by default. Each one layers on top of the show above, never a replacement for it.",
                    color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                )
                Spacer(Modifier.height(12.dp))

                FeatureRow(
                    title = "Music DNA",
                    gist = "A visual fingerprint unique to each track.",
                    info = "Tempo, key and structure shape a slow colour floor underneath the " +
                        "reactive show, so two songs never light the room the same way. The " +
                        "same track lights it the same way every time, which is the point: the " +
                        "room starts to feel familiar before you have placed the song.\n\nIt " +
                        "locks in a few seconds into a track, once that track has been " +
                        "analysed.\n\nTip: run a sweep under Settings, Light Sync, Track " +
                        "analysis first. Until a track has been read its fingerprint is being " +
                        "guessed at from what has played so far.",
                    checked = musicDna,
                ) { on -> scope.launch { settings.setMusicDnaEnabled(on) } }

                Spacer(Modifier.height(16.dp))
                FeatureRow(
                    title = "Emotional arc",
                    gist = "Colour temperature follows the song's own shape.",
                    info = "Cool through the calm parts, warming into a build, hot on the drop, " +
                        "cold on a breakdown. The room reads the song's shape rather than just " +
                        "its level, so a loud passage that is not going anywhere stays " +
                        "cool.\n\nTip: it layers under everything else rather than replacing " +
                        "it, so it is worth leaving on alongside Music DNA and Phantom stage. " +
                        "If the room ends up too warm overall, that is the colour setting on " +
                        "the Lights tab and not this.",
                    checked = emotionalArc,
                ) { on -> scope.launch { settings.setEmotionalArcEnabled(on) } }

                Spacer(Modifier.height(16.dp))
                FeatureRow(
                    title = "Phantom stage",
                    gist = "Each part of the mix gets its own lamp.",
                    info = "Bass, drums, guitar, vocals and synths each take a fixed spot in the " +
                        "room for the session, glowing softly and flashing when that part of " +
                        "the mix hits.\n\nIt is a rough approximation from frequency, not real " +
                        "instrument separation. A bass guitar and a kick drum share a lamp, and " +
                        "a synth playing in the vocal range lands on the vocal one.\n\nTip: it " +
                        "needs four or more lamps, spread out, to read as a stage. In a room " +
                        "with two, everything piles onto both and it looks like a brighter " +
                        "version of the normal show.",
                    checked = phantomStage,
                ) { on -> scope.launch { settings.setPhantomStageEnabled(on) } }

                Spacer(Modifier.height(16.dp))
                FeatureRow(
                    title = "Phone conductor",
                    gist = "Conduct the room by moving the phone.",
                    info = "Tilt to shift colour across the room, flick for a flash, or turn the " +
                        "phone slowly to spin colour around the space.\n\nIt stands down after " +
                        "a few seconds of no motion, so the phone can sit on a table without " +
                        "conducting anything.\n\nTip: it overrides the show while you are " +
                        "moving, so it is a party trick rather than something to leave running. " +
                        "Hand someone the phone and let them find it.",
                    checked = phoneConductor,
                ) { on -> scope.launch { settings.setPhoneConductorEnabled(on) } }

                Spacer(Modifier.height(22.dp))
                // No "…has moved to…" line here any more. The app has never been
                // published, so nobody reading this has a previous version to be
                // redirected from — it only ever told a first-time user that
                // something they have not seen is somewhere they were not looking.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Direct mode needs no timing offset.",
                        color = TextFaint, style = MaterialTheme.typography.bodySmall,
                    )
                    InfoChip(
                        "Direct mode timing",
                        "Direct mode syncs this phone's own playback, so it can measure how " +
                            "far the audio tap runs ahead of the speaker and compensate " +
                            "exactly. There is nothing left for you to dial in, which is why " +
                            "the offset slider is not shown on this route.\n\nTip: if the " +
                            "lights still feel late here, it is the bridge or the network " +
                            "rather than the timing. Check that nothing else is driving the " +
                            "same entertainment area.",
                        Modifier.heightIn(0.dp),
                    )
                }
            }
        }
    }
}

/**
 * One light-show feature: what it is called, one line on what it does, the switch,
 * and the rest of the explanation behind a chip.
 *
 * These five were written out by hand five times — a weighted Column of a 13sp title
 * over a three-or-four-line 11sp description, beside an [AccentSwitch] — which is a
 * `ToggleRow` in everything but name. The descriptions are the good part of this
 * screen and none of them was cut; they moved somewhere with room to be read.
 */
@Composable
private fun FeatureRow(
    title: String,
    gist: String,
    info: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    title, color = TextSecondary, fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                InfoChip(title, info, Modifier.heightIn(0.dp))
            }
            Text(gist, color = TextFaint, fontSize = 11.sp, lineHeight = 15.sp)
        }
        AccentSwitch(checked, onChange)
    }
}

@Composable
private fun NoBridgeCard() {
    GlassCard(radius = 18.dp) {
        Column(Modifier.padding(18.dp)) {
            Text("No bridge paired", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Pair one in Settings → Servers → Light Sync, then pick an entertainment " +
                    "area here.",
                color = TextMuted, fontSize = 13.sp,
            )
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
/**
 * [showDynamic] is false on the direct path: album-art and song colours are
 * derived by the Home Assistant integration, and the direct engine has no
 * equivalent yet — it renders its fallback palette for all three. Offering them
 * would be three tiles that all quietly do the same thing.
 */
@Composable
private fun ColourPicker(
    selected: String?,
    showDynamic: Boolean = true,
    /**
     * How the Song source derives its colours on this path.
     *
     * The two are genuinely different things behind the same name. Home
     * Assistant runs syncoV2, which reads the track's chroma and colours the
     * room from its actual key. The direct path has no chroma, so Song there is
     * fresh random colours turning over on the beat. Describing both as
     * "harmony" would be a lie on one of them.
     */
    songFromBeats: Boolean = false,
    onSelect: (String) -> Unit,
) {
    val accent = LocalAccent.current
    val palette = LocalPalette.current

    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // The dynamic sources preview with the *current* album's real colours, so
        // the choice shows what it will actually do to the room.
        if (showDynamic) SwatchTile(
            colours = listOf(accent, palette.swatch(1), palette.swatch(2)),
            label = "Album art",
            caption = "Weighted",
            icon = Icons.Default.AutoAwesome,
            selected = selected == LightSyncRepository.ALBUM_COLOUR,
        ) { onSelect(LightSyncRepository.ALBUM_COLOUR) }

        if (showDynamic) SwatchTile(
            colours = listOf(palette.swatch(1), accent),
            label = "Album art",
            caption = "Even",
            icon = Icons.Default.Image,
            selected = selected == LightSyncRepository.ALBUM_COLOUR_V1,
        ) { onSelect(LightSyncRepository.ALBUM_COLOUR_V1) }

        if (showDynamic) SwatchTile(
            colours = listOf(Color(0xFF33FFC2), Color(0xFF7D40FF), Color(0xFFFF59D1)),
            label = "Song",
            caption = if (songFromBeats) "On the beat" else "Harmony",
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
        LightSyncRepository.SONG_COLOUR ->
            if (songFromBeats) "Fresh colours on every beat, turning the room over on the big hits."
            else "Colours derived from the song's own key and harmony."
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
        Text(
            name,
            color = tint,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

/**
 * [onCommit] is the release. Where it is given, [onChange] is the live value under the
 * finger and should stay cheap — anything that writes to storage belongs on the commit,
 * or the slider stutters against its own recompositions.
 */
@Composable
private fun LabeledSlider(
    icon: ImageVector,
    value: Float,
    onChange: (Float) -> Unit,
    trailing: String,
    onCommit: ((Float) -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Icon(icon, null, tint = TextMuted, modifier = Modifier.size(16.dp))
        HSlider(value, onChange, modifier = Modifier.weight(1f), onCommit = onCommit)
        Text(trailing, color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.widthIn(min = 44.dp))
    }
}

/** The small ⟲ beside a tunable, returning it to its default. Same shape as [OffsetStep]. */
@Composable
private fun ResetStep(enabled: Boolean, description: String, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Refresh,
            contentDescription = description,
            tint = if (enabled) accent else TextFaint,
            modifier = Modifier.size(15.dp),
        )
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

/**
 * One line describing what the show is doing.
 *
 * `SCAN_DRIVEN` is the important one, and its wording is deliberate: "following the
 * beat grid", not "reacting to the beat". The two are genuinely different things —
 * one is a schedule read off an offline analysis, the other is a response to audio
 * arriving right now — and claiming the second while doing the first is the bug this
 * whole path exists to fix.
 */
private fun showStatusText(status: com.engabd.sendpin.hue.ShowStatus, speaker: String?): String = when (status) {
    com.engabd.sendpin.hue.ShowStatus.OFF -> "Lights are steady"
    // "on this phone", not "this phone": Music Assistant playing to this phone
    // counts, and the old wording read as though it never could.
    com.engabd.sendpin.hue.ShowStatus.WAITING -> "Waiting for music on this phone"
    com.engabd.sendpin.hue.ShowStatus.LIVE_PCM -> "Reacting to the beat"
    com.engabd.sendpin.hue.ShowStatus.SCAN_DRIVEN ->
        "Following the beat grid, playing on ${speaker ?: "another speaker"}, so the show is " +
            "scheduled from this track's analysis"
    com.engabd.sendpin.hue.ShowStatus.SCAN_PENDING -> "Analysing this track…"
    com.engabd.sendpin.hue.ShowStatus.NO_SCAN ->
        "Playing on ${speaker ?: "another speaker"}, which this phone can't hear, and there is " +
            "no analysis for this track"
    com.engabd.sendpin.hue.ShowStatus.CAPTURE -> "Reacting to another app"
    com.engabd.sendpin.hue.ShowStatus.CAPTURE_BLOCKED -> "That app does not allow its audio to be captured"
}

/** The speaker-offset slider's half-range, in milliseconds. */
private const val OFFSET_RANGE_MS = 2000

/** Snapped to 10 ms: finer than that is below what anyone can hear against a beat. */
private fun offsetFromSlider(fraction: Float): Int {
    val raw = (fraction * 2f - 1f) * OFFSET_RANGE_MS
    return (Math.round(raw / 10f) * 10).coerceIn(-OFFSET_RANGE_MS, OFFSET_RANGE_MS)
}


/**
 * Light Sync listening to whatever else is playing on the phone.
 *
 * Written as a set-up action rather than a plain switch because it is not one: it
 * needs a runtime microphone grant (which is what `AudioPlaybackCapture` is gated on,
 * even though no microphone is opened), a system consent dialog, and a foreground
 * service — and on Android 14+ the consent dialog reappears every single time capture
 * starts, because the projection token is single-use. Presenting that as a toggle
 * would promise a thing the platform does not offer.
 *
 * The blocked message is the part that earns the feature its keep. An app that opts
 * out of capture is delivered as bit-exact silence with no error of any kind, so
 * without saying so plainly the only observation available to the user is "the lights
 * do nothing", which is indistinguishable from a bug here.
 */
@Composable
private fun CaptureCard(
    enabled: Boolean,
    state: com.engabd.sendpin.capture.PlaybackCapture.State,
    accent: androidx.compose.ui.graphics.Color,
    onEnable: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        if (ok) onEnable(true)
    }

    GlassCard(radius = 18.dp, fill = if (enabled) accent.a(0.10f) else Glass) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    Text(
                        "React to other apps",
                        color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    )
                    Text(
                        captureBlurb(enabled, state),
                        color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    )
                }
                AccentSwitch(enabled) { on ->
                    if (on && !granted) permission.launch(android.Manifest.permission.RECORD_AUDIO)
                    else onEnable(on)
                }
            }
            if (state == com.engabd.sendpin.capture.PlaybackCapture.State.BLOCKED) {
                Text(
                    "This app does not allow its audio to be captured. YouTube, YouTube Music " +
                        "and most DRM-protected apps opt out, and nothing here can change that. " +
                        "Spotify, podcast apps and local players usually work.",
                    color = WarnAmber, style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state == com.engabd.sendpin.capture.PlaybackCapture.State.STOPPED_BY_SYSTEM ||
                state == com.engabd.sendpin.capture.PlaybackCapture.State.DENIED
            ) {
                Text(
                    "Android asks permission each time capture starts, the grant is single-use " +
                        "and cannot be remembered.",
                    color = TextFaint, style = MaterialTheme.typography.bodySmall,
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, accent.a(0.55f), RoundedCornerShape(12.dp))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text("Start again", color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun captureBlurb(
    enabled: Boolean,
    state: com.engabd.sendpin.capture.PlaybackCapture.State,
): String = when {
    !enabled -> "Follow music from any other app on this phone."
    state == com.engabd.sendpin.capture.PlaybackCapture.State.RUNNING -> "Listening."
    state == com.engabd.sendpin.capture.PlaybackCapture.State.QUIET -> "Listening, nothing playing."
    state == com.engabd.sendpin.capture.PlaybackCapture.State.BLOCKED -> "That app will not allow it."
    state == com.engabd.sendpin.capture.PlaybackCapture.State.STARTING -> "Starting…"
    state == com.engabd.sendpin.capture.PlaybackCapture.State.STOPPED_BY_SYSTEM -> "Capture was stopped."
    state == com.engabd.sendpin.capture.PlaybackCapture.State.DENIED -> "Permission was not granted."
    else -> "Not listening."
}
