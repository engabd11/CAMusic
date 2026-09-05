package com.engabd.sendpin.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ui.design.Bloom
import com.engabd.sendpin.ui.design.GlassCard
import com.engabd.sendpin.ui.design.TitleGap
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.LocalPalette
import com.engabd.sendpin.ui.design.Motion
import com.engabd.sendpin.ui.design.navBarInset
import com.engabd.sendpin.ui.screens.settings.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * The top level of Settings.
 *
 * Everything used to live on one scroll, then on eight flat categories grouped by
 * *which class implements it* — which is why Music Assistant's address lived under
 * "Servers" while the choice of whether to browse it lived under "Library".
 *
 * The six sections below are the questions someone actually arrives with, and each
 * one is now an *index* rather than a page. That second half is the more important
 * one: five categories over a single scroll each is not really two levels, and
 * "Audio Engine & DSP" had become nine cards spanning bit-perfect output, ReplayGain,
 * crossfade, DJ Radio, a road-safety feature with its own GPS subsystem, shake
 * gestures and lyrics timing — six unrelated subjects sharing a page because no
 * better home existed. Now every section opens on a short list of pages, and a page
 * holds one subject.
 *
 * Two sections keep a body of their own instead of a menu, because in both cases the
 * body *is* the index: Media Providers opens on the list of servers, each leading to
 * its own page, and Illumination opens on the transport picker, which has to be
 * answered before anything under it means anything.
 *
 * Depth is carried by [detail], hoisted alongside [section] so that re-tapping the
 * Settings tab comes all the way back to the index — see `App.kt`. A route there is
 * a sub-page's name, a server's id, or one of the Light Sync pages; nothing about the
 * navigation changed when the pages arrived, there are simply more places to go.
 *
 * Pages are emitted **card by card** into the host list rather than as one item —
 * see the body below. A page composed inside a single item never recycles, so it is
 * measured whole in one frame, which is what the Audio page's scroll was missing.
 */
enum class SettingsSection(
    val title: String,
    /** What is actually behind the row. A one-word category is a guess until opened. */
    val subtitle: String,
    val icon: ImageVector,
) {
    PROVIDERS(
        "Media Providers & Accounts",
        "Your music servers, connected libraries, and local storage",
        Icons.Default.LibraryMusic,
    ),
    AUDIO(
        "Audio Engine & DSP",
        "Output and signal path, the equaliser, ReplayGain, crossfade, and gestures",
        Icons.Default.GraphicEq,
    ),
    LIGHTS_SYNC(
        "Illumination & Sync",
        "The route to your lights, the Hue Bridge, track analysis, and ambience",
        Icons.Default.Lightbulb,
    ),
    APPEARANCE(
        "Interface & Appearance",
        "Themes, album art accents, seek bar styles, motion, and album bloom",
        Icons.Default.Palette,
    ),
    /**
     * Driving mode and Android Auto.
     *
     * Both were homeless. Driving mode was the fifth of nine cards inside "Audio
     * Engine & DSP" — a road-safety feature asking for Bluetooth, phone state and
     * fine location, buried in a page about DACs whose description never mentioned a
     * car. Android Auto shipped with services, a browse tree and a manifest entry,
     * and no presence in Settings whatsoever, so there was no way to find out whether
     * it would work short of driving somewhere.
     */
    DRIVING(
        "Driving & Android Auto",
        "Controls over the map, the speed limit alert, and what the car's screen shows",
        Icons.Default.DirectionsCar,
    ),
    SYSTEM_ABOUT(
        "System, Storage & About",
        "Downloads, encrypted backup and restore, diagnostics, version, and statistics",
        Icons.Default.Settings,
    ),
}

