package com.engabd.sendpin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ui.design.AlbumArt
import com.engabd.sendpin.ui.design.LocalPalette
import com.engabd.sendpin.ui.design.PlayButton
import com.engabd.sendpin.ui.design.SettledArt
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.GlassStrong
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel
import com.engabd.sendpin.ui.viewmodel.PlayerViewModel

/**
 * Which way the car's screen is cut in two.
 *
 * There is no third answer on purpose. The player and the library are both always
 * present — that is the whole point of the car layout — so the only question a head
 * unit can ask is whether they sit beside each other or above each other.
 */
enum class CarPanes { SIDE_BY_SIDE, STACKED }

/**
 * A pane narrower than this cannot hold either half: the library's grid puts three
 * covers on a row, and the transport row needs five targets a driver can hit.
 */
private const val MIN_PANE_WIDTH_DP = 280f

/**
 * A pane shorter than this cannot hold the player's controls under a title, nor
 * enough library rows to be worth scrolling.
 */
private const val MIN_PANE_HEIGHT_DP = 260f

/** The player's share when the two panes sit side by side. The library gets the rest. */
private const val PLAYER_WEIGHT_WIDE = 0.42f

/** The player's share when they are stacked. Lists gain more from height than art does. */
private const val PLAYER_WEIGHT_TALL = 0.45f

/** The smallest thing worth aiming at from a driver's seat. */
private val CarTarget = 56.dp

/**
 * How to cut the car's screen, given the size of the window the app actually has.
 *
 * The window, not the display, and not the orientation — those are three different
 * numbers on Android Automotive, and only this one is true. A portrait head unit
 * sharing its screen with another app hands this app a short, wide region while the
 * *display* is still portrait, and a layout reading `Configuration.orientation` would
 * stack two halves into a region with room for neither.
 *
 * So the shape of what we were given decides, with one qualification either way: a
 * window too short to stack is split sideways even though it is portrait, and one too
 * narrow to split is stacked even though it is landscape. Only when both cuts fit does
 * the window's own proportion choose, and then it chooses the obvious way — wider than
 * tall means side by side.
 *
 * Pure, and taking dp rather than a `Constraints`, so the three cases that matter are
 * a unit test rather than three head units.
 */
fun carPanes(widthDp: Float, heightDp: Float): CarPanes {
    val fitsWide = widthDp / 2f >= MIN_PANE_WIDTH_DP
    val fitsTall = heightDp / 2f >= MIN_PANE_HEIGHT_DP
    return when {
        fitsWide && !fitsTall -> CarPanes.SIDE_BY_SIDE
        fitsTall && !fitsWide -> CarPanes.STACKED
        widthDp >= heightDp -> CarPanes.SIDE_BY_SIDE
        else -> CarPanes.STACKED
    }
}

/**
 * The car's whole app: a player and a library, both on screen, and nothing else.
 *
 * This replaces the phone's tab bar rather than adapting it. A driver has two
 * questions — what is playing, and what to play next — and a bottom nav answers them
 * by making each one hide the other. Five tabs is also five targets in a strip along
 * the bottom edge of a screen mounted at arm's length, which is the worst place on the
 * panel to put anything that has to be hit while moving.
 *
 * So both live on the main screen and neither navigates. The library browses inside
 * its own pane — [LibraryScreen] drives [LibraryViewModel]'s browse stack when its
 * click callbacks are left at their defaults — so there is no NavHost here at all, and
 * no back stack for a car to strand someone in. Everything the phone reaches through
 * the other tabs is behind the one gear in the player's corner, which is where a
 * *stationary* task belongs.
 *
 * Reached from [com.engabd.sendpin.ui.App] on Android Automotive
 * ([com.engabd.sendpin.data.Platform.isAutomotive]) — not on projected Android Auto,
 * where the car draws its own template from the browse tree in `car/` and none of this
 * is on screen. See that method's doc for why the two are not the same surface.
 */
