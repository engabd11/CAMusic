package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.saveable.rememberSaveable
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
    /**
     * Stops a running ambience show, from the tile that reports it.
     *
     * A callback rather than an [com.engabd.sendpin.ui.viewmodel.EffectsViewModel] of
     * this screen's own, because stopping is not just `stopAmbience()`: the view model
     * also releases the audio, cancels the sleep timer and — the part that cannot be
     * skipped — puts Light Sync back the way it found it, but only when the show is
     * the reason it came on. Wiring it in `App.kt` means the tile and the Effects
     * screen drive the same object rather than two that can disagree.
     */
    onStopAmbience: () -> Unit = {},
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
        "direct" -> DirectLightSyncScreen(onBack, onOpenEffects, onOpenRhythmGame, onStopAmbience)
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

    // The same album the direct path washes with. Process-scoped, so this page is lit
    // by whatever is playing even though the show itself runs on Home Assistant and
    // this phone may be decoding nothing at all.
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as com.engabd.sendpin.SendpinApp
    val settings = remember { com.engabd.sendpin.data.AppSettings(context.applicationContext) }
    val lightSource by app.activeLightSyncSource.collectAsStateWithLifecycle()
    val chameleonBloom by settings.chameleonBloom.collectAsStateWithLifecycle(initialValue = false)

    var openPill by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(LightTab.LOOK) }

    val enabled = area?.enabled == true

    Box(Modifier.fillMaxSize().background(Ink)) {
        // The full album wash, the same one Now Playing wears - see [AlbumWash].
        // This was a single flat bloom, which was already an improvement on the fixed
        // ambient accent it replaced, but still left the page whose entire subject is
        // lighting a room from music as the one art-bearing surface not lit by the
        // record that is on. It also earns the page real glass: [MeltBackdrop] records
        // the backdrop every [GlassCard] here samples, and with nothing recorded they
        // were all falling back to a flat fill. The state is still said in brightness
        // rather than in hue.
        AlbumWash(
            artUrl = lightSource.artUrl,
            palette = LocalPalette.current,
            bloom = chameleonBloom,
            dim = idleFade(idle = !enabled, dimmed = 0.55f),
        )

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

            // Lazily, for the reason the direct path below is lazy: a Column that
            // scrolls composes and measures its whole contents in one frame and
            // recycles none of it. This page is shorter than that one but the same
            // shape — nine sections, three horizontally scrolling rows of chips, and
            // a dozen sliders behind the Advanced switch.
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 18.dp, end = 18.dp, bottom = navBarInset() + 16.dp,
                ),
            ) {

                if (!connected) {
                    item(key = "ha_connect", contentType = "section") {
                        ConnectCard(
                            prefillUrl = prefillUrl,
                            prefillToken = prefillToken,
                            error = error,
                            onConnect = viewModel::connect,
                        )
                    }
                    return@LazyColumn
                }

                if (areas.isEmpty()) {
                    item(key = "ha_empty", contentType = "section") {
                        EmptyAreas(error) { viewModel.refresh() }
                    }
                    return@LazyColumn
                }

                val a = area ?: return@LazyColumn

                item(key = "ha_hero", contentType = "section") {
                    // The hero: the switch, the zone and the level in one card - the
                    // same shape the direct path wears, because these are the same two
                    // questions and there is no reason for the two routes to the lights
                    // to be two different pages to learn.
                    GlassCard(radius = 20.dp, fill = if (enabled) accent.a(0.10f) else Glass) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
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

                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LightPill(
                                    label = "Zone",
                                    value = a.name,
                                    icon = Icons.Default.Lightbulb,
                                    tint = LocalPalette.current.swatch(0),
                                    expanded = openPill == "zone",
                                    modifier = Modifier.weight(1f),
                                ) { openPill = if (openPill == "zone") null else "zone" }

                                LightPill(
                                    label = "Level",
                                    value = a.mode?.label() ?: "Auto",
                                    icon = Icons.Default.GraphicEq,
                                    tint = LocalPalette.current.swatch(1),
                                    expanded = openPill == "level",
                                    modifier = Modifier.weight(1f),
                                ) { openPill = if (openPill == "level") null else "level" }
                            }

                            PillPanel(visible = openPill == "zone") {
                                Column {
                                    // Which zone, and what it listens to. One question:
                                    // a zone that follows nothing is not configured, and
                                    // these were two sections apart.
                                    if (areas.size > 1) {
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
                                        Spacer(Modifier.height(14.dp))
                                    }
                                    SectionLabel("Follow player")
                                    Spacer(Modifier.height(10.dp))
                                    PlayerRow(
                                        selected = a.mediaPlayer,
                                        players = mediaPlayers,
                                        onSelect = viewModel::setFollowPlayer,
                                    )
                                }
                            }

                            PillPanel(visible = openPill == "level") {
                                Column {
                                    val modeOptions = a.modeOptions.ifEmpty { ModeFallback }
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        modeOptions.forEach { opt -> Pill(opt.label(), opt == a.mode) { viewModel.setMode(opt) } }
                                    }
                                    // What the selected rung actually does - the ladder's names do not say.
                                    LightSyncRepository.MODE_BLURBS[a.mode]?.let {
                                        Spacer(Modifier.height(9.dp))
                                        Text(it, color = TextFaint, style = MaterialTheme.typography.bodySmall)
                                    }

                                    // Auto rungs - the intensities Auto may pick from (only when mode == auto).
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
                                }
                            }
                        }
                    }
                }

                item(key = "ha_effect", contentType = "section") {
                    // Where the direct path puts its two feature tiles. This route has
                    // neither ambience nor the rhythm game, but it does have the one
                    // question they answer between them - which show is running - so the
                    // effect picker sits in that slot rather than being the fifth of
                    // nine identical sections. An empty tile row would be worse than no
                    // tile row.
                    val effectOptions = a.effectOptions.ifEmpty { EffectFallback }
                    Spacer(Modifier.height(18.dp))
                    SectionLabel("Effect")
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        effectOptions.forEach { opt -> EffectTile(opt, effectIcon(opt), opt == a.effect) { viewModel.setEffect(opt) } }
                    }
                    LightSyncRepository.EFFECT_BLURBS[a.effect]?.let {
                        Spacer(Modifier.height(9.dp))
                        Text(it, color = TextFaint, style = MaterialTheme.typography.bodySmall)
                    }
                }

                item(key = "ha_tabs", contentType = "section") {
                    Spacer(Modifier.height(20.dp))
                    // No Shows tab on this route: saved shows and the light-show layers
                    // are the direct engine's, and a tab that opens on nothing is worse
                    // than one that is not offered.
                    LightTabs(
                        tabs = listOf(LightTab.LOOK, LightTab.TUNING),
                        selected = tab,
                        accent = accent,
                    ) { tab = it }
                }

                if (tab == LightTab.LOOK) item(key = "ha_colour", contentType = "section") {
                    Spacer(Modifier.height(22.dp))
                    SectionLabel("Colour")
                    Spacer(Modifier.height(10.dp))
                    ColourPicker(selected = a.colour, onSelect = viewModel::setColour)
                }

                if (tab == LightTab.LOOK) item(key = "ha_brightness", contentType = "section") {
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
                }

                if (tab == LightTab.TUNING) item(key = "ha_timing", contentType = "section") {
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
                }

                if (tab == LightTab.TUNING) item(key = "ha_advanced", contentType = "section") {
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
    onStopAmbience: () -> Unit = {},
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
    // Which saved show the room is actually on. Compared rather than remembered, so
    // the highlight is right after leaving the tab, after a genre rule fires on its
    // own, and — the point — stops being right the moment a tunable moves. See
    // ShowPreset.matches.
    val liveShow by settings.liveShowPreset.collectAsState(initial = com.engabd.sendpin.hue.ShowPreset.default())
    val activePresetId = remember(presets, liveShow) {
        presets.firstOrNull { it.matches(liveShow) }?.id
    }

    // Built from exactly what DirectLightSync.findOverride reads — the active
    // source's own palette fields, artwork URL and track id — and from nothing else.
    // An override filed under a key the engine never looks up is a palette the user
    // saved and the room never shows, which is the failure this feature already had
    // once. `paletteAlbum`/`paletteArtist` rather than `scanTrack`'s: the latter is
    // null on the Music Assistant feed, which left the churning artwork URL as MA's
    // only key — see ActiveLightSyncSource.
    val paletteKeys = com.engabd.sendpin.hue.CoverPaletteOverride.keysFor(
        album = lightSource.paletteAlbum,
        artist = lightSource.paletteArtist,
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

    // What the Ambience tile shows. Process-scoped, so a show started from the effects
    // screen or left running from a previous visit is reflected here without this
    // screen having to have been the thing that started it.
    val ambienceRunning by direct.ambienceRunning.collectAsStateWithLifecycle()
    val chameleonBloom by settings.chameleonBloom.collectAsStateWithLifecycle(initialValue = false)

    // The best run on whatever is playing, for the Rhythm Lights tile. Read once per
    // track rather than collected: the store is a small SharedPreferences blob written
    // only when a run finishes, and a score that appears mid-scroll would be noise.
    val gameTrackKey = remember(lightSource.scanTrack?.id, lightSource.artUrl) {
        runCatching { direct.currentGameTrackKey() }.getOrNull()
    }
    val rhythmBest = remember(gameTrackKey) {
        com.engabd.sendpin.game.GameRecords.bestFor(context, gameTrackKey)
            ?.bestScore?.takeIf { it > 0 }
    }

    // Which hero pill is open, and which tab is on show. Saveable so that leaving the
    // Lights tab and coming back does not throw away where the reader was — this
    // screen is a NavHost destination, so plain `remember` is dropped on every visit.
    var openPill by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(LightTab.LOOK) }

    // Read here rather than inside a section, because the hero offers the one capture
    // action that has to be taken again every app session. What it is set *to* is
    // configured under Settings, Illumination & Sync - see PhoneAudioCard.
    val phoneAudioFeed by settings.phoneAudioFeed.collectAsState(initial = "auto")

    val showStatus = com.engabd.sendpin.hue.ShowStatusRules.statusFor(
        enabled = enabled,
        sessionOpen = live,
        feed = feed,
        framesFresh = framesFresh,
        scanState = scanState,
        captureBlocked = captureState == com.engabd.sendpin.capture.PlaybackCapture.State.BLOCKED,
    )

    Box(Modifier.fillMaxSize().background(Ink)) {
        // The same wash Now Playing wears, over the same album — see [AlbumWash]. This
        // page used to paint a single flat [PageBloom] and was the one art-bearing
        // surface in the app not lit by the record that is on, which is odd anywhere
        // and absurd on the screen whose entire subject is lighting a room from the
        // music. It also earns the page real glass for free: [MeltBackdrop] records the
        // backdrop every [GlassCard] here samples, and with nothing recorded they were
        // all falling back to a flat fill.
        AlbumWash(
            artUrl = lightSource.artUrl,
            palette = LocalPalette.current,
            bloom = chameleonBloom,
            dim = idleFade(idle = !live, dimmed = 0.55f),
        )

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

            // A lazy list, not a Column that scrolls.
            //
            // Everything below used to be one Column with `verticalScroll`, which meant
            // the whole page — six cards, four pill rows, twelve swatches, seven tunable
            // sliders with their blurbs, five feature rows and two long paragraphs —
            // was composed and measured in a single frame on the way in, and none of it
            // ever recycled. That is the same diagnosis `perf: recycle the Audio & DSP
            // page card by card` wrote up, and that commit's own message names this
            // screen as one it did not get to.
            //
            // The second half matters more here than it did there. This screen collects
            // around thirty flows, and several of them move with the *audio* —
            // `framesFresh` flips as the music plays. Inside one Column every one of
            // those emissions re-ran all six hundred lines; now an emission re-runs the
            // items that actually read it.
            //
            // Section spacing is left exactly as it was, in the `Spacer` each section
            // already opened with, rather than moved to `verticalArrangement` — the
            // gaps here are deliberately uneven (22dp between sections, 10dp under a
            // label, 16dp between feature rows) and a uniform arrangement would flatten
            // all three.
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 18.dp, end = 18.dp, bottom = navBarInset() + 16.dp,
                ),
            ) {
                if (bridgeIp.isBlank()) {
                    item(key = "ls_nobridge", contentType = "section") { NoBridgeCard() }
                    return@LazyColumn
                }

                item(key = "ls_hero", contentType = "section") {
                    // The hero: the switch, the room and the level in one card.
                    //
                    // These were three stacked sections, each opened by a label and a
                    // 22dp gap, which gave the two controls nearly everybody comes here
                    // to change the same weight as the seventh tunable slider. Streaming
                    // also needs something playing on this phone - either the local
                    // player, or Music Assistant playing to this phone through the
                    // ExoPlayer engine, which is the one that carries the analysis tap.
                    // See SendpinApp's direct-sync gate.
                    GlassCard(radius = 20.dp, fill = if (enabled) accent.a(0.10f) else Glass) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                                        // the switch themselves while that is true, that confirms
                                        // they want sync on regardless of the show - adopt it, so
                                        // stopping the show later does not turn it back off under
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

                            // Configuring what this phone listens to now lives in
                            // Settings, but Android 14+ makes the projection token
                            // single-use, so starting it is needed once per app session
                            // - and an action that recurs, two screens away, reads as
                            // the feature being broken. The choice moved; the tap that
                            // has to happen every session stayed here.
                            if (phoneAudioFeed != "internal" &&
                                !com.engabd.sendpin.capture.PlaybackCapture.hasToken()
                            ) {
                                Spacer(Modifier.height(12.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, accent.a(0.55f), RoundedCornerShape(12.dp))
                                        .clickable {
                                            com.engabd.sendpin.capture.PlaybackCapture.requestStart(context)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                ) {
                                    Text(
                                        "Start listening",
                                        color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                    )
                                }
                            }

                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LightPill(
                                    label = "Room",
                                    value = configs.firstOrNull { it.id == configId }?.name
                                        ?: if (loadingConfigs) "Loading..." else "Choose a room",
                                    icon = Icons.Default.Lightbulb,
                                    tint = LocalPalette.current.swatch(0),
                                    expanded = openPill == "room",
                                    modifier = Modifier.weight(1f),
                                ) { openPill = if (openPill == "room") null else "room" }

                                LightPill(
                                    label = "Level",
                                    value = if (intensity == AUTO_INTENSITY) "Auto" else intensity.label(),
                                    icon = Icons.Default.GraphicEq,
                                    tint = LocalPalette.current.swatch(1),
                                    expanded = openPill == "level",
                                    modifier = Modifier.weight(1f),
                                ) { openPill = if (openPill == "level") null else "level" }
                            }

                            PillPanel(visible = openPill == "room") {
                                when {
                                    loadingConfigs -> Text("Loading areas from the bridge...", color = TextMuted, fontSize = 13.sp)
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
                                        // that used to be fixed by leaving the tab and coming back -
                                        // the bridge was briefly unreachable - needs a way to ask.
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
                            }

                            PillPanel(visible = openPill == "level") {
                                Column {
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        // Auto is not a rung - it is a choice between rungs, resolved
                                        // per frame from the music's character. The rest come straight
                                        // off the engine's enum, so the pills can never offer a rung
                                        // the engine does not have.
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
                                                    // Never let the selection empty out - Auto with
                                                    // nothing to choose from has no answer to give.
                                                    val next = if (on) autoLevels - m.wire else autoLevels + m.wire
                                                    if (next.isNotEmpty()) {
                                                        scope.launch { settings.setLightSyncAutoLevels(next) }
                                                    }
                                                }
                                            }
                                        }
                                    }
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
                    // Directly under the Level pill, because two of the intensity
                    // rungs it opens relax or bypass the WCAG flash limiter.
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
                }

                item(key = "ls_tiles", contentType = "section") {
                    // The two things on this page you *go and do*, rather than set.
                    // They were a pair of chevron rows that said nothing about
                    // themselves - see [FeatureTiles].
                    Spacer(Modifier.height(18.dp))
                    FeatureTiles(
                        ambienceRunning = ambienceRunning,
                        rhythmBest = rhythmBest,
                        accent = accent,
                        onOpenAmbience = onOpenEffects,
                        onStopAmbience = onStopAmbience,
                        onOpenRhythm = onOpenRhythmGame,
                    )
                }

                item(key = "ls_tabs", contentType = "section") {
                    Spacer(Modifier.height(20.dp))
                    LightTabs(
                        tabs = LightTab.entries,
                        selected = tab,
                        accent = accent,
                    ) { tab = it }
                }

                if (tab == LightTab.LOOK) item(key = "ls_colour", contentType = "section") {
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
                }

                if (tab == LightTab.TUNING) item(key = "ls_offset", contentType = "section") {
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
                }

                if (tab == LightTab.LOOK) item(key = "ls_brightness", contentType = "section") {
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
                }

                if (tab == LightTab.TUNING) item(key = "ls_advanced", contentType = "section") {
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
                }

                if (tab == LightTab.SHOWS) item(key = "ls_shows", contentType = "section") {
                    // Saved shows. Above the layers rather than below them, because a
                    // preset is mostly a way of setting those layers and the intensity
                    // and the palette all at once - so it reads as "or just pick one of
                    // these" before the reader starts working through them one by one.
                    Spacer(Modifier.height(22.dp))
                    SavedShows(
                        presets = presets,
                        rules = genreRules,
                        genreAuto = genreAuto,
                        activeId = activePresetId,
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
                }

                if (tab == LightTab.SHOWS) item(key = "ls_layers", contentType = "section") {
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
                }

                if (tab == LightTab.TUNING) item(key = "ls_footer", contentType = "section") {
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

        // The palette editor, over the whole screen. A BoxScope sheet, so it belongs
        // to this Box rather than to the scrolling column that opens it.
        if (paletteEditorOpen) {
            // Pinned to the album that was playing when the sheet opened. `remember`
            // with no key inside this `if` lasts exactly as long as the edit, because
            // the block enters composition on open and leaves it on close. Without the
            // pin the next track to start would re-seed the palette out from under the
            // user and file the save against the new track's keys instead — see
            // CoverPaletteEditor's own note on seeding.
            val editorKeys = remember { paletteKeys }
            // The same fields the keys above are built from, so the sheet cannot
            // name one record and file the save against another.
            val editorAlbum = remember {
                lightSource.paletteAlbum?.takeIf { it.isNotBlank() } ?: "What's playing"
            }
            val editorArtist = remember { lightSource.paletteArtist?.takeIf { it.isNotBlank() } }
            val editorCover = remember { lightSource.artUrl }
            CoverPaletteEditor(
                albumName = editorAlbum,
                artistName = editorArtist,
                coverUrl = editorCover,
                // Open showing what is actually on the room, so this is an editor
                // for an existing correction and not only a way to start a new one.
                // Read live against the pinned keys, not pinned itself: the overrides
                // map is a flow whose first emission is empty, so what is already
                // saved arrives a frame or two after the sheet does.
                existing = editorKeys.firstNotNullOfOrNull { key ->
                    coverOverrides[key]?.takeIf { it.colors.isNotEmpty() }
                },
                onSave = { override ->
                    // Every key, not the best one — see
                    // AppSettings.setCoverPaletteOverrideForKeys.
                    scope.launch { settings.setCoverPaletteOverrideForKeys(editorKeys, override) }
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
internal fun FeatureRow(
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
    val colourScheme = com.engabd.sendpin.hue.ColorScheme.fromWire(scheme)
    val dynamic = colourScheme.isDynamic
    // Song draws its own colours from the music and never takes the album's — see
    // [SyncoEngine.setAlbumColors], which stores a correction under Song but does not
    // put it on the room. So a correction is kept and is real, and this card must not
    // claim it is what the lights are showing.
    val songScheme = colourScheme == com.engabd.sendpin.hue.ColorScheme.SONG
    val showing = dynamic && !songScheme
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
                            tint = if (canEdit && showing) TextMuted else TextFaint,
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
                                if (showing) "Corrected" else "Corrected, not in use",
                                color = if (showing) accent else TextMuted,
                                fontWeight = FontWeight.Bold, fontSize = 10.sp,
                            )
                        }
                    }
                    Text(
                        when {
                            !canEdit -> "Play something to correct the colours it lights the room with."
                            !dynamic ->
                                "The colour above is a fixed palette, so album colours are not in " +
                                    "use. Corrections are kept, and apply again on Album art."
                            songScheme ->
                                "Song draws its colours from the music itself, not the sleeve, so " +
                                    "album colours are not in use. Corrections are kept, and apply " +
                                    "again on Album art."
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