@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel = viewModel(),
    /**
     * The *shared* library view model. Passed in rather than resolved with
     * `viewModel()` here: inside a `NavHost` destination that builds a second instance
     * scoped to this back-stack entry, so editing a server's credentials in Settings
     * would connect a client the Library tab never sees.
     */
    libraryViewModel: LibraryViewModel,
    /**
     * Which category is open, or null for the index.
     *
     * Hoisted rather than held here so the tab bar can close it: tapping Settings
     * while a category is open comes back to the index, the same way tapping Library
     * while browsing comes back to the shelves.
     */
    section: SettingsSection? = null,
    onSection: (SettingsSection?) -> Unit = {},
    /** The page *within* a section — a server, or the Hue bridge. */
    detail: String? = null,
    onDetail: (String?) -> Unit = {},
    /**
     * Downloads get a screen of their own rather than a sub-page here: it is a list
     * to search and sort, which is a screen, not a settings card.
     */
    onOpenDownloads: () -> Unit = {},
    /** Stats gets a screen of its own for the same reason. */
    onOpenStats: () -> Unit = {},
    /**
     * The Ambience screen, cross-linked from Illumination.
     *
     * Ambience is reached from the Lights tab and always has been, but somebody
     * setting up the lights in Settings has no way to know it exists — so the
     * Illumination index points at it rather than describing it and leaving the
     * reader to go looking.
     */
    onOpenAmbience: () -> Unit = {},
) {
    // Back unwinds one level at a time, so a server's page returns to the server list
    // rather than all the way out of Settings.
    fun up() {
        when {
            detail == null -> onSection(null)
            // Three levels: the player page sits under a server, which sits under the
            // list. Popping straight to null from there would skip the page the user
            // came from and land them on the list, which is not where back goes.
            else -> onDetail(parentDetail(detail))
        }
    }

    BackHandler(enabled = section != null) { up() }

    val accent = LocalAccent.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { AppSettings(context) }
    val advanced by settings.advancedSettings.collectAsStateWithLifecycle(initialValue = false)

    // Home Assistant's credentials are read once and held here rather than inside the
    // Light Sync section, so navigating into the bridge page and back doesn't lose a
    // half-typed token.
    var haUrl by remember { mutableStateOf("") }
    var haToken by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        haUrl = settings.haUrl.first()
        haToken = settings.haToken.first()
    }

    val state = rememberSettingsOverview(settings, libraryViewModel)

    // The same swatch each section's own index row is tinted with, carried onto the
    // page itself — a quiet colour cue that this is "the Light Sync page" rather
    // than a fourth identical grey screen. Falls back to the ambient accent on the
    // index, where there is no one section to key off.
    val bloomColor = section?.let { LocalPalette.current.swatch(it.ordinal) } ?: accent

    Box(Modifier.fillMaxSize().background(Ink)) {
        Bloom(bloomColor, 420.dp, (-60).dp, (-56).dp, 0.30f)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (section != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary,
                        modifier = Modifier.size(24.dp).clip(CircleShape).clickable { up() },
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    headerTitle(section, detail), color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, letterSpacing = (-0.5).sp,
                )
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = navBarInset() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (section == null) {
                    // Nothing set up yet: the index reads identically whether the app is
                    // configured or completely empty, and a new user's first question is
                    // not which of six categories to browse.
                    if (state.servers == 0) {
                        item(key = "get-started") {
                            GetStartedCard(accent) { onSection(SettingsSection.PROVIDERS); onDetail(null) }
                        }
                    }
                    // The index. Every row says what is behind it *right now* — a static
                    // description of a category is a definition, and the reader already
                    // knows what "Downloads" means; what they do not know is whether
                    // theirs are taking a gigabyte or whether the bridge ever paired.
                    item(key = "advanced-toggle") {
                        AdvancedToggleCard(advanced, settings, accent, scope)
                    }
                    items(SettingsSection.entries, key = { it.name }, contentType = { "category" }) { s ->
                        // Each row takes its own swatch from the album palette, the same
                        // way the library's category tiles do — so the six read as a set
                        // of places rather than six repetitions of one accent, and both
                        // screens are visibly tinted by the record on the player.
                        val hue = LocalPalette.current.swatch(s.ordinal)
                        val tint by animateColorAsState(hue, Motion.effects(), label = "sectionHue")
                        NavRow(s.icon, s.title, state.subtitleFor(s) ?: s.subtitle, tint) {
                            onSection(s); onDetail(null)
                        }
                    }
                } else {
                    sectionItems(
                        section = section,
                        detail = detail,
                        onDetail = onDetail,
                        settings = settings,
                        libraryViewModel = libraryViewModel,
                        accent = accent,
                        scope = scope,
                        advanced = advanced,
                        haUrl = haUrl,
                        onHaUrl = { haUrl = it },
                        haToken = haToken,
                        onHaToken = { haToken = it },
                        onOpenDownloads = onOpenDownloads,
                        onOpenStats = onOpenStats,
                        onOpenAmbience = onOpenAmbience,
                        onOpenServer = { id ->
                            onSection(SettingsSection.PROVIDERS)
                            onDetail(id)
                        },
                    )
                }
            }
        }
    }
}