@Composable
fun CarShell(
    playerVm: PlayerViewModel,
    nowPlayingVm: NowPlayingViewModel,
    libraryVm: LibraryViewModel,
    art: SettledArt,
) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var settingsSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    var settingsDetail by rememberSaveable { mutableStateOf<String?>(null) }
    // Downloads and Stats are screens Settings links out to, and on the phone they are
    // navigation destinations. Here they are one more layer over the shell, for the
    // same reason Settings is: this app has no back stack in the car.
    var fullScreen by rememberSaveable { mutableStateOf<String?>(null) }

    // Hoisted out of the player pane so a sheet covers the car's screen rather than
    // the left third of it. The speaker picker in particular is a list of names, and a
    // list of names inside a 320dp pane is the phone problem this layout exists to
    // avoid.
    val sheets = rememberPlayerSheets()
    val favouritable by nowPlayingVm.favouritableItem.collectAsStateWithLifecycle()
    val st by nowPlayingVm.state.collectAsStateWithLifecycle()

    // Leaving Settings. `SettingsScreen` owns Back while a category is open (it means
    // "up one level" there, and its own handler is registered below this one, so it
    // wins while enabled); this is only the last step out.
    BackHandler(enabled = settingsOpen && settingsSection == null) { settingsOpen = false }

    Box(Modifier.fillMaxSize().background(Ink)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            when (carPanes(maxWidth.value, maxHeight.value)) {
                CarPanes.SIDE_BY_SIDE -> Row(Modifier.fillMaxSize()) {
                    CarPlayerPane(
                        Modifier.weight(PLAYER_WEIGHT_WIDE).fillMaxHeight(),
                        viewModel = nowPlayingVm,
                        art = art,
                        sheets = sheets,
                        onOpenSettings = { settingsOpen = true },
                    )
                    Box(Modifier.fillMaxHeight().width(1.dp).background(Glass))
                    CarLibraryPane(
                        Modifier.weight(1f - PLAYER_WEIGHT_WIDE).fillMaxHeight(),
                        libraryVm = libraryVm,
                        onManageDownloads = { fullScreen = "downloads" },
                    )
                }
                CarPanes.STACKED -> Column(Modifier.fillMaxSize()) {
                    CarPlayerPane(
                        Modifier.weight(PLAYER_WEIGHT_TALL).fillMaxWidth(),
                        viewModel = nowPlayingVm,
                        art = art,
                        sheets = sheets,
                        onOpenSettings = { settingsOpen = true },
                    )
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Glass))
                    CarLibraryPane(
                        Modifier.weight(1f - PLAYER_WEIGHT_TALL).fillMaxWidth(),
                        libraryVm = libraryVm,
                        onManageDownloads = { fullScreen = "downloads" },
                    )
                }
            }
        }

        // Over both panes, so a sheet is the car's screen and not a pane's.
        PlayerOverlays(
            st, sheets, nowPlayingVm,
            favouritable = favouritable,
            coverUrl = art.url,
        )

        if (settingsOpen) {
            Box(Modifier.fillMaxSize().background(Ink)) {
                SettingsScreen(
                    viewModel = playerVm,
                    libraryViewModel = libraryVm,
                    section = settingsSection,
                    onSection = { settingsSection = it; settingsDetail = null },
                    detail = settingsDetail,
                    onDetail = { settingsDetail = it },
                    onOpenDownloads = { fullScreen = "downloads" },
                    onOpenStats = { fullScreen = "stats" },
                )
                // The way out, and the only one: there is no tab bar behind this to
                // tap, and the screen's own arrow means "up a level" rather than
                // "done" — at the index it has no arrow at all. Bottom corner rather
                // than top, because it is the corner a hand already rests near and the
                // one no header can collide with.
                //
                // Labelled rather than a bare glyph, and inset past the system bars.
                // Both are the same lesson: a head unit has no Back gesture to fall
                // back on, so if this control is missed there is no way out of
                // Settings at all. Unpadded it sat *underneath* the car's own
                // navigation bar and was exactly that — invisible, on the one screen
                // that cannot afford it.
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(20.dp)
                        .heightIn(min = CarTarget)
                        .clip(RoundedCornerShape(100))
                        .background(GlassStrong)
                        .border(1.dp, Hairline, RoundedCornerShape(100))
                        .clickable {
                            settingsOpen = false
                            settingsSection = null
                            settingsDetail = null
                        }
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(24.dp))
                    Text(
                        "Done",
                        color = TextPrimary,
                        fontFamily = AppFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    )
                }
            }
        }

        when (fullScreen) {
            "downloads" -> Box(Modifier.fillMaxSize().background(Ink)) {
                DownloadsScreen(viewModel = libraryVm, onBack = { fullScreen = null })
            }
            "stats" -> Box(Modifier.fillMaxSize().background(Ink)) {
                StatsScreen(onBack = { fullScreen = null })
            }
        }
    }
}

