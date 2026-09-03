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
import androidx.compose.foundation.combinedClickable
import com.engabd.sendpin.ui.screens.settings.Note
import com.engabd.sendpin.ui.screens.settings.OledButton
import com.engabd.sendpin.ui.screens.settings.OledField
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
    /** Opens the ambience Effects screen. Only offered on the direct-to-bridge path. */
    onOpenEffects: () -> Unit = {},
    /** Opens the rhythm game. Only offered on the direct-to-bridge path. */
    onOpenRhythmGame: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember(context) {
        com.engabd.sendpin.data.AppSettings(context.applicationContext)
    }
    val lsMode by settings.lightSyncMode.collectAsState(initial = null)
    when (lsMode) {
        // Blank only of *content*: the wash is the same one both branches below
        // wear, so reading the stored mode is not a black frame between two
        // album-tinted pages.
        null -> Box(Modifier.fillMaxSize().background(Ink)) {
            PageBloom(alpha = 0.22f, size = 440.dp, x = (-40).dp)
        }
        // Effects drives the bridge's entertainment stream directly, which is what the
        // direct path already owns. The Home Assistant path talks to HA's own light
        // service and has no per-frame stream to script, so it does not offer them.
        "direct" -> DirectLightSyncScreen(onBack, onOpenEffects, onOpenRhythmGame)
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
        // The album's colour, the same wash the library and the settings pages wear
        // — see [PageBloom]. It used to be the ambient accent, which is a fixed
        // colour on two of its three settings, and flat grey whenever the show was
        // off: the one page in the app that stopped matching the rest of it. The
        // state is still said, in brightness rather than in hue.
        PageBloom(alpha = if (enabled) 0.42f else 0.22f, size = 440.dp, x = (-40).dp)

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
private fun DirectLightSyncScreen(
    onBack: () -> Unit,
    onOpenEffects: () -> Unit = {},
    onOpenRhythmGame: () -> Unit = {},
) {
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
    val maScanState by app.scanFrameSource.state.collectAsStateWithLifecycle()
    val mpdScanState by app.mpdScanFrameSource.state.collectAsStateWithLifecycle()
    // Whichever of the two is actually doing something. The two are exclusive in
    // practice — MPD driving and a remote MA speaker playing can't both be the
    // active local session at once — so this only ever falls back to the MA
    // reading, which is what it always showed before MPD had a scan source too.
    val scanState = if (mpdScanState != com.engabd.sendpin.hue.ScanFeedState.IDLE) mpdScanState else maScanState
    // Collected, not read through `derivedStateOf`. That form reads a StateFlow's
    // `.value`, which is not snapshot state, so the derivation had no dependencies to
    // invalidate on: it computed the feed once on first composition and never again.
    // Open the screen before starting anything and it stayed on LOCAL_PCM for the rest
    // of the visit, so a Music Assistant queue on a remote speaker — the one case the
    // whole SCAN_REMOTE path exists for — was reported through the local player's
    // branch of [ShowStatusRules] and never said what it was actually doing.
    //
    // The whole source rather than a `map` of its `feed`: mapping needs a seed value to
    // collect against, and the only honest seed is the flow's own `.value` — which is
    // the very read this is replacing, and which lint rejects for exactly the reason
    // above. A StateFlow collected whole brings its current value with it. The extra
    // recompositions are nothing: [SendpinApp.activeLightSyncSource] is deduplicated by
    // `stateIn` and only changes on a backend handover.
    val lightSource by app.activeLightSyncSource.collectAsStateWithLifecycle()
    val feed = lightSource.feed
    val maNow by app.maNowPlaying.now.collectAsStateWithLifecycle()
    val remoteSpeaker = maNow?.takeIf { !it.isSelf }?.playerName
    // Same "who is this playing on" line MPD's own Now Playing pill uses (see
    // NowPlayingViewModel.State.playerName) - MPD's configured output name where
    // it said one, else just "MPD", rather than "another speaker".
    val mpdRemoteActive by app.localPlayer.remoteActive.collectAsStateWithLifecycle()
    val mpdOutputName by app.localPlayer.remoteOutputDeviceName.collectAsStateWithLifecycle()
    val speakerLabel = remoteSpeaker ?: (mpdOutputName ?: "MPD").takeIf { mpdRemoteActive }
    val enabled by settings.lightSyncEnabled.collectAsState(initial = false)
    val intensity by settings.lightSyncIntensity.collectAsState(initial = "high")
    val autoLevels by settings.lightSyncAutoLevels.collectAsState(initial = listOf("subtle", "medium", "high"))
    val colour by settings.lightSyncColor.collectAsState(initial = "album_art_v2")
    val brightnessPct by settings.lightSyncBrightness.collectAsState(initial = 100)
    val advanced by settings.lightSyncAdvanced.collectAsState(initial = false)
    val spatial by settings.lightSyncSpatial.collectAsState(initial = false)
    val musicDna by settings.musicDnaEnabled.collectAsState(initial = false)
    val emotionalArc by settings.emotionalArcEnabled.collectAsState(initial = false)
    val phantomStage by settings.phantomStageEnabled.collectAsState(initial = false)
    val stemSeparation by settings.stemSeparation.collectAsState(initial = false)
    val phoneConductor by settings.phoneConductorEnabled.collectAsState(initial = false)
    val presets by settings.showPresets.collectAsState(initial = emptyList())
    val genreRules by settings.genrePresetRules.collectAsState(initial = emptyList())
    val genreAuto by settings.genrePresetsEnabled.collectAsState(initial = false)
    val tunables by settings.lightSyncTunables.collectAsState(initial = emptyMap())
    val bridgeIp by settings.hueBridgeIp.collectAsState(initial = "")
    val configId by settings.hueEntertainmentConfigId.collectAsState(initial = "")
    val speakerOffsetMs by settings.lightSyncSpeakerOffsetMs.collectAsState(initial = 0)
    val captureState by com.engabd.sendpin.capture.PlaybackCapture.state.collectAsStateWithLifecycle()
    val coverOverrides by settings.coverPaletteOverrides.collectAsState(initial = emptyMap())

    // Built from exactly what DirectLightSync.findOverride reads — the active
    // source's LocalTrack and artwork URL — and from nothing else. An override
    // filed under a key the engine never looks up is a palette the user saved and
    // the room never shows, which is the failure this feature already had once.
    val paletteKeys = com.engabd.sendpin.hue.CoverPaletteOverride.keysFor(
        album = lightSource.scanTrack?.album,
        artist = lightSource.scanTrack?.artist,
        coverUrl = lightSource.artUrl,
        trackId = lightSource.scanTrack?.id,
    )
    val savedPalette = paletteKeys.firstNotNullOfOrNull { key ->
        coverOverrides[key]?.takeIf { it.colors.isNotEmpty() }
    }
    var paletteEditorOpen by remember { mutableStateOf(false) }

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
        // As on the Home Assistant path above — see [PageBloom].
        PageBloom(alpha = if (live) 0.42f else 0.22f, size = 440.dp, x = (-40).dp)

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
                                showStatusText(showStatus, speakerLabel),
                                color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                            )
                        }
                        AccentSwitch(enabled) { on ->
                            scope.launch {
                                // A running ambience show may have turned this on itself
                                // (see EffectsViewModel.start). If the user now touches
                                // the switch themselves while that's true, that confirms
                                // they want sync on regardless of the show — adopt it, so
                                // stopping the show later doesn't turn it back off under
                                // them.
                                if (on && app.ambienceSyncOwnership.value ==
                                    com.engabd.sendpin.hue.ambience.AmbienceSyncOwnership.AUTO_ENABLED
                                ) {
                                    app.ambienceSyncOwnership.value =
                                        com.engabd.sendpin.hue.ambience.AmbienceSyncOwnership.USER_ADOPTED
                                }
                                settings.setLightSyncEnabled(on)
                            }
                        }
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
                SectionLabel("Ambience")
                Spacer(Modifier.height(10.dp))
                GlassCard(radius = 18.dp) {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onOpenEffects).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Glass)
                                .border(1.dp, Hairline, RoundedCornerShape(13.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, tint = TextMuted, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Effects", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                ExperimentalBadge()
                            }
                            Text(
                                "Fireworks, thunderstorm, fireplace and more - with their own sound, " +
                                    "and no music needed",
                                color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                            )
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = TextFaint, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("Colour")
                Spacer(Modifier.height(10.dp))
                // Every dynamic source works on the direct path now.
                ColourPicker(selected = colour, showDynamic = true, songFromBeats = true) { scheme ->
                    scope.launch { settings.setLightSyncColor(scheme) }
                }

                // Correcting the colours an album lights the room with belongs here,
                // beside the picker that chose to use them. It was previously only
                // reachable by long-pressing the artwork on Now Playing — behind a
                // sheet of queue and share actions, and gated on the track being
                // favouritable, which has nothing to do with its colours.
                Spacer(Modifier.height(10.dp))
                AlbumColoursCard(
                    scheme = colour,
                    label = lightSource.scanTrack?.album?.takeIf { it.isNotBlank() }
                        ?: maNow?.album?.takeIf { it.isNotBlank() }
                        ?: lightSource.scanTrack?.title
                        ?: maNow?.title,
                    saved = savedPalette,
                    canEdit = paletteKeys.isNotEmpty(),
                    accent = accent,
                    onEdit = { paletteEditorOpen = true },
                    onReset = {
                        scope.launch { settings.setCoverPaletteOverrideForKeys(paletteKeys, null) }
                    },
                )

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
                SectionLabel("Play")
                Spacer(Modifier.height(10.dp))
                GlassCard(radius = 18.dp, modifier = Modifier.clickable(onClick = onOpenRhythmGame).fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                            Text("Rhythm Lights", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Tap along and flash the room.", color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
                    }
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("What this phone listens to")
                Spacer(Modifier.height(10.dp))
                val phoneAudioFeed by settings.phoneAudioFeed.collectAsState(initial = "auto")
                PhoneAudioCard(
                    selected = phoneAudioFeed,
                    state = captureState,
                    accent = accent,
                    onSelect = { choice ->
                        scope.launch { settings.setPhoneAudioFeed(choice) }
                        // The choice *is* the switch. This is the whole of the fix:
                        // picking a mode that needs capture now starts capture, where
                        // before it only changed which feed the picker would have
                        // preferred had anything ever started one.
                        if (choice == "internal") {
                            com.engabd.sendpin.capture.PlaybackCapture.stop(context)
                        } else if (!com.engabd.sendpin.capture.PlaybackCapture.hasToken()) {
                            com.engabd.sendpin.capture.PlaybackCapture.requestStart(context)
                        }
                    },
                    onStartCapture = { com.engabd.sendpin.capture.PlaybackCapture.requestStart(context) },
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

                // Saved shows. Above the layers rather than below them, because a
                // preset is mostly a way of setting those layers and the intensity
                // and the palette all at once - so it reads as "or just pick one of
                // these" before the reader starts working through them one by one.
                Spacer(Modifier.height(22.dp))
                SavedShows(
                    presets = presets,
                    rules = genreRules,
                    genreAuto = genreAuto,
                    accent = accent,
                    onApply = { preset -> scope.launch { settings.applyShowPreset(preset) } },
                    onSave = { name ->
                        scope.launch {
                            val captured = settings.captureShowPreset(name)
                            settings.saveShowPresets(presets + captured)
                        }
                    },
                    onRename = { preset, name ->
                        scope.launch {
                            settings.saveShowPresets(
                                presets.map { if (it.id == preset.id) it.copy(name = name) else it },
                            )
                        }
                    },
                    onDelete = { preset ->
                        scope.launch {
                            settings.saveShowPresets(presets.filterNot { it.id == preset.id })
                            // A rule pointing at a deleted preset would silently never
                            // fire, which looks exactly like the rules being broken.
                            settings.saveGenrePresetRules(
                                genreRules.filterNot { it.presetId == preset.id },
                            )
                        }
                    },
                    onGenreAuto = { on -> scope.launch { settings.setGenrePresetsEnabled(on) } },
                    onAddRule = { genre, preset ->
                        scope.launch {
                            settings.saveGenrePresetRules(
                                genreRules.filterNot { it.genre.equals(genre, true) } +
                                    com.engabd.sendpin.hue.GenrePresetRule(genre.trim(), preset.id),
                            )
                        }
                    },
                    onRemoveRule = { rule ->
                        scope.launch { settings.saveGenrePresetRules(genreRules - rule) }
                    },
                )

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
                    // Music DNA reads the offline track scan, and there is no scan for a
                    // stream arriving from Music Assistant — `scanTrack` is null for both
                    // MA feeds by construction, so `MusicDnaLayer.apply` hands back its
                    // input unchanged. The toggle moved and nothing happened, which reads
                    // as a broken feature rather than an inapplicable one.
                    unavailable = if (feed == com.engabd.sendpin.hue.LightSyncFeed.SENDSPIN_PCM) {
                        "Needs a local track scan - not available while Music Assistant " +
                            "is streaming to this phone."
                    } else null,
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

                if (phantomStage) {
                    Spacer(Modifier.height(16.dp))
                    FeatureRow(
                        title = "Real instrument separation",
                        gist = "Use actual stem energy instead of frequency bands, where a scan has it.",
                        info = "Separates vocals, a stereo-width signal (synths, wide guitars) and " +
                            "bass from the mix during the offline track scan — a mid-side " +
                            "decomposition, not a machine-learning model, run on this phone with " +
                            "nothing sent anywhere.\n\nOff: Phantom Stage uses frequency bands as a " +
                            "proxy for instruments, as above. On: it uses the real stem energy for " +
                            "tracks that have been scanned since this shipped.\n\nTracks that have " +
                            "not been scanned, or were scanned before this existed, fall back to " +
                            "the frequency-band proxy automatically — re-read them under Track " +
                            "analysis to fill them in.",
                        checked = stemSeparation,
                    ) { on -> scope.launch { settings.setStemSeparation(on) } }
                }

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

        // The palette editor, over the whole screen. A BoxScope sheet, so it belongs
        // to this Box rather than to the scrolling column that opens it.
        if (paletteEditorOpen) {
            CoverPaletteEditor(
                albumName = lightSource.scanTrack?.album?.takeIf { it.isNotBlank() }
                    ?: maNow?.album?.takeIf { it.isNotBlank() }
                    ?: "What's playing",
                artistName = lightSource.scanTrack?.artist?.takeIf { it.isNotBlank() }
                    ?: maNow?.artist?.takeIf { it.isNotBlank() },
                coverUrl = lightSource.artUrl,
                // Open showing what is actually on the room, so this is an editor
                // for an existing correction and not only a way to start a new one.
                existing = savedPalette,
                onSave = { override ->
                    // Every key, not the best one — see
                    // AppSettings.setCoverPaletteOverrideForKeys.
                    scope.launch { settings.setCoverPaletteOverrideForKeys(paletteKeys, override) }
                    paletteEditorOpen = false
                },
                onClose = { paletteEditorOpen = false },
            )
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
    /**
     * Why this cannot work right now, or null when it can.
     *
     * A toggle that does nothing is worse than one that is not offered: the listener
     * flips it, sees no change, and concludes the *feature* is broken. Saying so in the
     * row turns "Music DNA does nothing" into a fact about the source.
     */
    unavailable: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    title,
                    color = if (unavailable != null) TextFaint else TextSecondary,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                )
                InfoChip(title, info, Modifier.heightIn(0.dp))
            }
            Text(unavailable ?: gist, color = TextFaint, fontSize = 11.sp, lineHeight = 15.sp)
        }
        AccentSwitch(checked && unavailable == null, enabled = unavailable == null) { onChange(it) }
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

/**
 * Saved shows: apply one, save the current one, and optionally let the genre of
 * whatever is playing pick between them.
 *
 * A chip per preset rather than a list, because applying one is the common action
 * by a wide margin and everything else - rename, delete, tie it to a genre - is
 * rare enough to live behind a long-press.
 */
@Composable
private fun SavedShows(
    presets: List<com.engabd.sendpin.hue.ShowPreset>,
    rules: List<com.engabd.sendpin.hue.GenrePresetRule>,
    genreAuto: Boolean,
    accent: Color,
    onApply: (com.engabd.sendpin.hue.ShowPreset) -> Unit,
    onSave: (String) -> Unit,
    onRename: (com.engabd.sendpin.hue.ShowPreset, String) -> Unit,
    onDelete: (com.engabd.sendpin.hue.ShowPreset) -> Unit,
    onGenreAuto: (Boolean) -> Unit,
    onAddRule: (String, com.engabd.sendpin.hue.ShowPreset) -> Unit,
    onRemoveRule: (com.engabd.sendpin.hue.GenrePresetRule) -> Unit,
) {
    var saving by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<com.engabd.sendpin.hue.ShowPreset?>(null) }
    var applied by remember { mutableStateOf<String?>(null) }

    SectionLabel("Saved shows")
    Spacer(Modifier.height(2.dp))
    Text(
        "The whole show - intensity, palette, brightness, layers and tunables - under one " +
            "name. Applying one never turns the lights on or off, or moves them to another room.",
        color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
    )
    Spacer(Modifier.height(12.dp))

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { preset ->
            PresetChip(
                preset = preset,
                accent = accent,
                justApplied = applied == preset.id,
                onClick = { applied = preset.id; onApply(preset) },
                onLongClick = { editing = preset },
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    OledButton("Save this show as\u2026", accent = accent, outline = true) { saving = true }
    Spacer(Modifier.height(4.dp))
    Note("Long-press a show to rename it, delete it, or tie it to a genre.")

    Spacer(Modifier.height(14.dp))
    FeatureRow(
        title = "Pick a show by genre",
        gist = "Change the room to match what is playing.",
        info = "When a track starts, its genre is matched against the rules you have set and " +
            "that show is applied. First matching rule wins, so the order you add them in " +
            "is the priority order.\n\nMatching is loose in both directions, because no two " +
            "servers agree on genre strings: a rule for \"jazz\" catches \"Vocal Jazz\", and a " +
            "rule for \"Progressive House\" is caught by a track tagged \"house\".\n\nA track " +
            "with no genre, or one nothing matches, leaves the room exactly as it is - " +
            "rather than resetting to a default halfway through a record.\n\nTip: two or " +
            "three broad rules beat a dozen narrow ones. The point is that a quiet album " +
            "does not light the room like a club.",
        checked = genreAuto,
        unavailable = if (rules.isEmpty()) "Add a rule first - long-press a show above." else null,
    ) { on -> onGenreAuto(on) }

    if (rules.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        rules.forEach { rule ->
            val name = presets.firstOrNull { it.id == rule.presetId }?.name ?: "(deleted)"
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${rule.genre}  \u2192  $name",
                    color = TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                CircleBtn(Icons.Default.Close, "Remove rule") { onRemoveRule(rule) }
            }
        }
    }

    if (saving) {
        NamePromptDialog(
            title = "Save this show",
            note = "Everything on this screen as it is right now, under a name.",
            initial = "",
            confirmLabel = "Save",
            accent = accent,
            onDismiss = { saving = false },
            onConfirm = { name -> saving = false; onSave(name) },
        )
    }

    editing?.let { preset ->
        PresetActionsDialog(
            preset = preset,
            presets = presets,
            accent = accent,
            onDismiss = { editing = null },
            onRename = { name -> editing = null; onRename(preset, name) },
            onDelete = { editing = null; onDelete(preset) },
            onAddRule = { genre -> editing = null; onAddRule(genre, preset) },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PresetChip(
    preset: com.engabd.sendpin.hue.ShowPreset,
    accent: Color,
    justApplied: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (justApplied) accent.a(0.16f) else Glass)
            .border(
                1.dp,
                if (justApplied) accent.a(0.45f) else Hairline,
                RoundedCornerShape(14.dp),
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
    ) {
        Text(
            preset.name.ifBlank { "Untitled" },
            color = if (justApplied) accent else TextPrimary,
            fontWeight = FontWeight.Bold, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            preset.summary(),
            color = TextFaint, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NamePromptDialog(
    title: String,
    note: String,
    initial: String,
    confirmLabel: String,
    accent: Color,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink2,
        title = { Text(title, color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OledField(name, { name = it }, "Name", "e.g. Dinner", accent)
                Note(note)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text(
                    confirmLabel,
                    color = if (name.isBlank()) TextFaint else accent,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        },
    )
}

/**
 * Rename, delete, or tie a preset to a genre.
 *
 * One dialog for all three because they are all rare: a preset is applied dozens of
 * times for every once it is edited, which is why the chip's tap does the applying
 * and everything here is behind a long-press.
 */
@Composable
private fun PresetActionsDialog(
    preset: com.engabd.sendpin.hue.ShowPreset,
    presets: List<com.engabd.sendpin.hue.ShowPreset>,
    accent: Color,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onAddRule: (String) -> Unit,
) {
    var name by remember { mutableStateOf(preset.name) }
    var genre by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink2,
        title = { Text(preset.name.ifBlank { "Untitled" }, color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OledField(name, { name = it }, "Name", "e.g. Dinner", accent)
                OledField(genre, { genre = it }, "Use for genre (optional)", "e.g. jazz", accent)
                Note(preset.summary())
                OledButton(
                    if (confirmDelete) "Tap again to delete" else "Delete this show",
                    accent = accent, danger = true,
                ) {
                    if (confirmDelete) onDelete() else confirmDelete = true
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    // A genre typed here is the reason the dialog was opened; a name
                    // change alone is the other reason. Both at once is fine.
                    if (genre.isNotBlank()) onAddRule(genre)
                    else if (name.trim() != preset.name) onRename(name.trim())
                    else onDismiss()
                },
                enabled = name.isNotBlank(),
            ) {
                Text("Done", color = if (name.isBlank()) TextFaint else accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        },
    )
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
private fun AccentSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    Switch(
        checked = checked, onCheckedChange = onChange, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Ink, checkedTrackColor = accent, checkedBorderColor = accent,
            uncheckedThumbColor = TextMuted, uncheckedTrackColor = Glass, uncheckedBorderColor = Hairline,
        ),
    )
}

@Composable
internal fun CircleBtn(icon: ImageVector, cd: String, onClick: () -> Unit) {
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
 * What this phone's Light Sync is listening to, and the one control that decides it.
 *
 * ## Why this is one card and not two
 *
 * It used to be two, and they did not add up. A "Use this phone's audio" selector
 * offered Auto and Always projection, and a separate "React to other apps" switch
 * owned `MediaProjection`. But the selector only expressed a *preference* — the feed
 * picker consults it and then asks whether capture happens to be running — and
 * nothing in the selector ever started capture. So choosing Auto or Always
 * projection and expecting the room to follow Spotify did exactly nothing, forever,
 * with no error: the picker fell through to the local tap, which was silent, and the
 * lights ran the idle show. The two controls could also disagree by construction,
 * since either could be set to something the other contradicted.
 *
 * Now the choice is the switch. Picking a mode that needs capture asks for it, and
 * picking the one that does not stops it, so there is nothing left for a second
 * toggle to say.
 *
 * ## Why it is not a plain switch
 *
 * Capture needs a runtime microphone grant (which is what `AudioPlaybackCapture` is
 * gated on, even though no microphone is opened), a system consent dialog, and a
 * foreground service — and on Android 14+ that consent dialog reappears every single
 * time capture starts, because the projection token is single-use. So the card can
 * want capture and not have it, and it has to be able to say so and offer the way
 * back, rather than showing a switch that is on while nothing is listening.
 *
 * The blocked message is the part that earns the feature its keep. An app that opts
 * out of capture is delivered as bit-exact silence with no error of any kind, so
 * without saying so plainly the only observation available to the user is "the
 * lights do nothing", which is indistinguishable from a bug here.
 */
/**
 * The row that opens the album-palette editor, and says whether this album already
 * has corrected colours.
 *
 * ## Why it lives on this screen
 *
 * The editor shipped reachable only by long-pressing the artwork on Now Playing,
 * inside a sheet of play/queue/share actions — and that long-press was gated on the
 * track being *favouritable*, which is a question about whether a server can star it
 * and has nothing whatever to do with its colours. On a local file with no library
 * behind it the gesture did nothing at all. Nobody looking for a Light Sync setting
 * was going to find it there even when it worked.
 *
 * So it is here, under the colour picker that chose to use album colours in the
 * first place. The Now Playing entry stays — it is the right shortcut when you are
 * already looking at the cover you want to fix.
 *
 * [scheme] is the stored wire name rather than a boolean, because the card has three
 * things to say and only one of them is "tap to edit": a fixed palette means these
 * colours are not in use at all, and nothing playing means there is no album to save
 * them against.
 */
@Composable
private fun AlbumColoursCard(
    scheme: String,
    /** Album name, or the track's, for saying which record this would correct. */
    label: String?,
    saved: com.engabd.sendpin.hue.CoverPaletteOverride?,
    /** False when nothing is playing, so there is no key to file an override under. */
    canEdit: Boolean,
    accent: Color,
    onEdit: () -> Unit,
    onReset: () -> Unit,
) {
    val dynamic = com.engabd.sendpin.hue.ColorScheme.fromWire(scheme).isDynamic
    GlassCard(radius = 18.dp, fill = if (saved != null) accent.a(0.10f) else Glass) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = canEdit, onClick = onEdit)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The saved colours themselves where there are some — the only
                // preview worth showing, and the one that answers "did that save?"
                // without opening anything. The extracted palette is not shown here
                // because knowing it means decoding the cover, which is work this
                // row should not be doing on every recomposition of the screen.
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Glass)
                        .border(1.dp, Hairline, RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val colours = saved?.colors.orEmpty()
                    if (colours.isEmpty()) {
                        Icon(
                            Icons.Default.Palette, null,
                            tint = if (canEdit && dynamic) TextMuted else TextFaint,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            colours.take(4).chunked(2).forEach { pair ->
                                Row(Modifier.weight(1f).fillMaxWidth()) {
                                    pair.forEach { argb ->
                                        Box(
                                            Modifier.weight(1f).fillMaxHeight()
                                                .background(Color(argb or (0xFF shl 24))),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Album colours",
                            color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        )
                        if (saved != null) {
                            Text(
                                "Corrected",
                                color = accent, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                            )
                        }
                    }
                    Text(
                        when {
                            !canEdit -> "Play something to correct the colours it lights the room with."
                            !dynamic ->
                                "The colour above is a fixed palette, so album colours are not in " +
                                    "use. Corrections are kept, and apply again on Album art."
                            saved != null && label != null -> "Your colours for $label."
                            saved != null -> "Your colours, not the artwork's."
                            label != null -> "Change the colours $label lights the room with."
                            else -> "Change the colours this album lights the room with."
                        },
                        color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    )
                }
                if (canEdit) {
                    Icon(Icons.Default.ChevronRight, null, tint = TextFaint, modifier = Modifier.size(20.dp))
                }
            }

            if (saved != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onReset)
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                ) {
                    Text(
                        "Use the artwork's own colours again",
                        color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhoneAudioCard(
    selected: String,
    state: com.engabd.sendpin.capture.PlaybackCapture.State,
    accent: Color,
    onSelect: (String) -> Unit,
    onStartCapture: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val wantsCapture = selected != "internal"
    var granted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    // Remembers which choice asked for the grant, so answering the dialog completes
    // that choice rather than starting capture for a mode the user has since left.
    var pendingChoice by remember { mutableStateOf<String?>(null) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        val choice = pendingChoice
        pendingChoice = null
        if (ok && choice != null) onSelect(choice)
    }

    fun choose(choice: String) {
        if (choice != "internal" && !granted) {
            pendingChoice = choice
            permission.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        onSelect(choice)
    }

    GlassCard(radius = 18.dp, fill = if (wantsCapture) accent.a(0.10f) else Glass) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                Text(
                    "What this phone listens to",
                    color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                )
                Text(
                    phoneFeedBlurb(selected, state),
                    color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                )
            }

            val options = listOf("Auto", "CAMusic only", "Other apps")
            val values = listOf("auto", "internal", "projection")
            SegmentedToggle(
                options = options,
                selectedIndex = values.indexOf(selected).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth(),
            ) { choose(values[it]) }

            Text(
                when (selected) {
                    "internal" ->
                        "Only CAMusic's own playback drives the lights. Nothing else on the " +
                            "phone is listened to, and no permission is needed."
                    "projection" ->
                        "Always listens through screen capture, even while CAMusic is playing. " +
                            "Pick this if you want another app's audio to win outright."
                    else ->
                        "Uses CAMusic's own audio while it is playing - which is exact and free - " +
                            "and listens to whatever else is playing when it is not."
                },
                color = TextFaint, style = MaterialTheme.typography.bodySmall,
            )

            if (state == com.engabd.sendpin.capture.PlaybackCapture.State.BLOCKED) {
                Text(
                    "That app does not allow its audio to be captured. YouTube, YouTube Music " +
                        "and most DRM-protected apps opt out, and nothing here can change that. " +
                        "Spotify, podcast apps and local players usually work.",
                    color = WarnAmber, style = MaterialTheme.typography.bodySmall,
                )
            }

            // Wanting capture and not having it is a normal, recurring state rather
            // than an error: the grant is single-use, so this is where every app
            // restart lands. Offer the way back instead of a switch that lies.
            if (wantsCapture && !com.engabd.sendpin.capture.PlaybackCapture.hasToken()) {
                Text(
                    "Android asks permission each time listening starts, and the grant cannot " +
                        "be remembered - so this needs one tap per app session.",
                    color = TextFaint, style = MaterialTheme.typography.bodySmall,
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, accent.a(0.55f), RoundedCornerShape(12.dp))
                        .clickable {
                            if (granted) {
                                onStartCapture()
                            } else {
                                pendingChoice = selected
                                permission.launch(android.Manifest.permission.RECORD_AUDIO)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        if (state == com.engabd.sendpin.capture.PlaybackCapture.State.OFF) "Start listening"
                        else "Start again",
                        color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

private fun phoneFeedBlurb(
    selected: String,
    state: com.engabd.sendpin.capture.PlaybackCapture.State,
): String = when {
    selected == "internal" -> "CAMusic's own playback."
    state == com.engabd.sendpin.capture.PlaybackCapture.State.RUNNING -> "Listening."
    state == com.engabd.sendpin.capture.PlaybackCapture.State.QUIET -> "Listening, nothing playing."
    state == com.engabd.sendpin.capture.PlaybackCapture.State.BLOCKED -> "That app will not allow it."
    state == com.engabd.sendpin.capture.PlaybackCapture.State.STARTING -> "Starting..."
    state == com.engabd.sendpin.capture.PlaybackCapture.State.STOPPED_BY_SYSTEM -> "Listening was stopped."
    state == com.engabd.sendpin.capture.PlaybackCapture.State.DENIED -> "Permission was not granted."
    selected == "projection" -> "Other apps on this phone, once started."
    else -> "CAMusic when it is playing, other apps when it is not."
}