/**
 * A section's body, card by card.
 *
 * Every page in Settings is emitted straight into the host [LazyColumn] rather than
 * wrapped in one item of it. A page composed inside a single item never recycles: the
 * whole of it is composed and measured in one frame on the way in, which is what the
 * Audio page's scroll was missing before `perf: recycle the Audio & DSP page card by
 * card`, and every other page had the same shape. Each card's flow collectors go
 * inside its own item lambda for the same reason, so a card scrolled off drops its
 * subscriptions.
 *
 * Keys carry the route, so drilling from one page to another replaces the item set
 * rather than redrawing slots that never changed. The list's own 10dp spacing
 * supplies the card gaps, which is why nothing here wraps itself in a Column.
 *
 * The two sections with a body of their own — Providers and Illumination — still hand
 * their whole body over as one item. Both route internally and neither is a plain run
 * of cards; splitting them would mean teaching this function about server ids.
 */
private fun LazyListScope.sectionItems(
    section: SettingsSection,
    detail: String?,
    onDetail: (String?) -> Unit,
    settings: AppSettings,
    libraryViewModel: LibraryViewModel,
    accent: Color,
    scope: CoroutineScope,
    advanced: Boolean,
    haUrl: String,
    onHaUrl: (String) -> Unit,
    haToken: String,
    onHaToken: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenAmbience: () -> Unit,
    onOpenServer: (String) -> Unit,
) {
    // The section index: which pages are behind this category. Empty for the two
    // that are their own index — see [subPagesFor].
    //
    // Shown for an unknown route as well as for no route at all. `detail` is
    // `rememberSaveable`, so it survives process death and an app update with it — a
    // page that has since been renamed or removed would otherwise restore as a blank
    // screen with a back arrow, which reads as a crash.
    val pages = subPagesFor(section)
    if (pages.isNotEmpty() && pages.none { it.route == detail }) {
        items(pages, key = { "${section.name}_${it.route}" }, contentType = { "page" }) { page ->
            NavRow(page.icon, page.title, page.subtitle, accent) { onDetail(page.route) }
        }
        return
    }

    when (section) {
        // Two levels of its own already: a list of servers, then one server's page.
        SettingsSection.PROVIDERS -> item(key = "providers", contentType = "section") {
            LibrariesSection(
                settings = settings,
                libraryVm = libraryViewModel,
                accent = accent,
                scope = scope,
                detail = detail,
                onDetail = onDetail,
            )
        }

        // Its index is the transport picker plus the rows under it, because which
        // transport is in use decides what the rows below even are.
        SettingsSection.LIGHTS_SYNC -> item(key = "lights", contentType = "section") {
            LightSyncSection(
                settings = settings,
                accent = accent,
                scope = scope,
                advanced = advanced,
                detail = detail,
                onDetail = onDetail,
                haUrl = haUrl,
                onHaUrl = onHaUrl,
                haToken = haToken,
                onHaToken = onHaToken,
                onOpenAmbience = onOpenAmbience,
                onOpenServer = onOpenServer,
            )
        }

        SettingsSection.AUDIO -> when (detail) {
            AUDIO_OUTPUT_ROUTE -> card("audio_output") { OutputCard(settings, accent, scope, advanced) }
            AUDIO_EQ_ROUTE -> card("audio_eq") { EqualiserCard(accent) }
            AUDIO_GAIN_ROUTE -> card("audio_gain") { LoudnessCard(settings, accent, scope) }
            AUDIO_BETWEEN_ROUTE -> {
                card("audio_continuous") { ContinuousPlayCard(settings, accent, scope) }
                card("audio_djradio") { DjRadioSettingsCard(settings, scope) }
            }
            AUDIO_BEHAVIOUR_ROUTE -> {
                card("behaviour_player") { BehaviorPlayerCard(settings, accent, scope) }
                card("behaviour_hands") { BehaviorHandsCard(settings, accent, scope) }
                card("behaviour_scans") { BehaviorScansCard(settings, accent, scope, advanced) }
                if (advanced) card("behaviour_lyrics") { BehaviorLyricsCard(settings, scope) }
            }
        }

        SettingsSection.APPEARANCE -> when (detail) {
            LOOK_THEME_ROUTE -> {
                card("look_theme") { ThemeCard(settings, scope) }
                card("look_accent") { AccentCard(settings, scope) }
            }
            LOOK_PLAYER_ROUTE -> {
                card("look_layout") { NowPlayingLayoutCard(settings, scope) }
                card("look_seekbar") { SeekBarCard(settings, scope) }
            }
            LOOK_MOTION_ROUTE -> {
                card("look_bloom") { ChameleonCard(settings, accent, scope) }
                // Behind the advanced switch, as it always was: the system's own
                // setting is right for almost everybody, and this only exists to
                // disagree with it.
                if (advanced) card("look_motion") { MotionCard(settings, scope) }
            }
        }

        SettingsSection.DRIVING -> when (detail) {
            DRIVE_MODE_ROUTE -> card("drive_mode") { DrivingModeCard(settings, accent, scope) }
            DRIVE_SAFETY_ROUTE -> card("drive_safety") { SafetyCard(settings, accent, scope) }
            DRIVE_AUTO_ROUTE -> card("drive_auto") { AndroidAutoCard(settings, accent) }
        }

        SettingsSection.SYSTEM_ABOUT -> when (detail) {
            SYS_STORAGE_ROUTE -> card("sys_storage") {
                DownloadsSection(libraryViewModel, settings, accent, scope, onOpenDownloads)
            }
            SYS_BACKUP_ROUTE -> card("sys_backup") { BackupSection(settings, accent, scope) }
            SYS_DIAGNOSTICS_ROUTE -> card("sys_diagnostics") { DiagnosticsCard(accent) }
            SYS_ABOUT_ROUTE -> {
                card("sys_about") { AboutCard(accent) }
                card("sys_stats") {
                    NavRow(
                        icon = Icons.Default.BarChart,
                        title = "Listening statistics",
                        subtitle = "Your recent top artists, total listening time, and format breakdown",
                        accent = accent,
                        onClick = onOpenStats,
                    )
                }
            }
        }
    }
}