/**
 * The player half.
 *
 * Built from `NowPlayingParts` rather than from [NowPlayingScreen] — it is the third
 * caller that file was written for, and it takes the same scrubber, seek row, title
 * block and wash. What is *not* shared is the transport row and what the pane drops
 * when it is short, and those two are what make this a car player rather than a narrow
 * phone one.
 */
@Composable
private fun CarPlayerPane(
    modifier: Modifier,
    viewModel: NowPlayingViewModel,
    art: SettledArt,
    sheets: PlayerSheetState,
    onOpenSettings: () -> Unit,
) {
    val st by viewModel.state.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val palette = LocalPalette.current
    val scrubber = rememberScrubber(viewModel)
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    val chameleonBloom by settings.chameleonBloom.collectAsStateWithLifecycle(initialValue = false)
    val washDim = idleFade(st.idle, 0.5f)
    val artGlow = idleFade(st.idle, 0.18f, 0.45f)

    BoxWithConstraints(modifier.background(Ink)) {
        AlbumWash(art.url, palette, chameleonBloom, washDim)

        // What the pane can afford, in the order it can afford to lose it. The
        // transport row is the part that must never go, so the cover goes first and
        // the volume slider second — a car has a knob for that, and this app is not
        // the only thing reaching for it.
        //
        // 470dp is the fixed chrome measured on a head unit rather than guessed: the
        // top bar, the title block, the seek row and a full-size transport row come to
        // about 340dp, and a cover worth drawing needs another 130. Below that the
        // pane both drops the cover and takes the controls down a size, because the
        // failure it is avoiding is the play button clipped by the bottom edge — the
        // one control in the app that must always be hittable.
        val compact = maxHeight < 470.dp
        val showCover = !compact
        val showVolume = maxHeight >= 620.dp

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = if (compact) 10.dp else 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (showCover) Arrangement.Top else Arrangement.Center,
        ) {
            if (!connected && !st.isLocalSession) OfflineBanner()

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TopBar(
                        playerName = st.playerName,
                        isSelf = st.isSelf,
                        groupSize = st.groupSize,
                        localSession = st.isLocalSession,
                        onTap = { if (st.isLocalSession) sheets.device = true else sheets.speakers = true },
                        // No source badge. On the phone it fills the corner opposite
                        // the speaker pill; here the gear has that corner, and the two
                        // together squeezed the pill until the player's own name
                        // truncated. Which backend is playing is also the one thing on
                        // that bar a driver never needs — and the library pane beside
                        // this one is already wearing the same badge.
                        source = null,
                    )
                }
                // Everything the car does not need on its main screen, behind one
                // target in the corner furthest from the road.
                Box(
                    Modifier
                        .size(CarTarget)
                        .clip(CircleShape)
                        .background(Glass)
                        .clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Settings, "Settings", modifier = Modifier.size(24.dp))
                }
            }

            if (showCover) {
                Spacer(Modifier.height(10.dp))
                AlbumArt(
                    art = art,
                    glow = palette.accent,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    radius = 16.dp,
                    glowAlpha = artGlow.value,
                )
                Spacer(Modifier.height(14.dp))
            }

            // The idle notice is a sentence, and a sentence is the first thing to go
            // when the pane is short — it says "browse", and in this layout the
            // library it would send you to is already on screen beside the player.
            if (st.idle && !compact) {
                IdleNotice(st.playerName, st.blank, onBrowse = {})
                Spacer(Modifier.height(12.dp))
            }

            TrackTitleBlock(st, showComposer = false)

            Spacer(Modifier.height(if (compact) 8.dp else 14.dp))

            SeekRow(scrubber, st.durationMs, playing = st.isPlaying)

            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))

            CarTransportRow(st, viewModel, compact = compact)

            if (showVolume) {
                Spacer(Modifier.height(16.dp))
                VolumeRow(st.volume) { viewModel.setVolume(it) }
            }
        }
    }
}