/** One settings card as its own list item. See [sectionItems] for why. */
private fun LazyListScope.card(key: String, content: @Composable () -> Unit) {
    item(key = key, contentType = "card") { content() }
}

// ── The index's live state ────────────────────────────────────────────────

/**
 * What each settings category currently *is*, for the row that points at it.
 *
 * Gathered in one place rather than per row: every value here already exists as a flow
 * somebody else is collecting, and six rows each opening their own subscription to
 * answer one line of text would be six collectors on the index of a screen the user is
 * usually passing through.
 */
/**
 * How many switches the Playback behaviour page holds, for the Audio row's summary.
 *
 * A constant rather than a count of anything: the flows are collected one by one in
 * [rememberSettingsOverview], so nothing here can derive the total, and a hard-coded
 * denominator that drifts from the page is worse than none. Adding a switch to
 * `BehaviorSettings` means changing this too.
 *
 * (It named `SettingsSection.BEHAVIOR` until that constant was merged away, and went
 * on naming it for two releases after — which is the argument for the paragraph
 * above rather than against it.)
 */
private const val BEHAVIOR_TOGGLES = 5

private data class SettingsOverview(
    val servers: Int = 0,
    val activeLibrary: String? = null,
    val libraryStatus: String? = null,
    val downloadCount: Int = 0,
    val downloadBytes: Long = 0,
    val lightSyncSummary: String? = null,
    val appearanceSummary: String? = null,
    /** How many of [BEHAVIOR_TOGGLES] switches on the Playback behaviour page are on. */
    val behaviorOn: Int = 0,
    val behaviorLyricsOffsetMs: Int = 0,
    val drivingEnabled: Boolean = false,
    val drivingCar: String = "",
) {
    /** The live line for [section], or null to fall back to its written description. */
    fun subtitleFor(section: SettingsSection): String? = when (section) {
        SettingsSection.PROVIDERS -> when {
            servers == 0 -> "No music library yet, start here"
            else -> listOfNotNull(activeLibrary, libraryStatus).joinToString(" · ")
                .takeIf { it.isNotBlank() }
        }
        SettingsSection.AUDIO -> listOfNotNull(
            if (behaviorOn == 0) "Standard playback"
            else "$behaviorOn of $BEHAVIOR_TOGGLES gestures and effects on",
            behaviorLyricsOffsetMs.takeIf { it != 0 }?.let { "lyrics %+d ms".format(it) },
        ).joinToString(" · ")
        SettingsSection.LIGHTS_SYNC -> lightSyncSummary
        SettingsSection.APPEARANCE -> appearanceSummary
        SettingsSection.DRIVING -> when {
            !drivingEnabled -> null
            drivingCar.isNotBlank() -> "On, for $drivingCar"
            else -> "On, no car picked yet"
        }
        SettingsSection.SYSTEM_ABOUT -> when (downloadCount) {
            0 -> "Nothing downloaded yet"
            else -> "$downloadCount ${if (downloadCount == 1) "offline track" else "offline tracks"} · " +
                formatBytes(downloadBytes)
        }
    }
}

@Composable
private fun rememberSettingsOverview(
    settings: AppSettings,
    libraryViewModel: LibraryViewModel,
): SettingsOverview {
    val servers by settings.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeId by settings.activeServerId.collectAsStateWithLifecycle(initialValue = "")
    val ready by libraryViewModel.ready.collectAsStateWithLifecycle()
    val connecting by libraryViewModel.connecting.collectAsStateWithLifecycle()
    val offline by libraryViewModel.offline.collectAsStateWithLifecycle()
    val downloads by libraryViewModel.downloads.collectAsStateWithLifecycle()
    val mode by settings.lightSyncMode.collectAsStateWithLifecycle(initialValue = AppSettings.MODE_HA)
    val bridgeIp by settings.hueBridgeIp.collectAsStateWithLifecycle(initialValue = "")
    val haAddress by settings.haUrl.collectAsStateWithLifecycle(initialValue = "")
    val themeKey by settings.theme.collectAsStateWithLifecycle(initialValue = ThemeChoice.OLED.key)
    val accentKey by settings.accentSource.collectAsStateWithLifecycle(initialValue = AccentChoice.ALBUM.key)
    // The five Playback & Behavior switches, for that row's "n of five on" line. All
    // five are off by default, so an untouched install reads "Nothing turned on yet"
    // rather than a count nobody has to act on.
    val showVisualizer by settings.showVisualizer.collectAsStateWithLifecycle(initialValue = false)
    val swipeToSkip by settings.swipeToSkip.collectAsStateWithLifecycle(initialValue = false)
    val sensorGestures by settings.sensorGestures.collectAsStateWithLifecycle(initialValue = false)
    val djMode by settings.djMode.collectAsStateWithLifecycle(initialValue = false)
    val listeningDna by settings.listeningDna.collectAsStateWithLifecycle(initialValue = false)
    val lyricsOffset by settings.lyricsOffsetMs.collectAsStateWithLifecycle(initialValue = 0)
    val drivingOn by settings.drivingEnabled.collectAsStateWithLifecycle(initialValue = false)
    val drivingCarName by settings.drivingCarName.collectAsStateWithLifecycle(initialValue = "")

    // Stat-ing the files is disk work, so it happens off the main thread and only when
    // the index of downloads actually changes.
    var bytes by remember { mutableStateOf(0L) }
    LaunchedEffect(downloads) {
        bytes = withContext(Dispatchers.IO) { libraryViewModel.downloadBytes() }
    }

    val active = servers.firstOrNull { it.id == activeId }
    return SettingsOverview(
        servers = servers.size,
        activeLibrary = active?.displayName,
        libraryStatus = when {
            active == null -> null
            connecting -> "Connecting…"
            offline -> "Offline, playing downloads"
            ready -> "Connected"
            else -> "Not connected"
        },
        downloadCount = downloads.size,
        downloadBytes = bytes,
        lightSyncSummary = if (mode == AppSettings.MODE_DIRECT) {
            if (bridgeIp.isNotBlank()) "Hue Bridge · paired" else "Hue Bridge · not paired yet"
        } else {
            if (haAddress.isNotBlank()) "Home Assistant · $haAddress" else "Home Assistant · not set up"
        },
        appearanceSummary = "${ThemeChoice.from(themeKey).label} · ${AccentChoice.from(accentKey).label}",
        behaviorOn = listOf(showVisualizer, swipeToSkip, sensorGestures, djMode, listeningDna)
            .count { it },
        behaviorLyricsOffsetMs = lyricsOffset,
        drivingEnabled = drivingOn,
        drivingCar = drivingCarName,
    )
}