/**
 * The same five controls the phone has, at the size a car needs them.
 *
 * [TransportIcon] already takes its size as a parameter and grows its own hit box with
 * it, so this is the phone's row with three numbers changed and the quality badge left
 * off — a chip that opens a card about sample rates is not something to read while
 * driving. `SpaceEvenly` rather than `SpaceBetween`: with targets this large the two
 * ends would otherwise be pinned to the pane's edges, where a thumb reaching across
 * finds the divider before it finds the button.
 *
 * [compact] is the short-pane size, and it is still larger than the phone's: 72dp of
 * play button against the phone's 68, and 36dp skip icons against 26. A pane short
 * enough to need this is one the app is sharing with something else, and the row has
 * to fit whole — a play button clipped by the bottom edge is worse than a small one.
 */
@Composable
private fun CarTransportRow(
    state: NowPlayingViewModel.State,
    viewModel: NowPlayingViewModel,
    compact: Boolean,
) {
    val edge = if (compact) 24.dp else 30.dp
    val skip = if (compact) 36.dp else 44.dp
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportIcon(Icons.Default.Shuffle, "Shuffle", edge, state.shuffle) { viewModel.toggleShuffle() }
        TransportIcon(Icons.Default.SkipPrevious, "Previous", skip) { viewModel.previous() }
        PlayButton(state.isPlaying, size = if (compact) 72.dp else 92.dp) { viewModel.playPause() }
        TransportIcon(Icons.Default.SkipNext, "Next", skip) { viewModel.next() }
        TransportIcon(
            if (state.repeatMode == "one") Icons.Default.RepeatOne else Icons.Default.Repeat,
            "Repeat", edge, state.repeatMode != "off",
        ) { viewModel.cycleRepeat() }
    }
}

/**
 * The library half — the phone's own [LibraryScreen], unchanged.
 *
 * Deliberately not a car-specific browser. Every click callback is left at its
 * default, which is [LibraryViewModel.open]: the screen browses *within itself*
 * through the view model's node stack, so shelves, search, the library switcher and
 * the long-press sheet all work in a pane with no navigation graph behind it.
 *
 * The column count stays at the phone's six rather than growing with the pane. Six is
 * three covers to a row, and in a pane this wide that makes each cover larger than it
 * ever is on a phone — the right direction for something hit at a glance. The tablet
 * path's 8 and 12 are for fitting *more* on screen, which is the opposite of what a
 * car wants.
 */
@Composable
private fun CarLibraryPane(
    modifier: Modifier,
    libraryVm: LibraryViewModel,
    onManageDownloads: () -> Unit,
) {
    Box(modifier) {
        LibraryScreen(
            viewModel = libraryVm,
            gridCols = 6,
            onManageDownloads = onManageDownloads,
        )
    }
}