/**
 * The one card a phone with no library needs.
 *
 * Six categories, all of them about parts of a thing that has not been set up, is a
 * poor first screen: the app cannot browse, play, download or light anything until a
 * server exists, and none of the six rows says so.
 */
@Composable
private fun GetStartedCard(accent: androidx.compose.ui.graphics.Color, onAddLibrary: () -> Unit) {
    SettingsCard(
        title = "Start here",
        lead = "Add a library and the rest of the app comes alive.",
        info = "CAMusic plays your own music, from your own server. Music Assistant drives " +
            "speakers around the house and keeps one queue across them. Navidrome, Subsonic and " +
            "Jellyfin play on this phone, with downloads that work with no network at " +
            "all.\n\nYou can add more than one and switch between them. Nothing gets thrown " +
            "away when you do.\n\nTip: if you are not sure which you have, start with the " +
            "server you already sign in to from a browser. Music Assistant is the one with a " +
            "speakers page.",
    ) {
        OledButton("Add a music library", accent = accent, onClick = onAddLibrary)
    }
}

/**
 * One level up from [detail], or null for the section's own index.
 *
 * Everything in Settings is two levels except one thing: a Music Assistant server's
 * "this phone as a player" page, which is a page under a page. Its route carries the
 * server it belongs to, so going up from it is a matter of reading that back out
 * rather than a third piece of state — see [playerRoute].
 */
private fun parentDetail(detail: String): String? = serverIdOfPlayerRoute(detail)

/**
 * What the header says.
 *
 * A detail page under a section is still that section as far as the user is concerned
 * — but "Libraries" over a form for one server is less use than naming the thing in
 * front of them, so the sub-pages that have a name of their own use it.
 */
@Composable
private fun headerTitle(section: SettingsSection?, detail: String?): String = when {
    section == null -> "Settings"
    section == SettingsSection.PROVIDERS && detail == PICK_ROUTE -> "Add a server"
    section == SettingsSection.PROVIDERS && serverIdOfPlayerRoute(detail) != null -> "This phone"
    section == SettingsSection.PROVIDERS && detail != null -> "Server"
    section == SettingsSection.LIGHTS_SYNC && detail == BRIDGE_ROUTE -> "Hue Bridge"
    section == SettingsSection.LIGHTS_SYNC && detail == HA_ROUTE -> "Home Assistant"
    section == SettingsSection.LIGHTS_SYNC && detail == ANALYSIS_ROUTE -> "Track analysis"
    else -> subPageTitle(section, detail) ?: section.title
}


// ── Backup & Restore section ─────────────────────────────────────────────

/**
 * Export every setting — including saved servers — to an encrypted JSON file, or
 * restore one. The password never leaves this device: it exists only to derive
 * [com.engabd.sendpin.data.PortableCrypto]'s key, which is why losing it means
 * losing the backup — there is nothing to reset it with.
 */
@Composable
private fun BackupSection(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val context = LocalContext.current
    var exportPrompt by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    // Held between the picker's launch and its callback — CreateDocument hands off
    // to a separate activity, and the composition (with its remember state) is
    // what survives that round trip, not any local variable in the click handler.
    var exportPassword by remember { mutableStateOf<String?>(null) }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val password = exportPassword
        exportPassword = null
        if (uri == null || password == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = try {
                val blob = settings.exportSettings(password)
                if (blob == null) {
                    "Export failed"
                } else {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        OutputStreamWriter(out).use { it.write(blob) }
                    }
                    "Exported"
                }
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
        }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importUri = uri
    }

    SettingsCard(
        title = "Backup & restore",
        lead = "Every setting, including saved servers, to an encrypted file.",
        info = "Export every setting, saved servers and their logins included, to a single " +
            "encrypted file. Restore it on a new install and the app comes back exactly as it " +
            "was.\n\nThe password is yours alone. It is not stored anywhere, not in the file " +
            "and not on this phone, so there is no way to recover a backup without it.\n\nTip: " +
            "take one before changing servers or reinstalling. It is the only copy of your Hue " +
            "pairing, which otherwise means pressing the button on the bridge again.",
    ) {
        OledButton(text = "Export settings", accent = accent, outline = true) { exportPrompt = true }
        Spacer(Modifier.height(8.dp))
        OledButton(text = "Import settings", accent = accent, outline = true) {
            openDoc.launch(arrayOf("application/json", "*/*"))
        }
        status?.let { Spacer(Modifier.height(6.dp)); Note(it, warn = it.startsWith("Export failed") || it.startsWith("Import failed")) }
    }

    if (exportPrompt) {
        PasswordPromptDialog(
            title = "Export settings",
            note = "This password encrypts the file. Choose one you'll remember, it can't be reset.",
            confirmLabel = "Export",
            onDismiss = { exportPrompt = false },
            onConfirm = { password ->
                exportPrompt = false
                exportPassword = password
                createDoc.launch("camusic-settings-backup.json")
            },
        )
    }

    importUri?.let { uri ->
        PasswordPromptDialog(
            title = "Import settings",
            note = "This replaces your saved servers and other settings on this device.",
            confirmLabel = "Import",
            onDismiss = { importUri = null },
            onConfirm = { password ->
                importUri = null
                scope.launch {
                    status = try {
                        val text = context.contentResolver.openInputStream(uri)?.use { input ->
                            BufferedReader(InputStreamReader(input)).readText()
                        }
                        if (text == null) {
                            "Import failed: couldn't read that file"
                        } else if (settings.importSettings(text, password)) {
                            "Imported, restart the app for everything to take effect"
                        } else {
                            "Import failed: wrong password, or not a CAMusic backup"
                        }
                    } catch (e: Exception) {
                        "Import failed: ${e.message}"
                    }
                }
            },
        )
    }
}

/** The master switch that reveals the full settings surface. */
@Composable
private fun AdvancedToggleCard(
    advanced: Boolean,
    settings: AppSettings,
    accent: Color,
    scope: CoroutineScope,
) {
    GlassCard(radius = 16.dp) {
        Row(
            Modifier.fillMaxWidth()
                .clickable { scope.launch { settings.setAdvancedSettings(!advanced) } }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                Text(
                    "Advanced settings",
                    color = TextPrimary,
                    fontFamily = AppFont,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    if (advanced) "Showing every control and explanation" else "Showing only the everyday controls",
                    color = TextFaint,
                    fontFamily = AppFont,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box(
                Modifier.size(44.dp, 24.dp).clip(RoundedCornerShape(100))
                    .background(if (advanced) accent else Glass)
                    .border(1.dp, if (advanced) accent.a(0.5f) else Hairline, RoundedCornerShape(100))
                    .padding(2.dp),
                contentAlignment = if (advanced) Alignment.CenterEnd else Alignment.CenterStart,
            ) { Box(Modifier.size(18.dp).clip(CircleShape).background(if (advanced) Ink else TextMuted)) }
        }
    }
}

@Composable
private fun PasswordPromptDialog(
    title: String,
    note: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val accent = LocalAccent.current
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink2,
        title = { Text(title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SecretField(
                    value = password,
                    onChange = { password = it },
                    label = "Password",
                    accent = accent,
                    visible = visible,
                    onVisibilityChange = { visible = it },
                )
                Note(note)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(password) }, enabled = password.isNotBlank()) {
                Text(confirmLabel, color = if (password.isBlank()) TextFaint else accent, fontFamily = AppFont, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted, fontFamily = AppFont)
            }
        },
    )
}
